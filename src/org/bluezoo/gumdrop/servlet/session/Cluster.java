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

package org.bluezoo.gumdrop.servlet.session;

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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.DatagramServer;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * Cluster component for distributed session replication.
 *
 * <p>A single Cluster instance handles session replication for all
 * distributable contexts in a container. Each context registers with
 * its own context UUID, which is regenerated on hot deployment to
 * trigger session repopulation from other nodes.
 *
 * <p>Features include:
 * <ul>
 *   <li>AES-256-GCM encryption for all messages</li>
 *   <li>Replay protection via sequence numbers and timestamps</li>
 *   <li>Delta replication for efficiency</li>
 *   <li>Message fragmentation for large sessions</li>
 *   <li>Per-context replication on hot deployment</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Cluster extends DatagramServer {

    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.session.L10N");
    private static final Logger LOGGER = Logger.getLogger(Cluster.class.getName());

    private static final int UUID_SIZE_BYTES = 16;
    private static final int DIGEST_SIZE_BYTES = 16;
    private static final int MAX_DATAGRAM_SIZE = 65507;
    private static final int NODE_EXPIRY_TIME = 15000;
    private static final int PING_FREQUENCY = 5000;

    // Encryption constants
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    // Replay protection constants
    private static final long MAX_TIMESTAMP_DRIFT_MS = 30000;
    private static final int SEQUENCE_WINDOW_SIZE = 1024;

    // Event types
    private static final byte EVENT_REPLICATE = 0;
    private static final byte EVENT_PASSIVATE = 1;
    private static final byte EVENT_PING = 2;
    private static final byte EVENT_DELTA = 3;
    private static final byte EVENT_FRAGMENT = 4;

    // Fragment reassembly constants
    private static final int FRAGMENT_TIMEOUT_MS = 30000;
    private static final int MAX_PENDING_FRAGMENTS = 100;

    // Header size: nodeUuid(16) + contextUuid(16) + sequence(8) + timestamp(8) + eventType(1) = 49
    private static final int HEADER_SIZE = UUID_SIZE_BYTES * 2 + 8 + 8 + 1;

    private final ClusterContainer container;
    private final SecretKey sharedSecret;
    private final UUID nodeUuid;
    private final InetAddress group;
    private final InetAddress loopback;
    private final int port;
    private final SecureRandom secureRandom;
    private final ProtocolFamily protocolFamily;
    private final AtomicLong sequenceNumber;
    private final Map<UUID, NodeSequenceState> nodeSequenceStates;
    private final Map<Long, FragmentSet> pendingFragments;

    // Registered contexts: contextUuid -> SessionManager
    private final Map<UUID, SessionManager> sessionManagers;

    // Node tracking: nodeUuid -> (contextUuid -> lastSeen)
    private final Map<UUID, Map<UUID, Long>> nodeContexts;

    private InetSocketAddress groupSocketAddress;
    private InetSocketAddress loopbackSocketAddress;
    private ByteBuffer pingBuffer;
    private TimerHandle pingTimerHandle;

    // Telemetry metrics (null if not configured)
    private ClusterMetrics metrics;

    /**
     * Creates a new cluster instance for a container.
     *
     * @param container the cluster container
     * @throws IOException if the cluster cannot be initialized
     */
    public Cluster(ClusterContainer container) throws IOException {
        super();
        this.container = container;

        byte[] clusterKey = container.getClusterKey();
        if (clusterKey == null || clusterKey.length != 32) {
            throw new IllegalArgumentException(L10N.getString("err.cluster_bad_secret_key"));
        }
        this.sharedSecret = new SecretKeySpec(clusterKey, "AES");
        this.secureRandom = new SecureRandom();
        this.nodeUuid = UUID.randomUUID();
        this.port = container.getClusterPort();
        this.group = InetAddress.getByName(container.getClusterGroupAddress());
        this.sequenceNumber = new AtomicLong(0);
        this.nodeSequenceStates = new ConcurrentHashMap<>();
        this.pendingFragments = new ConcurrentHashMap<>();
        this.sessionManagers = new ConcurrentHashMap<>();
        this.nodeContexts = new ConcurrentHashMap<>();

        if (group instanceof Inet6Address) {
            protocolFamily = StandardProtocolFamily.INET6;
            loopback = InetAddress.getByName("::1");
        } else {
            protocolFamily = StandardProtocolFamily.INET;
            loopback = InetAddress.getByName("127.0.0.1");
        }
        groupSocketAddress = new InetSocketAddress(group, port);
        loopbackSocketAddress = new InetSocketAddress(loopback, port);
        // Ping buffer includes both node and context UUIDs (context UUID is all zeros for ping)
        pingBuffer = ByteBuffer.allocate(HEADER_SIZE);
    }

    /**
     * Registers a context with the cluster.
     * This should be called when a context is initialized or reloaded.
     *
     * @param contextUuid the unique ID for this context instance
     * @param sessionManager the session manager for the context
     */
    public void registerContext(UUID contextUuid, SessionManager sessionManager) {
        sessionManagers.put(contextUuid, sessionManager);
        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("info.cluster_context_registered");
            message = MessageFormat.format(message, contextUuid,
                    sessionManager.getContext().getServletContextName());
            LOGGER.fine(message);
        }
    }

    /**
     * Unregisters a context from the cluster.
     * This should be called when a context is destroyed.
     *
     * @param contextUuid the context UUID to unregister
     */
    public void unregisterContext(UUID contextUuid) {
        SessionManager removed = sessionManagers.remove(contextUuid);
        if (removed != null && LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("info.cluster_context_unregistered");
            message = MessageFormat.format(message, contextUuid);
            LOGGER.fine(message);
        }
    }

    /**
     * Returns the session manager for a given context UUID.
     *
     * @param contextUuid the context UUID
     * @return the session manager, or null if not found
     */
    SessionManager getSessionManager(UUID contextUuid) {
        return sessionManagers.get(contextUuid);
    }

    /**
     * Returns the session manager for a given context digest.
     *
     * @param digest the context digest (MD5 hash)
     * @return the session manager, or null if not found
     */
    private SessionManager getSessionManagerByDigest(byte[] digest) {
        SessionContext context = container.getContextByDigest(digest);
        if (context == null) {
            return null;
        }
        // Find the SessionManager for this context
        for (SessionManager manager : sessionManagers.values()) {
            if (manager.getContext() == context) {
                return manager;
            }
        }
        return null;
    }

    @Override
    protected int getPort() {
        return port;
    }

    @Override
    protected String getDescription() {
        return "cluster";
    }

    @Override
    protected void configureChannel(DatagramChannel channel) throws IOException {
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.bind(new InetSocketAddress(port));

        NetworkInterface networkInterface = null;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isUp() && ni.supportsMulticast() && !ni.isLoopback()) {
                networkInterface = ni;
                break;
            }
        }
        if (networkInterface == null) {
            throw new IOException(L10N.getString("err.no_network_interface"));
        }
        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface);
        channel.join(group, networkInterface);
    }

    @Override
    public void open() throws IOException {
        super.open();
        // Channel registration is handled by DatagramServer.open()

        // Initialize metrics if telemetry is configured
        initializeMetrics();

        if (LOGGER.isLoggable(Level.INFO)) {
            String message = L10N.getString("info.cluster_started");
            message = MessageFormat.format(message, port, group.getHostAddress());
            LOGGER.info(message);
        }
        schedulePing();
    }

    /**
     * Initializes metrics for this cluster if telemetry is configured.
     */
    private void initializeMetrics() {
        TelemetryConfig config = getTelemetryConfig();
        if (config != null && config.isMetricsEnabled()) {
            metrics = new ClusterMetrics(config);
        }
    }

    @Override
    public void close() {
        if (pingTimerHandle != null) {
            pingTimerHandle.cancel();
            pingTimerHandle = null;
        }
        super.close();
    }

    private void schedulePing() {
        // Use inherited ChannelHandler.scheduleTimer() method
        pingTimerHandle = scheduleTimer(PING_FREQUENCY, new PingTimerCallback());
    }

    private void onPingTimer() {
        try {
            ping();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.cluster_ping"), e);
        }

        // Expire old nodes
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Map<UUID, Long>>> nodeIt = nodeContexts.entrySet().iterator();
             nodeIt.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Long>> nodeEntry = nodeIt.next();
            Map<UUID, Long> contexts = nodeEntry.getValue();
            for (Iterator<Map.Entry<UUID, Long>> ctxIt = contexts.entrySet().iterator();
                 ctxIt.hasNext(); ) {
                Map.Entry<UUID, Long> ctxEntry = ctxIt.next();
                if (now - ctxEntry.getValue() > NODE_EXPIRY_TIME) {
                    ctxIt.remove();
                }
            }
            if (contexts.isEmpty()) {
                nodeIt.remove();
                if (metrics != null) {
                    metrics.recordNodeLeft();
                }
            }
        }
        schedulePing();
    }

    @Override
    protected void receive(ByteBuffer data, InetSocketAddress source) {
        try {
            ByteBuffer buf = decrypt(data);
            long hi = buf.getLong();
            long lo = buf.getLong();
            UUID remoteNodeUuid = new UUID(hi, lo);
            hi = buf.getLong();
            lo = buf.getLong();
            UUID remoteContextUuid = new UUID(hi, lo);
            long msgSequence = buf.getLong();
            long msgTimestamp = buf.getLong();
            byte eventType = buf.get();

            if (!remoteNodeUuid.equals(nodeUuid)) {
                long now = System.currentTimeMillis();
                if (Math.abs(now - msgTimestamp) > MAX_TIMESTAMP_DRIFT_MS) {
                    String message = L10N.getString("warn.cluster_timestamp_rejected");
                    message = MessageFormat.format(message, remoteNodeUuid, msgTimestamp, now);
                    LOGGER.warning(message);
                    if (metrics != null) {
                        metrics.recordTimestampError();
                    }
                    return;
                }

                if (!validateAndRecordSequence(remoteNodeUuid, msgSequence)) {
                    String message = L10N.getString("warn.cluster_replay_detected");
                    message = MessageFormat.format(message, remoteNodeUuid, msgSequence);
                    LOGGER.warning(message);
                    if (metrics != null) {
                        metrics.recordReplayError();
                    }
                    return;
                }

                // Record message received
                if (metrics != null) {
                    metrics.recordMessageReceived(data.limit(), getEventTypeName(eventType));
                }

                switch (eventType) {
                    case EVENT_PING:
                        break;
                    case EVENT_REPLICATE:
                    case EVENT_PASSIVATE:
                        if (buf.remaining() > 0) {
                            receiveSession(eventType, buf);
                        }
                        break;
                    case EVENT_DELTA:
                        if (buf.remaining() > 0) {
                            receiveDelta(buf);
                        }
                        break;
                    case EVENT_FRAGMENT:
                        if (buf.remaining() > 0) {
                            receiveFragment(buf);
                        }
                        break;
                }

                // Track node and context
                boolean newNode = !nodeContexts.containsKey(remoteNodeUuid);
                Map<UUID, Long> contexts = nodeContexts.get(remoteNodeUuid);
                if (contexts == null) {
                    contexts = new ConcurrentHashMap<>();
                    nodeContexts.put(remoteNodeUuid, contexts);
                }

                // Check if this is a new context UUID for this node
                // (context UUID of all zeros is a ping, skip context tracking for pings)
                boolean isZeroContext = (remoteContextUuid.getMostSignificantBits() == 0 &&
                        remoteContextUuid.getLeastSignificantBits() == 0);
                boolean newContext = false;
                if (!isZeroContext) {
                    newContext = !contexts.containsKey(remoteContextUuid);
                    contexts.put(remoteContextUuid, now);
                }

                // Replicate based on what's new
                if (newNode) {
                    // New node - replicate all contexts
                    if (metrics != null) {
                        metrics.recordNodeJoined();
                    }
                    replicateAll();
                } else if (newContext) {
                    // Known node but new context - replicate just that context
                    replicateForRemoteContext(remoteContextUuid);
                }
            }
        } catch (GeneralSecurityException e) {
            String message = L10N.getString("err.cluster_decrypt");
            LOGGER.severe(message);
            if (metrics != null) {
                metrics.recordDecryptError();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.cluster_message"), e);
        }
    }

    /**
     * Returns a human-readable name for an event type.
     */
    private static String getEventTypeName(byte eventType) {
        switch (eventType) {
            case EVENT_REPLICATE: return "replicate";
            case EVENT_PASSIVATE: return "passivate";
            case EVENT_PING: return "ping";
            case EVENT_DELTA: return "delta";
            case EVENT_FRAGMENT: return "fragment";
            default: return "unknown";
        }
    }

    private void writeHeader(ByteBuffer buf, byte eventType, UUID contextUuid) {
        buf.putLong(nodeUuid.getMostSignificantBits());
        buf.putLong(nodeUuid.getLeastSignificantBits());
        buf.putLong(contextUuid.getMostSignificantBits());
        buf.putLong(contextUuid.getLeastSignificantBits());
        buf.putLong(sequenceNumber.incrementAndGet());
        buf.putLong(System.currentTimeMillis());
        buf.put(eventType);
    }

    private void ping() throws IOException {
        pingBuffer.clear();
        // Use zero UUID for context in ping messages
        writeHeader(pingBuffer, EVENT_PING, new UUID(0, 0));
        pingBuffer.flip();
        sendToCluster(pingBuffer, false, "ping");
    }

    /**
     * Replicates a session to the cluster.
     *
     * @param contextUuid the context UUID
     * @param session the session to replicate
     * @throws IOException if replication fails
     */
    public void replicate(UUID contextUuid, Session session) throws IOException {
        if (!session.isDirty()) {
            return;
        }
        SessionContext context = session.context;
        byte[] digest = context.getContextDigest();

        long startTime = System.currentTimeMillis();
        String replicationType;

        if (session.needsFullReplication()) {
            replicateFull(contextUuid, digest, session);
            replicationType = "full";
        } else {
            replicateDelta(contextUuid, digest, session);
            replicationType = "delta";
            if (metrics != null) {
                metrics.recordDeltaSent();
            }
        }
        session.clearDirtyState();

        // Record metrics
        if (metrics != null) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordSessionReplicated(context.getServletContextName());
            metrics.recordReplicationDuration(duration, replicationType);
        }
    }

    private void replicateFull(UUID contextUuid, byte[] digest, Session session) throws IOException {
        ByteBuffer sessionBuf = session.serialize();
        int available = MAX_DATAGRAM_SIZE - HEADER_SIZE - DIGEST_SIZE_BYTES - 4 - GCM_IV_LENGTH - GCM_TAG_LENGTH;

        if (sessionBuf.remaining() <= available) {
            ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
            writeHeader(buf, EVENT_REPLICATE, contextUuid);
            buf.put(digest);
            buf.putInt(1);
            buf.put(sessionBuf);
            buf.flip();
            sendToCluster(buf, true, "replicate");
        } else {
            sendFragmented(contextUuid, digest, EVENT_REPLICATE, sessionBuf);
        }
    }

    private void replicateDelta(UUID contextUuid, byte[] digest, Session session) throws IOException {
        ByteBuffer deltaBuf = session.serializeDelta();
        int available = MAX_DATAGRAM_SIZE - HEADER_SIZE - DIGEST_SIZE_BYTES - GCM_IV_LENGTH - GCM_TAG_LENGTH;

        if (deltaBuf.remaining() <= available) {
            ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
            writeHeader(buf, EVENT_DELTA, contextUuid);
            buf.put(digest);
            buf.put(deltaBuf);
            buf.flip();
            sendToCluster(buf, true, "delta");
        } else {
            sendFragmented(contextUuid, digest, EVENT_DELTA, deltaBuf);
        }
    }

    private void sendFragmented(UUID contextUuid, byte[] digest, byte eventType, ByteBuffer data) throws IOException {
        // Fragment header: setId(8) + fragmentIndex(2) + totalFragments(2) + eventType(1) + digest(16) = 29
        int fragmentHeaderSize = 8 + 2 + 2 + 1 + DIGEST_SIZE_BYTES;
        int maxFragmentData = MAX_DATAGRAM_SIZE - HEADER_SIZE - fragmentHeaderSize - GCM_IV_LENGTH - GCM_TAG_LENGTH;

        int totalSize = data.remaining();
        int totalFragments = (totalSize + maxFragmentData - 1) / maxFragmentData;
        long fragmentSetId = secureRandom.nextLong();

        if (LOGGER.isLoggable(Level.FINE)) {
            String message = L10N.getString("debug.cluster_fragmenting");
            message = MessageFormat.format(message, totalSize, totalFragments);
            LOGGER.fine(message);
        }

        for (int i = 0; i < totalFragments; i++) {
            int fragmentSize = Math.min(maxFragmentData, data.remaining());
            ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
            writeHeader(buf, EVENT_FRAGMENT, contextUuid);
            buf.putLong(fragmentSetId);
            buf.putShort((short) i);
            buf.putShort((short) totalFragments);
            buf.put(eventType);
            buf.put(digest);

            byte[] fragmentData = new byte[fragmentSize];
            data.get(fragmentData);
            buf.put(fragmentData);
            buf.flip();
            sendToCluster(buf, true, "fragment");

            if (metrics != null) {
                metrics.recordFragmentSent();
            }
        }
    }

    /**
     * Passivates a session (removes from cluster).
     *
     * @param contextUuid the context UUID
     * @param session the session to passivate
     * @throws IOException if passivation fails
     */
    public void passivate(UUID contextUuid, Session session) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        writeHeader(buf, EVENT_PASSIVATE, contextUuid);
        buf.put(session.context.getContextDigest());
        buf.putInt(1);
        session.serializeId(buf);
        buf.flip();
        sendToCluster(buf, true, "passivate");
    }

    private void receiveSession(byte eventType, ByteBuffer buf) throws IOException {
        byte[] digest = new byte[DIGEST_SIZE_BYTES];
        buf.get(digest);
        SessionManager sessionManager = getSessionManagerByDigest(digest);
        if (sessionManager == null) {
            String message = L10N.getString("warn.no_context_with_digest");
            LOGGER.warning(message);
            return;
        }
        SessionContext context = sessionManager.getContext();
        if (!context.isDistributable()) {
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
                        if (LOGGER.isLoggable(Level.FINE)) {
                            String message = L10N.getString("info.cluster_received_session");
                            message = MessageFormat.format(message, session.id);
                            LOGGER.finest(message);
                        }
                        sessionManager.addClusterSession(session);
                        if (metrics != null) {
                            metrics.recordSessionReceived(context.getServletContextName());
                        }
                    }
                } finally {
                    thread.setContextClassLoader(originalClassLoader);
                }
                break;
            case EVENT_PASSIVATE:
                int numSessionIds = buf.getInt();
                for (int i = 0; i < numSessionIds; i++) {
                    String sessionId = Session.deserializeId(buf);
                    sessionManager.passivateClusterSession(sessionId);
                    if (metrics != null) {
                        metrics.recordSessionPassivated(context.getServletContextName());
                    }
                }
                break;
        }
    }

    private void receiveDelta(ByteBuffer buf) throws IOException {
        byte[] digest = new byte[DIGEST_SIZE_BYTES];
        buf.get(digest);
        SessionManager sessionManager = getSessionManagerByDigest(digest);
        if (sessionManager == null) {
            String message = L10N.getString("warn.no_context_with_digest");
            LOGGER.warning(message);
            return;
        }
        SessionContext context = sessionManager.getContext();
        if (!context.isDistributable()) {
            return;
        }

        Thread thread = Thread.currentThread();
        ClassLoader contextClassLoader = context.getContextClassLoader();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(contextClassLoader);
        try {
            SessionSerializer.DeltaUpdate delta = SessionSerializer.deserializeDelta(buf);
            Session session = (Session) sessionManager.getSession(delta.sessionId);
            if (session != null) {
                if (session.applyDelta(delta.version, delta.updatedAttributes,
                        delta.removedAttributes)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        String message = L10N.getString("debug.cluster_applied_delta");
                        message = MessageFormat.format(message, delta.sessionId,
                                delta.updatedAttributes.size(), delta.removedAttributes.size());
                        LOGGER.fine(message);
                    }
                    if (metrics != null) {
                        metrics.recordDeltaReceived();
                    }
                } else {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        String message = L10N.getString("debug.cluster_stale_delta");
                        message = MessageFormat.format(message, delta.sessionId, delta.version);
                        LOGGER.fine(message);
                    }
                }
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    String message = L10N.getString("debug.cluster_missing_session");
                    message = MessageFormat.format(message, delta.sessionId);
                    LOGGER.fine(message);
                }
            }
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }
    }

    private void receiveFragment(ByteBuffer buf) throws IOException {
        long fragmentSetId = buf.getLong();
        int fragmentIndex = buf.getShort() & 0xFFFF;
        int totalFragments = buf.getShort() & 0xFFFF;
        byte originalEventType = buf.get();
        byte[] contextDigest = new byte[DIGEST_SIZE_BYTES];
        buf.get(contextDigest);

        FragmentSet fragmentSet = pendingFragments.get(fragmentSetId);
        if (fragmentSet == null) {
            if (pendingFragments.size() >= MAX_PENDING_FRAGMENTS) {
                cleanupExpiredFragments();
                if (pendingFragments.size() >= MAX_PENDING_FRAGMENTS) {
                    LOGGER.warning(L10N.getString("warn.cluster_too_many_fragments"));
                    return;
                }
            }
            fragmentSet = new FragmentSet(totalFragments, originalEventType, contextDigest);
            pendingFragments.put(fragmentSetId, fragmentSet);
        }

        if (fragmentIndex >= totalFragments || totalFragments != fragmentSet.totalFragments) {
            String message = L10N.getString("warn.cluster_invalid_fragment");
            message = MessageFormat.format(message, fragmentIndex, totalFragments);
            LOGGER.warning(message);
            return;
        }

        byte[] fragmentData = new byte[buf.remaining()];
        buf.get(fragmentData);
        fragmentSet.addFragment(fragmentIndex, fragmentData);

        if (LOGGER.isLoggable(Level.FINEST)) {
            String message = L10N.getString("debug.cluster_received_fragment");
            message = MessageFormat.format(message, fragmentIndex + 1, totalFragments);
            LOGGER.finest(message);
        }

        if (metrics != null) {
            metrics.recordFragmentReceived();
        }

        if (fragmentSet.isComplete()) {
            pendingFragments.remove(fragmentSetId);
            ByteBuffer reassembled = fragmentSet.reassemble();

            if (LOGGER.isLoggable(Level.FINE)) {
                String message = L10N.getString("debug.cluster_reassembled");
                message = MessageFormat.format(message, totalFragments, reassembled.remaining());
                LOGGER.fine(message);
            }

            if (metrics != null) {
                metrics.recordFragmentReassembled();
            }

            switch (originalEventType) {
                case EVENT_REPLICATE:
                    ByteBuffer wrappedBuf = ByteBuffer.allocate(
                            contextDigest.length + 4 + reassembled.remaining());
                    wrappedBuf.put(contextDigest);
                    wrappedBuf.putInt(1);
                    wrappedBuf.put(reassembled);
                    wrappedBuf.flip();
                    receiveSession(EVENT_REPLICATE, wrappedBuf);
                    break;
                case EVENT_DELTA:
                    ByteBuffer deltaBuf = ByteBuffer.allocate(
                            contextDigest.length + reassembled.remaining());
                    deltaBuf.put(contextDigest);
                    deltaBuf.put(reassembled);
                    deltaBuf.flip();
                    receiveDelta(deltaBuf);
                    break;
            }
        }
    }

    private void cleanupExpiredFragments() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Long, FragmentSet>> it = pendingFragments.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Long, FragmentSet> entry = it.next();
            if (now - entry.getValue().createdTime > FRAGMENT_TIMEOUT_MS) {
                it.remove();
                if (LOGGER.isLoggable(Level.FINE)) {
                    String message = L10N.getString("debug.cluster_fragment_timeout");
                    LOGGER.fine(message);
                }
                if (metrics != null) {
                    metrics.recordFragmentTimedOut();
                }
            }
        }
    }

    /**
     * Replicates all sessions from all registered contexts.
     * Called when a new node joins the cluster.
     */
    private void replicateAll() throws IOException {
        for (Map.Entry<UUID, SessionManager> entry : sessionManagers.entrySet()) {
            UUID contextUuid = entry.getKey();
            SessionManager manager = entry.getValue();
            SessionContext context = manager.getContext();
            if (context.isDistributable()) {
                replicateContextSessions(contextUuid, manager);
            }
        }
    }

    /**
     * Replicates sessions for a specific remote context.
     * Called when a known node registers a new context (e.g., after hot deployment).
     * We find the matching local context and replicate its sessions.
     *
     * @param remoteContextUuid the context UUID from the remote node
     */
    private void replicateForRemoteContext(UUID remoteContextUuid) throws IOException {
        // The remote context UUID doesn't match our local context UUIDs directly.
        // We need to replicate ALL our contexts to help the reloaded context on the remote node.
        // The remote node will use the context digest to route sessions appropriately.
        replicateAll();
    }

    private void replicateContextSessions(UUID contextUuid, SessionManager manager) throws IOException {
        SessionContext context = manager.getContext();
        byte[] digest = context.getContextDigest();
        Collection<Session> sessions = manager.getAllSessions();

        ByteBuffer buf = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        writeHeader(buf, EVENT_REPLICATE, contextUuid);
        buf.put(digest);

        int sessionCountPosition = buf.position();
        buf.putInt(0);

        int sessionCount = 0;
        synchronized (sessions) {
            for (Session session : sessions) {
                ByteBuffer sessionBuf = session.serialize();
                if (sessionBuf.remaining() <= buf.remaining()) {
                    buf.put(sessionBuf);
                    sessionCount++;
                } else {
                    buf.putInt(sessionCountPosition, sessionCount);
                    buf.flip();
                    sendToCluster(buf, true, "replicate");

                    buf.clear();
                    writeHeader(buf, EVENT_REPLICATE, contextUuid);
                    buf.put(digest);
                    sessionCountPosition = buf.position();
                    buf.putInt(0);
                    buf.put(sessionBuf);
                    sessionCount = 1;
                }
            }
        }

        if (sessionCount > 0) {
            buf.putInt(sessionCountPosition, sessionCount);
            buf.flip();
            sendToCluster(buf, true, "replicate");
        }
    }

    private void sendToCluster(ByteBuffer buf, boolean log) throws IOException {
        sendToCluster(buf, log, null);
    }

    private void sendToCluster(ByteBuffer buf, boolean log, String messageType) throws IOException {
        try {
            ByteBuffer ciphertext = encrypt(buf);
            int len = ciphertext.remaining();

            ByteBuffer loopbackBuffer = ciphertext.duplicate();
            send(loopbackBuffer, loopbackSocketAddress);
            if (log && LOGGER.isLoggable(Level.FINEST)) {
                String message = L10N.getString("info.cluster_send_unicast");
                message = MessageFormat.format(message, len, loopbackSocketAddress);
                LOGGER.finest(message);
            }

            send(ciphertext, groupSocketAddress);
            if (log && LOGGER.isLoggable(Level.FINEST)) {
                String message = L10N.getString("info.cluster_send");
                message = MessageFormat.format(message, len, groupSocketAddress);
                LOGGER.finest(message);
            }

            // Record metrics
            if (metrics != null && messageType != null) {
                metrics.recordMessageSent(len, messageType);
            }
        } catch (GeneralSecurityException e) {
            String message = L10N.getString("err.cluster_encrypt");
            LOGGER.severe(message);
        }
    }

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

    private class PingTimerCallback implements Runnable {
        @Override
        public void run() {
            onPingTimer();
        }
    }

    /**
     * Tracks sequence number state for a single remote node.
     */
    private static class NodeSequenceState {
        private long highestSeq = -1;
        private long[] windowBitmap = new long[SEQUENCE_WINDOW_SIZE / 64];

        synchronized boolean validateAndRecord(long seq) {
            if (seq > highestSeq) {
                long shift = seq - highestSeq;
                if (shift >= SEQUENCE_WINDOW_SIZE) {
                    for (int i = 0; i < windowBitmap.length; i++) {
                        windowBitmap[i] = 0;
                    }
                } else {
                    shiftBitmap((int) shift);
                }
                highestSeq = seq;
                setBit(0);
                return true;
            } else if (seq == highestSeq) {
                return false;
            } else {
                long offset = highestSeq - seq;
                if (offset >= SEQUENCE_WINDOW_SIZE) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        String message = L10N.getString("debug.cluster_seq_too_old");
                        message = MessageFormat.format(message, seq, highestSeq, SEQUENCE_WINDOW_SIZE);
                        LOGGER.fine(message);
                    }
                    return false;
                }
                if (isBitSet((int) offset)) {
                    return false;
                }
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
                int wordShift = shift / 64;
                int bitShift = shift % 64;
                for (int i = windowBitmap.length - 1; i >= wordShift; i--) {
                    windowBitmap[i] = windowBitmap[i - wordShift];
                }
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
            long carry = 0;
            for (int i = 0; i < windowBitmap.length; i++) {
                long newCarry = windowBitmap[i] >>> (64 - shift);
                windowBitmap[i] = (windowBitmap[i] << shift) | carry;
                carry = newCarry;
            }
        }
    }

    /**
     * Tracks fragments for reassembly of a large message.
     */
    private static class FragmentSet {
        final int totalFragments;
        final byte originalEventType;
        final byte[] contextDigest;
        final long createdTime;
        final byte[][] fragments;
        int receivedCount;

        FragmentSet(int totalFragments, byte originalEventType, byte[] contextDigest) {
            this.totalFragments = totalFragments;
            this.originalEventType = originalEventType;
            this.contextDigest = contextDigest;
            this.createdTime = System.currentTimeMillis();
            this.fragments = new byte[totalFragments][];
            this.receivedCount = 0;
        }

        synchronized void addFragment(int index, byte[] data) {
            if (fragments[index] == null) {
                fragments[index] = data;
                receivedCount++;
            }
        }

        synchronized boolean isComplete() {
            return receivedCount == totalFragments;
        }

        synchronized ByteBuffer reassemble() {
            int totalSize = 0;
            for (byte[] fragment : fragments) {
                totalSize += fragment.length;
            }
            ByteBuffer result = ByteBuffer.allocate(totalSize);
            for (byte[] fragment : fragments) {
                result.put(fragment);
            }
            result.flip();
            return result;
        }
    }

}

