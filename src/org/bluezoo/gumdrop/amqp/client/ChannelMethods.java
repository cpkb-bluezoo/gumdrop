/*
 * ChannelMethods.java
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
 * Encode/decode for {@code channel} class (20) method arguments. See
 * {@link ConnectionMethods} for the streaming-vs-buffering rationale
 * (method arguments are always small and frame-bounded).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ChannelMethods {

    private ChannelMethods() {
    }

    /** {@code channel.open} (20,10) — sent by the client. */
    static ByteBuffer encodeOpen() {
        int size = 4 + FieldTable.shortStringEncodedSize(""); // reserved-1 (out-of-band)
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_OPEN);
        FieldTable.putShortString(buf, "");
        buf.flip();
        return buf;
    }

    /** {@code channel.open-ok} (20,11) — sent by the server; no fields we care about. */
    static void decodeOpenOk(ByteBuffer payload) throws AMQPProtocolException {
        int len = payload.getInt();
        payload.position(payload.position() + len); // reserved-1 (channel-id), discarded
    }

    static void decodeOpen(ByteBuffer payload) throws AMQPProtocolException {
        FieldTable.getShortString(payload); // reserved-1 (out-of-band), discarded
    }

    static ByteBuffer encodeOpenOk() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_OPEN_OK);
        buf.putInt(0); // reserved-1 (channel-id), empty
        buf.flip();
        return buf;
    }

    /** {@code channel.flow} (20,20) — sent by either peer. */
    static boolean decodeFlow(ByteBuffer payload) {
        return AMQPBits.unpack(payload.get(), 0);
    }

    static ByteBuffer encodeFlow(boolean active) {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_FLOW);
        buf.put(AMQPBits.pack(active));
        buf.flip();
        return buf;
    }

    /** {@code channel.flow-ok} (20,21) — sent by either peer. */
    static boolean decodeFlowOk(ByteBuffer payload) {
        return AMQPBits.unpack(payload.get(), 0);
    }

    static ByteBuffer encodeFlowOk(boolean active) {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_FLOW_OK);
        buf.put(AMQPBits.pack(active));
        buf.flip();
        return buf;
    }

    /** {@code channel.close} (20,40) — sent by either peer. */
    static ByteBuffer encodeClose(int replyCode, String replyText) {
        int size = 4 + 2 + FieldTable.shortStringEncodedSize(replyText) + 2 + 2;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_CLOSE);
        buf.putShort((short) replyCode);
        FieldTable.putShortString(buf, replyText);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.flip();
        return buf;
    }

    static ConnectionMethods.CloseReason decodeClose(ByteBuffer payload) throws AMQPProtocolException {
        return ConnectionMethods.decodeClose(payload);
    }

    /** {@code channel.close-ok} (20,41) — sent by either peer; no arguments. */
    static ByteBuffer encodeCloseOk() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putShort((short) AMQPMethod.CLASS_CHANNEL);
        buf.putShort((short) AMQPMethod.CHANNEL_CLOSE_OK);
        buf.flip();
        return buf;
    }
}
