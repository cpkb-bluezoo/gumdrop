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
     * Receives decompressed certificate bytes as they are produced. The
     * buffer is only valid for the duration of the call.
     */
    public interface Sink {
        void decoded(ByteBuffer data) throws HandshakeFormatException;
    }

    /**
     * Creates a push-style streaming decompressor. Memory use is a fixed
     * scratch buffer plus codec state, independent of message size.
     *
     * @param algorithm negotiated algorithm
     * @param maxDecompressedSize output limit, enforced as output is produced
     * @param sink receives decompressed chunks
     * @return the decompressor
     * @throws HandshakeFormatException if the algorithm is unsupported
     */
    public static Decompressor newDecompressor(CertificateCompressionAlgorithm algorithm,
            int maxDecompressedSize, Sink sink) throws HandshakeFormatException {
        if (algorithm == CertificateCompressionAlgorithm.BROTLI) {
            return new BrotliStream(maxDecompressedSize, sink);
        }
        if (algorithm == CertificateCompressionAlgorithm.ZLIB) {
            return new ZlibStream(maxDecompressedSize, sink);
        }
        throw new HandshakeFormatException("unsupported certificate compression algorithm");
    }

    /** Streaming decompressor; feed compressed bytes in arbitrary chunks. */
    public abstract static class Decompressor {

        static final int SCRATCH_SIZE = 8192;

        final int max;
        final Sink sink;
        int total;

        Decompressor(int max, Sink sink) {
            this.max = max;
            this.sink = sink;
        }

        /**
         * Consumes all remaining bytes of {@code compressed}.
         *
         * @param compressed next chunk of compressed data
         * @param end true if this is the final chunk
         * @throws HandshakeFormatException on corrupt, truncated or oversize input
         */
        public abstract void write(ByteBuffer compressed, boolean end) throws HandshakeFormatException;

        void emit(ByteBuffer data) throws HandshakeFormatException {
            int n = data.remaining();
            if (n > max - total) {
                throw new HandshakeFormatException("decompressed certificate exceeds limit");
            }
            total += n;
            sink.decoded(data);
        }
    }

    private static final class ZlibStream extends Decompressor {

        private final Inflater inflater = new Inflater(false);
        private final byte[] out = new byte[SCRATCH_SIZE];
        private byte[] in = new byte[SCRATCH_SIZE];
        private boolean done;

        ZlibStream(int max, Sink sink) {
            super(max, sink);
        }

        @Override
        public void write(ByteBuffer compressed, boolean end) throws HandshakeFormatException {
            try {
                while (compressed.hasRemaining()) {
                    if (done) {
                        throw new HandshakeFormatException("trailing data after zlib certificate compression");
                    }
                    int n = Math.min(compressed.remaining(), in.length);
                    compressed.get(in, 0, n);
                    inflater.setInput(in, 0, n);
                    drain();
                }
                if (end) {
                    if (!done) {
                        throw new HandshakeFormatException("truncated zlib certificate compression");
                    }
                }
            } catch (HandshakeFormatException e) {
                inflater.end();
                throw e;
            }
            if (end) {
                inflater.end();
            }
        }

        private void drain() throws HandshakeFormatException {
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(out);
                } catch (DataFormatException e) {
                    throw new HandshakeFormatException("zlib decompression failed");
                }
                if (n > 0) {
                    emit(ByteBuffer.wrap(out, 0, n));
                } else if (inflater.finished()) {
                    break;
                } else if (inflater.needsInput()) {
                    return;
                } else if (inflater.needsDictionary()) {
                    throw new HandshakeFormatException("zlib decompression failed");
                }
            }
            if (inflater.getRemaining() > 0) {
                throw new HandshakeFormatException("trailing data after zlib certificate compression");
            }
            done = true;
        }
    }

    private static final class BrotliStream extends Decompressor {

        private final BrotliDecoder decoder = new BrotliDecoder();
        private HandshakeFormatException failure;

        BrotliStream(int max, Sink sink) {
            super(max, sink);
            decoder.setHandler(new BrotliDefaultHandler() {
                @Override
                public void content(ByteBuffer data, boolean end) throws BrotliException {
                    if (data != null && data.hasRemaining()) {
                        try {
                            emit(data);
                        } catch (HandshakeFormatException e) {
                            failure = e;
                            throw new BrotliException(e.getMessage());
                        }
                    }
                }
            });
        }

        @Override
        public void write(ByteBuffer compressed, boolean end) throws HandshakeFormatException {
            try {
                if (compressed.hasRemaining()) {
                    decoder.receive(compressed);
                }
                if (end) {
                    decoder.close();
                }
            } catch (BrotliException e) {
                if (failure != null) {
                    throw failure;
                }
                throw new HandshakeFormatException("brotli decompression failed");
            }
        }
    }

    /**
     * Decompresses a complete {@code CompressedCertificate} payload held in
     * memory, using the streaming decompressor.
     *
     * @param algorithm negotiated algorithm
     * @param compressed compressed certificate message bytes
     * @param maxDecompressedSize output size limit
     * @return the framed Certificate message
     * @throws HandshakeFormatException if decompression fails or exceeds the limit
     */
    public static byte[] decompress(CertificateCompressionAlgorithm algorithm, byte[] compressed,
            int maxDecompressedSize) throws HandshakeFormatException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Decompressor d = newDecompressor(algorithm, maxDecompressedSize, new Sink() {
            @Override
            public void decoded(ByteBuffer data) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        });
        d.write(ByteBuffer.wrap(compressed), true);
        return out.toByteArray();
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
