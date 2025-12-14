/*
 * DKIMMessageParser.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.smtp.auth;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.rfc5322.MessageParser;

/**
 * A message parser that captures raw header bytes for DKIM verification.
 *
 * <p>This parser extends {@link MessageParser} and intercepts header lines
 * to capture their raw bytes before decoding. DKIM signature verification
 * requires the original, unmodified header bytes for canonicalization.
 *
 * <p>Headers are captured in order of appearance. The parser tracks multiple
 * occurrences of headers with the same name (e.g., multiple Received headers),
 * which is important because DKIM signs headers in a specific order.
 *
 * <h4>Captured Headers</h4>
 *
 * <p>All headers are captured, but DKIM typically signs:
 * <ul>
 *   <li>From (required)</li>
 *   <li>To, Cc, Reply-To</li>
 *   <li>Subject</li>
 *   <li>Date</li>
 *   <li>Message-ID</li>
 *   <li>Content-Type, MIME-Version</li>
 *   <li>DKIM-Signature (itself, with b= removed)</li>
 * </ul>
 *
 * <p>The actual signed headers are determined by the h= tag in the
 * DKIM-Signature header.
 *
 * <h4>Usage</h4>
 *
 * <pre><code>
 * DKIMMessageParser parser = new DKIMMessageParser();
 * parser.setMessageHandler(handler);
 *
 * // Feed message data
 * parser.receive(buffer);
 *
 * // After headers are complete, get raw header bytes
 * byte[] fromBytes = parser.getHeaderBytes("from");
 * List&lt;byte[]&gt; allReceivedHeaders = parser.getAllHeaderBytes("received");
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DKIMValidator
 */
public class DKIMMessageParser extends MessageParser {

    /**
     * Stores a raw header with name and bytes.
     *
     * <p>For DKIM canonicalization:
     * <ul>
     *   <li><strong>Simple mode</strong>: Use {@link #getBytes()} - preserves
     *       CRLF at fold points exactly as received</li>
     *   <li><strong>Relaxed mode</strong>: Use {@link #getBytesUnfolded()} -
     *       removes CRLF at fold points (keeps the continuation whitespace)</li>
     * </ul>
     *
     * <p>Note: The final line-ending CRLF is preserved in both modes.
     */
    public static class RawHeader {
        private final String name;
        private final byte[] bytes;

        RawHeader(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        /**
         * Returns the header name.
         *
         * @return the header name (original case preserved)
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the raw header bytes for simple canonicalization.
         *
         * <p>Includes CRLF at fold points exactly as received.
         * This is the format needed for DKIM "simple" header canonicalization.
         *
         * @return the raw bytes with fold CRLFs preserved
         */
        public byte[] getBytes() {
            return bytes;
        }

        /**
         * Returns the header bytes with fold CRLFs removed.
         *
         * <p>Removes CRLF (or LF) at fold points but keeps the continuation
         * whitespace (space or tab). The final line-ending is preserved.
         * This is the format needed for DKIM "relaxed" header canonicalization
         * (before further whitespace processing).
         *
         * <p>A fold is detected as CRLF (or LF) followed by a space or tab.
         *
         * @return the bytes with fold line-endings stripped
         */
        public byte[] getBytesUnfolded() {
            // Count how many fold CRLFs we need to remove
            int foldBytes = 0;
            for (int i = 0; i < bytes.length - 1; i++) {
                if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                    // Check if followed by whitespace (fold)
                    if (i + 2 < bytes.length) {
                        byte next = bytes[i + 2];
                        if (next == ' ' || next == '\t') {
                            foldBytes += 2; // CRLF to remove
                            i++; // skip the LF in loop
                        }
                    }
                } else if (bytes[i] == '\n') {
                    // Bare LF fold (non-conformant but handle it)
                    if (i + 1 < bytes.length) {
                        byte next = bytes[i + 1];
                        if (next == ' ' || next == '\t') {
                            foldBytes += 1; // LF to remove
                        }
                    }
                }
            }

            if (foldBytes == 0) {
                return bytes;
            }

            // Build result without fold line-endings
            byte[] result = new byte[bytes.length - foldBytes];
            int destPos = 0;
            int i = 0;
            while (i < bytes.length) {
                if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    // Check if this is a fold (followed by whitespace)
                    if (i + 2 < bytes.length) {
                        byte next = bytes[i + 2];
                        if (next == ' ' || next == '\t') {
                            // Skip the CRLF, keep the whitespace
                            i += 2;
                            continue;
                        }
                    }
                    // Not a fold, copy the CRLF
                    result[destPos++] = bytes[i++];
                    result[destPos++] = bytes[i++];
                } else if (bytes[i] == '\n') {
                    // Check if this is a bare LF fold
                    if (i + 1 < bytes.length) {
                        byte next = bytes[i + 1];
                        if (next == ' ' || next == '\t') {
                            // Skip the LF, keep the whitespace
                            i++;
                            continue;
                        }
                    }
                    // Not a fold, copy the LF
                    result[destPos++] = bytes[i++];
                } else {
                    result[destPos++] = bytes[i++];
                }
            }

            return result;
        }

        /**
         * Returns the raw header as a string using ISO-8859-1 encoding.
         * This preserves the byte values exactly (simple mode).
         *
         * @return the header as string with fold CRLFs preserved
         */
        public String asString() {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }

        /**
         * Returns the unfolded header as a string using ISO-8859-1 encoding.
         *
         * @return the header as string with fold CRLFs removed
         */
        public String asStringUnfolded() {
            return new String(getBytesUnfolded(), StandardCharsets.ISO_8859_1);
        }
    }

    /** All headers in order of appearance */
    private List<RawHeader> rawHeaders;

    /** Headers indexed by lowercase name for fast lookup */
    private Map<String, List<RawHeader>> rawHeaderMap;

    /** Accumulator for folded header lines */
    private ByteArrayOutputStream currentHeaderBytes;

    /** Current header name being accumulated */
    private String currentHeaderName;

    /** Whether we have finished processing headers */
    private boolean headersComplete;

    // -- Body hash computation --

    /** Body hash digest */
    private MessageDigest bodyHashDigest;

    /** Sink for potential trailing empty lines */
    private ByteArrayOutputStream trailingSink;

    /** Whether to use relaxed body canonicalization */
    private boolean bodyRelaxed;

    /** Body length limit from DKIM signature (l= tag) */
    private long bodyLengthLimit;

    /** Bytes processed so far (for body length limit) */
    private long bodyBytesProcessed;

    /**
     * Creates a new DKIM message parser.
     */
    public DKIMMessageParser() {
        super();
        this.rawHeaders = new ArrayList<RawHeader>();
        this.rawHeaderMap = new HashMap<String, List<RawHeader>>();
        this.currentHeaderBytes = new ByteArrayOutputStream();
        this.headersComplete = false;
    }

    /**
     * Intercepts header lines to capture raw bytes.
     *
     * <p>This method captures the raw bytes before calling the parent
     * implementation. Folded headers (continuation lines) are accumulated
     * together with their preceding header line.
     */
    @Override
    protected void headerLine(ByteBuffer buffer) throws MIMEParseException {
        if (headersComplete) {
            // Already processed headers, just delegate
            super.headerLine(buffer);
            return;
        }

        // Capture the raw bytes before parent processes them
        int start = buffer.position();
        int end = buffer.limit();

        // Check if this is an empty line (end of headers)
        int length = end - start;
        boolean isEmpty = isEmptyLine(buffer, start, length);
        
        if (isEmpty) {
            // Flush any pending header before ending
            flushCurrentHeader();
            headersComplete = true;
            super.headerLine(buffer);
            return;
        }

        // Check if this is a continuation line (starts with whitespace)
        byte firstByte = buffer.get(start);
        boolean isContinuation = (firstByte == ' ' || firstByte == '\t');

        if (isContinuation) {
            // Continuation - append to current header (CRLF before this is preserved)
            if (currentHeaderName != null) {
                appendRawBytes(buffer, start, end);
            }
        } else {
            // New header - flush previous and start new
            flushCurrentHeader();

            // Extract header name from this line
            currentHeaderName = extractHeaderName(buffer, start, end);
            if (currentHeaderName != null) {
                appendRawBytes(buffer, start, end);
            }
        }

        // Call parent to do normal processing
        super.headerLine(buffer);
    }

    /**
     * Extracts the header name from a header line.
     */
    private String extractHeaderName(ByteBuffer buffer, int start, int end) {
        // Find the colon
        for (int i = start; i < end; i++) {
            byte c = buffer.get(i);
            if (c == ':') {
                // Extract name
                int nameLen = i - start;
                byte[] nameBytes = new byte[nameLen];
                for (int j = 0; j < nameLen; j++) {
                    nameBytes[j] = buffer.get(start + j);
                }
                return new String(nameBytes, StandardCharsets.ISO_8859_1).trim();
            }
            // Stop if we hit illegal characters for field-name
            if (c < 33 || c > 126 || c == ':') {
                break;
            }
        }
        return null;
    }

    /**
     * Appends raw bytes from the buffer to the current header accumulator.
     */
    private void appendRawBytes(ByteBuffer buffer, int start, int end) {
        int length = end - start;
        byte[] bytes = new byte[length];
        int savedPos = buffer.position();
        buffer.position(start);
        buffer.get(bytes);
        buffer.position(savedPos);
        currentHeaderBytes.write(bytes, 0, length);
    }

    /**
     * Flushes the current accumulated header to storage.
     */
    private void flushCurrentHeader() {
        if (currentHeaderName == null || currentHeaderBytes.size() == 0) {
            return;
        }

        byte[] bytes = currentHeaderBytes.toByteArray();
        RawHeader header = new RawHeader(currentHeaderName, bytes);

        // Add to ordered list
        rawHeaders.add(header);

        // Add to map by lowercase name
        String lowerName = currentHeaderName.toLowerCase();
        List<RawHeader> list = rawHeaderMap.get(lowerName);
        if (list == null) {
            list = new ArrayList<RawHeader>();
            rawHeaderMap.put(lowerName, list);
        }
        list.add(header);

        // Reset for next header
        currentHeaderName = null;
        currentHeaderBytes.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API for accessing captured headers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all captured headers in order of appearance.
     *
     * @return list of raw headers
     */
    public List<RawHeader> getRawHeaders() {
        return rawHeaders;
    }

    /**
     * Returns the first raw header with the given name (case-insensitive).
     *
     * @param name the header name
     * @return the raw header, or null if not found
     */
    public RawHeader getRawHeader(String name) {
        List<RawHeader> list = rawHeaderMap.get(name.toLowerCase());
        if (list != null && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Returns the raw bytes of the first header with the given name.
     *
     * @param name the header name (case-insensitive)
     * @return the raw bytes, or null if not found
     */
    public byte[] getHeaderBytes(String name) {
        RawHeader header = getRawHeader(name);
        return header != null ? header.getBytes() : null;
    }

    /**
     * Returns all raw headers with the given name (case-insensitive).
     * Headers are returned in order of appearance (first to last).
     *
     * @param name the header name
     * @return list of raw headers (may be empty)
     */
    public List<RawHeader> getAllRawHeaders(String name) {
        List<RawHeader> list = rawHeaderMap.get(name.toLowerCase());
        if (list != null) {
            return list;
        }
        return new ArrayList<RawHeader>();
    }

    /**
     * Returns the raw bytes of all headers with the given name.
     *
     * @param name the header name (case-insensitive)
     * @return list of raw byte arrays
     */
    public List<byte[]> getAllHeaderBytes(String name) {
        List<byte[]> result = new ArrayList<byte[]>();
        List<RawHeader> list = rawHeaderMap.get(name.toLowerCase());
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                result.add(list.get(i).getBytes());
            }
        }
        return result;
    }

    /**
     * Returns whether headers have been completely processed.
     *
     * @return true if the empty line terminating headers has been seen
     */
    public boolean isHeadersComplete() {
        return headersComplete;
    }

    /**
     * Returns the parsed DKIM-Signature header if present.
     *
     * @return the DKIM signature, or null if not found
     */
    public DKIMSignature getDKIMSignature() {
        RawHeader sigHeader = getRawHeader("dkim-signature");
        if (sigHeader == null) {
            return null;
        }

        // Extract just the value part (after "DKIM-Signature:")
        String full = sigHeader.asString();
        int colonPos = full.indexOf(':');
        if (colonPos < 0) {
            return null;
        }
        String value = full.substring(colonPos + 1);
        return DKIMSignature.parse(value);
    }

    @Override
    public void reset() {
        super.reset();
        rawHeaders.clear();
        rawHeaderMap.clear();
        currentHeaderBytes.reset();
        currentHeaderName = null;
        headersComplete = false;
        bodyHashDigest = null;
        if (trailingSink != null) {
            trailingSink.reset();
        }
        bodyBytesProcessed = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Body hash computation for DKIM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initializes body hash computation.
     *
     * <p>Call this after headers are complete and the DKIM-Signature has been
     * parsed. The algorithm and canonicalization are determined from the signature.
     *
     * @param algorithm the hash algorithm ("SHA-256" or "SHA-1")
     * @param relaxed true for relaxed body canonicalization
     * @param lengthLimit body length limit from l= tag, or -1 for no limit
     * @throws NoSuchAlgorithmException if algorithm not supported
     */
    public void initBodyHash(String algorithm, boolean relaxed, long lengthLimit) 
            throws NoSuchAlgorithmException {
        this.bodyHashDigest = MessageDigest.getInstance(algorithm);
        this.bodyRelaxed = relaxed;
        this.bodyLengthLimit = lengthLimit;
        this.bodyBytesProcessed = 0;
        this.trailingSink = new ByteArrayOutputStream();
    }

    /**
     * Returns the computed body hash.
     *
     * <p>Call this after parsing is complete. Trailing empty lines in the
     * sink are intentionally not included in the hash (per RFC 6376).
     *
     * @return the body hash bytes, or null if not initialized
     */
    public byte[] getBodyHash() {
        if (bodyHashDigest == null) {
            return null;
        }
        // Trailing content in sink is discarded (trailing empty lines)
        return bodyHashDigest.digest();
    }

    /**
     * Intercepts body lines to compute the DKIM body hash.
     *
     * <p>Uses a sink to defer hashing of potential trailing empty lines.
     * Lines with content cause the sink to be flushed to the digest first.
     *
     * <p>If body hash hasn't been initialized yet, auto-initializes it
     * from the DKIM-Signature header (if present).
     */
    @Override
    protected void bodyLine(ByteBuffer buffer) throws MIMEParseException {
        // Auto-initialize body hash if not yet done
        if (bodyHashDigest == null && headersComplete) {
            autoInitBodyHash();
        }

        // Compute body hash if initialized
        if (bodyHashDigest != null) {
            hashBodyLine(buffer);
        }

        // Call parent for normal MIME processing
        super.bodyLine(buffer);
    }

    /**
     * Auto-initializes body hash from the DKIM-Signature header.
     */
    private void autoInitBodyHash() {
        DKIMSignature sig = getDKIMSignature();
        if (sig == null) {
            return;
        }

        // Algorithm is like "rsa-sha256" - extract hash part
        String algorithm = sig.getAlgorithm();
        String digestName;
        if (algorithm != null && algorithm.contains("sha256")) {
            digestName = "SHA-256";
        } else if (algorithm != null && algorithm.contains("sha1")) {
            digestName = "SHA-1";
        } else {
            return;
        }

        // Body canonicalization
        String bodyCanon = sig.getBodyCanonicalization();
        boolean relaxed = "relaxed".equals(bodyCanon);

        try {
            initBodyHash(digestName, relaxed, sig.getBodyLength());
        } catch (NoSuchAlgorithmException e) {
            // Can't compute body hash - signature verification will fail
        }
    }

    /**
     * Processes a body line for DKIM hash computation.
     */
    private void hashBodyLine(ByteBuffer buffer) {
        int start = buffer.position();
        int end = buffer.limit();
        int length = end - start;

        if (bodyRelaxed) {
            hashBodyLineRelaxed(buffer, start, end);
        } else {
            hashBodyLineSimple(buffer, start, length);
        }
    }

    /**
     * Simple body canonicalization.
     *
     * <p>Hash lines as-is, but defer empty lines (CRLF only) to the sink.
     * When a non-empty line is seen, flush sink first.
     */
    private void hashBodyLineSimple(ByteBuffer buffer, int start, int length) {
        // Check if this is an empty line (just CRLF or LF)
        boolean isEmpty = isEmptyLine(buffer, start, length);

        if (isEmpty) {
            // Add CRLF to sink - might be trailing
            trailingSink.write('\r');
            trailingSink.write('\n');
        } else {
            // Non-empty line - flush sink then hash this line
            flushSinkToDigest();
            hashBytes(buffer, start, length);
        }
    }

    /**
     * Relaxed body canonicalization.
     *
     * <p>Reduces WSP sequences to single SP, removes trailing WSP on each line.
     * Lines that become empty after WSP removal are deferred to sink.
     */
    private void hashBodyLineRelaxed(ByteBuffer buffer, int start, int end) {
        // Process line: compress WSP, strip trailing WSP
        ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
        boolean prevWasWSP = false;
        boolean hasContent = false;

        for (int i = start; i < end; i++) {
            byte b = buffer.get(i);

            // Skip line terminators - we'll add CRLF at the end
            if (b == '\r' || b == '\n') {
                continue;
            }

            if (b == ' ' || b == '\t') {
                // Whitespace - mark it but don't write yet
                if (hasContent && !prevWasWSP) {
                    prevWasWSP = true;
                }
            } else {
                // Non-whitespace content
                if (prevWasWSP) {
                    lineOut.write(' ');  // Emit single SP for WSP sequence
                    prevWasWSP = false;
                }
                lineOut.write(b);
                hasContent = true;
            }
        }

        // Now we have the canonicalized line content (without trailing WSP)
        byte[] lineBytes = lineOut.toByteArray();

        if (lineBytes.length == 0) {
            // Line is empty after canonicalization - add to sink
            trailingSink.write('\r');
            trailingSink.write('\n');
        } else {
            // Line has content - flush sink then hash this line + CRLF
            flushSinkToDigest();
            hashBytesArray(lineBytes);
            hashByte((byte) '\r');
            hashByte((byte) '\n');
        }
    }

    /**
     * Checks if a line is empty (only contains CRLF or LF).
     */
    private boolean isEmptyLine(ByteBuffer buffer, int start, int length) {
        if (length == 0) {
            return true;
        }
        if (length == 1) {
            byte b = buffer.get(start);
            return b == '\n' || b == '\r';
        }
        if (length == 2) {
            return buffer.get(start) == '\r' && buffer.get(start + 1) == '\n';
        }
        return false;
    }

    /**
     * Flushes the trailing sink to the digest.
     */
    private void flushSinkToDigest() {
        byte[] sinkBytes = trailingSink.toByteArray();
        if (sinkBytes.length > 0) {
            hashBytesArray(sinkBytes);
            trailingSink.reset();
        }
    }

    /**
     * Hashes bytes from the buffer, respecting body length limit.
     */
    private void hashBytes(ByteBuffer buffer, int start, int length) {
        for (int i = 0; i < length; i++) {
            if (bodyLengthLimit >= 0 && bodyBytesProcessed >= bodyLengthLimit) {
                return;
            }
            bodyHashDigest.update(buffer.get(start + i));
            bodyBytesProcessed++;
        }
    }

    /**
     * Hashes a byte array, respecting body length limit.
     */
    private void hashBytesArray(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bodyLengthLimit >= 0 && bodyBytesProcessed >= bodyLengthLimit) {
                return;
            }
            bodyHashDigest.update(bytes[i]);
            bodyBytesProcessed++;
        }
    }

    /**
     * Hashes a single byte, respecting body length limit.
     */
    private void hashByte(byte b) {
        if (bodyLengthLimit >= 0 && bodyBytesProcessed >= bodyLengthLimit) {
            return;
        }
        bodyHashDigest.update(b);
        bodyBytesProcessed++;
    }

}
