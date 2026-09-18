/*
 * ContentEncoding.java
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

package org.bluezoo.gumdrop.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.bluezoo.micula.BrotliDecoder;
import org.bluezoo.micula.BrotliDefaultHandler;
import org.bluezoo.micula.BrotliException;
import org.bluezoo.micula.BrotliSink;
/**
 * {@code Content-Encoding} (RFC 9110 section 8.4): {@code br}, {@code gzip},
 * and {@code deflate} using micula and {@link Deflater}/{@link Inflater}
 * (not {@code InputStream}/{@code OutputStream} wrappers).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentEncoding {

    /** Default maximum decompressed body size (64 MiB). */
    public static final int DEFAULT_MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024;

    private static final byte[] GZIP_HEADER = {
            (byte) 0x1f, (byte) 0x8b, Deflater.DEFLATED, 0,
            0, 0, 0, 0, // mtime
            0, (byte) 0xff // xfl + OS
    };

    /**
     * Supported content coding tokens on the wire.
     */
    public enum Coding {
        BR("br"),
        GZIP("gzip"),
        DEFLATE("deflate");

        private final String token;

        Coding(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        static Coding fromToken(String token) {
            if (token == null) {
                return null;
            }
            String t = token.trim().toLowerCase(Locale.ROOT);
            if ("br".equals(t)) {
                return BR;
            }
            if ("gzip".equals(t) || "x-gzip".equals(t)) {
                return GZIP;
            }
            if ("deflate".equals(t)) {
                return DEFLATE;
            }
            return null;
        }
    }

    /** Server preference order when several codings appear in {@code Accept-Encoding}. */
    private static final Coding[] SERVER_PREFERENCE = {
            Coding.BR, Coding.GZIP, Coding.DEFLATE
    };

    private ContentEncoding() {
    }

    /**
     * Chooses a response coding from the client's {@code Accept-Encoding} field.
     *
     * @param acceptEncoding raw header value, or null
     * @return a coding to use, or null if none are acceptable
     */
    public static Coding selectFromAcceptEncoding(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isEmpty()) {
            return null;
        }
        List<String> offered = parseCodingTokens(acceptEncoding);
        for (int p = 0; p < SERVER_PREFERENCE.length; p++) {
            Coding pref = SERVER_PREFERENCE[p];
            for (int i = 0; i < offered.size(); i++) {
                if (pref.token().equals(offered.get(i))) {
                    return pref;
                }
            }
        }
        return null;
    }

    /**
     * Parses the first supported coding from a {@code Content-Encoding} header.
     *
     * @param contentEncoding header value
     * @return coding, or null if unsupported or absent
     */
    public static Coding parseContentEncoding(String contentEncoding) {
        if (contentEncoding == null || contentEncoding.isEmpty()) {
            return null;
        }
        List<String> tokens = parseCodingTokens(contentEncoding);
        for (int i = 0; i < tokens.size(); i++) {
            Coding coding = Coding.fromToken(tokens.get(i));
            if (coding != null) {
                return coding;
            }
        }
        return null;
    }

    private static List<String> parseCodingTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        int start = 0;
        int len = value.length();
        while (start <= len) {
            int end = value.indexOf(',', start);
            if (end < 0) {
                end = len;
            }
            String part = value.substring(start, end).trim();
            if (!part.isEmpty()) {
                int semi = part.indexOf(';');
                if (semi >= 0) {
                    part = part.substring(0, semi).trim();
                }
                if (!part.isEmpty() && !"identity".equalsIgnoreCase(part) && !"*".equals(part)) {
                    tokens.add(part.toLowerCase(Locale.ROOT));
                }
            }
            start = end + 1;
        }
        return tokens;
    }

    /**
     * Creates a streaming encoder for the given coding.
     *
     * @param coding selected coding
     * @return encoder instance
     */
    public static Encoder createEncoder(Coding coding) {
        if (coding == null) {
            throw new NullPointerException("coding");
        }
        switch (coding) {
            case BR:
                return new BrotliBodyEncoder();
            case GZIP:
                return new GzipEncoder();
            case DEFLATE:
                return new ZlibEncoder();
            default:
                throw new IllegalArgumentException("unsupported coding");
        }
    }

    /**
     * Decompresses a complete encoded body.
     *
     * @param coding content coding
     * @param encoded compressed bytes
     * @param maxDecompressedSize output limit
     * @return decompressed bytes
     * @throws ContentEncodingException if decoding fails
     */
    /**
     * Compresses a complete body with the given coding.
     */
    public static byte[] compressFully(Coding coding, byte[] input)
            throws ContentEncodingException {
        if (coding == null) {
            throw new NullPointerException("coding");
        }
        Encoder encoder = createEncoder(coding);
        try {
            encoder.write(ByteBuffer.wrap(input), true);
            return drainEncoded(encoder);
        } finally {
            encoder.close();
        }
    }

    /**
     * Creates a streaming decoder for the given coding.
     *
     * @param coding content coding
     * @param maxDecompressedSize output limit
     * @return decoder instance
     */
    public static Decoder createDecoder(Coding coding, int maxDecompressedSize) {
        if (coding == null) {
            throw new NullPointerException("coding");
        }
        if (maxDecompressedSize <= 0) {
            throw new IllegalArgumentException("maxDecompressedSize");
        }
        switch (coding) {
            case BR:
                return new BrotliBodyDecoder(maxDecompressedSize);
            case GZIP:
                return new GzipDecoder(maxDecompressedSize);
            case DEFLATE:
                return new ZlibDecoder(maxDecompressedSize);
            default:
                throw new IllegalArgumentException("unsupported coding");
        }
    }

    public static byte[] decompress(Coding coding, byte[] encoded, int maxDecompressedSize)
            throws ContentEncodingException {
        if (coding == null) {
            throw new NullPointerException("coding");
        }
        if (encoded.length == 0) {
            return encoded;
        }
        Decoder decoder = createDecoder(coding, maxDecompressedSize);
        try {
            decoder.write(ByteBuffer.wrap(encoded), true);
            return drainDecoded(decoder);
        } finally {
            decoder.close();
        }
    }

    private static byte[] drainEncoded(Encoder encoder) throws ContentEncodingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer chunk;
        while ((chunk = encoder.readEncoded()) != null) {
            out.write(chunk.array(), chunk.position(), chunk.remaining());
        }
        return out.toByteArray();
    }

    private static byte[] drainDecoded(Decoder decoder) throws ContentEncodingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer chunk;
        while ((chunk = decoder.readDecoded()) != null) {
            out.write(chunk.array(), chunk.position(), chunk.remaining());
        }
        return out.toByteArray();
    }

    /**
     * Incremental HTTP response body encoder.
     */
    /**
     * Incremental HTTP message body decoder.
     */
    public interface Decoder {
        /**
         * Supplies encoded body bytes.
         *
         * @param data compressed bytes (may be empty)
         * @param end true when no further compressed bytes will be supplied
         */
        void write(ByteBuffer data, boolean end) throws ContentEncodingException;

        /**
         * Returns decoded output accumulated since the last call, or null if none.
         */
        ByteBuffer readDecoded();

        /** Releases native decompressor state. */
        void close();
    }

    public interface Encoder {
        /**
         * Supplies plaintext body bytes.
         *
         * @param data plaintext (may be empty)
         * @param end true when no further plaintext will be supplied
         */
        void write(ByteBuffer data, boolean end) throws ContentEncodingException;

        /**
         * Returns encoded output accumulated since the last call, or null if none.
         */
        ByteBuffer readEncoded();

        /** Releases native compressor state. */
        void close();
    }

    private static final class ZlibEncoder implements Encoder {
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        private final byte[] buf = new byte[4096];
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (data != null && data.hasRemaining()) {
                byte[] in = new byte[data.remaining()];
                data.get(in);
                deflater.setInput(in);
            }
            if (end) {
                deflater.finish();
            }
            drain();
        }

        @Override
        public ByteBuffer readEncoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            deflater.end();
        }

        private void drain() {
            while (!deflater.needsInput() || deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n <= 0 && !deflater.finished()) {
                    break;
                }
                if (n > 0) {
                    pending.addLast(ByteBuffer.wrap(buf, 0, n));
                }
                if (deflater.finished() && n == 0) {
                    break;
                }
            }
        }
    }

    private static final class GzipEncoder implements Encoder {
        private final CRC32 crc = new CRC32();
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        private final byte[] buf = new byte[4096];
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();
        private boolean headerWritten;
        private long uncompressedSize;

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (!headerWritten) {
                headerWritten = true;
                pending.addLast(ByteBuffer.wrap(GZIP_HEADER.clone()));
            }
            if (data != null && data.hasRemaining()) {
                int len = data.remaining();
                byte[] in = new byte[len];
                data.get(in);
                crc.update(in);
                uncompressedSize += len;
                deflater.setInput(in);
            }
            if (end) {
                deflater.finish();
            }
            drain(end);
        }

        @Override
        public ByteBuffer readEncoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            deflater.end();
        }

        private void drain(boolean end) {
            while (!deflater.needsInput() || deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n <= 0 && !deflater.finished()) {
                    break;
                }
                if (n > 0) {
                    pending.addLast(ByteBuffer.wrap(buf, 0, n));
                }
                if (deflater.finished() && n == 0) {
                    break;
                }
            }
            if (end && deflater.finished()) {
                pending.addLast(ByteBuffer.wrap(gzipTrailerBytes()));
            }
        }

        private byte[] gzipTrailerBytes() {
            byte[] trailer = new byte[8];
            long c = crc.getValue();
            trailer[0] = (byte) (c & 0xff);
            trailer[1] = (byte) ((c >> 8) & 0xff);
            trailer[2] = (byte) ((c >> 16) & 0xff);
            trailer[3] = (byte) ((c >> 24) & 0xff);
            long isize = uncompressedSize & 0xffffffffL;
            trailer[4] = (byte) (isize & 0xff);
            trailer[5] = (byte) ((isize >> 8) & 0xff);
            trailer[6] = (byte) ((isize >> 16) & 0xff);
            trailer[7] = (byte) ((isize >> 24) & 0xff);
            return trailer;
        }
    }

    private static final class BrotliBodyEncoder implements Encoder {
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();
        private final BrotliSink sink = new BrotliSink() {
            @Override
            public void compressed(ByteBuffer data) throws BrotliException {
                if (data != null && data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    pending.addLast(ByteBuffer.wrap(chunk));
                }
            }
        };
        private org.bluezoo.micula.BrotliEncoder encoder;
        private boolean closed;

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (closed) {
                return;
            }
            try {
                if (encoder == null) {
                    encoder = new org.bluezoo.micula.BrotliEncoder(sink);
                    encoder.setQuality(2);
                    encoder.setWindowBits(22);
                }
                if (data != null && data.hasRemaining()) {
                    encoder.receive(data);
                }
                if (end) {
                    encoder.close();
                    closed = true;
                    encoder = null;
                }
            } catch (BrotliException e) {
                throw new ContentEncodingException("brotli compression failed", e);
            }
        }

        @Override
        public ByteBuffer readEncoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            if (!closed && encoder != null) {
                try {
                    encoder.close();
                } catch (BrotliException ignored) {
                    // best effort
                }
            }
            closed = true;
            encoder = null;
        }
    }

    private static final class ZlibDecoder implements Decoder {
        private final Inflater inflater = new Inflater(false);
        private final byte[] buf = new byte[4096];
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();
        private final int maxDecompressedSize;
        private long decompressedSize;
        private boolean ended;

        ZlibDecoder(int maxDecompressedSize) {
            this.maxDecompressedSize = maxDecompressedSize;
        }

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (ended) {
                return;
            }
            if (data != null && data.hasRemaining()) {
                feedInflater(data);
            }
            if (end) {
                ended = true;
                if (!inflater.finished()) {
                    throw new ContentEncodingException("truncated compressed body");
                }
            }
        }

        @Override
        public ByteBuffer readDecoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            inflater.end();
        }

        private void feedInflater(ByteBuffer data) throws ContentEncodingException {
            byte[] in = new byte[data.remaining()];
            data.get(in);
            inflater.setInput(in);
            inflateAvailable();
        }

        private void inflateAvailable() throws ContentEncodingException {
            try {
                while (!inflater.needsInput()) {
                    int n = inflater.inflate(buf);
                    if (n == 0) {
                        break;
                    }
                    enqueueDecoded(n);
                }
            } catch (DataFormatException e) {
                throw new ContentEncodingException("inflation failed", e);
            }
        }

        private void enqueueDecoded(int n) throws ContentEncodingException {
            decompressedSize += n;
            if (decompressedSize > maxDecompressedSize) {
                throw new ContentEncodingException("decompressed body exceeds limit");
            }
            pending.addLast(ByteBuffer.wrap(buf, 0, n));
        }
    }

    private static final class GzipDecoder implements Decoder {
        private static final int TRAILER_LEN = 8;

        private final int maxDecompressedSize;
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();
        private final byte[] buf = new byte[4096];
        private int headerRemaining = GZIP_HEADER.length;
        private Inflater inflater;
        private long decompressedSize;
        private boolean ended;

        GzipDecoder(int maxDecompressedSize) {
            this.maxDecompressedSize = maxDecompressedSize;
        }

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (ended) {
                return;
            }
            if (data != null && data.hasRemaining()) {
                skipHeader(data);
                if (headerRemaining > 0) {
                    if (end) {
                        throw new ContentEncodingException("truncated gzip body");
                    }
                    return;
                }
                if (inflater == null) {
                    inflater = new Inflater(true);
                }
                byte[] in = new byte[data.remaining()];
                data.get(in);
                inflater.setInput(in);
                inflateAvailable();
            }
            if (end) {
                ended = true;
                if (inflater == null || !inflater.finished()) {
                    throw new ContentEncodingException("truncated gzip body");
                }
                if (inflater.getRemaining() != TRAILER_LEN) {
                    throw new ContentEncodingException("truncated gzip body");
                }
            }
        }

        private void skipHeader(ByteBuffer data) {
            while (headerRemaining > 0 && data.hasRemaining()) {
                data.get();
                headerRemaining--;
            }
        }

        @Override
        public ByteBuffer readDecoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            if (inflater != null) {
                inflater.end();
            }
        }

        private void inflateAvailable() throws ContentEncodingException {
            try {
                while (!inflater.needsInput()) {
                    int n = inflater.inflate(buf);
                    if (n == 0) {
                        break;
                    }
                    decompressedSize += n;
                    if (decompressedSize > maxDecompressedSize) {
                        throw new ContentEncodingException("decompressed body exceeds limit");
                    }
                    pending.addLast(ByteBuffer.wrap(buf, 0, n));
                }
            } catch (DataFormatException e) {
                throw new ContentEncodingException("inflation failed", e);
            }
        }
    }

    private static final class BrotliBodyDecoder implements Decoder {
        private final Deque<ByteBuffer> pending = new ArrayDeque<ByteBuffer>();
        private final int maxDecompressedSize;
        private long decompressedSize;
        private BrotliDecoder decoder;
        private boolean closed;

        BrotliBodyDecoder(int maxDecompressedSize) {
            this.maxDecompressedSize = maxDecompressedSize;
        }

        @Override
        public void write(ByteBuffer data, boolean end) throws ContentEncodingException {
            if (closed) {
                return;
            }
            try {
                if (decoder == null) {
                    decoder = new BrotliDecoder();
                    decoder.setHandler(new BrotliDefaultHandler() {
                        @Override
                        public void content(ByteBuffer chunk, boolean endOfStream) throws BrotliException {
                            if (chunk != null && chunk.hasRemaining()) {
                                int n = chunk.remaining();
                                decompressedSize += n;
                                if (decompressedSize > maxDecompressedSize) {
                                    throw new BrotliException("decompressed body exceeds limit");
                                }
                                byte[] copy = new byte[n];
                                chunk.get(copy);
                                pending.addLast(ByteBuffer.wrap(copy));
                            }
                        }
                    });
                }
                if (data != null && data.hasRemaining()) {
                    decoder.receive(data);
                }
                if (end) {
                    decoder.close();
                    closed = true;
                    decoder = null;
                }
            } catch (BrotliException e) {
                throw new ContentEncodingException("brotli decompression failed", e);
            }
        }

        @Override
        public ByteBuffer readDecoded() {
            return pending.pollFirst();
        }

        @Override
        public void close() {
            if (!closed && decoder != null) {
                try {
                    decoder.close();
                } catch (BrotliException ignored) {
                    // best effort
                }
            }
            closed = true;
            decoder = null;
        }
    }

    /**
     * Thrown when content encoding fails.
     */
    public static class ContentEncodingException extends Exception {
        private static final long serialVersionUID = 1L;

        public ContentEncodingException(String message) {
            super(message);
        }

        public ContentEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
