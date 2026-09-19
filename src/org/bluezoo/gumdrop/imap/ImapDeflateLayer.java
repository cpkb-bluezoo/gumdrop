/*
 * ImapDeflateLayer.java
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

package org.bluezoo.gumdrop.imap;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * RFC 4978 DEFLATE compression for IMAP connections.
 *
 * <p>Each transmitted command or response line is compressed independently
 * with {@link Deflater#SYNC_FLUSH} so peers can decode incrementally.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4978">RFC 4978</a>
 */
public final class ImapDeflateLayer {

    private static final int SCRATCH_SIZE = 8192;

    private final Deflater deflater;
    private final Inflater inflater;
    private final byte[] scratch = new byte[SCRATCH_SIZE];

    /**
     * Creates a new DEFLATE layer for one IMAP connection.
     */
    public ImapDeflateLayer() {
        deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        inflater = new Inflater(false);
    }

    /**
     * Compresses a single IMAP frame (typically one line including CRLF)
     * and flushes with SYNC_FLUSH.
     *
     * @param plaintext uncompressed wire bytes
     * @return compressed bytes to send on the connection
     */
    public byte[] compressAndFlush(byte[] plaintext) {
        deflater.setInput(plaintext);
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(plaintext.length + 64);
        while (!deflater.needsInput()) {
            int count = deflater.deflate(scratch, 0, scratch.length,
                    Deflater.SYNC_FLUSH);
            if (count > 0) {
                out.write(scratch, 0, count);
            } else {
                break;
            }
        }
        return out.toByteArray();
    }

    /**
     * Decompresses bytes read from the connection.
     *
     * @param compressed incoming compressed data (may be partial)
     * @return newly decompressed plaintext (empty if more input is needed)
     * @throws DataFormatException if the stream is corrupt
     */
    public byte[] inflate(ByteBuffer compressed) throws DataFormatException {
        if (compressed != null && compressed.hasRemaining()) {
            byte[] chunk = new byte[compressed.remaining()];
            compressed.get(chunk);
            inflater.setInput(chunk);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int count;
            try {
                count = inflater.inflate(scratch);
            } catch (DataFormatException e) {
                throw e;
            }
            if (count > 0) {
                out.write(scratch, 0, count);
            } else if (inflater.needsInput()) {
                break;
            } else if (count < 0) {
                throw new DataFormatException("Inflater error " + count);
            }
        }
        return out.toByteArray();
    }

    /**
     * Releases native zlib resources.
     */
    public void close() {
        deflater.end();
        inflater.end();
    }
}
