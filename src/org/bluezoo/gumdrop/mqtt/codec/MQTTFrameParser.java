/*
 * MQTTFrameParser.java
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
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Push-parser for MQTT control packets with PUBLISH payload streaming.
 *
 * <p>Follows the same event-driven pattern as {@code H2Parser}: consumes
 * bytes from a {@link ByteBuffer} and delivers decoded events to an
 * {@link MQTTEventHandler} via typed callbacks. No intermediate packet
 * objects are allocated (except {@link ConnectPacket}).
 *
 * <p>PUBLISH packets are delivered as a streaming triple:
 * {@link MQTTEventHandler#startPublish startPublish} /
 * {@link MQTTEventHandler#publishData publishData} /
 * {@link MQTTEventHandler#endPublish endPublish}. This avoids
 * buffering the entire payload (which can be up to 256 MB) in the
 * parser. SUBSCRIBE and UNSUBSCRIBE use a similar SAX-style pattern.
 *
 * <p>The parser maintains a three-state machine:
 * <ul>
 *   <li><b>IDLE</b> — parsing fixed headers; for non-PUBLISH packets,
 *       the complete packet is buffered and dispatched as a single
 *       event call.</li>
 *   <li><b>PUBLISH_HEADER</b> — accumulating PUBLISH variable header
 *       bytes (topic name, packet ID, properties).</li>
 *   <li><b>STREAMING_PAYLOAD</b> — forwarding PUBLISH payload chunks
 *       directly from the network buffer.</li>
 * </ul>
 *
 * <p>The parser is version-aware. Call {@link #setVersion(MQTTVersion)}
 * after receiving a CONNECT packet to enable MQTT 5.0 property parsing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MQTTEventHandler
 */
public class MQTTFrameParser {

    private static final Logger LOGGER =
            Logger.getLogger(MQTTFrameParser.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    /** Default maximum packet size: 256 MB. */
    public static final int DEFAULT_MAX_PACKET_SIZE = 268_435_455;

    private final MQTTEventHandler handler;
    private int maxPacketSize = DEFAULT_MAX_PACKET_SIZE;
    private MQTTVersion version = MQTTVersion.V3_1_1;

    // Three-state machine for PUBLISH streaming
    private enum State { IDLE, PUBLISH_HEADER, STREAMING_PAYLOAD }
    private State state = State.IDLE;

    // PUBLISH streaming fields (valid in PUBLISH_HEADER / STREAMING_PAYLOAD)
    private int pubFlags;
    private int pubRemainingLength;
    private byte[] pubHeaderBuf;
    private int pubHeaderLen;
    private int pubPayloadRemaining;

    public MQTTFrameParser(MQTTEventHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        this.handler = handler;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    public MQTTVersion getVersion() {
        return version;
    }

    public void setVersion(MQTTVersion version) {
        this.version = version;
    }

    /**
     * Parses MQTT events from the buffer.
     *
     * <p>Consumes as many complete events as possible. For non-PUBLISH
     * packets, the handler callback is invoked once the complete packet
     * is available. For PUBLISH packets, streaming events are delivered
     * incrementally.
     *
     * @param buf the buffer containing packet data (in read mode)
     */
    public void receive(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            switch (state) {
                case IDLE:
                    if (!processIdle(buf)) return;
                    break;
                case PUBLISH_HEADER:
                    processPublishHeader(buf);
                    break;
                case STREAMING_PAYLOAD:
                    processStreamingPayload(buf);
                    break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // IDLE state: parse fixed header, dispatch or enter PUBLISH streaming
    // ─────────────────────────────────────────────────────────────────────

    private boolean processIdle(ByteBuffer buf) {
        int startPos = buf.position();

        if (buf.remaining() < 2) return false;

        int fixedHeader = buf.get(startPos) & 0xFF;
        int typeValue = (fixedHeader >> 4) & 0x0F;
        int flags = fixedHeader & 0x0F;

        buf.position(startPos + 1);
        int remainingLength = VariableLengthEncoding.decode(buf);

        if (remainingLength == VariableLengthEncoding.NEEDS_MORE_DATA) {
            buf.position(startPos);
            return false;
        }
        if (remainingLength == VariableLengthEncoding.MALFORMED) {
            handler.parseError(L10N.getString("err.malformed_remaining_length"));
            return false;
        }
        if (remainingLength > maxPacketSize) {
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.packet_too_large"),
                    remainingLength, maxPacketSize));
            return false;
        }

        MQTTPacketType type = MQTTPacketType.fromValue(typeValue);
        if (type == null) {
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.unknown_packet_type"), typeValue));
            return false;
        }

        if (type == MQTTPacketType.PUBLISH) {
            pubFlags = flags;
            pubRemainingLength = remainingLength;
            pubHeaderBuf = new byte[Math.min(remainingLength + 1, 512)];
            pubHeaderLen = 0;
            state = State.PUBLISH_HEADER;

            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("PUBLISH detected: flags=" + flags +
                        ", remainingLength=" + remainingLength);
            }
            return true;
        }

        // Non-PUBLISH: require complete packet body
        if (buf.remaining() < remainingLength) {
            buf.position(startPos);
            return false;
        }

        int savedLimit = buf.limit();
        buf.limit(buf.position() + remainingLength);
        ByteBuffer payload = buf.slice();
        buf.limit(savedLimit);
        buf.position(buf.position() + remainingLength);

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Parsed MQTT packet: type=" + type.name() +
                    ", flags=" + flags + ", length=" + remainingLength);
        }

        try {
            dispatchNonPublish(type, flags, payload);
        } catch (Exception e) {
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.decode_error"), type, e.getMessage()));
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLISH_HEADER state: accumulate variable header, call startPublish
    // ─────────────────────────────────────────────────────────────────────

    private void processPublishHeader(ByteBuffer buf) {
        while (buf.hasRemaining() && state == State.PUBLISH_HEADER) {
            // Grow buffer if full
            if (pubHeaderLen >= pubHeaderBuf.length) {
                int newSize = Math.min(pubRemainingLength + 1,
                        pubHeaderBuf.length * 2);
                if (newSize <= pubHeaderBuf.length) break;
                byte[] grown = new byte[newSize];
                System.arraycopy(pubHeaderBuf, 0, grown, 0, pubHeaderLen);
                pubHeaderBuf = grown;
            }

            int space = pubHeaderBuf.length - pubHeaderLen;
            int forPacket = pubRemainingLength - pubHeaderLen;
            int toCopy = Math.min(buf.remaining(), Math.min(forPacket, space));
            if (toCopy <= 0) break;

            buf.get(pubHeaderBuf, pubHeaderLen, toCopy);
            pubHeaderLen += toCopy;

            int headerSize = tryParsePublishHeader();
            if (headerSize >= 0) {
                pubPayloadRemaining = pubRemainingLength - headerSize;

                int excess = pubHeaderLen - headerSize;
                if (excess > 0) {
                    handler.publishData(
                            ByteBuffer.wrap(pubHeaderBuf, headerSize, excess));
                    pubPayloadRemaining -= excess;
                }

                pubHeaderBuf = null;

                if (pubPayloadRemaining <= 0) {
                    handler.endPublish();
                    state = State.IDLE;
                } else {
                    state = State.STREAMING_PAYLOAD;
                }
                return;
            }
        }
    }

    /**
     * Attempts to parse the PUBLISH variable header from accumulated
     * bytes. Calls {@link MQTTEventHandler#startPublish} on success.
     *
     * @return header size in bytes on success, -1 if more data needed
     */
    private int tryParsePublishHeader() {
        ByteBuffer buf = ByteBuffer.wrap(pubHeaderBuf, 0, pubHeaderLen);

        if (buf.remaining() < 2) return -1;
        int topicLen = ((buf.get(0) & 0xFF) << 8) | (buf.get(1) & 0xFF);
        if (buf.remaining() < 2 + topicLen) return -1;
        buf.getShort();
        byte[] topicBytes = new byte[topicLen];
        buf.get(topicBytes);

        int qos = (pubFlags >> 1) & 0x03;
        int packetId = 0;
        if (qos > 0) {
            if (buf.remaining() < 2) return -1;
            packetId = buf.getShort() & 0xFFFF;
        }

        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0) {
            int propStart = buf.position();
            if (!buf.hasRemaining()) return -1;
            int propLen = VariableLengthEncoding.decode(buf);
            if (propLen == VariableLengthEncoding.NEEDS_MORE_DATA) return -1;
            if (propLen == VariableLengthEncoding.MALFORMED) {
                handler.parseError(L10N.getString("err.malformed_publish_props"));
                state = State.IDLE;
                pubHeaderBuf = null;
                return -1;
            }
            if (buf.remaining() < propLen) return -1;
            buf.position(propStart);
            props = MQTTProperties.decode(buf);
        }

        int headerSize = buf.position();
        boolean dup = (pubFlags & 0x08) != 0;
        boolean retain = (pubFlags & 0x01) != 0;
        int payloadLength = pubRemainingLength - headerSize;

        handler.startPublish(dup, qos, retain,
                new String(topicBytes, StandardCharsets.UTF_8),
                packetId, props, payloadLength);

        return headerSize;
    }

    // ─────────────────────────────────────────────────────────────────────
    // STREAMING_PAYLOAD state: forward payload chunks, call endPublish
    // ─────────────────────────────────────────────────────────────────────

    private void processStreamingPayload(ByteBuffer buf) {
        int toDeliver = Math.min(buf.remaining(), pubPayloadRemaining);
        if (toDeliver > 0) {
            int savedLimit = buf.limit();
            buf.limit(buf.position() + toDeliver);
            handler.publishData(buf.slice());
            buf.limit(savedLimit);
            buf.position(buf.position() + toDeliver);
            pubPayloadRemaining -= toDeliver;
        }

        if (pubPayloadRemaining <= 0) {
            handler.endPublish();
            state = State.IDLE;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Non-PUBLISH event dispatch
    // ─────────────────────────────────────────────────────────────────────

    private void dispatchNonPublish(MQTTPacketType type,
                                    @SuppressWarnings("unused") int flags,
                                    ByteBuffer payload) {
        switch (type) {
            case CONNECT:
                handler.connect(decodeConnect(payload));
                break;
            case CONNACK:
                decodeConnAck(payload);
                break;
            case PUBACK:
                decodeSimpleAck(payload, MQTTPacketType.PUBACK);
                break;
            case PUBREC:
                decodeSimpleAck(payload, MQTTPacketType.PUBREC);
                break;
            case PUBREL:
                decodeSimpleAck(payload, MQTTPacketType.PUBREL);
                break;
            case PUBCOMP:
                decodeSimpleAck(payload, MQTTPacketType.PUBCOMP);
                break;
            case SUBSCRIBE:
                decodeSubscribe(payload);
                break;
            case SUBACK:
                decodeSubAck(payload);
                break;
            case UNSUBSCRIBE:
                decodeUnsubscribe(payload);
                break;
            case UNSUBACK:
                decodeUnsubAck(payload);
                break;
            case PINGREQ:
                handler.pingReq();
                break;
            case PINGRESP:
                handler.pingResp();
                break;
            case DISCONNECT:
                decodeDisconnect(payload);
                break;
            case AUTH:
                decodeAuth(payload);
                break;
            default:
                handler.parseError(MessageFormat.format(
                        L10N.getString("err.unexpected_packet_type"), type));
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Decoders (dispatch events directly — no packet objects)
    // ─────────────────────────────────────────────────────────────────────

    private ConnectPacket decodeConnect(ByteBuffer buf) {
        ConnectPacket packet = new ConnectPacket();

        readUTF8String(buf); // protocol name (not stored; version from level)
        int protocolLevel = buf.get() & 0xFF;

        MQTTVersion ver = MQTTVersion.fromProtocolLevel(protocolLevel);
        if (ver == null) {
            handler.parseError(MessageFormat.format(
                    L10N.getString("err.unsupported_protocol"), protocolLevel));
            return packet;
        }
        packet.setVersion(ver);
        this.version = ver;

        int connectFlags = buf.get() & 0xFF;
        boolean hasUsername = (connectFlags & 0x80) != 0;
        boolean hasPassword = (connectFlags & 0x40) != 0;
        boolean willRetain = (connectFlags & 0x20) != 0;
        int willQoSValue = (connectFlags >> 3) & 0x03;
        boolean willFlag = (connectFlags & 0x04) != 0;
        boolean cleanSession = (connectFlags & 0x02) != 0;

        packet.setCleanSession(cleanSession);

        int keepAlive = buf.getShort() & 0xFFFF;
        packet.setKeepAlive(keepAlive);

        if (ver == MQTTVersion.V5_0) {
            packet.setProperties(MQTTProperties.decode(buf));
        }

        packet.setClientId(readUTF8String(buf));

        if (willFlag) {
            packet.setWillFlag(true);
            packet.setWillQoS(QoS.fromValue(willQoSValue));
            packet.setWillRetain(willRetain);

            if (ver == MQTTVersion.V5_0) {
                packet.setWillProperties(MQTTProperties.decode(buf));
            }
            packet.setWillTopic(readUTF8String(buf));
            packet.setWillPayload(readBinaryData(buf));
        }

        if (hasUsername) {
            packet.setUsername(readUTF8String(buf));
        }
        if (hasPassword) {
            packet.setPassword(readBinaryData(buf));
        }

        return packet;
    }

    private void decodeConnAck(ByteBuffer buf) {
        int ackFlags = buf.get() & 0xFF;
        boolean sessionPresent = (ackFlags & 0x01) != 0;
        int returnCode = buf.get() & 0xFF;

        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0 && buf.hasRemaining()) {
            props = MQTTProperties.decode(buf);
        }
        handler.connAck(sessionPresent, returnCode, props);
    }

    private void decodeSimpleAck(ByteBuffer buf, MQTTPacketType type) {
        int packetId = buf.getShort() & 0xFFFF;
        int reasonCode = 0;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0 && buf.hasRemaining()) {
            reasonCode = buf.get() & 0xFF;
            if (buf.hasRemaining()) {
                props = MQTTProperties.decode(buf);
            }
        }
        switch (type) {
            case PUBACK:  handler.pubAck(packetId, reasonCode, props);  break;
            case PUBREC:  handler.pubRec(packetId, reasonCode, props);  break;
            case PUBREL:  handler.pubRel(packetId, reasonCode, props);  break;
            case PUBCOMP: handler.pubComp(packetId, reasonCode, props); break;
            default: break;
        }
    }

    private void decodeSubscribe(ByteBuffer buf) {
        int packetId = buf.getShort() & 0xFFFF;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0) {
            props = MQTTProperties.decode(buf);
        }
        handler.startSubscribe(packetId, props);
        while (buf.hasRemaining()) {
            String topicFilter = readUTF8String(buf);
            int options = buf.get() & 0xFF;
            handler.subscribeFilter(topicFilter, options & 0x03);
        }
        handler.endSubscribe();
    }

    private void decodeSubAck(ByteBuffer buf) {
        int packetId = buf.getShort() & 0xFFFF;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0) {
            props = MQTTProperties.decode(buf);
        }
        int count = buf.remaining();
        int[] returnCodes = new int[count];
        for (int i = 0; i < count; i++) {
            returnCodes[i] = buf.get() & 0xFF;
        }
        handler.subAck(packetId, props, returnCodes);
    }

    private void decodeUnsubscribe(ByteBuffer buf) {
        int packetId = buf.getShort() & 0xFFFF;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0) {
            props = MQTTProperties.decode(buf);
        }
        handler.startUnsubscribe(packetId, props);
        while (buf.hasRemaining()) {
            handler.unsubscribeFilter(readUTF8String(buf));
        }
        handler.endUnsubscribe();
    }

    private void decodeUnsubAck(ByteBuffer buf) {
        int packetId = buf.getShort() & 0xFFFF;
        MQTTProperties props = MQTTProperties.EMPTY;
        int[] reasonCodes = new int[0];
        if (version == MQTTVersion.V5_0 && buf.hasRemaining()) {
            props = MQTTProperties.decode(buf);
            reasonCodes = new int[buf.remaining()];
            for (int i = 0; i < reasonCodes.length; i++) {
                reasonCodes[i] = buf.get() & 0xFF;
            }
        }
        handler.unsubAck(packetId, props, reasonCodes);
    }

    private void decodeDisconnect(ByteBuffer buf) {
        int reasonCode = 0;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (version == MQTTVersion.V5_0 && buf.hasRemaining()) {
            reasonCode = buf.get() & 0xFF;
            if (buf.hasRemaining()) {
                props = MQTTProperties.decode(buf);
            }
        }
        handler.disconnect(reasonCode, props);
    }

    private void decodeAuth(ByteBuffer buf) {
        int reasonCode = 0;
        MQTTProperties props = MQTTProperties.EMPTY;
        if (buf.hasRemaining()) {
            reasonCode = buf.get() & 0xFF;
            if (buf.hasRemaining()) {
                props = MQTTProperties.decode(buf);
            }
        }
        handler.auth(reasonCode, props);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Wire format helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String readUTF8String(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBinaryData(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] data = new byte[len];
        buf.get(data);
        return data;
    }
}
