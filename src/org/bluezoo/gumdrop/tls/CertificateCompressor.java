/*
 * CertificateCompressor.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.bluezoo.micula.BrotliDecoder;
import org.bluezoo.micula.BrotliDefaultHandler;
import org.bluezoo.micula.BrotliException;
import org.bluezoo.micula.BrotliSink;
import org.bluezoo.micula.BrotliWriter;

/**
 * RFC 8879 certificate message compression and decompression (Brotli via
 * micula, zlib via {@link Deflater}/{@link Inflater}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CertificateCompressor {

    /** Default maximum decompressed certificate message size (256 KiB). */
    public static final int DEFAULT_MAX_DECOMPRESSED_SIZE = 262144;

    private CertificateCompressor() {
    }

    /**
     * Selects the preferred algorithm from the intersection of local policy
     * and the peer's offer. Prefers Brotli when both sides support it.
     *
     * @param localEnabled algorithms this endpoint may use
     * @param peerOfferIds raw ids from the peer's {@code compress_certificate} extension
     * @return the chosen algorithm, or null if none match
     */
    public static CertificateCompressionAlgorithm selectAlgorithm(
            List<CertificateCompressionAlgorithm> localEnabled, byte[] peerOfferIds) {
        if (localEnabled == null || localEnabled.isEmpty() || peerOfferIds == null
                || peerOfferIds.length == 0) {
            return null;
        }
        boolean peerBrotli = false;
        boolean peerZlib = false;
        for (int i = 0; i < peerOfferIds.length; i++) {
            CertificateCompressionAlgorithm alg = CertificateCompressionAlgorithm.fromId(
                    peerOfferIds[i] & 0xff);
            if (alg == CertificateCompressionAlgorithm.BROTLI) {
                peerBrotli = true;
            } else if (alg == CertificateCompressionAlgorithm.ZLIB) {
                peerZlib = true;
            }
        }
        for (int i = 0; i < localEnabled.size(); i++) {
            CertificateCompressionAlgorithm local = localEnabled.get(i);
            if (local == CertificateCompressionAlgorithm.BROTLI && peerBrotli) {
                return CertificateCompressionAlgorithm.BROTLI;
            }
        }
        for (int i = 0; i < localEnabled.size(); i++) {
            CertificateCompressionAlgorithm local = localEnabled.get(i);
            if (local == CertificateCompressionAlgorithm.ZLIB && peerZlib) {
                return CertificateCompressionAlgorithm.ZLIB;
            }
        }
        return null;
    }

    /**
     * Compresses a complete TLS Certificate handshake message (type + length + body).
     *
     * @param algorithm negotiated algorithm
     * @param certificateMessage framed Certificate message
     * @return compressed bytes
     * @throws HandshakeFormatException if compression fails
     */
    public static byte[] compress(CertificateCompressionAlgorithm algorithm, byte[] certificateMessage)
            throws HandshakeFormatException {
        if (algorithm == CertificateCompressionAlgorithm.BROTLI) {
            return compressBrotli(certificateMessage);
        }
        if (algorithm == CertificateCompressionAlgorithm.ZLIB) {
            return compressZlib(certificateMessage);
        }
        throw new HandshakeFormatException("unsupported certificate compression algorithm");
    }

    /**
     * Decompresses a {@code CompressedCertificate} payload.
     *
     * @param algorithm negotiated algorithm
     * @param compressed compressed certificate message bytes
     * @param maxDecompressedSize output size limit
     * @return the framed Certificate message
     * @throws HandshakeFormatException if decompression fails or exceeds the limit
     */
    public static byte[] decompress(CertificateCompressionAlgorithm algorithm, byte[] compressed,
            int maxDecompressedSize) throws HandshakeFormatException {
        if (algorithm == CertificateCompressionAlgorithm.BROTLI) {
            return decompressBrotli(compressed, maxDecompressedSize);
        }
        if (algorithm == CertificateCompressionAlgorithm.ZLIB) {
            return decompressZlib(compressed, maxDecompressedSize);
        }
        throw new HandshakeFormatException("unsupported certificate compression algorithm");
    }

    private static byte[] compressZlib(byte[] input) throws HandshakeFormatException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 32);
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] decompressZlib(byte[] compressed, int maxSize) throws HandshakeFormatException {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buf);
                } catch (DataFormatException e) {
                    throw new HandshakeFormatException("zlib decompression failed");
                }
                if (n == 0 && inflater.needsInput()) {
                    throw new HandshakeFormatException("truncated zlib certificate compression");
                }
                out.write(buf, 0, n);
                if (out.size() > maxSize) {
                    throw new HandshakeFormatException("decompressed certificate exceeds limit");
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static byte[] compressBrotli(byte[] input) throws HandshakeFormatException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 64);
        BrotliSink sink = new BrotliSink() {
            @Override
            public void compressed(ByteBuffer data) throws BrotliException {
                if (data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    out.write(chunk, 0, chunk.length);
                }
            }
        };
        try {
            BrotliWriter.write(ByteBuffer.wrap(input), sink, 2, 22);
        } catch (BrotliException e) {
            throw new HandshakeFormatException("brotli compression failed");
        }
        return out.toByteArray();
    }

    private static byte[] decompressBrotli(byte[] compressed, int maxSize) throws HandshakeFormatException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new BrotliDefaultHandler() {
            @Override
            public void content(ByteBuffer data, boolean end) throws BrotliException {
                if (data != null && data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    out.write(chunk, 0, chunk.length);
                    if (out.size() > maxSize) {
                        throw new BrotliException("decompressed certificate exceeds limit");
                    }
                }
            }
        });
        try {
            decoder.receive(ByteBuffer.wrap(compressed));
            decoder.close();
        } catch (BrotliException e) {
            throw new HandshakeFormatException("brotli decompression failed");
        }
        return out.toByteArray();
    }

    /**
     * Returns the default enabled algorithm list (Brotli then zlib).
     *
     * @return mutable list for {@link HandshakeConfig}
     */
    public static List<CertificateCompressionAlgorithm> defaultEnabledAlgorithms() {
        List<CertificateCompressionAlgorithm> list = new ArrayList<CertificateCompressionAlgorithm>(2);
        list.add(CertificateCompressionAlgorithm.BROTLI);
        list.add(CertificateCompressionAlgorithm.ZLIB);
        return list;
    }
}
