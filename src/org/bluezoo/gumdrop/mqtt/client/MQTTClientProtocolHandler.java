/*
 * MQTTClientProtocolHandler.java
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

package org.bluezoo.gumdrop.mqtt.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.mqtt.broker.QoSManager;
import org.bluezoo.gumdrop.mqtt.codec.*;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageWriter;

/**
 * Client-side MQTT protocol handler.
 *
 * <p>Drives the client-side state machine: sends CONNECT, processes
 * CONNACK, handles publish/subscribe acknowledgments, and manages
 * keep-alive pings. Reuses the same codec as the server-side handler.
 *
 * <p>Incoming PUBLISH messages arrive as streaming events and are
 * accumulated via an {@link MQTTMessageWriter} before delivery
 * to the {@link MQTTMessageListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTClientProtocolHandler
        implements ProtocolHandler, MQTTEventHandler {

    private static final Logger LOGGER =
            Logger.getLogger(MQTTClientProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");
    private static final String LOG_CLIENT_ERROR = "log.client_error";

    enum State {
        CONNECTING,
        AWAITING_CONNACK,
        CONNECTED,
        DISCONNECTED
    }

    private final ConnectPacket connectPacket;
    private final MQTTClientCallback callback;
    private final MQTTMessageListener messageListener;
    private final MQTTMessageStore messageStore;
    private final MQTTFrameParser parser;
    private final QoSManager qosManager = new QoSManager();

    private Endpoint endpoint;
    private MQTTVersion version;
    private State state = State.CONNECTING;
    private TimerHandle keepAliveTimer;

    // Streaming PUBLISH accumulation
    private int rxPubQoS;
    private boolean rxPubRetain;
    private String rxPubTopicName;
    private int rxPubPacketId;
    private MQTTMessageWriter rxPubWriter;

    public MQTTClientProtocolHandler(ConnectPacket connectPacket,
                                     MQTTClientCallback callback,
                                     MQTTMessageListener messageListener,
                                     MQTTMessageStore messageStore) {
        this.connectPacket = connectPacket;
        this.callback = callback;
        this.messageListener = messageListener;
        this.messageStore = messageStore;
        this.version = connectPacket.getVersion();
        this.parser = new MQTTFrameParser(this);
        this.parser.setVersion(version);
    }

    public QoSManager getQoSManager() {
        return qosManager;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ProtocolHandler lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        state = State.AWAITING_CONNACK;
        endpoint.send(MQTTPacketEncoder.encodeConnect(connectPacket));
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void disconnected() {
        state = State.DISCONNECTED;
        cancelKeepAlive();
        if (callback != null) {
            callback.connectionLost(null);
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("log.client_tls_established"));
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, L10N.getString(LOG_CLIENT_ERROR), cause);
        state = State.DISCONNECTED;
        cancelKeepAlive();
        if (callback != null) {
            callback.connectionLost(cause);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MQTTEventHandler — received from server
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void connect(ConnectPacket packet) {
        // Client-side: not expected
    }

    @Override
    public void connAck(boolean sessionPresent, int returnCode,
                        MQTTProperties properties) {
        if (state != State.AWAITING_CONNACK) return;

        if (returnCode == CONNACK_ACCEPTED) {
            state = State.CONNECTED;
            startKeepAlive();
        } else {
            state = State.DISCONNECTED;
        }

        if (callback != null) {
            callback.connected(sessionPresent, returnCode);
        }
    }

    // ── Incoming PUBLISH streaming ─────────────────────────────────────

    @Override
    public void startPublish(boolean dup, int qos, boolean retain,
                             String topicName, int packetId,
                             MQTTProperties properties, int payloadLength) {
        if (state != State.CONNECTED) return;

        rxPubQoS = qos;
        rxPubRetain = retain;
        rxPubTopicName = topicName;
        rxPubPacketId = packetId;
        rxPubWriter = messageStore.createWriter();
    }

    @Override
    public void publishData(ByteBuffer data) {
        if (rxPubWriter != null) {
            try {
                rxPubWriter.write(data);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        L10N.getString(LOG_CLIENT_ERROR), e);
                try {
                    rxPubWriter.discard();
                } catch (IOException ignored) {
                    // Best-effort discard
                }
                rxPubWriter = null;
            }
        }
    }

    @Override
    public void endPublish() {
        if (rxPubWriter == null) return;

        MQTTMessageContent content;
        try {
            content = rxPubWriter.commit();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString(LOG_CLIENT_ERROR), e);
            return;
        } finally {
            rxPubWriter = null;
        }

        QoS qos = QoS.fromValue(rxPubQoS);
        switch (qos) {
            case AT_MOST_ONCE:
                break;
            case AT_LEAST_ONCE:
                sendPacket(MQTTPacketEncoder.encodePubAck(
                        rxPubPacketId, 0, MQTTProperties.EMPTY, version));
                break;
            case EXACTLY_ONCE: {
                if (!qosManager.isInboundQoS2Tracked(rxPubPacketId)) {
                    QoSManager.InFlightMessage inFlight =
                            new QoSManager.InFlightMessage(
                                    rxPubPacketId, rxPubTopicName,
                                    content, QoS.EXACTLY_ONCE);
                    qosManager.trackInboundQoS2(inFlight);
                }
                sendPacket(MQTTPacketEncoder.encodePubRec(
                        rxPubPacketId, 0, MQTTProperties.EMPTY, version));
                return;
            }
        }

        if (messageListener != null) {
            messageListener.messageReceived(rxPubTopicName, content,
                    rxPubQoS, rxPubRetain);
        } else {
            content.release();
        }
    }

    // ── QoS acknowledgments ────────────────────────────────────────────

    @Override
    public void pubAck(int packetId, int reasonCode,
                       MQTTProperties properties) {
        qosManager.completeQoS1Outbound(packetId);
        if (callback != null) {
            callback.publishComplete(packetId);
        }
    }

    @Override
    public void pubRec(int packetId, int reasonCode,
                       MQTTProperties properties) {
        qosManager.receivedPubRec(packetId);
        sendPacket(MQTTPacketEncoder.encodePubRel(
                packetId, 0, MQTTProperties.EMPTY, version));
    }

    @Override
    public void pubRel(int packetId, int reasonCode,
                       MQTTProperties properties) {
        QoSManager.InFlightMessage msg =
                qosManager.receivedPubRel(packetId);
        if (msg != null && messageListener != null) {
            MQTTMessageContent content = msg.getContent();
            if (content != null) {
                messageListener.messageReceived(msg.getTopic(), content,
                        msg.getQoS().getValue(), false);
            }
        }
        sendPacket(MQTTPacketEncoder.encodePubComp(
                packetId, 0, MQTTProperties.EMPTY, version));
    }

    @Override
    public void pubComp(int packetId, int reasonCode,
                        MQTTProperties properties) {
        qosManager.completePubComp(packetId);
        if (callback != null) {
            callback.publishComplete(packetId);
        }
    }

    // ── SUBSCRIBE / UNSUBSCRIBE events ─────────────────────────────────

    @Override
    public void startSubscribe(int packetId, MQTTProperties properties) {
        // Client-side: not expected
    }

    @Override
    public void subscribeFilter(String topicFilter, int qos) {
        // Client-side: not expected
    }

    @Override
    public void endSubscribe() {
        // Client-side: not expected
    }

    @Override
    public void subAck(int packetId, MQTTProperties properties,
                       int[] returnCodes) {
        if (callback != null) {
            callback.subscribeAcknowledged(packetId, returnCodes);
        }
    }

    @Override
    public void startUnsubscribe(int packetId, MQTTProperties properties) {
        // Client-side: not expected
    }

    @Override
    public void unsubscribeFilter(String topicFilter) {
        // Client-side: not expected
    }

    @Override
    public void endUnsubscribe() {
        // Client-side: not expected
    }

    @Override
    public void unsubAck(int packetId, MQTTProperties properties,
                         int[] reasonCodes) {
        // Unsubscribe confirmed
    }

    // ── Ping / Disconnect / Auth ───────────────────────────────────────

    @Override
    public void pingReq() {
        // Client-side: not expected
    }

    @Override
    public void pingResp() {
        // Keep-alive confirmed
    }

    @Override
    public void disconnect(int reasonCode, MQTTProperties properties) {
        state = State.DISCONNECTED;
        cancelKeepAlive();
        endpoint.close();
    }

    @Override
    public void auth(int reasonCode, MQTTProperties properties) {
        // Client-side: not expected
    }

    @Override
    public void parseError(String message) {
        LOGGER.warning(MessageFormat.format(
                L10N.getString("log.client_parse_error"), message));
        endpoint.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Client operations (called by MQTTClient)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sends a PUBLISH packet.
     *
     * @return the packet ID (0 for QoS 0)
     */
    public int publish(String topic, byte[] payload, QoS qos, boolean retain) {
        int packetId = 0;
        if (qos != QoS.AT_MOST_ONCE) {
            packetId = qosManager.nextPacketId();
            MQTTMessageContent content =
                    new org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore
                            .InMemoryContent(payload);
            QoSManager.InFlightMessage inFlight = new QoSManager.InFlightMessage(
                    packetId, topic, content, qos);
            qosManager.trackOutbound(inFlight);
        }

        sendPacket(MQTTPacketEncoder.encodePublish(
                topic, qos.getValue(), false, retain, packetId, payload,
                MQTTProperties.EMPTY, version));
        return packetId;
    }

    /**
     * Sends a SUBSCRIBE packet.
     *
     * @return the packet ID
     */
    public int subscribe(String[] topicFilters, QoS[] qosLevels) {
        int packetId = qosManager.nextPacketId();
        int[] qosValues = new int[qosLevels.length];
        for (int i = 0; i < qosLevels.length; i++) {
            qosValues[i] = qosLevels[i].getValue();
        }
        sendPacket(MQTTPacketEncoder.encodeSubscribe(
                packetId, topicFilters, qosValues,
                MQTTProperties.EMPTY, version));
        return packetId;
    }

    /**
     * Sends an UNSUBSCRIBE packet.
     *
     * @return the packet ID
     */
    public int unsubscribe(String[] topicFilters) {
        int packetId = qosManager.nextPacketId();
        sendPacket(MQTTPacketEncoder.encodeUnsubscribe(
                packetId, topicFilters, MQTTProperties.EMPTY, version));
        return packetId;
    }

    /**
     * Sends a DISCONNECT packet and closes the connection.
     */
    public void disconnect() {
        sendPacket(MQTTPacketEncoder.encodeDisconnect(
                DISCONNECT_NORMAL, MQTTProperties.EMPTY, version));
        state = State.DISCONNECTED;
        cancelKeepAlive();
        endpoint.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Keep-alive
    // ═══════════════════════════════════════════════════════════════════

    private void startKeepAlive() {
        int keepAlive = connectPacket.getKeepAlive();
        if (keepAlive > 0 && endpoint != null) {
            long intervalMs = keepAlive * 1000L;
            keepAliveTimer = endpoint.scheduleTimer(intervalMs,
                    this::sendPing);
        }
    }

    private void sendPing() {
        if (state == State.CONNECTED && endpoint != null
                && endpoint.isOpen()) {
            sendPacket(MQTTPacketEncoder.encodePingReq());
            int keepAlive = connectPacket.getKeepAlive();
            if (keepAlive > 0) {
                keepAliveTimer = endpoint.scheduleTimer(
                        keepAlive * 1000L, this::sendPing);
            }
        }
    }

    private void cancelKeepAlive() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }
    }

    private void sendPacket(ByteBuffer data) {
        if (endpoint != null && endpoint.isOpen()) {
            endpoint.send(data);
        }
    }
}
