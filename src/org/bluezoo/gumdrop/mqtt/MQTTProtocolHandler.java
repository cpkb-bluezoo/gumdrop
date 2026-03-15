/*
 * MQTTProtocolHandler.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.mqtt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.mqtt.broker.QoSManager;
import org.bluezoo.gumdrop.mqtt.broker.RetainedMessageStore;
import org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.broker.WillManager;
import org.bluezoo.gumdrop.mqtt.codec.*;
import org.bluezoo.gumdrop.mqtt.handler.*;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageWriter;

/**
 * Server-side MQTT protocol handler.
 *
 * <p>Implements {@link ProtocolHandler} to receive raw bytes from the
 * transport and {@link MQTTEventHandler} to process decoded events.
 * Manages the MQTT connection state machine and delegates to the
 * broker components for message routing.
 *
 * <p>PUBLISH messages arrive as streaming events ({@code startPublish},
 * {@code publishData}, {@code endPublish}). The handler writes payload
 * chunks to an {@link MQTTMessageWriter} obtained from the
 * {@link MQTTMessageStore} and processes the committed
 * {@link MQTTMessageContent} on {@code endPublish}.
 *
 * <p>Application-level decisions (connect authorization, publish/subscribe
 * ACLs) follow the async continuation-passing pattern used throughout
 * Gumdrop: handler methods receive a state interface and call back into
 * it when the decision is ready.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTProtocolHandler implements ProtocolHandler, MQTTEventHandler {

    private static final Logger LOGGER =
            Logger.getLogger(MQTTProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final String LOG_CONNECTION_ERROR = "log.connection_error";

    enum State {
        AWAITING_CONNECT,
        AWAITING_CONNECT_AUTH,
        CONNECTED,
        DISCONNECTING
    }

    private static final int STREAM_CHUNK_SIZE = 8192;

    private final MQTTListener listener;
    private final MQTTFrameParser parser;
    private final SubscriptionManager subscriptionManager;
    private final WillManager willManager;
    private final MQTTMessageStore messageStore;

    private Endpoint endpoint;
    private MQTTSession session;
    private MQTTVersion version = MQTTVersion.V3_1_1;
    private State state = State.AWAITING_CONNECT;
    private boolean cleanDisconnect;

    // Application-level handlers (optional)
    private ConnectHandler connectHandler;
    private PublishHandler publishHandler;
    private SubscribeHandler subscribeHandler;

    // Timers
    private TimerHandle connectTimer;
    private TimerHandle keepAliveTimer;

    // Streaming PUBLISH accumulation
    private int pubQoS;
    private boolean pubRetain;
    private String pubTopicName;
    private int pubPacketId;
    private MQTTMessageWriter pubWriter;

    // SAX-style SUBSCRIBE accumulation
    private SubscribeTransaction subscribeTx;

    // SAX-style UNSUBSCRIBE accumulation
    private int unsubPacketId;

    // Telemetry
    private Span sessionSpan;
    private Span authenticatedSpan;
    private long connectionTimeMillis;

    public MQTTProtocolHandler(MQTTListener listener,
                               SubscriptionManager subscriptionManager,
                               WillManager willManager,
                               MQTTMessageStore messageStore) {
        this.listener = listener;
        this.subscriptionManager = subscriptionManager;
        this.willManager = willManager;
        this.messageStore = messageStore;
        this.parser = new MQTTFrameParser(this);
    }

    public void setConnectHandler(ConnectHandler handler) {
        this.connectHandler = handler;
    }

    public void setPublishHandler(PublishHandler handler) {
        this.publishHandler = handler;
    }

    public void setSubscribeHandler(SubscribeHandler handler) {
        this.subscribeHandler = handler;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ProtocolHandler lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        this.connectionTimeMillis = System.currentTimeMillis();
        if (listener.getMaxPacketSize() > 0) {
            parser.setMaxPacketSize(listener.getMaxPacketSize());
        }
        initConnectionTrace();
        startSessionSpan();
        MQTTServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.connectionOpened();
        }
        connectTimer = endpoint.scheduleTimer(CONNECT_TIMEOUT_MS, () -> {
            if (state == State.AWAITING_CONNECT ||
                    state == State.AWAITING_CONNECT_AUTH) {
                LOGGER.fine(L10N.getString("log.connect_timeout"));
                endpoint.close();
            }
        });
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void disconnected() {
        cancelTimers();
        if (session != null && !cleanDisconnect) {
            WillManager.WillMessage will = willManager.remove(session.getClientId());
            if (will != null) {
                publishWillMessage(will);
            }
            if (session.isCleanSession()) {
                subscriptionManager.removeClient(session.getClientId());
            } else {
                session.setEndpoint(null);
            }
        }
        if (cleanDisconnect) {
            endSessionSpan(null);
        } else {
            endSessionSpanError("connection lost");
        }
        MQTTServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            long elapsed = System.currentTimeMillis() - connectionTimeMillis;
            double durationMs = elapsed;
            metrics.connectionClosed(durationMs);
        }
        if (connectHandler != null) {
            connectHandler.disconnected();
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.tls_established"), info));
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, L10N.getString(LOG_CONNECTION_ERROR),
                cause);
        endSessionSpanError(cause.getMessage());
        Trace trace = endpoint.getTrace();
        if (trace != null) {
            trace.recordException(cause);
        }
        endpoint.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    // MQTTEventHandler — event dispatch
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void connect(ConnectPacket packet) {
        if (state != State.AWAITING_CONNECT) {
            protocolViolation(MessageFormat.format(
                    L10N.getString("err.connect_wrong_state"), state));
            return;
        }
        cancelTimer(connectTimer);
        connectTimer = null;

        version = packet.getVersion();
        parser.setVersion(version);

        String clientId = packet.getClientId();
        if (clientId == null || clientId.isEmpty()) {
            if (packet.isCleanSession()) {
                clientId = UUID.randomUUID().toString();
            } else {
                sendConnAck(false, CONNACK_IDENTIFIER_REJECTED);
                endpoint.close();
                return;
            }
        }

        Realm realm = listener.getRealm();
        if (realm != null) {
            MQTTServerMetrics metrics = getServerMetrics();
            if (metrics != null) {
                metrics.authAttempt();
            }
            String username = packet.getUsername();
            byte[] password = packet.getPassword();
            if (username == null || password == null) {
                if (metrics != null) {
                    metrics.authFailure();
                }
                sendConnAck(false, CONNACK_BAD_USERNAME_PASSWORD);
                endpoint.close();
                return;
            }
            if (!realm.passwordMatch(username,
                    new String(password, StandardCharsets.UTF_8))) {
                if (metrics != null) {
                    metrics.authFailure();
                }
                sendConnAck(false, CONNACK_BAD_USERNAME_PASSWORD);
                endpoint.close();
                return;
            }
            if (metrics != null) {
                metrics.authSuccess();
            }
        }

        if (connectHandler != null) {
            state = State.AWAITING_CONNECT_AUTH;
            connectHandler.handleConnect(new ConnectStateImpl(packet, clientId),
                    packet, endpoint);
            return;
        }

        completeConnect(packet, clientId);
    }

    private void completeConnect(ConnectPacket packet, String clientId) {
        boolean sessionPresent = false;
        MQTTSession existing = subscriptionManager.getSession(clientId);
        if (existing != null) {
            if (existing.isConnected()) {
                Endpoint oldEndpoint = existing.getEndpoint();
                if (oldEndpoint != null) {
                    oldEndpoint.close();
                }
            }
            if (packet.isCleanSession()) {
                subscriptionManager.removeClient(clientId);
            } else {
                sessionPresent = true;
            }
        }

        session = new MQTTSession(clientId, version, packet.isCleanSession());
        session.setEndpoint(endpoint);
        session.setKeepAlive(packet.getKeepAlive());
        session.setUsername(packet.getUsername());
        subscriptionManager.registerSession(session);

        if (packet.isWillFlag()) {
            try {
                MQTTMessageWriter writer = messageStore.createWriter();
                byte[] willPayload = packet.getWillPayload();
                if (willPayload != null && willPayload.length > 0) {
                    writer.write(ByteBuffer.wrap(willPayload));
                }
                MQTTMessageContent willContent = writer.commit();
                willManager.set(clientId, packet.getWillTopic(),
                        willContent,
                        packet.getWillQoS() != null
                                ? packet.getWillQoS() : QoS.AT_MOST_ONCE,
                        packet.isWillRetain());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString(LOG_CONNECTION_ERROR), e);
            }
        }

        sendConnAck(sessionPresent, CONNACK_ACCEPTED);
        state = State.CONNECTED;

        addSessionAttribute("mqtt.client_id", clientId);
        addSessionAttribute("mqtt.version", version.toString());
        addSessionAttribute("mqtt.clean_session", packet.isCleanSession());
        addSessionEvent("CONNECTED");

        if (packet.getUsername() != null) {
            startAuthenticatedSpan(packet.getUsername());
        }

        if (packet.getKeepAlive() > 0) {
            long keepAliveMs = (long) (packet.getKeepAlive() * 1500);
            keepAliveTimer = endpoint.scheduleTimer(keepAliveMs,
                    this::keepAliveExpired);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.client_connected"), clientId));
        }
    }

    @Override
    public void connAck(boolean sessionPresent, int returnCode,
                        MQTTProperties properties) {
        // Server-side: not expected
    }

    // ── PUBLISH streaming ──────────────────────────────────────────────

    @Override
    public void startPublish(boolean dup, int qos, boolean retain,
                             String topicName, int packetId,
                             MQTTProperties properties, int payloadLength) {
        if (state != State.CONNECTED) {
            protocolViolation(MessageFormat.format(
                    L10N.getString("err.publish_wrong_state"), state));
            return;
        }
        resetKeepAliveTimer();

        pubQoS = qos;
        pubRetain = retain;
        pubTopicName = topicName;
        pubPacketId = packetId;
        pubWriter = messageStore.createWriter();
    }

    @Override
    public void publishData(ByteBuffer data) {
        if (pubWriter != null) {
            try {
                pubWriter.write(data);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString(LOG_CONNECTION_ERROR), e);
                try {
                    pubWriter.discard();
                } catch (IOException ignored) {
                    // Best-effort discard; already handling a write error
                }
                pubWriter = null;
            }
        }
    }

    @Override
    public void endPublish() {
        if (pubWriter == null) return;

        MQTTMessageContent content;
        try {
            content = pubWriter.commit();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString(LOG_CONNECTION_ERROR), e);
            return;
        } finally {
            pubWriter = null;
        }

        addSessionEvent("PUBLISH");
        MQTTServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.publishReceived(content.size(), pubQoS);
        }

        if (publishHandler != null) {
            publishHandler.authorizePublish(
                    new PublishStateImpl(pubTopicName, pubQoS, pubRetain,
                            pubPacketId, content),
                    session.getClientId(), pubTopicName, pubQoS, pubRetain);
            return;
        }

        completePublish(pubTopicName, pubQoS, pubRetain, pubPacketId, content);
    }

    private void completePublish(String topicName, int qos, boolean retain,
                                 int packetId, MQTTMessageContent content) {
        QoS qosEnum = QoS.fromValue(qos);
        switch (qosEnum) {
            case AT_MOST_ONCE:
                break;
            case AT_LEAST_ONCE:
                sendPacket(MQTTPacketEncoder.encodePubAck(
                        packetId, 0, MQTTProperties.EMPTY, version));
                break;
            case EXACTLY_ONCE: {
                QoSManager qosManager = session.getQoSManager();
                if (qosManager.isInboundQoS2Tracked(packetId)) {
                    content.release();
                    sendPacket(MQTTPacketEncoder.encodePubRec(
                            packetId, 0, MQTTProperties.EMPTY, version));
                    return;
                }
                QoSManager.InFlightMessage inFlight =
                        new QoSManager.InFlightMessage(
                                packetId, topicName, content,
                                QoS.EXACTLY_ONCE);
                qosManager.trackInboundQoS2(inFlight);
                sendPacket(MQTTPacketEncoder.encodePubRec(
                        packetId, 0, MQTTProperties.EMPTY, version));
                return;
            }
        }

        routePublish(topicName, content, qosEnum, retain);
    }

    private void rejectPublish(int qos, int packetId) {
        if (qos == QoS.AT_LEAST_ONCE.getValue()) {
            int reasonCode = version == MQTTVersion.V5_0 ? 0x87 : 0;
            sendPacket(MQTTPacketEncoder.encodePubAck(
                    packetId, reasonCode, MQTTProperties.EMPTY, version));
        }
    }

    // ── QoS acknowledgments ────────────────────────────────────────────

    @Override
    public void pubAck(int packetId, int reasonCode,
                       MQTTProperties properties) {
        if (state != State.CONNECTED) return;
        resetKeepAliveTimer();
        if (session != null) {
            session.getQoSManager().completeQoS1Outbound(packetId);
        }
    }

    @Override
    public void pubRec(int packetId, int reasonCode,
                       MQTTProperties properties) {
        if (state != State.CONNECTED) return;
        resetKeepAliveTimer();
        if (session != null) {
            session.getQoSManager().receivedPubRec(packetId);
            sendPacket(MQTTPacketEncoder.encodePubRel(
                    packetId, 0, MQTTProperties.EMPTY, version));
        }
    }

    @Override
    public void pubRel(int packetId, int reasonCode,
                       MQTTProperties properties) {
        if (state != State.CONNECTED) return;
        resetKeepAliveTimer();
        if (session != null) {
            QoSManager.InFlightMessage msg =
                    session.getQoSManager().receivedPubRel(packetId);
            if (msg != null) {
                routePublish(msg.getTopic(), msg.getContent(),
                        msg.getQoS(), false);
            }
            sendPacket(MQTTPacketEncoder.encodePubComp(
                    packetId, 0, MQTTProperties.EMPTY, version));
        }
    }

    @Override
    public void pubComp(int packetId, int reasonCode,
                        MQTTProperties properties) {
        if (state != State.CONNECTED) return;
        resetKeepAliveTimer();
        if (session != null) {
            session.getQoSManager().completePubComp(packetId);
        }
    }

    // ── SUBSCRIBE (SAX-style) ──────────────────────────────────────────

    @Override
    public void startSubscribe(int packetId, MQTTProperties properties) {
        if (state != State.CONNECTED) {
            protocolViolation(MessageFormat.format(
                    L10N.getString("err.subscribe_wrong_state"), state));
            return;
        }
        resetKeepAliveTimer();
        addSessionEvent("SUBSCRIBE");
        MQTTServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.subscribeReceived();
        }
        subscribeTx = new SubscribeTransaction(packetId);
    }

    @Override
    public void subscribeFilter(String topicFilter, int qos) {
        if (subscribeTx == null) return;
        subscribeTx.addFilter(topicFilter, qos);
    }

    @Override
    public void endSubscribe() {
        if (subscribeTx == null) return;
        subscribeTx.end();
        subscribeTx = null;
    }

    @Override
    public void subAck(int packetId, MQTTProperties properties,
                       int[] returnCodes) {
        // Server-side: not expected
    }

    // ── UNSUBSCRIBE (SAX-style) ────────────────────────────────────────

    @Override
    public void startUnsubscribe(int packetId, MQTTProperties properties) {
        if (state != State.CONNECTED) {
            protocolViolation(MessageFormat.format(
                    L10N.getString("err.unsubscribe_wrong_state"), state));
            return;
        }
        resetKeepAliveTimer();
        addSessionEvent("UNSUBSCRIBE");
        MQTTServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.unsubscribeReceived();
        }
        unsubPacketId = packetId;
    }

    @Override
    public void unsubscribeFilter(String topicFilter) {
        if (session != null) {
            subscriptionManager.unsubscribe(session.getClientId(), topicFilter);
        }
    }

    @Override
    public void endUnsubscribe() {
        sendPacket(MQTTPacketEncoder.encodeUnsubAck(
                unsubPacketId, new int[0], MQTTProperties.EMPTY, version));
    }

    @Override
    public void unsubAck(int packetId, MQTTProperties properties,
                         int[] reasonCodes) {
        // Server-side: not expected
    }

    // ── Ping / Disconnect / Auth ───────────────────────────────────────

    @Override
    public void pingReq() {
        if (state != State.CONNECTED) return;
        resetKeepAliveTimer();
        sendPacket(MQTTPacketEncoder.encodePingResp());
    }

    @Override
    public void pingResp() {
        // Server-side: not expected
    }

    @Override
    public void disconnect(int reasonCode, MQTTProperties properties) {
        cleanDisconnect = true;
        state = State.DISCONNECTING;
        addSessionEvent("DISCONNECT");
        if (session != null) {
            willManager.clear(session.getClientId());
            if (session.isCleanSession()) {
                subscriptionManager.removeClient(session.getClientId());
            }
        }
        endpoint.close();
    }

    @Override
    public void auth(int reasonCode, MQTTProperties properties) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.auth_not_implemented"));
        }
    }

    @Override
    public void parseError(String message) {
        LOGGER.warning(MessageFormat.format(
                L10N.getString("log.parse_error"), message));
        endpoint.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    // State implementations (async continuation callbacks)
    // ═══════════════════════════════════════════════════════════════════

    private class ConnectStateImpl implements ConnectState {

        private final ConnectPacket packet;
        private final String clientId;
        private boolean resolved;

        ConnectStateImpl(ConnectPacket packet, String clientId) {
            this.packet = packet;
            this.clientId = clientId;
        }

        @Override
        public void acceptConnection() {
            if (resolved) return;
            resolved = true;
            completeConnect(packet, clientId);
        }

        @Override
        public void rejectBadCredentials() {
            reject(CONNACK_BAD_USERNAME_PASSWORD);
        }

        @Override
        public void rejectNotAuthorized() {
            reject(CONNACK_NOT_AUTHORIZED);
        }

        @Override
        public void reject(int returnCode) {
            if (resolved) return;
            resolved = true;
            state = State.DISCONNECTING;
            sendConnAck(false, returnCode);
            endpoint.close();
        }
    }

    private class PublishStateImpl implements PublishState {

        private final String topicName;
        private final int qos;
        private final boolean retain;
        private final int packetId;
        private final MQTTMessageContent content;
        private boolean resolved;

        PublishStateImpl(String topicName, int qos, boolean retain,
                         int packetId, MQTTMessageContent content) {
            this.topicName = topicName;
            this.qos = qos;
            this.retain = retain;
            this.packetId = packetId;
            this.content = content;
        }

        @Override
        public void allowPublish() {
            if (resolved) return;
            resolved = true;
            completePublish(topicName, qos, retain, packetId, content);
        }

        @Override
        public void rejectPublish() {
            if (resolved) return;
            resolved = true;
            content.release();
            MQTTProtocolHandler.this.rejectPublish(qos, packetId);
        }
    }

    /**
     * Tracks async authorization results for all subscriptions in a
     * single SUBSCRIBE packet. Sends SUBACK when every topic filter
     * has been resolved and {@code end()} has been called.
     */
    private class SubscribeTransaction {

        private final int packetId;
        private final List<String> topicFilters = new ArrayList<>();
        private final List<Integer> returnCodes = new ArrayList<>();
        private int pending;
        private boolean ended;

        SubscribeTransaction(int packetId) {
            this.packetId = packetId;
        }

        void addFilter(String topicFilter, int qos) {
            int index = topicFilters.size();
            topicFilters.add(topicFilter);
            returnCodes.add(0);
            pending++;

            if (subscribeHandler != null) {
                subscribeHandler.authorizeSubscription(
                        stateForIndex(index),
                        session.getClientId(),
                        topicFilter,
                        QoS.fromValue(qos));
            } else {
                QoS requestedQoS = QoS.fromValue(qos);
                subscriptionManager.subscribe(session.getClientId(),
                        topicFilter, requestedQoS);
                returnCodes.set(index, requestedQoS.getValue());
                deliverRetained(topicFilter, requestedQoS);
                pending--;
            }
        }

        void end() {
            ended = true;
            checkComplete();
        }

        private void checkComplete() {
            if (ended && pending == 0) {
                int[] codes = new int[returnCodes.size()];
                for (int i = 0; i < codes.length; i++) {
                    codes[i] = returnCodes.get(i);
                }
                sendPacket(MQTTPacketEncoder.encodeSubAck(
                        packetId, codes, MQTTProperties.EMPTY, version));
            }
        }

        SubscribeState stateForIndex(final int index) {
            return new SubscribeState() {

                private boolean resolved;

                @Override
                public void grantSubscription(QoS grantedQoS) {
                    if (resolved) return;
                    resolved = true;
                    subscriptionManager.subscribe(session.getClientId(),
                            topicFilters.get(index), grantedQoS);
                    returnCodes.set(index, grantedQoS.getValue());
                    deliverRetained(topicFilters.get(index), grantedQoS);
                    pending--;
                    checkComplete();
                }

                @Override
                public void rejectSubscription() {
                    if (resolved) return;
                    resolved = true;
                    returnCodes.set(index, SUBACK_FAILURE);
                    pending--;
                    checkComplete();
                }
            };
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Message routing
    // ═══════════════════════════════════════════════════════════════════

    private void routePublish(String topic, MQTTMessageContent content,
                              QoS qos, boolean retain) {
        if (retain) {
            subscriptionManager.getRetainedStore().set(topic, content, qos);
        }

        Map<String, QoS> subscribers =
                subscriptionManager.resolveSubscribers(topic);
        if (subscribers.isEmpty()) {
            if (!retain) {
                content.release();
            }
            return;
        }

        if (content.isBuffered()) {
            byte[] payload = content.asByteArray();
            for (Map.Entry<String, QoS> entry : subscribers.entrySet()) {
                MQTTSession targetSession =
                        subscriptionManager.getSession(entry.getKey());
                if (targetSession == null || !targetSession.isConnected()) {
                    continue;
                }
                QoS effectiveQoS = qos.getValue() <= entry.getValue().getValue()
                        ? qos : entry.getValue();
                deliverBuffered(targetSession, topic, payload, effectiveQoS);
            }
        } else {
            broadcastPublish(subscribers, topic, content, qos);
        }

        if (!retain) {
            content.release();
        }
    }

    /**
     * Delivers a buffered (small) publish to a single subscriber session.
     * Encodes the full packet in one shot.
     */
    private void deliverBuffered(MQTTSession targetSession, String topic,
                                 byte[] payload, QoS qos) {
        int packetId = 0;
        if (qos != QoS.AT_MOST_ONCE) {
            packetId = targetSession.getQoSManager().nextPacketId();
            QoSManager.InFlightMessage inFlight = new QoSManager.InFlightMessage(
                    packetId, topic,
                    new org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore
                            .InMemoryContent(payload),
                    qos);
            targetSession.getQoSManager().trackOutbound(inFlight);
        }

        ByteBuffer wire = MQTTPacketEncoder.encodePublish(
                topic, qos.getValue(), false, false, packetId, payload,
                MQTTProperties.EMPTY, targetSession.getVersion());
        Endpoint ep = targetSession.getEndpoint();
        if (ep != null && ep.isOpen()) {
            ep.send(wire);
        }
    }

    /**
     * Per-subscriber delivery state used during horizontal fan-out.
     */
    private static class SubscriberDelivery {
        final Endpoint endpoint;
        final ByteBuffer header;

        SubscriberDelivery(Endpoint endpoint, ByteBuffer header) {
            this.endpoint = endpoint;
            this.header = header;
        }
    }

    /**
     * Horizontal fan-out: reads the content once and broadcasts each
     * chunk to all subscribers. Each subscriber gets its own header
     * (different packetId/QoS) but shares the same payload bytes.
     */
    private void broadcastPublish(Map<String, QoS> subscribers,
                                  String topic,
                                  MQTTMessageContent content, QoS qos) {
        List<SubscriberDelivery> deliveries = new ArrayList<>();
        for (Map.Entry<String, QoS> entry : subscribers.entrySet()) {
            MQTTSession targetSession =
                    subscriptionManager.getSession(entry.getKey());
            if (targetSession == null || !targetSession.isConnected()) {
                continue;
            }
            Endpoint ep = targetSession.getEndpoint();
            if (ep == null || !ep.isOpen()) {
                continue;
            }

            QoS effectiveQoS = qos.getValue() <= entry.getValue().getValue()
                    ? qos : entry.getValue();
            int packetId = 0;
            if (effectiveQoS != QoS.AT_MOST_ONCE) {
                packetId = targetSession.getQoSManager().nextPacketId();
                QoSManager.InFlightMessage inFlight =
                        new QoSManager.InFlightMessage(
                                packetId, topic, content, effectiveQoS);
                targetSession.getQoSManager().trackOutbound(inFlight);
            }

            ByteBuffer header = MQTTPacketEncoder.encodePublishHeader(
                    topic, effectiveQoS.getValue(), false, false, packetId,
                    content.size(), MQTTProperties.EMPTY,
                    targetSession.getVersion());
            deliveries.add(new SubscriberDelivery(ep, header));
        }

        if (deliveries.isEmpty()) {
            return;
        }

        // Phase 1: send all headers
        for (SubscriberDelivery d : deliveries) {
            if (d.endpoint.isOpen()) {
                d.endpoint.send(d.header);
            }
        }

        // Phase 2: broadcast payload chunks
        try (ReadableByteChannel ch = content.openChannel()) {
            ByteBuffer buf = ByteBuffer.allocate(STREAM_CHUNK_SIZE);
            while (ch.read(buf) != -1) {
                buf.flip();
                for (SubscriberDelivery d : deliveries) {
                    if (d.endpoint.isOpen()) {
                        d.endpoint.send(buf.duplicate());
                    }
                    buf.rewind();
                }
                buf.clear();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString(LOG_CONNECTION_ERROR), e);
        }
    }

    /**
     * Delivers a publish to the current client session (used for retained
     * messages and single-subscriber delivery).
     */
    private void deliverToClient(String topic, MQTTMessageContent content,
                                 QoS qos, boolean retain) {
        int packetId = 0;
        if (qos != QoS.AT_MOST_ONCE && session != null) {
            packetId = session.getQoSManager().nextPacketId();
            QoSManager.InFlightMessage inFlight = new QoSManager.InFlightMessage(
                    packetId, topic, content, qos);
            session.getQoSManager().trackOutbound(inFlight);
        }

        if (content.isBuffered()) {
            sendPacket(MQTTPacketEncoder.encodePublish(
                    topic, qos.getValue(), false, retain, packetId,
                    content.asByteArray(),
                    MQTTProperties.EMPTY, version));
        } else {
            sendPacket(MQTTPacketEncoder.encodePublishHeader(
                    topic, qos.getValue(), false, retain, packetId,
                    content.size(), MQTTProperties.EMPTY, version));
            streamPayload(content, endpoint);
        }
    }

    private void streamPayload(MQTTMessageContent content, Endpoint ep) {
        try (ReadableByteChannel ch = content.openChannel()) {
            ByteBuffer buf = ByteBuffer.allocate(STREAM_CHUNK_SIZE);
            while (ch.read(buf) != -1) {
                buf.flip();
                if (ep.isOpen()) {
                    ep.send(buf.duplicate());
                }
                buf.clear();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString(LOG_CONNECTION_ERROR), e);
        }
    }

    private void deliverRetained(String topicFilter, QoS grantedQoS) {
        RetainedMessageStore retainedStore =
                subscriptionManager.getRetainedStore();
        List<RetainedMessageStore.RetainedMessage> retained =
                retainedStore.match(topicFilter);
        for (RetainedMessageStore.RetainedMessage rm : retained) {
            QoS deliverQoS = rm.getQoS().getValue() <= grantedQoS.getValue()
                    ? rm.getQoS() : grantedQoS;
            deliverToClient(rm.getTopic(), rm.getContent(), deliverQoS, true);
        }
    }

    private void publishWillMessage(WillManager.WillMessage will) {
        routePublish(will.getTopic(), will.getContent(),
                will.getQoS(), will.isRetain());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Telemetry
    // ═══════════════════════════════════════════════════════════════════

    private void initConnectionTrace() {
        if (!endpoint.isTelemetryEnabled()) {
            return;
        }
        TelemetryConfig telemetryConfig = endpoint.getTelemetryConfig();
        String spanName = L10N.getString("telemetry.mqtt_connection");
        Trace trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
        endpoint.setTrace(trace);

        if (trace != null) {
            Span rootSpan = trace.getRootSpan();
            rootSpan.addAttribute("net.transport", "ip_tcp");
            if (endpoint.getRemoteAddress() != null) {
                rootSpan.addAttribute("net.peer.ip",
                        endpoint.getRemoteAddress().toString());
            }
            rootSpan.addAttribute("rpc.system", "mqtt");
        }
    }

    private void startSessionSpan() {
        Trace trace = endpoint.getTrace();
        if (trace == null) {
            return;
        }
        String spanName = L10N.getString("telemetry.mqtt_session");
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);
    }

    private void startAuthenticatedSpan(String username) {
        Trace trace = endpoint.getTrace();
        if (trace == null) {
            return;
        }
        String spanName = L10N.getString("telemetry.mqtt_authenticated");
        authenticatedSpan = trace.startSpan(spanName, SpanKind.INTERNAL);
        if (authenticatedSpan != null) {
            authenticatedSpan.addAttribute("enduser.id", username);
        }
        addSessionAttribute("enduser.id", username);
    }

    private void endSessionSpan(String message) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
        }
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        if (message != null) {
            sessionSpan.addAttribute("mqtt.result", message);
        }
        sessionSpan.setStatusOk();
        sessionSpan.end();
    }

    private void endSessionSpanError(String message) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.setStatusError(message);
            authenticatedSpan.end();
        }
        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }
        sessionSpan.setStatusError(message);
        sessionSpan.end();
    }

    private void addSessionAttribute(String key, Object value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            if (value instanceof Boolean) {
                sessionSpan.addAttribute(key, (Boolean) value);
            } else {
                sessionSpan.addAttribute(key, String.valueOf(value));
            }
        }
    }

    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    private MQTTServerMetrics getServerMetrics() {
        return listener != null ? listener.getMetrics() : null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void sendConnAck(boolean sessionPresent, int returnCode) {
        sendPacket(MQTTPacketEncoder.encodeConnAck(
                sessionPresent, returnCode, MQTTProperties.EMPTY, version));
    }

    private void sendPacket(ByteBuffer data) {
        if (endpoint != null && endpoint.isOpen()) {
            endpoint.send(data);
        }
    }

    private void protocolViolation(String message) {
        LOGGER.warning(MessageFormat.format(
                L10N.getString("log.protocol_violation"), message));
        endpoint.close();
    }

    private void keepAliveExpired() {
        if (state == State.CONNECTED) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("log.keepalive_expired"),
                    session != null ? session.getClientId() : "unknown"));
            endpoint.close();
        }
    }

    private void resetKeepAliveTimer() {
        cancelTimer(keepAliveTimer);
        if (session != null && session.getKeepAlive() > 0) {
            long keepAliveMs = (long) (session.getKeepAlive() * 1500);
            keepAliveTimer = endpoint.scheduleTimer(keepAliveMs,
                    this::keepAliveExpired);
        }
    }

    private void cancelTimers() {
        cancelTimer(connectTimer);
        cancelTimer(keepAliveTimer);
    }

    private void cancelTimer(TimerHandle timer) {
        if (timer != null) {
            timer.cancel();
        }
    }

    // -- Accessors for testing --

    State getState() {
        return state;
    }

    MQTTSession getSession() {
        return session;
    }
}
