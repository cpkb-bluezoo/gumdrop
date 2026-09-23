/*
 * HandshakeMessageReassembler.java
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

import java.util.Arrays;

/**
 * Reassembles TLS handshake messages when the record layer splits one
 * message across multiple records (RFC 8446 section 4 framing over
 * RFC 8446 section 5.1 record boundaries).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HandshakeMessageReassembler {

    private static final int TYPE_COMPRESSED_CERTIFICATE = HandshakeMessages.HANDSHAKE_TYPE_COMPRESSED_CERTIFICATE;

    private byte[] pending;
    private int streamRemaining;
    private boolean streaming;

    void reset() {
        pending = null;
        streaming = false;
        streamRemaining = 0;
    }

    /**
     * Consumes one record's handshake plaintext and invokes
     * {@code completeMessage} for each fully framed message reassembled.
     *
     * @param payload handshake bytes from one record
     * @param completeMessage callback for each complete message
     * @throws HandshakeFormatException if length prefix is malformed
     */
    void feed(byte[] payload, MessageConsumer completeMessage) throws HandshakeFormatException {
        feed(payload, completeMessage, null);
    }

    /**
     * As {@link #feed(byte[], MessageConsumer)}, but a
     * {@code CompressedCertificate} message is not buffered: its 4-byte
     * header and then its body are handed to {@code consumer} as they
     * arrive, so its size never accumulates here.
     *
     * @param payload handshake bytes from one record
     * @param consumer callback for complete messages and streamed events
     * @throws HandshakeFormatException if length prefix is malformed
     */
    void feed(byte[] payload, StreamingMessageConsumer consumer) throws HandshakeFormatException {
        feed(payload, consumer, consumer);
    }

    private void feed(byte[] payload, MessageConsumer completeMessage, StreamingMessageConsumer stream)
            throws HandshakeFormatException {
        byte[] data = payload;
        int pos = 0;
        while (true) {
            if (streaming) {
                int n = Math.min(streamRemaining, data.length - pos);
                if (n > 0) {
                    stream.streamData(Arrays.copyOfRange(data, pos, pos + n));
                    pos += n;
                    streamRemaining -= n;
                }
                if (streamRemaining > 0) {
                    return;
                }
                streaming = false;
                stream.streamEnd();
            }
            if (pending != null) {
                byte[] combined = new byte[pending.length + data.length - pos];
                System.arraycopy(pending, 0, combined, 0, pending.length);
                System.arraycopy(data, pos, combined, pending.length, data.length - pos);
                pending = null;
                data = combined;
                pos = 0;
            }
            int available = data.length - pos;
            if (available == 0) {
                return;
            }
            if (available < 4) {
                pending = Arrays.copyOfRange(data, pos, data.length);
                return;
            }
            int msgLen = ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8)
                    | (data[pos + 3] & 0xff);
            if (stream != null && (data[pos] & 0xff) == TYPE_COMPRESSED_CERTIFICATE) {
                stream.streamStart(Arrays.copyOfRange(data, pos, pos + 4));
                pos += 4;
                streaming = true;
                streamRemaining = msgLen;
                continue;
            }
            int frameLen = 4 + msgLen;
            if (available < frameLen) {
                pending = Arrays.copyOfRange(data, pos, data.length);
                return;
            }
            completeMessage.accept(Arrays.copyOfRange(data, pos, pos + frameLen));
            pos += frameLen;
        }
    }

    interface MessageConsumer {
        void accept(byte[] completeMessage);
    }

    interface StreamingMessageConsumer extends MessageConsumer {
        void streamStart(byte[] messageHeader);

        void streamData(byte[] chunk);

        void streamEnd();
    }
}
