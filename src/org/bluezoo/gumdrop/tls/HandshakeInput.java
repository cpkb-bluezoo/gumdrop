/*
 * HandshakeInput.java
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


package org.bluezoo.gumdrop.tls;

/**
 * One unit of inbound handshake work queued on a
 * {@link HandshakeAsyncScheduler}: a complete message, or one event of an
 * incrementally delivered message (see
 * {@link HandshakeEngine#beginStreamedMessage}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HandshakeInput {

    private static final int MESSAGE = 0;
    private static final int STREAM_BEGIN = 1;
    private static final int STREAM_DATA = 2;
    private static final int STREAM_END = 3;

    private final int kind;
    private final byte[] data;

    private HandshakeInput(int kind, byte[] data) {
        this.kind = kind;
        this.data = data;
    }

    static HandshakeInput message(byte[] message) {
        return new HandshakeInput(MESSAGE, message);
    }

    static HandshakeInput streamBegin(byte[] header) {
        return new HandshakeInput(STREAM_BEGIN, header);
    }

    static HandshakeInput streamData(byte[] chunk) {
        return new HandshakeInput(STREAM_DATA, chunk);
    }

    static HandshakeInput streamEnd() {
        return new HandshakeInput(STREAM_END, null);
    }

    /** The message bytes, for engines that never receive streamed input. */
    byte[] wholeMessage() {
        return data;
    }

    void dispatch(HandshakeEngine engine, TlsEventSink sink) {
        switch (kind) {
            case MESSAGE:
                engine.processMessage(data, sink);
                break;
            case STREAM_BEGIN:
                engine.beginStreamedMessage(data, sink);
                break;
            case STREAM_DATA:
                engine.streamedMessageData(data, sink);
                break;
            default:
                engine.endStreamedMessage(sink);
                break;
        }
    }
}
