/*
 * ConnectionMethods.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.nio.ByteBuffer;

/**
 * Encode/decode for {@code connection} class (10) method arguments.
 *
 * <p>Method frame payloads are always small (a handful of short strings
 * and integers describing the connection) and, unlike message bodies,
 * are guaranteed by the protocol to fit within a single frame — buffering
 * one whole method's arguments is therefore not a streaming concern the
 * way a message body is.
 *
 * <p>Every {@code encode*} method returns a complete method-frame payload
 * (class ID + method ID + arguments), ready to hand to
 * {@link AMQPFrame#encode}. Every {@code decode*} method reads from a
 * payload positioned just after the class ID and method ID (which the
 * caller has already dispatched on).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ConnectionMethods {

    private ConnectionMethods() {
    }

    /** {@code connection.start} (10,10) — sent by the server. */
    static final class Start {
        final int versionMajor;
        final int versionMinor;
        final FieldTable serverProperties;
        final String mechanisms;
        final String locales;

        Start(int versionMajor, int versionMinor, FieldTable serverProperties,
                String mechanisms, String locales) {
            this.versionMajor = versionMajor;
            this.versionMinor = versionMinor;
            this.serverProperties = serverProperties;
            this.mechanisms = mechanisms;
            this.locales = locales;
        }
    }

    static Start decodeStart(ByteBuffer payload) throws AMQPProtocolException {
        int major = payload.get() & 0xFF;
        int minor = payload.get() & 0xFF;
        int tableLen = payload.getInt();
        FieldTable props = FieldTable.decode(payload, tableLen);
        String mechanisms = FieldTable.getLongString(payload);
        String locales = FieldTable.getLongString(payload);
        return new Start(major, minor, props, mechanisms, locales);
    }

    static ByteBuffer encodeStart(int versionMajor, int versionMinor, FieldTable serverProperties,
            String mechanisms, String locales) {
        int size = 4 + 1 + 1
                + 4 + serverProperties.encodedContentSize()
                + FieldTable.longStringEncodedSize(mechanisms)
                + FieldTable.longStringEncodedSize(locales);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_START);
        buf.put((byte) versionMajor);
        buf.put((byte) versionMinor);
        ByteBuffer props = serverProperties.encode();
        buf.putInt(props.remaining());
        buf.put(props);
        FieldTable.putLongString(buf, mechanisms);
        FieldTable.putLongString(buf, locales);
        buf.flip();
        return buf;
    }

    /** Decoded {@code connection.start-ok} arguments — used by a server-side implementation. */
    static final class StartOk {
        final FieldTable clientProperties;
        final String mechanism;
        final byte[] response;
        final String locale;

        StartOk(FieldTable clientProperties, String mechanism, byte[] response, String locale) {
            this.clientProperties = clientProperties;
            this.mechanism = mechanism;
            this.response = response;
            this.locale = locale;
        }
    }

    static StartOk decodeStartOk(ByteBuffer payload) throws AMQPProtocolException {
        int tableLen = payload.getInt();
        FieldTable clientProperties = FieldTable.decode(payload, tableLen);
        String mechanism = FieldTable.getShortString(payload);
        int responseLen = payload.getInt();
        byte[] response = new byte[responseLen];
        payload.get(response);
        String locale = FieldTable.getShortString(payload);
        return new StartOk(clientProperties, mechanism, response, locale);
    }

    /** {@code connection.start-ok} (10,11) — sent by the client. */
    static ByteBuffer encodeStartOk(FieldTable clientProperties, String mechanism,
            byte[] response, String locale) {
        int size = 4
                + 4 + clientProperties.encodedContentSize()
                + FieldTable.shortStringEncodedSize(mechanism)
                + 4 + response.length
                + FieldTable.shortStringEncodedSize(locale);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_START_OK);
        ByteBuffer props = clientProperties.encode();
        buf.putInt(props.remaining());
        buf.put(props);
        FieldTable.putShortString(buf, mechanism);
        buf.putInt(response.length);
        buf.put(response);
        FieldTable.putShortString(buf, locale);
        buf.flip();
        return buf;
    }

    /**
     * {@code connection.secure} (10,20) — sent by the server when a
     * multi-step SASL mechanism (e.g. GSSAPI) needs another challenge
     * after {@code start-ok}, before it is ready to send {@code tune}.
     */
    static byte[] decodeSecure(ByteBuffer payload) {
        int len = payload.getInt();
        byte[] challenge = new byte[len];
        payload.get(challenge);
        return challenge;
    }

    /** {@code connection.secure-ok} (10,21) — sent by the client. */
    static ByteBuffer encodeSecureOk(byte[] response) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + response.length);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_SECURE_OK);
        buf.putInt(response.length);
        buf.put(response);
        buf.flip();
        return buf;
    }

    /** {@code connection.tune} (10,30) — sent by the server. */
    static final class Tune {
        final int channelMax;
        final long frameMax;
        final int heartbeat;

        Tune(int channelMax, long frameMax, int heartbeat) {
            this.channelMax = channelMax;
            this.frameMax = frameMax;
            this.heartbeat = heartbeat;
        }
    }

    static Tune decodeTune(ByteBuffer payload) {
        int channelMax = payload.getShort() & 0xFFFF;
        long frameMax = payload.getInt() & 0xFFFFFFFFL;
        int heartbeat = payload.getShort() & 0xFFFF;
        return new Tune(channelMax, frameMax, heartbeat);
    }

    static ByteBuffer encodeTune(int channelMax, long frameMax, int heartbeat) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 4 + 2);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_TUNE);
        buf.putShort((short) channelMax);
        buf.putInt((int) frameMax);
        buf.putShort((short) heartbeat);
        buf.flip();
        return buf;
    }

    /** {@code connection.tune-ok} (10,31) — sent by the client. */
    static ByteBuffer encodeTuneOk(int channelMax, long frameMax, int heartbeat) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 4 + 2);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_TUNE_OK);
        buf.putShort((short) channelMax);
        buf.putInt((int) frameMax);
        buf.putShort((short) heartbeat);
        buf.flip();
        return buf;
    }

    static Tune decodeTuneOk(ByteBuffer payload) {
        return decodeTune(payload);
    }

    /** {@code connection.open} (10,40) — sent by the client. */
    static ByteBuffer encodeOpen(String virtualHost) {
        int size = 4 + FieldTable.shortStringEncodedSize(virtualHost)
                + FieldTable.shortStringEncodedSize("") // reserved-1 (capabilities)
                + 1; // reserved-2 (insist bit)
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_OPEN);
        FieldTable.putShortString(buf, virtualHost);
        FieldTable.putShortString(buf, "");
        buf.put(AMQPBits.pack(false));
        buf.flip();
        return buf;
    }

    static String decodeOpen(ByteBuffer payload) throws AMQPProtocolException {
        return FieldTable.getShortString(payload); // virtual-host
    }

    /** {@code connection.open-ok} (10,41) — sent by the server; no fields we care about. */
    static void decodeOpenOk(ByteBuffer payload) throws AMQPProtocolException {
        FieldTable.getShortString(payload); // reserved-1 (known-hosts), discarded
    }

    static ByteBuffer encodeOpenOk() {
        ByteBuffer buf = ByteBuffer.allocate(4 + FieldTable.shortStringEncodedSize(""));
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_OPEN_OK);
        FieldTable.putShortString(buf, "");
        buf.flip();
        return buf;
    }

    /** {@code connection.close} / {@code channel.close} shared reply shape. */
    static final class CloseReason {
        final int replyCode;
        final String replyText;
        final int classId;
        final int methodId;

        CloseReason(int replyCode, String replyText, int classId, int methodId) {
            this.replyCode = replyCode;
            this.replyText = replyText;
            this.classId = classId;
            this.methodId = methodId;
        }
    }

    /** {@code connection.close} (10,50) — sent by either peer. */
    static ByteBuffer encodeClose(int replyCode, String replyText) {
        int size = 4 + 2 + FieldTable.shortStringEncodedSize(replyText) + 2 + 2;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_CLOSE);
        buf.putShort((short) replyCode);
        FieldTable.putShortString(buf, replyText);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.flip();
        return buf;
    }

    static CloseReason decodeClose(ByteBuffer payload) throws AMQPProtocolException {
        int replyCode = payload.getShort() & 0xFFFF;
        String replyText = FieldTable.getShortString(payload);
        int classId = payload.getShort() & 0xFFFF;
        int methodId = payload.getShort() & 0xFFFF;
        return new CloseReason(replyCode, replyText, classId, methodId);
    }

    /** {@code connection.close-ok} (10,51) — sent by either peer; no arguments. */
    static ByteBuffer encodeCloseOk() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AMQPMethod.CLASS_CONNECTION);
        buf.putShort((short) AMQPMethod.CONNECTION_CLOSE_OK);
        buf.flip();
        return buf;
    }
}
