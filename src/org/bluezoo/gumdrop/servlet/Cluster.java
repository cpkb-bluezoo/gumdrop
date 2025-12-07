/*
 * Cluster.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.bluezoo.gumdrop.DatagramServer;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * This component is used to discover other gumdrop nodes on the network.
 * It is the basis for routing distributed sessions between nodes.
 *
 * <p>Extends {@link DatagramServer} to integrate with the Gumdrop event loop
 * rather than running its own thread and selector.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class Cluster extends DatagramServer {

    private static final int UUID_SIZE_BYTES = 16;
    private static final int DIGEST_SIZE_BYTES = 16;
    private static final int MAX_DATAGRAM_SIZE = 65507;
    private static final int NODE_EXPIRY_TIME = 15000;
    private static final int PING_FREQUENCY = 5000;

    // Encryption constants
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96-bit IV
    private static final int GCM_TAG_LENGTH = 16; // 128-bit authentication tag

    // Replay protection constants
    private static final long MAX_TIMESTAMP_DRIFT_MS = 30000; // 30 seconds max clock drift
    private static final int SEQUENCE_WINDOW_SIZE = 1024; // Track last N sequence numbers per node

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
    private ByteBuffer pingBuffer;
    private final SecureRandom secureRandom;
    private final ProtocolFamily protocolFamily;
    private TimerHandle pingTimerHandle;

    // Replay protection: our outbound sequence number (monotonically increasing)
    private final AtomicLong sequenceNumber;
    // Replay protection: per-node state tracking (highest seen sequence and window bitmap)
    private final Map<UUID, NodeSequenceState> nodeSequenceStates;

    /**
     * Constructor.
     * @param container the container
     * @param sharedSecret the secret key. Must be 32 bytes long.
     */
    Cluster(Container container, byte[] sharedSecret) throws IOException {
        super();
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

        // Initialize replay protection
        sequenceNumber = new AtomicLong(0);
        nodeSequenceStates = new ConcurrentHashMap<>();

        if (group instanceof Inet6Address) {
            protocolFamily = StandardProtocolFamily.INET6;
            loopback = InetAddress.getByName("::1");
        } else {
            protocolFamily = StandardProtocolFamily.INET;
            loopback = InetAddress.getByName("127.0.0.1");
        }
        groupSocketAddress = new InetSocketAddress(group, port);
        loopbackSocketAddress = new InetSocketAddress(loopback, port);

        // Header now includes: UUID (16) + sequence (8) + timestamp (8) + event type (1) = 33 bytes
        pingBuffer = ByteBuffer.allocate(UUID_SIZE_BYTES + 8 + 8 + 1);
    }

    // -- DatagramServer configuration --

    @Override
    protected int getPort() {
        return port;
    }

    @Override
    public String getDescription() {
        return "cluster";
    }

    @Override
    protected ProtocolFamily getProtocolFamily() {
        return protocolFamily;
    }

    @Override
    protected void configureChannel(DatagramChannel channel) throws IOException {
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

    // -- Lifecycle --

    @Override
    public void open() throws IOException {
        super.open();
        // Start periodic ping timer
        schedulePing();
    }

    @Override
    public void close() {
        // Cancel ping timer
        if (pingTimerHandle != null) {
            pingTimerHandle.cancel();
            pingTimerHandle = null;
        }
        super.close();
    }

    private void schedulePing() {
        pingTimerHandle = scheduleTimer(PING_FREQUENCY, new PingTimerCallback());
    }

    private void onPingTimer() {
        try {
            ping();
        } catch (IOException e) {
            Context.LOGGER.log(Level.WARNING, "Error sending ping", e);
        }

        // Reap expired nodes and their sequence states
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID,Long>> i = nodes.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<UUID,Long> entry = i.next();
            long t = entry.getValue();
            if (now - t > NODE_EXPIRY_TIME) {
                UUID expiredNode = entry.getKey();
                i.remove();
                // Also remove sequence tracking state for expired nodes
                nodeSequenceStates.remove(expiredNode);
                if (Context.LOGGER.isLoggable(Level.FINE)) {
                    String message = Context.L10N.getString("debug.cluster_node_expired");
                    message = MessageFormat.format(message, expiredNode);
                    Context.LOGGER.fine(message);
                }
            }
        }

        // Schedule next ping
        schedulePing();
    }

    // -- Message handling --

    @Override
    protected void receive(ByteBuffer data, InetSocketAddress source) {
        try {
            // Decrypt incoming message
            ByteBuffer buf = decrypt(data);

            // Read message uuid
            long hi = buf.getLong();
            long lo = buf.getLong();
            UUID nodeUuid = new UUID(hi, lo);

            // Read sequence number and timestamp for replay protection
            long msgSequence = buf.getLong();
            long msgTimestamp = buf.getLong();

            // Event type
            byte eventType = buf.get();

            if (!nodeUuid.equals(uuid)) { // another node
                // Validate timestamp (protect against replay attacks with old messages)
                long now = System.currentTimeMillis();
                if (Math.abs(now - msgTimestamp) > MAX_TIMESTAMP_DRIFT_MS) {
                    String message = Context.L10N.getString("warn.cluster_timestamp_rejected");
                    message = MessageFormat.format(message, nodeUuid, msgTimestamp, now);
                    Context.LOGGER.warning(message);
                    return;
                }

                // Check for replay using per-node sequence tracking
                if (!validateAndRecordSequence(nodeUuid, msgSequence)) {
                    String message = Context.L10N.getString("warn.cluster_replay_detected");
                    message = MessageFormat.format(message, nodeUuid, msgSequence);
                    Context.LOGGER.warning(message);
                    return;
                }

                if (eventType != EVENT_PING && buf.remaining() > 0) {
                    // session message
                    receiveSession(eventType, buf);
                }
                // Have we heard from this node before?
                if (!nodes.containsKey(nodeUuid)) {
                    replicateAll();
                }
                // Update cluster membership
                nodes.put(nodeUuid, Long.valueOf(now));
            }
        } catch (GeneralSecurityException e) {
            String message = Context.L10N.getString("err.cluster_decrypt");
            Context.LOGGER.severe(message);
        } catch (IOException e) {
            Context.LOGGER.log(Level.WARNING, "Error processing cluster message", e);
        }
    }

    private void writeHeader(ByteBuffer buf, byte eventType) {
        // Our UUID
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        // Sequence number for replay protection (monotonically increasing)
        buf.putLong(sequenceNumber.incrementAndGet());
        // Timestamp for recency validation
        buf.putLong(System.currentTimeMillis());
        // event type
        buf.put(eventType);
    }

    private void ping() throws IOException {
        pingBuffer.clear();
        writeHeader(pingBuffer, EVENT_PING);
        pingBuffer.flip(); // ready for reading
        sendToCluster(pingBuffer, false); // don't log pings
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
        sendToCluster(buf, true);
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
        sendToCluster(buf, true);
    }

    private void receiveSession(byte eventType, ByteBuffer buf) throws IOException {
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
                    sendToCluster(buf, true);

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
                sendToCluster(buf, true);
            }
        }
    }

    /**
     * Sends a message to both loopback (for same-machine nodes) and
     * the multicast group (for network nodes).
     */
    private void sendToCluster(ByteBuffer buf, boolean log) throws IOException {
        try {
            ByteBuffer ciphertext = encrypt(buf);
            int len = ciphertext.remaining();

            // We need to send both unicast to the loopback address, for
            // nodes in other processes on the same machine, and multicast
            // to the group address, for nodes on other hosts.

            // copy the buffer for loopback send
            ByteBuffer loopbackBuffer = ciphertext.duplicate();

            // Send loopback unicast message
            send(loopbackBuffer, loopbackSocketAddress);
            if (log && Context.LOGGER.isLoggable(Level.FINEST)) {
                String message = Context.L10N.getString("info.cluster_send_unicast");
                message = MessageFormat.format(message, len, loopbackSocketAddress);
                Context.LOGGER.finest(message);
            }

            // Send group multicast message
            send(ciphertext, groupSocketAddress);
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

    /**
     * Validates and records a sequence number from a remote node.
     * Uses a sliding window approach to detect replays while tolerating
     * out-of-order delivery within the window.
     *
     * @param nodeUuid the UUID of the sending node
     * @param seq the sequence number from the message
     * @return true if the message should be accepted, false if it's a replay
     */
    private boolean validateAndRecordSequence(UUID nodeUuid, long seq) {
        NodeSequenceState state = nodeSequenceStates.get(nodeUuid);
        if (state == null) {
            state = new NodeSequenceState();
            NodeSequenceState existing = nodeSequenceStates.putIfAbsent(nodeUuid, state);
            if (existing != null) {
                state = existing;
            }
        }
        return state.validateAndRecord(seq);
    }

    /**
     * Timer callback for periodic cluster pings.
     */
    private class PingTimerCallback implements Runnable {
        @Override
        public void run() {
            onPingTimer();
        }
    }

    /**
     * Tracks sequence number state for a single remote node.
     * Uses a sliding window bitmap to detect replays while allowing
     * out-of-order message delivery within the window.
     *
     * <p>The window tracks the highest sequence number seen and a bitmap
     * of which sequence numbers in the window have been received. This
     * allows detecting both:
     * <ul>
     *   <li>Replayed messages (same sequence number seen twice)</li>
     *   <li>Gaps in the sequence (missed messages)</li>
     * </ul>
     */
    private static class NodeSequenceState {
        // Highest sequence number received from this node
        private long highestSeq = -1;
        // Bitmap tracking which sequences in [highestSeq - WINDOW_SIZE + 1, highestSeq] 
        // have been seen. Bit 0 corresponds to highestSeq, bit 1 to highestSeq-1, etc.
        private long[] windowBitmap = new long[SEQUENCE_WINDOW_SIZE / 64];

        /**
         * Validates and records a sequence number.
         * @param seq the sequence number to validate
         * @return true if this is a new (non-replayed) message, false otherwise
         */
        synchronized boolean validateAndRecord(long seq) {
            if (seq > highestSeq) {
                // New high-water mark: shift the window
                long shift = seq - highestSeq;
                if (shift >= SEQUENCE_WINDOW_SIZE) {
                    // Completely new window, clear bitmap
                    for (int i = 0; i < windowBitmap.length; i++) {
                        windowBitmap[i] = 0;
                    }
                } else {
                    // Shift the bitmap by 'shift' positions
                    shiftBitmap((int) shift);
                }
                highestSeq = seq;
                // Mark the new highest sequence as seen (bit 0)
                setBit(0);
                return true;
            } else if (seq == highestSeq) {
                // Duplicate of the highest sequence
                return false;
            } else {
                // Sequence is within or below the window
                long offset = highestSeq - seq;
                if (offset >= SEQUENCE_WINDOW_SIZE) {
                    // Too old, outside our tracking window - reject as potential replay
                    if (Context.LOGGER.isLoggable(Level.FINE)) {
                        String message = Context.L10N.getString("debug.cluster_seq_too_old");
                        message = MessageFormat.format(message, seq, highestSeq, SEQUENCE_WINDOW_SIZE);
                        Context.LOGGER.fine(message);
                    }
                    return false;
                }
                // Check if we've seen this sequence before
                if (isBitSet((int) offset)) {
                    // Already seen - replay
                    return false;
                }
                // Mark as seen
                setBit((int) offset);
                return true;
            }
        }

        private void setBit(int index) {
            int wordIndex = index / 64;
            int bitIndex = index % 64;
            windowBitmap[wordIndex] |= (1L << bitIndex);
        }

        private boolean isBitSet(int index) {
            int wordIndex = index / 64;
            int bitIndex = index % 64;
            return (windowBitmap[wordIndex] & (1L << bitIndex)) != 0;
        }

        private void shiftBitmap(int shift) {
            if (shift >= 64) {
                // Shift by whole words first
                int wordShift = shift / 64;
                int bitShift = shift % 64;
                // Move words
                for (int i = windowBitmap.length - 1; i >= wordShift; i--) {
                    windowBitmap[i] = windowBitmap[i - wordShift];
                }
                // Clear lower words
                for (int i = 0; i < wordShift; i++) {
                    windowBitmap[i] = 0;
                }
                if (bitShift > 0) {
                    shiftBitmapBits(bitShift);
                }
            } else {
                shiftBitmapBits(shift);
            }
        }

        private void shiftBitmapBits(int shift) {
            // Shift all words by 'shift' bits, carrying over between words
            long carry = 0;
            for (int i = 0; i < windowBitmap.length; i++) {
                long newCarry = windowBitmap[i] >>> (64 - shift);
                windowBitmap[i] = (windowBitmap[i] << shift) | carry;
                carry = newCarry;
            }
        }

        /**
         * Returns the number of gaps (missed sequence numbers) in the current window.
         * Useful for monitoring cluster health.
         */
        int countGaps() {
            int total = 0;
            for (long word : windowBitmap) {
                total += Long.bitCount(~word);
            }
            // Subtract the bits beyond highestSeq if we haven't filled the window yet
            if (highestSeq < SEQUENCE_WINDOW_SIZE) {
                total -= (SEQUENCE_WINDOW_SIZE - (int) highestSeq - 1);
            }
            return Math.max(0, total);
        }
    }

}
