/*
 * Cluster.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * This component is used to discover other gumdrop nodes on the network.
 * It is the basis for routing distributed sessions between nodes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Cluster extends Thread {

    private static final int UUID_SIZE_BYTES = 16;
    private static final int DIGEST_SIZE_BYTES = 16;
    private static final int INT_SIZE_BYTES = 4;
    private static final int MAX_DATAGRAM_SIZE = 65507;
    private static final int NODE_EXPIRY_TIME = 15000;

    /**
     * Event type indicating that sessions are being updated
     */
    private static final byte EVENT_REPLICATE = 0;

    /**
     * Event type indicating that sessions are being removed/passivated
     */
    private static final byte EVENT_PASSIVATE = 1;

    /**
     * Event type indicating a ping
     */
    private static final byte EVENT_PING = 2;

    final Container container;
    final UUID uuid; // unique identifier for this node
    final InetAddress group;
    final int port;
    final Map<UUID,Long> nodes;
    private InetSocketAddress groupSocketAddress;
    private DatagramChannel channel;
    private ByteBuffer pingBuffer;

    Cluster(Container container) throws IOException {
        super("cluster");
        this.container = container;
        setDaemon(true);

        uuid = UUID.randomUUID();
        port = container.clusterPort;
        group = InetAddress.getByName(container.clusterGroupAddress);
        nodes = new LinkedHashMap<>();

        groupSocketAddress = new InetSocketAddress(group, port);
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(port));
        pingBuffer = ByteBuffer.allocate(UUID_SIZE_BYTES + 1);

        // Join multicast group on all interfaces that support it
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        boolean loopbackJoined = false;
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp()) {
                continue;
            }
            if (ni.supportsMulticast()) {
                try {
                    channel.join(group, ni);
                    String message = Context.L10N.getString("debug.cluster_joined_multicast_group");
                    message = MessageFormat.format(message, group, ni.getDisplayName());
                    Context.LOGGER.fine(message);
                } catch (IOException e) {
                    String message = Context.L10N.getString("debug.cluster_failed_multicast_group");
                    message = MessageFormat.format(message, group, ni.getDisplayName());
                    Context.LOGGER.fine(message);
                }
            } else if (ni.isLoopback()) {
                // Ensure we handle the loopback interface specifically,
                // even though it doesn't support multicast
                // This is how we get local, same-machine communication
                try {
                    // While DatagramChannel.join doesn't "join" on a loopback in the traditional sense,
                    // we need to set the interface to be used for sending
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                    loopbackJoined = true;
                } catch (IOException e) {
                    String message = Context.L10N.getString("err.cluster_failed_loopback");
                    message = MessageFormat.format(message, ni.getDisplayName());
                    Context.LOGGER.severe(message);
                }
            }
        }
        if (!loopbackJoined) {
            String message = Context.L10N.getString("err.cluster_no_loopback");
            Context.LOGGER.severe(message);
        }
    }

    public void run() {
        ByteBuffer readBuffer = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_READ);

            while (!isInterrupted()) {
                // ping
                ping();

                // select
                selector.select(1000);
                if (isInterrupted()) {
                    break;
                }

                // process keys
                Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();
                    if (key.isReadable()) {
                        DatagramChannel readableChannel = (DatagramChannel) key.channel();
                        SocketAddress remoteAddress = readableChannel.receive(readBuffer);
                        if (remoteAddress != null) {
                            readBuffer.flip();

                            // Read message uuid
                            long hi = readBuffer.getLong();
                            long lo = readBuffer.getLong();
                            UUID nodeUuid = new UUID(hi, lo);

                            // Event type
                            byte eventType = readBuffer.get();

                            if (!nodeUuid.equals(uuid)) { // another node
                                if (eventType != EVENT_PING && readBuffer.remaining() > 0) {
                                    // session message
                                    receive(eventType, readBuffer);
                                }
                                // Have we heard from this node before?
                                if (!nodes.containsKey(nodeUuid)) {
                                    replicateAll();
                                }
                                // Update cluster membership
                                nodes.put(nodeUuid, Long.valueOf(System.currentTimeMillis()));
                            }
                        }
                        readBuffer.clear(); // prepare for next message
                    }
                }

                // Reap expired nodes
                long now = System.currentTimeMillis();
                for (Iterator<Map.Entry<UUID,Long>> i = nodes.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry<UUID,Long> entry = i.next();
                    long t = entry.getValue();
                    if (now - t > NODE_EXPIRY_TIME) {
                        i.remove();
                    }
                }
            }
        } catch (IOException e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private void ping() throws IOException {
        pingBuffer.clear();
        // Our UUID
        pingBuffer.putLong(uuid.getMostSignificantBits());
        pingBuffer.putLong(uuid.getLeastSignificantBits());
        // ping type
        pingBuffer.put(EVENT_PING);
        pingBuffer.flip(); // ready for reading
        channel.send(pingBuffer, groupSocketAddress);
    }

    // NB this is called externally by request handler worker 
    // thread after servicing the request
    void replicate(Context context, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        // our uuid
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        // event type
        buf.put(EVENT_REPLICATE);
        // context digest
        buf.put(context.digest);
        buf.putInt(1); // one session to replicate
        // session
        ByteBuffer sessionBuf = session.serialize();
        buf.put(sessionBuf);
        buf.flip(); // ready for reading
        channel.send(buf, groupSocketAddress);
    }

    // Called by context when session will be passivated
    void passivate(Context context, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        // our uuid
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        // event type
        buf.put(EVENT_PASSIVATE);
        // context digest
        buf.put(context.digest);
        buf.putInt(1); // one session to passivate
        // session *id*
        session.serializeId(buf);
        buf.flip(); // ready for reading
        channel.send(buf, groupSocketAddress);
    }

    private void receive(byte eventType, ByteBuffer buf) throws IOException {
        byte[] digest = new byte[DIGEST_SIZE_BYTES]; // length of MD5 digest
        buf.get(digest);
        Context context = container.getContextByDigest(digest);
        if (context == null || !context.distributable) {
            String message = Context.L10N.getString("warn.no_context_with_digest");
            Context.LOGGER.warning(message);
            return;
        }
        switch (eventType) {
            case EVENT_REPLICATE:
                int numSessions = buf.getInt();
                Thread thread = Thread.currentThread();
                ClassLoader contextClassLoader = context.getContextClassLoader();
                ClassLoader originalClassLoader = thread.getContextClassLoader();
                thread.setContextClassLoader(contextClassLoader);
                try {
                    for (int i = 0; i < numSessions; i++) {
                        Session session = Session.deserialize(context, buf);
                        context.addSession(session);
                    }
                } finally {
                    thread.setContextClassLoader(originalClassLoader);
                }
                break;
            case EVENT_PASSIVATE:
                int numSessionIds = buf.getInt();
                for (int i = 0; i < numSessionIds; i++) {
                    String sessionId = Session.deserializeId(buf);
                    context.removeSession(sessionId, false);
                }
                break;
        }
    }

    private void replicateAll() throws IOException {
        for (Context context : container.contexts) {
            if (!context.distributable) {
                continue;
            }
            replicateContext(context);
        }
    }

    private void replicateContext(Context context) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        // our uuid
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        // event type
        buf.put(EVENT_REPLICATE);
        // context digest
        buf.put(context.digest);
        Collection<Session> sessions = context.sessions.values();
        synchronized (sessions) {
            // Reserve space for the session count, which we'll fill in later
            int sessionCountPosition = buf.position();
            buf.putInt(0); 
        
            int sessionCount = 0;
            for (Session session : sessions) {
                ByteBuffer sessionBuf = session.serialize();
                // Check if the session data will fit in the buffer
                if (sessionBuf.remaining() <= buf.remaining()) {
                    buf.put(sessionBuf);
                    sessionCount++;
                } else {
                    // Need to send this packet and start a new one
                    // Update session count
                    buf.putInt(sessionCountPosition, sessionCount);
                    // Flip buffer and send
                    buf.flip();
                    channel.send(buf, groupSocketAddress);

                    // Clear buffer
                    buf.clear();
                    // Re-add header
                    buf.putLong(uuid.getMostSignificantBits());
                    buf.putLong(uuid.getLeastSignificantBits());
                    buf.put(EVENT_REPLICATE);
                    buf.put(context.digest);
                    sessionCountPosition = buf.position();
                    buf.putInt(0);
                    // Add session
                    buf.put(sessionBuf);
                    sessionCount = 1;
                }
            }
            // Send final packet: last batch (might be all)
            if (sessionCount > 0) {
                // Update session count
                buf.putInt(sessionCountPosition, sessionCount);
                // Flip buffer and send
                buf.flip();
                channel.send(buf, groupSocketAddress);
            }
        }
    }

}
