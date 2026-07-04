/*
 * FTPAsciiLineEndings.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * NVT-ASCII line-ending conversion for FTP TYPE A (ASCII) transfers, per
 * RFC 959 section 3.1.1.1.
 *
 * <p>On the wire, ASCII-type data uses the NVT convention in which every
 * end-of-line is the two-byte sequence CRLF.  The server converts between
 * this network representation and its local text convention (a bare LF on
 * this platform):
 *
 * <ul>
 * <li><strong>Download (server &rarr; client)</strong>: a bare LF is expanded
 *     to CRLF by {@link #encode(ByteBuffer)}; an existing CRLF is preserved.
 *     Because a CRLF pair can straddle a chunk boundary, the instance carries
 *     the "previous byte was CR" flag across calls so the LF of a split CRLF
 *     is not prefixed with a second CR.</li>
 * <li><strong>Upload (client &rarr; server)</strong>: CR bytes are stripped by
 *     {@link #decode(ByteBuffer)} so a network CRLF collapses to the local LF.
 *     This is stateless and therefore inherently correct across chunk
 *     boundaries.  Stripping CR (rather than only CR-before-LF) matches common
 *     FTP server behaviour and is well defined for the text streams that TYPE A
 *     is intended for.</li>
 * </ul>
 *
 * <p>Buffers returned by both methods are drawn from {@link ByteBufferPool} and
 * MUST be released by the caller once they have been consumed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FTPAsciiLineEndings {

    /** Whether the last byte emitted by {@link #encode} was a CR. */
    private boolean lastByteWasCR;

    /**
     * Encodes local text to the NVT-ASCII network representation for a
     * download by expanding bare LF to CRLF.  Consumes {@code src}.
     *
     * @param src the local bytes to encode
     * @return a pooled buffer flipped for reading (caller must release it)
     */
    ByteBuffer encode(ByteBuffer src) {
        // Worst case: every byte is a bare LF, doubling the length.
        ByteBuffer out = ByteBufferPool.acquire(Math.max(1, src.remaining() * 2));
        while (src.hasRemaining()) {
            byte b = src.get();
            if (b == '\n' && !lastByteWasCR) {
                out.put((byte) '\r');
            }
            out.put(b);
            lastByteWasCR = (b == '\r');
        }
        out.flip();
        return out;
    }

    /**
     * Decodes the NVT-ASCII network representation to local text for an upload
     * by stripping CR bytes so CRLF collapses to LF.  Consumes {@code src}.
     * Stateless: the result depends only on {@code src}.
     *
     * @param src the network bytes to decode
     * @return a pooled buffer flipped for reading (caller must release it)
     */
    static ByteBuffer decode(ByteBuffer src) {
        ByteBuffer out = ByteBufferPool.acquire(Math.max(1, src.remaining()));
        while (src.hasRemaining()) {
            byte b = src.get();
            if (b != '\r') {
                out.put(b);
            }
        }
        out.flip();
        return out;
    }
}
