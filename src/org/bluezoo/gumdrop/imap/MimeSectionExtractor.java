/*
 * MimeSectionExtractor.java
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Runs a {@link MimeSectionParser} over a message channel and collects the
 * requested section in one of the forms a FETCH item needs. Only the section
 * itself (or, for partial fetches, the requested window of it) is retained;
 * the message is streamed through a small fixed buffer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MimeSectionExtractor {

    /** The form in which the section is wanted. */
    enum Form {
        /** BODY[...]: the raw section octets. */
        BODY,
        /** BINARY[...]: the content-transfer-decoded octets of a leaf part. */
        BINARY,
        /** BINARY.SIZE[...]: the decoded length only. */
        BINARY_SIZE,
        /** PREVIEW: a short text extract of the first text part. */
        PREVIEW
    }

    /** Outcome of an extraction. */
    static final class Result {
        /** The section exists and can be returned in the requested form. */
        boolean available;
        /** The section octets (BODY, BINARY) or the UTF-8 preview text. */
        byte[] data = new byte[0];
        /** The origin octet to report for a partial fetch. */
        long origin;
        /** The full length of the section in the requested form. */
        long size;
    }

    private static final int PREVIEW_INPUT_LIMIT = 16384;
    private static final int PREVIEW_CHARS = 200;
    private static final int READ_SIZE = 16384;

    private MimeSectionExtractor() {
    }

    /** ENVELOPE, BODY and BODYSTRUCTURE of a message, as wire strings. */
    static final class Structure {
        String envelope;
        String bodyStructure;
        String body;
    }

    /**
     * Streams the message once and computes its structure.
     *
     * @param envelopeOnly stop after the top-level header block
     */
    static Structure structure(ReadableByteChannel channel,
            boolean envelopeOnly) throws IOException {
        MimeSectionParser parser = MimeSectionParser.forStructure(envelopeOnly);
        ByteBuffer buf = ByteBuffer.allocate(READ_SIZE);
        boolean eof = false;
        while (!parser.isDone()) {
            int n = channel.read(buf);
            if (n < 0) {
                eof = true;
            }
            buf.flip();
            parser.receive(buf);
            if (eof) {
                break;
            }
            buf.compact();
        }
        if (eof) {
            parser.finish(buf);
        } else {
            parser.finish(ByteBuffer.allocate(0));
        }
        Structure result = new Structure();
        result.envelope = parser.envelope();
        result.bodyStructure = parser.bodyStructure();
        result.body = parser.body();
        return result;
    }

    /**
     * Extracts a section from the message read from {@code channel}.
     *
     * @param channel the message octets (blocking reads)
     * @param spec the section
     * @param form the wanted form
     * @param offset the first octet wanted, or -1 for no partial window
     * @param count the number of octets wanted (with offset)
     */
    static Result extract(ReadableByteChannel channel, MimeSectionSpec spec,
            Form form, long offset, long count) throws IOException {
        Collector collector = new Collector(form, offset, count);
        MimeSectionParser parser = new MimeSectionParser(spec, collector);
        ByteBuffer buf = ByteBuffer.allocate(READ_SIZE);
        boolean eof = false;
        while (!parser.isDone() && !collector.full()) {
            int n = channel.read(buf);
            if (n < 0) {
                eof = true;
            }
            buf.flip();
            parser.receive(buf);
            if (eof) {
                parser.finish(buf);
                break;
            }
            buf.compact();
        }
        return collector.result(parser.isFound());
    }

    /** Sink that applies the form, decoding and window. */
    private static final class Collector implements MimeSectionParser.Sink,
            MimeTransferDecoder.Output {
        private final Form form;
        private final long offset;
        private final long count;
        private final ByteArrayOutputStream window = new ByteArrayOutputStream();
        private MimeTransferDecoder decoder;
        private boolean usable = true;
        private String charset;
        private long position;

        Collector(Form form, long offset, long count) {
            this.form = form;
            this.offset = offset;
            this.count = count;
        }

        @Override
        public void begin(String mediaType, String charset, String encoding,
                boolean composite) {
            this.charset = charset;
            if (form == Form.BINARY || form == Form.BINARY_SIZE
                    || form == Form.PREVIEW) {
                if (form != Form.PREVIEW && composite) {
                    usable = false;
                    return;
                }
                decoder = MimeTransferDecoder.create(encoding, this);
                if (decoder == null) {
                    usable = false;
                }
            }
        }

        @Override
        public void data(byte[] buf, int off, int len) {
            if (!usable) {
                return;
            }
            if (decoder != null) {
                decoder.write(buf, off, len);
            } else {
                write(buf, off, len);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            if (form == Form.BINARY_SIZE) {
                position += len;
                return;
            }
            if (form == Form.PREVIEW) {
                int room = PREVIEW_INPUT_LIMIT - window.size();
                if (room > 0) {
                    window.write(buf, off, Math.min(room, len));
                }
                position += len;
                return;
            }
            long start = position;
            position += len;
            if (offset < 0) {
                window.write(buf, off, len);
                return;
            }
            long from = Math.max(start, offset);
            long to = Math.min(start + len, offset + count);
            if (to > from) {
                window.write(buf, off + (int) (from - start), (int) (to - from));
            }
        }

        boolean full() {
            if (form == Form.PREVIEW) {
                return window.size() >= PREVIEW_INPUT_LIMIT;
            }
            return form != Form.BINARY_SIZE && offset >= 0
                    && position >= offset + count;
        }

        Result result(boolean found) {
            Result result = new Result();
            if (decoder != null) {
                decoder.finish();
            }
            if (!found || !usable) {
                return result;
            }
            result.available = true;
            result.size = position;
            if (form == Form.PREVIEW) {
                result.data = previewText(window.toByteArray(), charset);
                return result;
            }
            if (form == Form.BINARY_SIZE) {
                return result;
            }
            result.data = window.toByteArray();
            result.origin = offset < 0 ? 0 : Math.min(offset, position);
            return result;
        }
    }

    private static byte[] previewText(byte[] raw, String charsetName) {
        Charset charset = StandardCharsets.UTF_8;
        if (charsetName != null) {
            try {
                charset = Charset.forName(charsetName);
            } catch (RuntimeException e) {
                charset = StandardCharsets.ISO_8859_1;
            }
        }
        String text = new String(raw, charset);
        StringBuilder sb = new StringBuilder();
        boolean space = true;
        int chars = 0;
        for (int i = 0; i < text.length() && chars < PREVIEW_CHARS; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)) {
                if (!space) {
                    sb.append(' ');
                    chars++;
                    space = true;
                }
            } else {
                sb.appendCodePoint(cp);
                chars++;
                space = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end).getBytes(StandardCharsets.UTF_8);
    }
}
