/*
 * MimeSectionParser.java
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.ContentTypeParser;

/**
 * Push parser that locates one IMAP body section (RFC 9051 section 6.4.5)
 * in a message and streams its octets to a {@link Sink}.
 *
 * <p>Input arrives in arbitrary chunks through {@link #receive}; complete
 * lines are consumed and any incomplete trailing line is left at the buffer
 * position for the caller to {@code compact()} and extend. The parse is a
 * single forward pass over an explicit stack of entity frames (no tree is
 * built); only the header block of a candidate entity (bounded) and the
 * current line are held in memory. Section data is passed to the sink as
 * soon as it is recognised.
 *
 * <p>Part numbering follows RFC 9051: the children of a multipart are
 * numbered from 1; a message/rfc822 part shares its number with the
 * encapsulated message, whose own parts (or, for a single-part message, its
 * body) are numbered underneath it; the body of a single-part top-level
 * message is part 1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MimeSectionParser {

    /** Receives the located section. */
    interface Sink {

        /**
         * The section has been located; data follows.
         *
         * @param mediaType lower-case type/subtype of the entity, or null
         * @param charset the charset parameter, or null
         * @param encoding the lower-case content transfer encoding, or null
         * @param composite true for multipart and message/rfc822 entities
         */
        void begin(String mediaType, String charset, String encoding,
                boolean composite);

        /**
         * Section octets, in order.
         */
        void data(byte[] buf, int off, int len);
    }

    static final int MAX_LINE = 8192;
    private static final int MAX_HEADER_BLOCK = 262144;
    private static final int MAX_HEADER_VALUE = 8192;
    private static final int MAX_DEPTH = 32;

    private static final Set<String> STRUCTURE_HEADERS = new HashSet<String>();

    static {
        String[] names = new String[] {
            "content-type", "content-transfer-encoding", "content-id",
            "content-description", "content-md5", "content-disposition",
            "content-language", "content-location", "date", "subject",
            "from", "sender", "reply-to", "to", "cc", "bcc", "in-reply-to",
            "message-id"
        };
        for (int i = 0; i < names.length; i++) {
            STRUCTURE_HEADERS.add(names[i]);
        }
    }

    private enum State { HEADERS, MULTIPART, LEAF, WRAP }

    private static final class Frame {
        final int[] number;
        final boolean message;
        State state = State.HEADERS;
        boolean candidate;
        ByteArrayOutputStream block;
        StringBuilder current = new StringBuilder();
        boolean inHeader;
        String contentType;
        String encoding;
        byte[] boundary;
        int children;
        // structure mode
        Map<String, String> hdr = new HashMap<String, String>();
        long bytes;
        long lines;
        boolean multipart;
        StringBuilder partsExt = new StringBuilder();
        StringBuilder partsBasic = new StringBuilder();
        String innerExt;
        String innerBasic;
        String innerEnvelope;
        String envelope;
        String ext;
        String basic;

        Frame(int[] number, boolean message) {
            this.number = number;
            this.message = message;
        }
    }

    private final MimeSectionSpec spec;
    private final Sink sink;
    private final List<Frame> stack = new ArrayList<Frame>();
    private final byte[] scratch = new byte[MAX_LINE];
    private final byte[] pendingEol = new byte[2];
    private int pendingEolLength;
    private boolean atLineStart = true;
    private boolean rawAll;
    private boolean emitting;
    private boolean found;
    private boolean done;
    private boolean previewTaken;
    private Frame target;
    private final boolean structure;
    private final boolean envelopeOnly;
    private int lastEol;
    private String rootExt;
    private String rootBasic;
    private String rootEnvelope;

    /**
     * Creates a parser that computes the ENVELOPE, BODY and BODYSTRUCTURE
     * of the message instead of locating a section.
     *
     * @param envelopeOnly stop after the top-level headers (ENVELOPE only)
     */
    static MimeSectionParser forStructure(boolean envelopeOnly) {
        return new MimeSectionParser(envelopeOnly);
    }

    private MimeSectionParser(boolean envelopeOnly) {
        this.spec = new MimeSectionSpec(new int[0],
                MimeSectionSpec.Kind.STRUCTURE, new HashSet<String>());
        this.sink = null;
        this.structure = true;
        this.envelopeOnly = envelopeOnly;
        pushFrame(new Frame(new int[0], true));
    }

    /** The BODYSTRUCTURE wire string (after {@link #finish}). */
    String bodyStructure() {
        return rootExt;
    }

    /** The BODY wire string (after {@link #finish}). */
    String body() {
        return rootBasic;
    }

    /** The ENVELOPE wire string (after the top-level headers). */
    String envelope() {
        return rootEnvelope;
    }

    MimeSectionParser(MimeSectionSpec spec, Sink sink) {
        this.spec = spec;
        this.sink = sink;
        this.structure = false;
        this.envelopeOnly = false;
        if (spec.kind == MimeSectionSpec.Kind.FULL && spec.path.length == 0) {
            rawAll = true;
            found = true;
            emitting = true;
            sink.begin(null, null, null, true);
        } else {
            pushFrame(new Frame(new int[0], true));
        }
    }

    /** True once the section was located. */
    boolean isFound() {
        return found;
    }

    /** True when nothing further can contribute to the section. */
    boolean isDone() {
        return done;
    }

    /**
     * Consumes complete lines. Any incomplete trailing line is left in the
     * buffer (position at its start).
     */
    void receive(ByteBuffer data) {
        while (!done && data.hasRemaining()) {
            int start = data.position();
            int limit = data.limit();
            int lf = -1;
            for (int i = start; i < limit; i++) {
                if (data.get(i) == '\n') {
                    lf = i;
                    break;
                }
            }
            int length;
            boolean complete;
            if (lf >= 0 && lf - start < MAX_LINE) {
                length = lf - start + 1;
                complete = true;
            } else if (limit - start >= MAX_LINE) {
                length = MAX_LINE;
                complete = false;
            } else {
                return;
            }
            data.get(scratch, 0, length);
            handleLine(length, complete);
        }
        if (done) {
            data.position(data.limit());
        }
    }

    /**
     * Signals the end of input; {@code rest} is the final unterminated line
     * (possibly empty).
     */
    void finish(ByteBuffer rest) {
        while (!done && rest.hasRemaining()) {
            int length = Math.min(rest.remaining(), MAX_LINE);
            rest.get(scratch, 0, length);
            handleLine(length, false);
        }
        if (!done && emitting && pendingEolLength > 0) {
            sink.data(pendingEol, 0, pendingEolLength);
            pendingEolLength = 0;
        }
        if (structure) {
            while (!stack.isEmpty()) {
                Frame closed = stack.remove(stack.size() - 1);
                closeFrame(closed, false);
            }
        }
        done = true;
    }

    private void pushFrame(Frame frame) {
        MimeSectionSpec.Kind kind = spec.kind;
        boolean sameNumber = Arrays.equals(frame.number, spec.path);
        if (frame.message) {
            if (sameNumber && (kind == MimeSectionSpec.Kind.HEADER
                    || kind == MimeSectionSpec.Kind.HEADER_FIELDS
                    || kind == MimeSectionSpec.Kind.HEADER_FIELDS_NOT)) {
                frame.candidate = true;
            }
            if (kind == MimeSectionSpec.Kind.MIME
                    && Arrays.equals(child(frame.number, 1), spec.path)) {
                frame.candidate = true;
            }
        } else if (kind == MimeSectionSpec.Kind.MIME && sameNumber) {
            frame.candidate = true;
        }
        if (frame.candidate) {
            frame.block = new ByteArrayOutputStream();
        }
        stack.add(frame);
    }

    private static int[] child(int[] number, int index) {
        int[] result = Arrays.copyOf(number, number.length + 1);
        result[number.length] = index;
        return result;
    }

    private void handleLine(int length, boolean complete) {
        int content;
        if (complete) {
            content = length - 1;
            if (content > 0 && scratch[content - 1] == '\r') {
                content--;
            }
        } else {
            content = length;
        }
        boolean startsLine = atLineStart;
        atLineStart = complete;
        if (rawAll) {
            sink.data(scratch, 0, length);
            return;
        }
        if (!startsLine) {
            if (structure) {
                countFragment(length, complete);
            }
            if (emitting) {
                emitBodyLine(content, length);
            } else {
                headerFragment(content);
            }
            return;
        }
        if (content >= 2 && scratch[0] == '-' && scratch[1] == '-') {
            if (handleBoundary(content, length)) {
                return;
            }
        }
        Frame top = stack.get(stack.size() - 1);
        if (structure) {
            countLine(content, length);
        }
        if (emitting) {
            emitBodyLine(content, length);
        }
        if (top.state == State.HEADERS) {
            headerLine(top, content, length);
        }
    }

    /** Adds a whole line to the body of every entity whose body is open. */
    private void countLine(int content, int length) {
        for (int i = 0; i < stack.size(); i++) {
            Frame frame = stack.get(i);
            if (frame.state != State.HEADERS) {
                frame.bytes += length;
                frame.lines++;
            }
        }
        lastEol = length - content;
    }

    private void countFragment(int length, boolean complete) {
        for (int i = 0; i < stack.size(); i++) {
            Frame frame = stack.get(i);
            if (frame.state != State.HEADERS) {
                frame.bytes += length;
            }
        }
        lastEol = complete ? Math.min(length, 2) : 0;
    }

    private void headerFragment(int content) {
        Frame top = stack.get(stack.size() - 1);
        if (top.state != State.HEADERS || !top.inHeader) {
            return;
        }
        appendValue(top, content, 0);
        if (top.block != null && top.block.size() < MAX_HEADER_BLOCK) {
            top.block.write(scratch, 0, content);
        }
    }

    private void appendValue(Frame frame, int len, int off) {
        if (frame.current.length() < MAX_HEADER_VALUE) {
            for (int i = off; i < len; i++) {
                frame.current.append((char) (scratch[i] & 0xff));
            }
        }
    }

    private void flushPending() {
        if (pendingEolLength > 0) {
            sink.data(pendingEol, 0, pendingEolLength);
            pendingEolLength = 0;
        }
    }

    private void emitBodyLine(int content, int length) {
        if (!emitting) {
            return;
        }
        flushPending();
        sink.data(scratch, 0, content);
        int eol = length - content;
        for (int i = 0; i < eol; i++) {
            pendingEol[i] = scratch[content + i];
        }
        pendingEolLength = eol;
    }

    /**
     * Handles a line starting with two hyphens. Returns true when it was a
     * delimiter of an open multipart (and has been fully handled).
     */
    private boolean handleBoundary(int content, int length) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.get(i);
            if (frame.state != State.MULTIPART || frame.boundary == null) {
                continue;
            }
            int blen = frame.boundary.length;
            if (content < 2 + blen) {
                continue;
            }
            boolean same = true;
            for (int j = 0; j < blen; j++) {
                if (scratch[2 + j] != frame.boundary[j]) {
                    same = false;
                    break;
                }
            }
            if (!same) {
                continue;
            }
            int pos = 2 + blen;
            boolean terminator = false;
            if (content >= pos + 2 && scratch[pos] == '-'
                    && scratch[pos + 1] == '-') {
                terminator = true;
                pos += 2;
            }
            boolean clean = true;
            for (int j = pos; j < content; j++) {
                if (scratch[j] != ' ' && scratch[j] != '\t') {
                    clean = false;
                    break;
                }
            }
            if (!clean) {
                continue;
            }
            popTo(i);
            if (done) {
                return true;
            }
            if (structure) {
                countLine(content, length);
            }
            if (emitting) {
                flushPending();
                sink.data(scratch, 0, content);
                int eol = length - content;
                for (int k = 0; k < eol; k++) {
                    pendingEol[k] = scratch[content + k];
                }
                pendingEolLength = eol;
            }
            if (terminator) {
                frame.state = State.LEAF;
            } else if (stack.size() < MAX_DEPTH) {
                frame.children++;
                pushFrame(new Frame(child(frame.number, frame.children),
                        false));
            }
            return true;
        }
        return false;
    }

    /** Closes every frame above index {@code keep}. */
    private void popTo(int keep) {
        while (stack.size() - 1 > keep) {
            Frame closed = stack.remove(stack.size() - 1);
            closeFrame(closed, true);
            if (closed == target) {
                emitting = false;
                done = true;
                pendingEolLength = 0;
            }
        }
        if (target != null && !stack.contains(target)) {
            emitting = false;
            done = true;
            pendingEolLength = 0;
        }
    }

    private void headerLine(Frame frame, int content, int length) {
        if (content == 0) {
            endHeaders(frame, length);
            return;
        }
        if (frame.block != null && frame.block.size() < MAX_HEADER_BLOCK) {
            frame.block.write(scratch, 0, length);
        }
        char first = (char) scratch[0];
        if (first == ' ' || first == '\t') {
            if (frame.inHeader) {
                appendValue(frame, content, 0);
            }
            return;
        }
        finishHeader(frame);
        frame.inHeader = true;
        appendValue(frame, content, 0);
    }

    private void finishHeader(Frame frame) {
        if (!frame.inHeader) {
            return;
        }
        String line = frame.current.toString();
        frame.current.setLength(0);
        frame.inHeader = false;
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String name = line.substring(0, colon).trim().toLowerCase(Locale.ENGLISH);
        String value = line.substring(colon + 1).trim();
        if (structure && STRUCTURE_HEADERS.contains(name)
                && !frame.hdr.containsKey(name)) {
            frame.hdr.put(name, value);
        }
        if (name.equals("content-type")) {
            frame.contentType = value;
        } else if (name.equals("content-transfer-encoding")) {
            frame.encoding = value.toLowerCase(Locale.ENGLISH);
        }
    }

    private void endHeaders(Frame frame, int blankLength) {
        finishHeader(frame);
        ContentType ct = null;
        if (frame.contentType != null) {
            ct = ContentTypeParser.parse(frame.contentType);
        }
        String primary = "text";
        String sub = "plain";
        String charset = null;
        String boundaryText = null;
        if (ct != null) {
            primary = ct.getPrimaryType().toLowerCase(Locale.ENGLISH);
            sub = ct.getSubType().toLowerCase(Locale.ENGLISH);
            charset = ct.getParameter("charset");
            boundaryText = ct.getParameter("boundary");
        }
        String mediaType = primary + "/" + sub;
        boolean multipart = primary.equals("multipart") && boundaryText != null
                && !boundaryText.isEmpty();
        boolean nested = !frame.message && primary.equals("message")
                && sub.equals("rfc822");
        boolean composite = multipart || nested;
        frame.multipart = multipart;
        if (structure && frame.message) {
            frame.envelope = MimeStructureFormat.envelope(frame.hdr);
            if (stack.size() == 1) {
                rootEnvelope = frame.envelope;
                if (envelopeOnly) {
                    done = true;
                    return;
                }
            }
        }
        int[] leafNumber = frame.message ? child(frame.number, 1)
                : frame.number;

        if (frame.candidate) {
            boolean emit;
            MimeSectionSpec.Kind kind = spec.kind;
            if (kind == MimeSectionSpec.Kind.MIME) {
                emit = frame.message ? (!multipart
                        && Arrays.equals(leafNumber, spec.path))
                        : Arrays.equals(frame.number, spec.path);
            } else {
                emit = true;
            }
            if (emit) {
                emitHeaderBlock(frame, blankLength, mediaType, charset,
                        composite);
                done = true;
                return;
            }
        }

        boolean bodyTarget = false;
        switch (spec.kind) {
            case FULL:
                if (frame.message) {
                    bodyTarget = !multipart
                            && Arrays.equals(leafNumber, spec.path);
                } else {
                    bodyTarget = Arrays.equals(frame.number, spec.path);
                }
                break;
            case TEXT:
                bodyTarget = frame.message
                        && Arrays.equals(frame.number, spec.path);
                break;
            case PREVIEW:
                bodyTarget = !composite && !previewTaken
                        && primary.equals("text");
                if (bodyTarget) {
                    previewTaken = true;
                }
                break;
            default:
                break;
        }
        if (bodyTarget) {
            target = frame;
            emitting = true;
            found = true;
            sink.begin(mediaType, charset, frame.encoding, composite);
        }

        if (multipart) {
            frame.state = State.MULTIPART;
            frame.boundary = boundaryText.getBytes(StandardCharsets.ISO_8859_1);
        } else if (nested && stack.size() < MAX_DEPTH) {
            frame.state = State.WRAP;
            pushFrame(new Frame(frame.number, true));
        } else {
            frame.state = State.LEAF;
        }
        frame.block = null;
    }

    /**
     * Builds the structure of a closed entity and hands it to its parent.
     * {@code byBoundary} drops the line terminator that belongs to the
     * delimiter from the entity's size.
     */
    private void closeFrame(Frame frame, boolean byBoundary) {
        if (!structure) {
            return;
        }
        if (frame.state == State.HEADERS) {
            finishHeader(frame);
            frame.state = State.LEAF;
        }
        if (byBoundary && frame.lines > 0 && frame.bytes >= lastEol) {
            frame.bytes -= lastEol;
        }
        if (frame.multipart) {
            frame.ext = MimeStructureFormat.multipart(frame.hdr,
                    frame.partsExt.toString(), true);
            frame.basic = MimeStructureFormat.multipart(frame.hdr,
                    frame.partsBasic.toString(), false);
        } else if (frame.state == State.WRAP && frame.innerExt != null) {
            frame.ext = MimeStructureFormat.leaf(frame.hdr, true, frame.bytes,
                    frame.lines, frame.innerEnvelope, frame.innerExt);
            frame.basic = MimeStructureFormat.leaf(frame.hdr, false,
                    frame.bytes, frame.lines, frame.innerEnvelope,
                    frame.innerBasic);
        } else {
            frame.ext = MimeStructureFormat.leaf(frame.hdr, true, frame.bytes,
                    frame.lines, null, null);
            frame.basic = MimeStructureFormat.leaf(frame.hdr, false,
                    frame.bytes, frame.lines, null, null);
        }
        if (stack.isEmpty()) {
            rootExt = frame.ext;
            rootBasic = frame.basic;
            return;
        }
        Frame parent = stack.get(stack.size() - 1);
        if (parent.multipart) {
            parent.partsExt.append(frame.ext);
            parent.partsBasic.append(frame.basic);
        } else if (parent.state == State.WRAP) {
            parent.innerExt = frame.ext;
            parent.innerBasic = frame.basic;
            parent.innerEnvelope = frame.envelope;
        }
    }

    private void emitHeaderBlock(Frame frame, int blankLength,
            String mediaType, String charset, boolean composite) {
        found = true;
        sink.begin(mediaType, charset, frame.encoding, composite);
        byte[] raw = frame.block.toByteArray();
        if (spec.kind == MimeSectionSpec.Kind.HEADER_FIELDS
                || spec.kind == MimeSectionSpec.Kind.HEADER_FIELDS_NOT) {
            boolean wanted = spec.kind == MimeSectionSpec.Kind.HEADER_FIELDS;
            int pos = 0;
            boolean include = false;
            while (pos < raw.length) {
                int end = pos;
                while (end < raw.length && raw[end] != '\n') {
                    end++;
                }
                int next = Math.min(end + 1, raw.length);
                byte first = raw[pos];
                if (first != ' ' && first != '\t') {
                    int colon = pos;
                    while (colon < end && raw[colon] != ':') {
                        colon++;
                    }
                    String name = "";
                    if (colon < end) {
                        name = new String(raw, pos, colon - pos,
                                StandardCharsets.ISO_8859_1).trim()
                                .toLowerCase(Locale.ENGLISH);
                    }
                    boolean listed = spec.fields.contains(name);
                    include = (colon < end || !wanted) && (listed == wanted);
                }
                if (include) {
                    sink.data(raw, pos, next - pos);
                }
                pos = next;
            }
            byte[] crlf = new byte[] {'\r', '\n'};
            sink.data(crlf, 0, 2);
        } else {
            sink.data(raw, 0, raw.length);
            sink.data(scratch, 0, blankLength);
        }
    }
}
