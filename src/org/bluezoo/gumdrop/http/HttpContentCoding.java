/*
 * HttpContentCoding.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * HTTP message body content codings (RFC 9110 section 8.4): {@code br},
 * {@code gzip}, and {@code deflate} using micula and {@link Deflater}/
 * {@link Inflater} (not {@code InputStream}/{@code OutputStream} wrappers).
 */
public final class HttpContentCoding {

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

    private HttpContentCoding() {
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
     * @throws HttpContentCodingException if decoding fails
     */
    public static byte[] decompress(Coding coding, byte[] encoded, int maxDecompressedSize)
            throws HttpContentCodingException {
        if (coding == null) {
            throw new NullPointerException("coding");
        }
        switch (coding) {
            case BR:
                return decompressBrotli(encoded, maxDecompressedSize);
            case GZIP:
                return decompressGzip(encoded, maxDecompressedSize);
            case DEFLATE:
                return decompressZlib(encoded, maxDecompressedSize);
            default:
                throw new HttpContentCodingException("unsupported coding");
        }
    }

    /**
     * Incremental HTTP response body encoder.
     */
    public interface Encoder {
        /**
         * Supplies plaintext body bytes.
         *
         * @param data plaintext (may be empty)
         * @param end true when no further plaintext will be supplied
         */
        void write(ByteBuffer data, boolean end) throws HttpContentCodingException;

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
        private ByteBuffer pending;

        @Override
        public void write(ByteBuffer data, boolean end) throws HttpContentCodingException {
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
            if (pending == null) {
                return null;
            }
            ByteBuffer copy = pending;
            pending = null;
            return copy;
        }

        @Override
        public void close() {
            deflater.end();
        }

        private void drain() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!deflater.needsInput() || deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n <= 0 && !deflater.finished()) {
                    break;
                }
                if (n > 0) {
                    out.write(buf, 0, n);
                }
                if (deflater.finished() && n == 0) {
                    break;
                }
            }
            if (out.size() > 0) {
                pending = ByteBuffer.wrap(out.toByteArray());
            }
        }
    }

    private static final class GzipEncoder implements Encoder {
        private final CRC32 crc = new CRC32();
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        private final byte[] buf = new byte[4096];
        private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        private boolean headerWritten;
        private ByteBuffer pending;
        private long uncompressedSize;

        @Override
        public void write(ByteBuffer data, boolean end) throws HttpContentCodingException {
            if (!headerWritten) {
                headerWritten = true;
                encoded.write(GZIP_HEADER, 0, GZIP_HEADER.length);
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
            if (encoded.size() > 0) {
                pending = ByteBuffer.wrap(encoded.toByteArray());
                encoded.reset();
            }
        }

        @Override
        public ByteBuffer readEncoded() {
            if (pending == null) {
                return null;
            }
            ByteBuffer copy = pending;
            pending = null;
            return copy;
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
                    encoded.write(buf, 0, n);
                }
                if (deflater.finished() && n == 0) {
                    break;
                }
            }
            if (end && deflater.finished()) {
                writeGzipTrailer(encoded);
            }
        }

        private void writeGzipTrailer(ByteArrayOutputStream out) {
            long c = crc.getValue();
            out.write((int) (c & 0xff));
            out.write((int) ((c >> 8) & 0xff));
            out.write((int) ((c >> 16) & 0xff));
            out.write((int) ((c >> 24) & 0xff));
            long isize = uncompressedSize & 0xffffffffL;
            out.write((int) (isize & 0xff));
            out.write((int) ((isize >> 8) & 0xff));
            out.write((int) ((isize >> 16) & 0xff));
            out.write((int) ((isize >> 24) & 0xff));
        }
    }

    private static final class BrotliBodyEncoder implements Encoder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final BrotliSink sink = new BrotliSink() {
            @Override
            public void compressed(ByteBuffer data) throws BrotliException {
                if (data != null && data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    out.write(chunk, 0, chunk.length);
                }
            }
        };
        private org.bluezoo.micula.BrotliEncoder encoder;
        private ByteBuffer pending;
        private boolean closed;

        @Override
        public void write(ByteBuffer data, boolean end) throws HttpContentCodingException {
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
                throw new HttpContentCodingException("brotli compression failed", e);
            }
            if (out.size() > 0) {
                pending = ByteBuffer.wrap(out.toByteArray());
                out.reset();
            }
        }

        @Override
        public ByteBuffer readEncoded() {
            if (pending == null) {
                return null;
            }
            ByteBuffer copy = pending;
            pending = null;
            return copy;
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

    private static byte[] decompressZlib(byte[] encoded, int maxSize) throws HttpContentCodingException {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(encoded);
            return inflateToBytes(inflater, maxSize);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decompressGzip(byte[] encoded, int maxSize) throws HttpContentCodingException {
        if (encoded.length < GZIP_HEADER.length + 8) {
            throw new HttpContentCodingException("truncated gzip body");
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(encoded, GZIP_HEADER.length,
                    encoded.length - GZIP_HEADER.length - 8);
            return inflateToBytes(inflater, maxSize);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decompressBrotli(byte[] encoded, int maxSize) throws HttpContentCodingException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length * 2);
        BrotliDecoder decoder = new BrotliDecoder();
        decoder.setHandler(new BrotliDefaultHandler() {
            @Override
            public void content(ByteBuffer data, boolean end) throws BrotliException {
                if (data != null && data.hasRemaining()) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    out.write(chunk, 0, chunk.length);
                    if (out.size() > maxSize) {
                        throw new BrotliException("decompressed body exceeds limit");
                    }
                }
            }
        });
        try {
            decoder.receive(ByteBuffer.wrap(encoded));
            decoder.close();
        } catch (BrotliException e) {
            throw new HttpContentCodingException("brotli decompression failed", e);
        }
        return out.toByteArray();
    }

    private static byte[] inflateToBytes(Inflater inflater, int maxSize)
            throws HttpContentCodingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && inflater.needsInput()) {
                    throw new HttpContentCodingException("truncated compressed body");
                }
                if (n > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxSize) {
                        throw new HttpContentCodingException("decompressed body exceeds limit");
                    }
                }
            }
        } catch (DataFormatException e) {
            throw new HttpContentCodingException("inflation failed", e);
        }
        return out.toByteArray();
    }

    /**
     * Thrown when content coding fails.
     */
    public static class HttpContentCodingException extends Exception {
        private static final long serialVersionUID = 1L;

        public HttpContentCodingException(String message) {
            super(message);
        }

        public HttpContentCodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
