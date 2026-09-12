/*
 * DtlsReassembler.java
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Reassembles DTLS handshake fragments (RFC 6347 section 4.2.3) into
 * complete TLS-shaped handshake messages for {@link Tls12HandshakeEngine}.
 * Version-agnostic -- DTLS 1.3 reuses the same 12-byte fragment header.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DtlsReassembler {

    private static final int FRAGMENT_HEADER_LEN = 12;

    private static final class PartialMessage {
        final int msgType;
        final int totalLength;
        final byte[] buffer;
        final BitSet received;
        int receivedCount;

        PartialMessage(int msgType, int totalLength) {
            this.msgType = msgType;
            this.totalLength = totalLength;
            this.buffer = new byte[totalLength];
            this.received = new BitSet(totalLength);
            this.receivedCount = 0;
        }

        void markReceived(int index, byte value) {
            if (!received.get(index)) {
                received.set(index);
                receivedCount++;
                buffer[index] = value;
            }
        }

        boolean isComplete() {
            return receivedCount == totalLength;
        }
    }

    private int nextWriteMessageSeq;
    private int nextDeliverSeq;
    private final TreeMap<Integer, PartialMessage> partials = new TreeMap<Integer, PartialMessage>();

    /**
     * Returns the next outbound {@code message_seq} and increments the counter.
     *
     * @return the message sequence number to stamp on the next flight
     */
    public int nextWriteMessageSeq() {
        return nextWriteMessageSeq++;
    }

    /**
     * Feeds one handshake record payload (fragment header + body).
     *
     * @param recordPayload the decrypted handshake record body
     * @return complete TLS-shaped messages ready for the handshake engine,
     *         in strict {@code message_seq} order
     */
    public List<byte[]> addFragment(byte[] recordPayload) throws HandshakeFormatException {
        if (recordPayload.length < FRAGMENT_HEADER_LEN) {
            throw new HandshakeFormatException("DTLS fragment header too short");
        }
        int msgType = recordPayload[0] & 0xff;
        int totalLength = ((recordPayload[1] & 0xff) << 16)
                | ((recordPayload[2] & 0xff) << 8)
                | (recordPayload[3] & 0xff);
        int messageSeq = ((recordPayload[4] & 0xff) << 8) | (recordPayload[5] & 0xff);
        int fragmentOffset = ((recordPayload[6] & 0xff) << 16)
                | ((recordPayload[7] & 0xff) << 8)
                | (recordPayload[8] & 0xff);
        int fragmentLength = ((recordPayload[9] & 0xff) << 16)
                | ((recordPayload[10] & 0xff) << 8)
                | (recordPayload[11] & 0xff);

        if (totalLength < 0 || fragmentOffset < 0 || fragmentLength < 0) {
            throw new HandshakeFormatException("negative DTLS fragment field");
        }
        if (fragmentOffset + fragmentLength > totalLength) {
            throw new HandshakeFormatException("DTLS fragment exceeds message length");
        }
        if (FRAGMENT_HEADER_LEN + fragmentLength > recordPayload.length) {
            throw new HandshakeFormatException("DTLS fragment body truncated");
        }

        if (messageSeq < nextDeliverSeq) {
            return new ArrayList<byte[]>();
        }

        PartialMessage partial = partials.get(Integer.valueOf(messageSeq));
        if (partial == null) {
            partial = new PartialMessage(msgType, totalLength);
            partials.put(Integer.valueOf(messageSeq), partial);
        } else if (partial.msgType != msgType || partial.totalLength != totalLength) {
            throw new HandshakeFormatException("conflicting DTLS fragment metadata");
        }

        int bodyOffset = FRAGMENT_HEADER_LEN;
        for (int i = 0; i < fragmentLength; i++) {
            partial.markReceived(fragmentOffset + i, recordPayload[bodyOffset + i]);
        }

        List<byte[]> ready = new ArrayList<byte[]>();
        while (true) {
            PartialMessage next = partials.get(Integer.valueOf(nextDeliverSeq));
            if (next == null || !next.isComplete()) {
                break;
            }
            partials.remove(Integer.valueOf(nextDeliverSeq));
            ready.add(frameTlsMessage(next.msgType, next.buffer));
            nextDeliverSeq++;
        }
        return ready;
    }

    private static byte[] frameTlsMessage(int msgType, byte[] body) {
        byte[] message = new byte[4 + body.length];
        message[0] = (byte) msgType;
        message[1] = (byte) ((body.length >> 16) & 0xff);
        message[2] = (byte) ((body.length >> 8) & 0xff);
        message[3] = (byte) (body.length & 0xff);
        System.arraycopy(body, 0, message, 4, body.length);
        return message;
    }

}
