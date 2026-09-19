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

    private byte[] pending;

    void reset() {
        pending = null;
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
        byte[] combined;
        if (pending != null) {
            combined = new byte[pending.length + payload.length];
            System.arraycopy(pending, 0, combined, 0, pending.length);
            System.arraycopy(payload, 0, combined, pending.length, payload.length);
            pending = null;
        } else {
            combined = payload;
        }
        while (combined.length >= 4) {
            int msgLen = ((combined[1] & 0xff) << 16)
                    | ((combined[2] & 0xff) << 8)
                    | (combined[3] & 0xff);
            if (msgLen < 0) {
                throw new HandshakeFormatException("invalid handshake message length");
            }
            int frameLen = 4 + msgLen;
            if (combined.length < frameLen) {
                pending = combined;
                return;
            }
            completeMessage.accept(Arrays.copyOfRange(combined, 0, frameLen));
            if (combined.length == frameLen) {
                return;
            }
            combined = Arrays.copyOfRange(combined, frameLen, combined.length);
        }
        if (combined.length > 0) {
            pending = combined;
        }
    }

    interface MessageConsumer {
        void accept(byte[] completeMessage);
    }
}
