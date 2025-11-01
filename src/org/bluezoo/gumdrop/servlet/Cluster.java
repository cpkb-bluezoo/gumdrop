/*
 * Cluster.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
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
    private static final int PING_FREQUENCY = 5000;

   // Encryption constants
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96-bit IV
    private static final int GCM_TAG_LENGTH = 16; // 128-bit authentication tag

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
    private final SecretKey sharedSecret;
    final UUID uuid; // unique identifier for this node
    final InetAddress group, loopback;
    final int port;
    final Map<UUID,Long> nodes;
    private InetSocketAddress groupSocketAddress, loopbackSocketAddress;
    private DatagramChannel channel;
    private ByteBuffer pingBuffer;
    private final SecureRandom secureRandom;
    private long lastPing = -1L;

    /**
     * Constructor.
     * @param container the container
     * @param sharedSecret the secret key. Must be 32 bytes long.
     */
    Cluster(Container container, byte[] sharedSecret) throws IOException {
        super("cluster");
        setDaemon(true);
        this.container = container;
        if (sharedSecret.length != 32) {
            throw new IllegalArgumentException(Context.L10N.getString("err.cluster_bad_secret_key"));
        }
        this.sharedSecret = new SecretKeySpec(sharedSecret, "AES");
        secureRandom = new SecureRandom();

        uuid = UUID.randomUUID();
        port = container.clusterPort;
        group = InetAddress.getByName(container.clusterGroupAddress);
        nodes = new LinkedHashMap<>();

        final ProtocolFamily family;
        
        if (group instanceof Inet6Address) {
            family = StandardProtocolFamily.INET6;
            loopback = InetAddress.getByName("::1");
        } else {
            family = StandardProtocolFamily.INET;
            loopback = InetAddress.getByName("127.0.0.1");
        }
        groupSocketAddress = new InetSocketAddress(group, port);
        loopbackSocketAddress = new InetSocketAddress(loopback, port);

        channel = DatagramChannel.open(family);
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        //channel.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
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
            if (ni.isLoopback()) {
                // Ensure we handle the loopback interface specifically,
                // even though it doesn't support multicast
                // This is how we get local, same-machine communication
                try {
                    // While DatagramChannel.join doesn't "join" on a loopback in the traditional sense,
                    // we need to set the interface to be used for sending
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                    loopbackJoined = true;
                    String message = Context.L10N.getString("debug.cluster_joined_loopback");
                    message = MessageFormat.format(message, ni.getDisplayName());
                    Context.LOGGER.fine(message);
                } catch (IOException e) {
                    String message = Context.L10N.getString("err.cluster_failed_loopback");
                    message = MessageFormat.format(message, ni.getDisplayName());
                    Context.LOGGER.severe(message);
                }
            } else if (ni.supportsMulticast()) {
                try {
                    channel.join(group, ni);
                    String message = Context.L10N.getString("debug.cluster_joined_multicast_group");
                    message = MessageFormat.format(message, group, ni.getDisplayName());
                    Context.LOGGER.fine(message);
                } catch (IOException e) {
                    String message = Context.L10N.getString("err.cluster_failed_multicast_group");
                    message = MessageFormat.format(message, group, ni.getDisplayName());
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
                if (lastPing < 0 || System.currentTimeMillis() >= lastPing + PING_FREQUENCY) {
                    ping();
                    lastPing = System.currentTimeMillis();
                }

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

                            try {
                                // Decrypt incoming message
                                ByteBuffer buf = decrypt(readBuffer);

                                // Read message uuid
                                long hi = buf.getLong();
                                long lo = buf.getLong();
                                UUID nodeUuid = new UUID(hi, lo);

                                // Event type
                                byte eventType = buf.get();

                                if (!nodeUuid.equals(uuid)) { // another node
                                    if (eventType != EVENT_PING && buf.remaining() > 0) {
                                        // session message
                                        receive(eventType, buf);
                                    }
                                    // Have we heard from this node before?
                                    if (!nodes.containsKey(nodeUuid)) {
                                        replicateAll();
                                    }
                                    // Update cluster membership
                                    nodes.put(nodeUuid, Long.valueOf(System.currentTimeMillis()));
                                }
                            } catch (GeneralSecurityException e) {
                                String message = Context.L10N.getString("err.cluster_decrypt");
                                Context.LOGGER.severe(message);
                            }
                        }
                        readBuffer.clear(); // prepare for next message
                    }
                }

                // Reap expired nodes
                for (Iterator<Map.Entry<UUID,Long>> i = nodes.entrySet().iterator(); i.hasNext(); ) {
                    Map.Entry<UUID,Long> entry = i.next();
                    long t = entry.getValue();
                    if (System.currentTimeMillis() - t > NODE_EXPIRY_TIME) {
                        i.remove();
                    }
                }
            }
        } catch (IOException e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private void writeHeader(ByteBuffer buf, byte eventType) {
        // Our UUID
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        // event type
        buf.put(eventType);
    }

    private void ping() throws IOException {
        pingBuffer.clear();
        writeHeader(pingBuffer, EVENT_PING);
        pingBuffer.flip(); // ready for reading
        send(pingBuffer, false); // don't log pings
    }

    // NB this is called externally by request handler worker 
    // thread after servicing the request
    void replicate(Context context, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        writeHeader(buf, EVENT_REPLICATE);
        // context digest
        buf.put(context.digest);
        // one session to replicate
        buf.putInt(1);
        // session serialization
        ByteBuffer sessionBuf = session.serialize();
        buf.put(sessionBuf);
        buf.flip(); // ready for reading
        send(buf, true);
    }

    // Called by context when session will be passivated
    void passivate(Context context, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        writeHeader(buf, EVENT_PASSIVATE);
        // context digest
        buf.put(context.digest);
        // one session to passivate
        buf.putInt(1);
        // session *id*
        session.serializeId(buf);
        buf.flip(); // ready for reading
        send(buf, true);
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
                        if (Context.LOGGER.isLoggable(Level.FINE)) {
                            String message = Context.L10N.getString("info.cluster_received_session");
                            message = MessageFormat.format(message, session.id);
                            Context.LOGGER.finest(message);
                        }
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
        writeHeader(buf, EVENT_REPLICATE);
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
                    send(buf, true);

                    // Clear buffer
                    buf.clear();
                    // Re-add header
                    writeHeader(buf, EVENT_REPLICATE);
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
                send(buf, true);
            }
        }
    }

    private void send(ByteBuffer buf, boolean log) throws IOException {
        try {
            ByteBuffer ciphertext = encrypt(buf);
            int len = ciphertext.remaining();

            // We need to send both unicast to the loopback address, for
            // nodes in other processes on the same machine, and multicast
            // to the group address, for nodes on other hosts.

            // copy the buffer
            ByteBuffer loopbackBuffer = ciphertext.duplicate();

            // Send loopback unicast message
            channel.send(loopbackBuffer, loopbackSocketAddress);
            if (log && Context.LOGGER.isLoggable(Level.FINEST)) {
                String message = Context.L10N.getString("info.cluster_send_unicast");
                message = MessageFormat.format(message, len, loopbackSocketAddress);
                Context.LOGGER.finest(message);
            }

            // Send group multicast message
            channel.send(ciphertext, groupSocketAddress);
            if (log && Context.LOGGER.isLoggable(Level.FINEST)) {
                String message = Context.L10N.getString("info.cluster_send");
                message = MessageFormat.format(message, len, groupSocketAddress);
                Context.LOGGER.finest(message);
            }
        } catch (GeneralSecurityException e) {
            String message = Context.L10N.getString("err.cluster_encrypt");
            Context.LOGGER.severe(message);
        }
    }

    /**
     * Encrypts the given buffer using AES-GCM and prepends a random IV.
     * The returned buffer contains the IV, ciphertext, and authentication tag.
     * @param cleartext The ByteBuffer containing the data to encrypt.
     * @return A ByteBuffer containing the encrypted message.
     * @throws Exception if encryption fails.
     */
    private ByteBuffer encrypt(ByteBuffer cleartext) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, sharedSecret, gcmParameterSpec);

        byte[] cleartextBytes = new byte[cleartext.remaining()];
        cleartext.get(cleartextBytes);

        byte[] encryptedBytes = cipher.doFinal(cleartextBytes);

        ByteBuffer encryptedBuf = ByteBuffer.allocate(GCM_IV_LENGTH + encryptedBytes.length);
        encryptedBuf.put(iv);
        encryptedBuf.put(encryptedBytes);
        encryptedBuf.flip();
        return encryptedBuf;
    }

    /**
     * Decrypts the message from the ByteBuffer, validates the tag, and returns
     * a buffer with the original plaintext cleartext.
     * @param ciphertext The ByteBuffer containing the IV, ciphertext, and tag.
     * @return A ByteBuffer with the decrypted cleartext.
     * @throws Exception if decryption or validation fails.
     */
    private ByteBuffer decrypt(ByteBuffer ciphertext) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        ciphertext.get(iv);

        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, sharedSecret, gcmParameterSpec);

        byte[] encryptedBytes = new byte[ciphertext.remaining()];
        ciphertext.get(encryptedBytes);

        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        ByteBuffer decryptedBuf = ByteBuffer.allocate(decryptedBytes.length);
        decryptedBuf.put(decryptedBytes);
        decryptedBuf.flip();
        return decryptedBuf;
    }


}
