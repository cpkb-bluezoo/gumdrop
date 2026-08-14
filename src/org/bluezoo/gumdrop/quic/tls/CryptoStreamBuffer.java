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
import java.io.IOException;
import java.nio.ByteBuffer;

import tech.kwik.agent15.TlsProtocolException;
import tech.kwik.agent15.engine.MessageProcessor;
import tech.kwik.agent15.engine.TlsMessageParser;

/**
 * Reassembles a QUIC CRYPTO stream for one {@link EncryptionLevel} and
 * dispatches each complete TLS handshake message to Agent15 as it
 * becomes available (RFC 9000 section 19.6, RFC 9001 section 4.1).
 *
 * <p>CRYPTO frames identify their data by byte offset within a
 * per-level stream, the same reassembly problem as a QUIC STREAM frame.
 * This implementation only accepts data that extends the stream
 * contiguously -- out-of-order or overlapping CRYPTO frames are
 * rejected rather than buffered for later reordering. That is
 * sufficient for a connection with no packet loss (this class exists to
 * get a handshake completing at all); real reordering support belongs
 * with the general stream-reassembly work.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CryptoStreamBuffer {

    /** RFC 8446 section 4: handshake message header is type(1) + length(3) octets. */
    private static final int MESSAGE_HEADER_LENGTH = 4;

    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    private long expectedOffset;
    private int consumedLength;

    /**
     * Accepts newly received CRYPTO frame data and dispatches every
     * complete handshake message now available, in order, to Agent15.
     *
     * @param offset the byte offset of {@code data} within this level's CRYPTO stream
     * @param data the received handshake data
     * @param parser the parser to use to split messages out of the stream
     * @param engine the engine to dispatch each parsed message to
     * @param level the encryption level this data was received at
     * @throws TlsProtocolException if Agent15 rejects a handshake message
     * @throws IOException if Agent15 fails to process a handshake message
     */
    public void receive(long offset, ByteBuffer data, TlsMessageParser parser,
            MessageProcessor engine, EncryptionLevel level)
            throws TlsProtocolException, IOException {
        if (offset != expectedOffset) {
            throw new IllegalStateException(
                    "Out-of-order or overlapping CRYPTO data is not supported yet: expected offset "
                    + expectedOffset + ", got " + offset);
        }

        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        accumulator.write(chunk, 0, chunk.length);
        expectedOffset += chunk.length;

        while (true) {
            byte[] all = accumulator.toByteArray();
            int available = all.length - consumedLength;
            if (available < MESSAGE_HEADER_LENGTH) {
                return;
            }

            int messageDataLength = ((all[consumedLength + 1] & 0xff) << 16)
                    | ((all[consumedLength + 2] & 0xff) << 8)
                    | (all[consumedLength + 3] & 0xff);
            int fullMessageLength = MESSAGE_HEADER_LENGTH + messageDataLength;
            if (available < fullMessageLength) {
                return;
            }

            ByteBuffer messageBuffer = ByteBuffer.wrap(all, consumedLength, fullMessageLength);
            parser.parseAndProcessHandshakeMessage(messageBuffer, engine, level.getProtectionKeysType());
            consumedLength += fullMessageLength;
        }
    }
}
