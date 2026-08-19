/*
 * StreamReassembler.java
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
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Reassembles a byte stream from chunks that may arrive out of order or
 * overlapping, as both QUIC CRYPTO and STREAM frames can (RFC 9000
 * section 2.2: each frame carries an explicit byte offset within its own
 * stream, with no guarantee of in-order delivery -- the underlying
 * transport is UDP). Buffers out-of-order data until the gap preceding it
 * closes, then hands back the longest contiguous run of bytes now
 * available at the front of the stream.
 *
 * <p>Shared by {@link CryptoStreamBuffer} (one instance per {@link
 * EncryptionLevel}) and {@link org.bluezoo.gumdrop.quic.QuicConnection}
 * (one instance per QUIC stream) -- both are the same reassembly problem,
 * just fed from different frame types with different downstream
 * consumers.
 *
 * <p>Not thread-safe; used only from a single {@code SelectorLoop}
 * thread, matching every other piece of per-connection QUIC state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class StreamReassembler {

    private static final byte[] EMPTY = new byte[0];

    private final long maxBufferedBytes;
    private final NavigableMap<Long, byte[]> pending = new TreeMap<Long, byte[]>();
    private long nextOffset;
    private long bufferedBytes;

    /**
     * @param maxBufferedBytes the maximum total bytes this reassembler
     *        will hold pending reordering before {@link #receive} throws
     *        {@link BufferLimitExceededException} (RFC 9000 section 7.5's
     *        guidance to bound resources consumed by reordered data) --
     *        callers whose byte budget is already bounded elsewhere
     *        (e.g. QUIC flow control) may pass {@link Long#MAX_VALUE}
     */
    public StreamReassembler(long maxBufferedBytes) {
        this.maxBufferedBytes = maxBufferedBytes;
    }

    /**
     * Returns the next contiguous byte offset this reassembler expects --
     * i.e. how many bytes, counting from 0, have been delivered so far
     * with no gaps.
     */
    public long getNextOffset() {
        return nextOffset;
    }

    /**
     * Accepts newly received data for the byte range
     * {@code [offset, offset + data.length)}. Data already fully or
     * partially covered by {@link #getNextOffset()} is discarded or
     * trimmed; data that closes a previously buffered gap is merged in,
     * cascading through as many now-contiguous buffered chunks as apply.
     *
     * @param offset the byte offset of {@code data} within the stream
     * @param data the received chunk
     * @return the newly-contiguous bytes now available at the front of
     *         the stream, in stream order; empty if this chunk was fully
     *         duplicate or is itself out-of-order and has been buffered
     *         for later
     * @throws BufferLimitExceededException if buffering this chunk would
     *         exceed the configured limit
     */
    public byte[] receive(long offset, byte[] data) throws BufferLimitExceededException {
        if (data.length == 0) {
            return EMPTY;
        }
        long end = offset + data.length;
        if (end <= nextOffset) {
            return EMPTY; // fully duplicate/already consumed
        }
        if (offset < nextOffset) {
            // Partial overlap with already-delivered data -- trim the
            // leading portion already accounted for.
            int trim = (int) (nextOffset - offset);
            data = Arrays.copyOfRange(data, trim, data.length);
            offset = nextOffset;
        }
        if (offset > nextOffset) {
            if (bufferedBytes + data.length > maxBufferedBytes) {
                throw new BufferLimitExceededException(
                        "Reordered data would exceed buffering limit of "
                        + maxBufferedBytes + " bytes");
            }
            pending.put(Long.valueOf(offset), data);
            bufferedBytes += data.length;
            return EMPTY;
        }

        // offset == nextOffset: contiguous. Deliver it, then cascade
        // through any now-contiguous buffered chunks, trimming each
        // against the advancing cursor so overlaps between pending
        // chunks (not just against already-delivered data) resolve
        // correctly regardless of how they overlap each other.
        nextOffset += data.length;
        if (pending.isEmpty()) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, data.length);
        while (true) {
            Map.Entry<Long, byte[]> first = pending.firstEntry();
            if (first == null || first.getKey().longValue() > nextOffset) {
                break;
            }
            pending.pollFirstEntry();
            byte[] chunk = first.getValue();
            bufferedBytes -= chunk.length;
            long chunkOffset = first.getKey().longValue();
            long chunkEnd = chunkOffset + chunk.length;
            if (chunkEnd <= nextOffset) {
                continue; // fully covered by data already merged in above
            }
            int trim = (int) (nextOffset - chunkOffset);
            byte[] newPart = trim > 0 ? Arrays.copyOfRange(chunk, trim, chunk.length) : chunk;
            nextOffset += newPart.length;
            out.write(newPart, 0, newPart.length);
        }
        return out.toByteArray();
    }

    /**
     * Thrown by {@link #receive} when accepting a chunk would exceed the
     * configured buffering limit.
     */
    public static final class BufferLimitExceededException extends IOException {

        private static final long serialVersionUID = 1L;

        BufferLimitExceededException(String message) {
            super(message);
        }
    }

}
