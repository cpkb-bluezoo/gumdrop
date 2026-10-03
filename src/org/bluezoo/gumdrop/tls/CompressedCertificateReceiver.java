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
 * into a {@link CertificateMessageParser}. What is compressed is the
 * Certificate message itself, without a handshake header (RFC 8879
 * section 4). Nothing proportional to the
 * message size is retained other than the parsed certificates.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CompressedCertificateReceiver {

    private final List<CertificateCompressionAlgorithm> offered;
    private final int maxDecompressedSize;
    private CertificateMessageParser parser;
    /** algorithm (2), uncompressed_length (3), compressed message length (3) */
    private static final int PREFIX_LENGTH = 8;

    private final byte[] prefix = new byte[PREFIX_LENGTH];
    private int prefixLen;
    private int bodyRemaining;
    private int compressedRemaining;
    private int uncompressedLength;
    private int decodedLength;
    private CertificateCompressor.Decompressor decompressor;
    private boolean algorithmMismatch;

    /**
     * @param offered the algorithms this endpoint offered; the message must
     *        use one of them (RFC 8879 section 4)
     * @param maxDecompressedSize limit on the decompressed Certificate message
     * @param bodyLength the CompressedCertificate body length from its handshake header
     */
    CompressedCertificateReceiver(List<CertificateCompressionAlgorithm> offered, int maxDecompressedSize,
            int bodyLength) {
        this.offered = offered;
        this.maxDecompressedSize = maxDecompressedSize;
        this.bodyRemaining = bodyLength;
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
            int n = Math.min(PREFIX_LENGTH - prefixLen, data.length - pos);
            System.arraycopy(data, pos, prefix, prefixLen, n);
            prefixLen += n;
            pos += n;
            if (prefixLen == PREFIX_LENGTH) {
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
        CertificateCompressionAlgorithm alg = CertificateCompressionAlgorithm.fromId(
                ((prefix[0] & 0xff) << 8) | (prefix[1] & 0xff));
        if (alg == null || !offered.contains(alg)) {
            algorithmMismatch = true;
            throw new HandshakeFormatException("certificate compression algorithm mismatch");
        }
        // RFC 8879 section 4: uncompressed_length bounds the output before
        // any of it is produced, and must then be met exactly
        uncompressedLength = ((prefix[2] & 0xff) << 16) | ((prefix[3] & 0xff) << 8) | (prefix[4] & 0xff);
        if (uncompressedLength > maxDecompressedSize) {
            throw new HandshakeFormatException("decompressed certificate exceeds limit");
        }
        parser = new CertificateMessageParser(uncompressedLength);
        compressedRemaining = ((prefix[5] & 0xff) << 16) | ((prefix[6] & 0xff) << 8) | (prefix[7] & 0xff);
        if (compressedRemaining != bodyRemaining + inThisChunk) {
            throw new HandshakeFormatException("inconsistent compressed certificate length");
        }
        decompressor = CertificateCompressor.newDecompressor(alg, maxDecompressedSize,
                new CertificateCompressor.Sink() {
                    @Override
                    public void decoded(ByteBuffer out) throws HandshakeFormatException {
                        decodedLength += out.remaining();
                        if (decodedLength > uncompressedLength) {
                            throw new HandshakeFormatException(
                                    "certificate longer than its uncompressed_length", true);
                        }
                        // RFC 8879 section 4: a message that does not decompress
                        // to the Certificate it declared is a bad certificate
                        try {
                            if (out.hasArray()) {
                                parser.write(out.array(), out.arrayOffset() + out.position(), out.remaining());
                                out.position(out.limit());
                            } else {
                                byte[] copy = new byte[out.remaining()];
                                out.get(copy);
                                parser.write(copy, 0, copy.length);
                            }
                        } catch (HandshakeFormatException e) {
                            if (e.isBadCertificate()) {
                                throw e;
                            }
                            throw new HandshakeFormatException(e.getMessage(), true);
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
        if (decodedLength != uncompressedLength) {
            throw new HandshakeFormatException("certificate shorter than its uncompressed_length", true);
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
