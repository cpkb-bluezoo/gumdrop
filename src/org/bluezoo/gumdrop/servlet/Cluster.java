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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * This component is used to discover other gumdrop nodes on the network.
 * It is the basis for routing distributed sessions between nodes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Cluster extends Thread {

    final Container container;
    final InetAddress local;
    final InetAddress group;
    final int port;
    final Map<SocketAddress,Long> nodes;
    private InetSocketAddress groupSocketAddress;
    private DatagramChannel channel;
    private ByteBuffer pingBuffer;

    Cluster(Container container) throws IOException {
        super("cluster");
        this.container = container;
        setDaemon(true);

        // Interface to be considered local
        InetAddress localhost = InetAddress.getLocalHost();
        InetAddress[] locals = InetAddress.getAllByName(localhost.getHostName());
        InetAddress l = null;
        for (int i = 0; i < locals.length; i++) {
            // Get first non-IPv6 address
            if (locals[i].getHostAddress().indexOf(':') == -1) // TODO IPv6
            {
                l = locals[i];
                break;
            }
        }
        if (l == null) {
            l = localhost;
        }
        local = l;

        port = container.clusterPort;
        group = InetAddress.getByName(container.clusterGroupAddress);
        nodes = new LinkedHashMap<>();

        groupSocketAddress = new InetSocketAddress(group, port);
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(port));
        //channel.join(group, local);
        pingBuffer = ByteBuffer.allocate(0);
    }

    public void run() {
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
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
                            if (readBuffer.remaining() > 0) {
                                // session message
                                receive(readBuffer);
                            }
                            // Have we heard from this node before?
                            if (!nodes.containsKey(remoteAddress)) {
                                replicateAll();
                            }
                            // Update cluster membership
                            nodes.put(remoteAddress, Long.valueOf(System.currentTimeMillis()));
                        }
                    }
                }

                // receive
                /*int len = Math.min(1024, multicaster.getReceiveBufferSize());
                  byte[] buf = new byte[len];
                  DatagramPacket packet = new DatagramPacket(buf, buf.length);
                  try {
                  while (!isInterrupted()) {
                  multicaster.receive(packet);
                  InetAddress remote = packet.getAddress();
                  if (!remote.equals(local)) {
                // Receive session
                len = packet.getLength();
                if (len > 0) {
                int off = packet.getOffset();
                byte[] data = packet.getData();
                receive(data, off, len);
                }
                // Have we heard from this node before?
                if (!nodes.containsKey(remote)) {
                replicateAll();
                }
                // Update cluster membership
                nodes.put(remote, Long.valueOf(System.currentTimeMillis()));
                }
                }
                } catch (SocketTimeoutException e) {
                // Fall through
                }*/
                // Reap expired nodes
                long now = System.currentTimeMillis();
                Collection<SocketAddress> addrs = new ArrayList<>(nodes.keySet());
                for (SocketAddress remote : addrs) {
                    long t = nodes.get(remote);
                    if (now - t > 15000) {
                        nodes.remove(remote);
                    }
                }
            }
        } catch (IOException e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    void ping() throws IOException {
        channel.send(pingBuffer, groupSocketAddress);
    }

    void replicate(Context context, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        buf.put(context.digest);
        session.serialize(buf);
        channel.send(buf, groupSocketAddress);
    }

    void receive(ByteBuffer buf) throws IOException {
        byte[] digest = new byte[16]; // length of MD5 digest
        buf.get(digest);
        Context context = container.getContextByDigest(digest);
        if (context == null || !context.distributable) {
            String message = Context.L10N.getString("warn.no_context_with_digest");
            Context.LOGGER.warning(message);
            return;
        }
        Thread thread = Thread.currentThread();
        ClassLoader contextClassLoader = context.getContextClassLoader();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(contextClassLoader);
        try {
            Session session = Session.deserialize(context, buf);
            synchronized (context) {
                context.sessions.put(session.id, session);
                // notify listeners?
            }
        } finally {
           thread.setContextClassLoader(originalClassLoader);
        }
    }

    void replicateAll() throws IOException {
        for (Context context : container.contexts) {
            if (!context.distributable) {
                continue;
            }
            synchronized (context.sessions) {
                for (Session session : context.sessions.values()) {
                    replicate(context, session);
                }
            }
        }
    }

}
