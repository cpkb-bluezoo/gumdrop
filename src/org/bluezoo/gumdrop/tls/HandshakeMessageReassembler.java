/*
 * HandshakeMessageReassembler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.Arrays;

/**
 * Reassembles TLS handshake messages when the record layer splits one
 * message across multiple records (RFC 8446 section 4 framing over
 * RFC 8446 section 5.1 record boundaries).
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
