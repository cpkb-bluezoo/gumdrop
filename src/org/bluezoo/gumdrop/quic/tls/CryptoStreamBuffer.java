/*
 * CryptoStreamBuffer.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reassembles a QUIC CRYPTO stream for one {@link EncryptionLevel} into
 * complete TLS handshake messages (RFC 9000 section 19.6, RFC 9001
 * section 4.1).
 *
 * <p>CRYPTO frames identify their data by byte offset within a per-level
 * stream, the same reassembly problem as a QUIC STREAM frame -- delegated
 * to the shared {@link StreamReassembler}, which buffers out-of-order or
 * overlapping frames until the gap preceding them closes. CRYPTO frames
 * are not subject to QUIC flow control the way STREAM frames are (RFC
 * 9000 section 7.5), so this buffer is capped independently as a
 * denial-of-service mitigation: a peer that keeps sending far-future
 * CRYPTO data without ever closing the gap causes {@link
 * #receiveAndExtractMessages} to throw rather than buffer unboundedly.
 *
 * <p>This class is pure reassembly: it hands back complete message
 * bytes rather than dispatching them to Agent15 itself, so that the
 * concrete {@code QuicTlsEngine} can run that dispatch through {@link
 * QuicHandshakeAsyncOffload}, off the caller's thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CryptoStreamBuffer {

    /** RFC 8446 section 4: handshake message header is type(1) + length(3) octets. */
    private static final int MESSAGE_HEADER_LENGTH = 4;

    // Generous for any real TLS 1.3 handshake flight (ClientHello,
    // EncryptedExtensions+Certificate+CertificateVerify+Finished) while
    // still bounding how much reordered data a misbehaving or malicious
    // peer can make this endpoint buffer before the handshake completes.
    private static final long MAX_BUFFERED_BYTES = 65536;

    private final StreamReassembler reassembler = new StreamReassembler(MAX_BUFFERED_BYTES);
    private final GrowableBuffer accumulator = new GrowableBuffer();
    private int consumedLength;

    /**
     * A {@link ByteArrayOutputStream} exposing its backing array and
     * length directly, so the reassembly loop below can read already
     * -written bytes -- and discard an already-consumed prefix -- without
     * paying for {@link ByteArrayOutputStream#toByteArray()}'s full-buffer
     * copy on every access.
     */
    private static final class GrowableBuffer extends ByteArrayOutputStream {

        byte[] array() {
            return buf;
        }

        int length() {
            return count;
        }

        /** Discards the first {@code n} bytes, shifting any remainder to the front. */
        void discard(int n) {
            if (n <= 0) {
                return;
            }
            System.arraycopy(buf, n, buf, 0, count - n);
            count -= n;
        }
    }

    /**
     * Accepts newly received CRYPTO frame data and returns every complete
     * handshake message now available, in order. Messages that were
     * already reassembled by an earlier call are never returned again.
     *
     * <p>Each returned buffer wraps a fresh, independent copy of its
     * message bytes (from {@link ByteArrayOutputStream#toByteArray()}),
     * so it remains valid to read at any later time, even after
     * subsequent calls to this method mutate the underlying accumulator.
     *
     * @param offset the byte offset of {@code data} within this level's CRYPTO stream
     * @param data the received handshake data
     * @return the complete handshake messages now available, in stream
     *         order, or an empty list if none are yet complete
     * @throws StreamReassembler.BufferLimitExceededException if reordered
     *         data exceeds this buffer's configured limit
     */
    public List<ByteBuffer> receiveAndExtractMessages(long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        byte[] contiguous = reassembler.receive(offset, chunk);
        if (contiguous.length == 0) {
            return Collections.emptyList();
        }
        accumulator.write(contiguous, 0, contiguous.length);

        List<ByteBuffer> messages = new ArrayList<ByteBuffer>();
        byte[] buffered = accumulator.array();
        while (true) {
            int available = accumulator.length() - consumedLength;
            if (available < MESSAGE_HEADER_LENGTH) {
                break;
            }

            int messageDataLength = ((buffered[consumedLength + 1] & 0xff) << 16)
                    | ((buffered[consumedLength + 2] & 0xff) << 8)
                    | (buffered[consumedLength + 3] & 0xff);
            int fullMessageLength = MESSAGE_HEADER_LENGTH + messageDataLength;
            if (available < fullMessageLength) {
                break;
            }

            byte[] message = new byte[fullMessageLength];
            System.arraycopy(buffered, consumedLength, message, 0, fullMessageLength);
            messages.add(ByteBuffer.wrap(message));
            consumedLength += fullMessageLength;
        }
        // Shift any already-consumed prefix out of the buffer now rather
        // than letting it grow for the life of the handshake -- the next
        // call's array()/discard() calls then only ever touch this call's
        // as-yet-unconsumed tail, not the whole stream received so far.
        accumulator.discard(consumedLength);
        consumedLength = 0;
        return messages;
    }
}
