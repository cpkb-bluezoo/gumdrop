/*
 * MQTTEventHandler.java
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

package org.bluezoo.gumdrop.mqtt.codec;

import java.nio.ByteBuffer;

/**
 * SAX-style callback interface for receiving decoded MQTT events from
 * {@link MQTTFrameParser}.
 *
 * <p>Each MQTT control packet is delivered as one or more typed method
 * calls with flat parameters — no intermediate packet objects are
 * allocated. This follows the same zero-allocation event pattern as
 * {@code H2FrameHandler} for HTTP/2.
 *
 * <p>Most packet types map to a single method call. PUBLISH, SUBSCRIBE,
 * and UNSUBSCRIBE use a streaming start/data/end pattern:
 *
 * <ul>
 *   <li><b>PUBLISH</b>: {@link #startPublish} is called once with the
 *       header fields, then {@link #publishData} is called zero or more
 *       times with payload chunks, then {@link #endPublish} signals
 *       completion.</li>
 *   <li><b>SUBSCRIBE</b>: {@link #startSubscribe} is called once, then
 *       {@link #subscribeFilter} is called one or more times (once per
 *       topic filter), then {@link #endSubscribe} signals completion.</li>
 *   <li><b>UNSUBSCRIBE</b>: {@link #startUnsubscribe} is called once,
 *       then {@link #unsubscribeFilter} is called one or more times,
 *       then {@link #endUnsubscribe} signals completion.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MQTTFrameParser
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html">MQTT 3.1.1</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html">MQTT 5.0</a>
 */
public interface MQTTEventHandler {

    // ─────────────────────────────────────────────────────────────────────
    // CONNACK return codes (MQTT 3.1.1 §3.2.2.3 Table 3.1)
    // ─────────────────────────────────────────────────────────────────────

    /** Connection accepted (0x00). */
    int CONNACK_ACCEPTED = 0x00;

    /** Connection refused: unacceptable protocol version (0x01). */
    int CONNACK_UNACCEPTABLE_PROTOCOL = 0x01;

    /** Connection refused: identifier rejected (0x02). */
    int CONNACK_IDENTIFIER_REJECTED = 0x02;

    /** Connection refused: server unavailable (0x03). */
    int CONNACK_SERVER_UNAVAILABLE = 0x03;

    /** Connection refused: bad user name or password (0x04). */
    int CONNACK_BAD_USERNAME_PASSWORD = 0x04;

    /** Connection refused: not authorized (0x05). */
    int CONNACK_NOT_AUTHORIZED = 0x05;

    // ─────────────────────────────────────────────────────────────────────
    // SUBACK return codes (MQTT 3.1.1 §3.9.3 Table 3.4)
    // ─────────────────────────────────────────────────────────────────────

    /** Subscription failure return code (0x80). */
    int SUBACK_FAILURE = 0x80;

    // ─────────────────────────────────────────────────────────────────────
    // DISCONNECT reason codes (MQTT 5.0 §3.14.2.1)
    // ─────────────────────────────────────────────────────────────────────

    /** Normal disconnection (0x00). */
    int DISCONNECT_NORMAL = 0x00;

    // ─────────────────────────────────────────────────────────────────────
    // AUTH reason codes (MQTT 5.0 §3.15.2.1)
    // ─────────────────────────────────────────────────────────────────────

    /** Authentication successful (0x00). */
    int AUTH_SUCCESS = 0x00;

    /** Continue authentication exchange (0x18). */
    int AUTH_CONTINUE = 0x18;

    /** Re-authenticate on an existing connection (0x19). */
    int AUTH_RE_AUTHENTICATE = 0x19;

    // ─────────────────────────────────────────────────────────────────────
    // Connection events
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a CONNECT packet is received.
     *
     * <p>CONNECT is the one packet type that retains a packet object due
     * to its 12+ fields (version, clean session, keep alive, client ID,
     * will fields, credentials).
     *
     * @param packet the decoded CONNECT packet
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718028">MQTT 3.1.1 §3.1</a>
     */
    void connect(ConnectPacket packet);

    /**
     * Called when a CONNACK packet is received.
     *
     * @param sessionPresent true if a stored session exists
     * @param returnCode the connect return/reason code
     * @param properties MQTT 5.0 properties ({@link MQTTProperties#EMPTY} for 3.1.1)
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718033">MQTT 3.1.1 §3.2</a>
     */
    void connAck(boolean sessionPresent, int returnCode,
                 MQTTProperties properties);

    // ─────────────────────────────────────────────────────────────────────
    // PUBLISH events (streaming)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a PUBLISH packet's variable header has been parsed.
     *
     * <p>After this call, {@link #publishData} will be called zero or
     * more times with payload chunks, followed by exactly one
     * {@link #endPublish} call.
     *
     * @param dup the DUP flag (redelivery indicator)
     * @param qos the QoS level (0, 1, or 2)
     * @param retain the RETAIN flag
     * @param topicName the topic name
     * @param packetId the packet identifier (0 for QoS 0)
     * @param properties MQTT 5.0 properties ({@link MQTTProperties#EMPTY} for 3.1.1)
     * @param payloadLength the total payload length in bytes
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718037">MQTT 3.1.1 §3.3</a>
     */
    void startPublish(boolean dup, int qos, boolean retain,
                      String topicName, int packetId,
                      MQTTProperties properties, int payloadLength);

    /**
     * Called zero or more times between {@link #startPublish} and
     * {@link #endPublish} to deliver payload data.
     *
     * <p>Multiple calls occur when the payload spans {@code receive()}
     * boundaries. The ByteBuffer is a slice — consume or copy its
     * contents before returning.
     *
     * @param data a chunk of payload data
     */
    void publishData(ByteBuffer data);

    /**
     * Called once to signal that the PUBLISH payload is complete.
     */
    void endPublish();

    // ─────────────────────────────────────────────────────────────────────
    // QoS acknowledgment events
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a PUBACK packet is received (QoS 1 acknowledgment).
     *
     * @param packetId the packet identifier
     * @param reasonCode the reason code (0 in MQTT 3.1.1)
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718043">MQTT 3.1.1 §3.4</a>
     */
    void pubAck(int packetId, int reasonCode, MQTTProperties properties);

    /**
     * Called when a PUBREC packet is received (QoS 2, step 1).
     *
     * @param packetId the packet identifier
     * @param reasonCode the reason code (0 in MQTT 3.1.1)
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718048">MQTT 3.1.1 §3.5</a>
     */
    void pubRec(int packetId, int reasonCode, MQTTProperties properties);

    /**
     * Called when a PUBREL packet is received (QoS 2, step 2).
     *
     * @param packetId the packet identifier
     * @param reasonCode the reason code (0 in MQTT 3.1.1)
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718053">MQTT 3.1.1 §3.6</a>
     */
    void pubRel(int packetId, int reasonCode, MQTTProperties properties);

    /**
     * Called when a PUBCOMP packet is received (QoS 2, final step).
     *
     * @param packetId the packet identifier
     * @param reasonCode the reason code (0 in MQTT 3.1.1)
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718058">MQTT 3.1.1 §3.7</a>
     */
    void pubComp(int packetId, int reasonCode, MQTTProperties properties);

    // ─────────────────────────────────────────────────────────────────────
    // SUBSCRIBE events (SAX-style)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a SUBSCRIBE packet begins.
     *
     * <p>After this call, {@link #subscribeFilter} will be called one or
     * more times (once per topic filter in the payload), followed by
     * exactly one {@link #endSubscribe} call.
     *
     * @param packetId the packet identifier
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718063">MQTT 3.1.1 §3.8</a>
     */
    void startSubscribe(int packetId, MQTTProperties properties);

    /**
     * Called one or more times between {@link #startSubscribe} and
     * {@link #endSubscribe}, once per topic filter in the SUBSCRIBE
     * payload.
     *
     * @param topicFilter the topic filter (may contain wildcards)
     * @param qos the requested maximum QoS level (0, 1, or 2)
     */
    void subscribeFilter(String topicFilter, int qos);

    /**
     * Called once to signal that all subscription filters have been
     * delivered.
     */
    void endSubscribe();

    // ─────────────────────────────────────────────────────────────────────
    // SUBACK event
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a SUBACK packet is received.
     *
     * @param packetId the packet identifier
     * @param properties MQTT 5.0 properties
     * @param returnCodes one return code per subscription (0/1/2 = granted QoS, 0x80 = failure)
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718068">MQTT 3.1.1 §3.9</a>
     */
    void subAck(int packetId, MQTTProperties properties, int[] returnCodes);

    // ─────────────────────────────────────────────────────────────────────
    // UNSUBSCRIBE events (SAX-style)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when an UNSUBSCRIBE packet begins.
     *
     * <p>After this call, {@link #unsubscribeFilter} will be called one
     * or more times, followed by exactly one {@link #endUnsubscribe}.
     *
     * @param packetId the packet identifier
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718072">MQTT 3.1.1 §3.10</a>
     */
    void startUnsubscribe(int packetId, MQTTProperties properties);

    /**
     * Called one or more times between {@link #startUnsubscribe} and
     * {@link #endUnsubscribe}, once per topic filter.
     *
     * @param topicFilter the topic filter to remove
     */
    void unsubscribeFilter(String topicFilter);

    /**
     * Called once to signal that all unsubscribe filters have been
     * delivered.
     */
    void endUnsubscribe();

    // ─────────────────────────────────────────────────────────────────────
    // UNSUBACK event
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when an UNSUBACK packet is received.
     *
     * @param packetId the packet identifier
     * @param properties MQTT 5.0 properties
     * @param reasonCodes per-filter reason codes (empty array for MQTT 3.1.1)
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718077">MQTT 3.1.1 §3.11</a>
     */
    void unsubAck(int packetId, MQTTProperties properties, int[] reasonCodes);

    // ─────────────────────────────────────────────────────────────────────
    // Ping events
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a PINGREQ packet is received.
     *
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718081">MQTT 3.1.1 §3.12</a>
     */
    void pingReq();

    /**
     * Called when a PINGRESP packet is received.
     *
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718086">MQTT 3.1.1 §3.13</a>
     */
    void pingResp();

    // ─────────────────────────────────────────────────────────────────────
    // Disconnect / Auth events
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when a DISCONNECT packet is received.
     *
     * @param reasonCode the reason code (0 for normal; always 0 in 3.1.1)
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718090">MQTT 3.1.1 §3.14</a>
     */
    void disconnect(int reasonCode, MQTTProperties properties);

    /**
     * Called when an AUTH packet is received (MQTT 5.0 only).
     *
     * @param reasonCode the authenticate reason code
     * @param properties MQTT 5.0 properties
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901217">MQTT 5.0 §3.15</a>
     */
    void auth(int reasonCode, MQTTProperties properties);

    // ─────────────────────────────────────────────────────────────────────
    // Error
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when the parser encounters a malformed packet.
     *
     * @param message description of the error
     */
    void parseError(String message);
}
