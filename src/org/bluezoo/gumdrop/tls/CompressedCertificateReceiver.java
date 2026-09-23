/*
 * CompressedCertificateReceiver.java
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

import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Incremental receiver for the body of an RFC 8879
 * {@code CompressedCertificate} message: reads the algorithm and length
 * prefix, streams the compressed bytes through a
 * {@link CertificateCompressor.Decompressor} and feeds the output straight
 * into a {@link CertificateMessageParser}. Nothing proportional to the
 * message size is retained other than the parsed certificates.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CompressedCertificateReceiver {

    private final CertificateCompressionAlgorithm expected;
    private final int maxDecompressedSize;
    private final CertificateMessageParser parser;
    private final byte[] prefix = new byte[4];
    private int prefixLen;
    private int bodyRemaining;
    private int compressedRemaining;
    private CertificateCompressor.Decompressor decompressor;
    private boolean algorithmMismatch;

    /**
     * @param expected the negotiated algorithm, or null to accept any known one
     * @param maxDecompressedSize limit on the decompressed Certificate message
     * @param bodyLength the CompressedCertificate body length from its handshake header
     */
    CompressedCertificateReceiver(CertificateCompressionAlgorithm expected, int maxDecompressedSize,
            int bodyLength) {
        this.expected = expected;
        this.maxDecompressedSize = maxDecompressedSize;
        this.bodyRemaining = bodyLength;
        this.parser = new CertificateMessageParser(maxDecompressedSize);
    }

    /**
     * Consumes the next bytes of the message body.
     *
     * @throws HandshakeFormatException if the body is malformed, exceeds
     *         the limit or fails to decompress
     */
    void write(byte[] data) throws HandshakeFormatException {
        int pos = 0;
        if (data.length > bodyRemaining) {
            throw new HandshakeFormatException("CompressedCertificate body overrun");
        }
        bodyRemaining -= data.length;
        while (decompressor == null && pos < data.length) {
            int n = Math.min(4 - prefixLen, data.length - pos);
            System.arraycopy(data, pos, prefix, prefixLen, n);
            prefixLen += n;
            pos += n;
            if (prefixLen == 4) {
                startDecompressor(data.length - pos);
            }
        }
        if (pos < data.length) {
            int n = data.length - pos;
            compressedRemaining -= n;
            decompressor.write(ByteBuffer.wrap(data, pos, n), compressedRemaining == 0);
        }
    }

    private void startDecompressor(int inThisChunk) throws HandshakeFormatException {
        CertificateCompressionAlgorithm alg = CertificateCompressionAlgorithm.fromId(prefix[0] & 0xff);
        if (alg == null) {
            throw new HandshakeFormatException("unknown certificate compression algorithm");
        }
        if (expected != null && alg != expected) {
            algorithmMismatch = true;
            throw new HandshakeFormatException("certificate compression algorithm mismatch");
        }
        compressedRemaining = ((prefix[1] & 0xff) << 16) | ((prefix[2] & 0xff) << 8) | (prefix[3] & 0xff);
        if (compressedRemaining != bodyRemaining + inThisChunk) {
            throw new HandshakeFormatException("inconsistent compressed certificate length");
        }
        decompressor = CertificateCompressor.newDecompressor(alg, maxDecompressedSize,
                new CertificateCompressor.Sink() {
                    @Override
                    public void decoded(ByteBuffer out) throws HandshakeFormatException {
                        if (out.hasArray()) {
                            parser.write(out.array(), out.arrayOffset() + out.position(), out.remaining());
                            out.position(out.limit());
                        } else {
                            byte[] copy = new byte[out.remaining()];
                            out.get(copy);
                            parser.write(copy, 0, copy.length);
                        }
                    }
                });
    }

    /**
     * Verifies the whole message arrived and decoded to a complete
     * Certificate message.
     *
     * @throws HandshakeFormatException if it is truncated or invalid
     */
    void finish() throws HandshakeFormatException {
        if (decompressor == null || compressedRemaining != 0 || bodyRemaining != 0) {
            throw new HandshakeFormatException("truncated CompressedCertificate");
        }
        parser.finish();
    }

    boolean isAlgorithmMismatch() {
        return algorithmMismatch;
    }

    byte[] getContext() {
        return parser.getContext();
    }

    List<X509Certificate> getChain() {
        return parser.getChain();
    }
}
