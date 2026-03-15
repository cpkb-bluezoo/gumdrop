/*
 * MQTTPacketEncoder.java
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

/**
 * Encodes MQTT control packets to wire format.
 *
 * <p>Each {@code encode*} method returns a {@link ByteBuffer} in read
 * mode containing the complete packet ready for transmission.
 *
 * <p>All methods use flat parameter signatures — no packet objects
 * are required except for {@link #encodeConnect(ConnectPacket)} which
 * retains the packet object due to its 12+ fields.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MQTTPacketEncoder {

    private MQTTPacketEncoder() {
    }

    public static ByteBuffer encodeConnect(ConnectPacket packet) {
        MQTTVersion ver = packet.getVersion();
        boolean v5 = ver == MQTTVersion.V5_0;

        byte[] clientIdBytes = utf8Bytes(packet.getClientId());
        byte[] willTopicBytes = null;
        byte[] usernameBytes = null;

        int payloadLen = 2 + clientIdBytes.length;

        if (packet.isWillFlag()) {
            willTopicBytes = utf8Bytes(packet.getWillTopic());
            payloadLen += 2 + willTopicBytes.length;
            byte[] willPayload = packet.getWillPayload();
            payloadLen += 2 + (willPayload != null ? willPayload.length : 0);
            if (v5 && packet.getWillProperties() != null) {
                MQTTProperties wp = packet.getWillProperties();
                int wpLen = wp.encodedLength();
                payloadLen += VariableLengthEncoding.encodedLength(wpLen) + wpLen;
            }
        }
        if (packet.getUsername() != null) {
            usernameBytes = utf8Bytes(packet.getUsername());
            payloadLen += 2 + usernameBytes.length;
        }
        if (packet.getPassword() != null) {
            payloadLen += 2 + packet.getPassword().length;
        }

        byte[] protocolNameBytes = utf8Bytes(ver.getProtocolName());
        int varHeaderLen = 2 + protocolNameBytes.length + 1 + 1 + 2;

        if (v5) {
            MQTTProperties props = packet.getProperties();
            int pLen = props.encodedLength();
            varHeaderLen += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }

        int remainingLength = varHeaderLen + payloadLen;
        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.CONNECT.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);

        writeUTF8String(buf, protocolNameBytes);
        buf.put((byte) ver.getProtocolLevel());

        int connectFlags = 0;
        if (packet.isCleanSession()) connectFlags |= 0x02;
        if (packet.isWillFlag()) {
            connectFlags |= 0x04;
            if (packet.getWillQoS() != null) {
                connectFlags |= (packet.getWillQoS().getValue() << 3);
            }
            if (packet.isWillRetain()) connectFlags |= 0x20;
        }
        if (packet.getPassword() != null) connectFlags |= 0x40;
        if (packet.getUsername() != null) connectFlags |= 0x80;
        buf.put((byte) connectFlags);
        buf.putShort((short) packet.getKeepAlive());

        if (v5) {
            packet.getProperties().encode(buf);
        }

        writeUTF8String(buf, clientIdBytes);

        if (packet.isWillFlag()) {
            if (v5 && packet.getWillProperties() != null) {
                packet.getWillProperties().encode(buf);
            }
            writeUTF8String(buf, willTopicBytes);
            byte[] willPayload = packet.getWillPayload();
            if (willPayload != null) {
                buf.putShort((short) willPayload.length);
                buf.put(willPayload);
            } else {
                buf.putShort((short) 0);
            }
        }
        if (usernameBytes != null) {
            writeUTF8String(buf, usernameBytes);
        }
        if (packet.getPassword() != null) {
            buf.putShort((short) packet.getPassword().length);
            buf.put(packet.getPassword());
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeConnAck(boolean sessionPresent,
                                           int returnCode,
                                           MQTTProperties props,
                                           MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;
        int propsEncodedLen = 0;
        if (v5) {
            int pLen = props.encodedLength();
            propsEncodedLen = VariableLengthEncoding.encodedLength(pLen) + pLen;
        }

        int remainingLength = 2 + propsEncodedLen;
        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.CONNACK.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.put((byte) (sessionPresent ? 0x01 : 0x00));
        buf.put((byte) returnCode);

        if (v5) {
            props.encode(buf);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodePublish(String topic, int qos,
                                           boolean dup, boolean retain,
                                           int packetId, byte[] payload,
                                           MQTTProperties props,
                                           MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;
        byte[] topicBytes = utf8Bytes(topic);
        if (payload == null) payload = new byte[0];

        int remainingLength = 2 + topicBytes.length;
        if (qos > 0) {
            remainingLength += 2;
        }
        if (v5) {
            int pLen = props.encodedLength();
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }
        remainingLength += payload.length;

        int flags = 0;
        if (dup) flags |= 0x08;
        flags |= (qos << 1);
        if (retain) flags |= 0x01;

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) ((MQTTPacketType.PUBLISH.getValue() << 4) | flags));
        VariableLengthEncoding.encode(buf, remainingLength);
        writeUTF8String(buf, topicBytes);
        if (qos > 0) {
            buf.putShort((short) packetId);
        }
        if (v5) {
            props.encode(buf);
        }
        buf.put(payload);

        buf.flip();
        return buf;
    }

    /**
     * Encodes just the PUBLISH fixed header and variable header, without
     * the payload. Used for streaming delivery where the payload is sent
     * separately via chunked {@link org.bluezoo.gumdrop.Endpoint#send}
     * calls from an {@link java.nio.channels.ReadableByteChannel}.
     *
     * <p>The remaining length in the fixed header includes the payload
     * size, so the receiver can determine the frame boundary.
     *
     * @param topic the topic name
     * @param qos the QoS level (0, 1, or 2)
     * @param dup the DUP flag
     * @param retain the RETAIN flag
     * @param packetId the packet identifier (0 for QoS 0)
     * @param payloadSize the payload size in bytes
     * @param props MQTT 5.0 properties
     * @param version the MQTT version
     * @return a ByteBuffer containing the fixed + variable header
     */
    public static ByteBuffer encodePublishHeader(String topic, int qos,
                                                 boolean dup, boolean retain,
                                                 int packetId,
                                                 long payloadSize,
                                                 MQTTProperties props,
                                                 MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;
        byte[] topicBytes = utf8Bytes(topic);

        int variableHeaderLen = 2 + topicBytes.length;
        if (qos > 0) {
            variableHeaderLen += 2;
        }
        if (v5) {
            int pLen = props.encodedLength();
            variableHeaderLen += VariableLengthEncoding.encodedLength(pLen)
                    + pLen;
        }

        int remainingLength = variableHeaderLen + (int) payloadSize;

        int flags = 0;
        if (dup) flags |= 0x08;
        flags |= (qos << 1);
        if (retain) flags |= 0x01;

        int headerLen = 1
                + VariableLengthEncoding.encodedLength(remainingLength)
                + variableHeaderLen;
        ByteBuffer buf = ByteBuffer.allocate(headerLen);

        buf.put((byte) ((MQTTPacketType.PUBLISH.getValue() << 4) | flags));
        VariableLengthEncoding.encode(buf, remainingLength);
        writeUTF8String(buf, topicBytes);
        if (qos > 0) {
            buf.putShort((short) packetId);
        }
        if (v5) {
            props.encode(buf);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodePubAck(int packetId, int reasonCode,
                                          MQTTProperties props,
                                          MQTTVersion version) {
        return encodeSimpleAck(MQTTPacketType.PUBACK, 0,
                packetId, reasonCode, props, version);
    }

    public static ByteBuffer encodePubRec(int packetId, int reasonCode,
                                          MQTTProperties props,
                                          MQTTVersion version) {
        return encodeSimpleAck(MQTTPacketType.PUBREC, 0,
                packetId, reasonCode, props, version);
    }

    public static ByteBuffer encodePubRel(int packetId, int reasonCode,
                                          MQTTProperties props,
                                          MQTTVersion version) {
        return encodeSimpleAck(MQTTPacketType.PUBREL, 0x02,
                packetId, reasonCode, props, version);
    }

    public static ByteBuffer encodePubComp(int packetId, int reasonCode,
                                           MQTTProperties props,
                                           MQTTVersion version) {
        return encodeSimpleAck(MQTTPacketType.PUBCOMP, 0,
                packetId, reasonCode, props, version);
    }

    public static ByteBuffer encodeSubscribe(int packetId,
                                             String[] topicFilters,
                                             int[] qosLevels,
                                             MQTTProperties props,
                                             MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;

        int remainingLength = 2;
        if (v5) {
            int pLen = props.encodedLength();
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }
        byte[][] topicBytes = new byte[topicFilters.length][];
        for (int i = 0; i < topicFilters.length; i++) {
            topicBytes[i] = utf8Bytes(topicFilters[i]);
            remainingLength += 2 + topicBytes[i].length + 1;
        }

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) ((MQTTPacketType.SUBSCRIBE.getValue() << 4) | 0x02));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.putShort((short) packetId);

        if (v5) {
            props.encode(buf);
        }

        for (int i = 0; i < topicFilters.length; i++) {
            writeUTF8String(buf, topicBytes[i]);
            buf.put((byte) qosLevels[i]);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeSubAck(int packetId, int[] returnCodes,
                                          MQTTProperties props,
                                          MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;

        int remainingLength = 2;
        if (v5) {
            int pLen = props.encodedLength();
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }
        remainingLength += returnCodes.length;

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.SUBACK.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.putShort((short) packetId);

        if (v5) {
            props.encode(buf);
        }

        for (int code : returnCodes) {
            buf.put((byte) code);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeUnsubscribe(int packetId,
                                               String[] topicFilters,
                                               MQTTProperties props,
                                               MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;

        int remainingLength = 2;
        if (v5) {
            int pLen = props.encodedLength();
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }
        byte[][] filterBytes = new byte[topicFilters.length][];
        for (int i = 0; i < topicFilters.length; i++) {
            filterBytes[i] = utf8Bytes(topicFilters[i]);
            remainingLength += 2 + filterBytes[i].length;
        }

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) ((MQTTPacketType.UNSUBSCRIBE.getValue() << 4) | 0x02));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.putShort((short) packetId);

        if (v5) {
            props.encode(buf);
        }

        for (byte[] fb : filterBytes) {
            writeUTF8String(buf, fb);
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeUnsubAck(int packetId, int[] reasonCodes,
                                            MQTTProperties props,
                                            MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;

        int remainingLength = 2;
        if (v5) {
            int pLen = props.encodedLength();
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
            remainingLength += reasonCodes.length;
        }

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.UNSUBACK.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.putShort((short) packetId);

        if (v5) {
            props.encode(buf);
            for (int code : reasonCodes) {
                buf.put((byte) code);
            }
        }

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodePingReq() {
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.put((byte) (MQTTPacketType.PINGREQ.getValue() << 4));
        buf.put((byte) 0);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodePingResp() {
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.put((byte) (MQTTPacketType.PINGRESP.getValue() << 4));
        buf.put((byte) 0);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeDisconnect(int reasonCode,
                                              MQTTProperties props,
                                              MQTTVersion version) {
        if (version != MQTTVersion.V5_0) {
            ByteBuffer buf = ByteBuffer.allocate(2);
            buf.put((byte) (MQTTPacketType.DISCONNECT.getValue() << 4));
            buf.put((byte) 0);
            buf.flip();
            return buf;
        }

        int remainingLength = 1;
        int pLen = props.encodedLength();
        remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.DISCONNECT.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.put((byte) reasonCode);
        props.encode(buf);

        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeAuth(int reasonCode, MQTTProperties props) {
        int remainingLength = 1;
        int pLen = props.encodedLength();
        remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.put((byte) (MQTTPacketType.AUTH.getValue() << 4));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.put((byte) reasonCode);
        props.encode(buf);

        buf.flip();
        return buf;
    }

    // -- Helpers --

    private static ByteBuffer encodeSimpleAck(MQTTPacketType type,
                                              int fixedFlags,
                                              int packetId, int reasonCode,
                                              MQTTProperties props,
                                              MQTTVersion version) {
        boolean v5 = version == MQTTVersion.V5_0;

        if (!v5) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.put((byte) ((type.getValue() << 4) | fixedFlags));
            buf.put((byte) 2);
            buf.putShort((short) packetId);
            buf.flip();
            return buf;
        }

        int remainingLength = 2 + 1;
        int pLen = props.encodedLength();
        if (pLen > 0) {
            remainingLength += VariableLengthEncoding.encodedLength(pLen) + pLen;
        }

        int totalLen = 1 + VariableLengthEncoding.encodedLength(remainingLength)
                + remainingLength;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.put((byte) ((type.getValue() << 4) | fixedFlags));
        VariableLengthEncoding.encode(buf, remainingLength);
        buf.putShort((short) packetId);
        buf.put((byte) reasonCode);
        if (pLen > 0) {
            props.encode(buf);
        }
        buf.flip();
        return buf;
    }

    private static byte[] utf8Bytes(String s) {
        return s != null ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    private static void writeUTF8String(ByteBuffer buf, byte[] bytes) {
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }
}
