/*
 * ExternalEntityDecoder.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;
import org.xml.sax.SAXException;

/**
 * Decodes byte streams from external entities into character streams for tokenization.
 * 
 * <p>This class handles:
 * <ul>
 * <li>BOM (Byte Order Mark) detection</li>
 * <li>XML/text declaration parsing (directly from bytes - no decode needed)</li>
 * <li>Charset decoding with underflow handling</li>
 * <li>Line-ending normalization</li>
 * <li>Location tracking (line/column numbers)</li>
 * </ul>
 * 
 * <p>Decoded character data is fed to a {@link Tokenizer} via its
 * {@code receive(CharBuffer)} method.
 * 
 * <h3>Zero-Copy Declaration Parsing</h3>
 * <p>XML/text declarations only contain 7-bit ASCII characters. This class
 * parses declarations directly from the ByteBuffer without creating a CharsetDecoder,
 * using the byte encoding scheme (UTF-8/UTF-16LE/UTF-16BE) detected from the BOM.
 * The CharsetDecoder is only created after the declaration is parsed and the
 * final encoding is known.
 * 
 * <h3>Buffer Contract</h3>
 * <p>The caller is responsible for proper buffer management following the standard
 * NIO pattern: read, flip, receive, compact. On entry to {@code receive()}, the
 * buffer must be in read mode (position at start of data, limit at end). On exit,
 * the buffer's position will point to the first unconsumed byte (which may be
 * part of an incomplete multi-byte character sequence). The caller must compact
 * the buffer before reading more data.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ExternalEntityDecoder {
    
    // ===== Configuration and Document Metadata =====
    
    /**
     * The character set used to decode incoming bytes into characters.
     * Only set after declaration parsing is complete.
     */
    private Charset charset;
    
    /**
     * The BOM detected at start of document.
     * Determines byte encoding for declaration parsing.
     */
    private BOM bom = BOM.NONE;
    
    // ===== Tokenizer Integration =====
    
    /**
     * Whether this decoder is for an external entity (vs. the document entity).
     * External entities have text declarations (no standalone attribute allowed),
     * while the document entity has an XML declaration (standalone attribute allowed).
     */
    private final boolean isExternalEntity;
    
    /**
     * Whether this entity is being processed as XML 1.1 (vs. XML 1.0).
     * Affects character validity rules and line normalization.
     * Set when XML/text declaration specifies version="1.1".
     */
    private boolean xml11 = false;
    
    // ===== Buffers =====
    
    /**
     * Character decoder for the current charset.
     * Only created after declaration parsing is complete.
     */
    private CharsetDecoder decoder;
    
    /**
     * Working buffer for decoded character data.
     * Reused to avoid allocation on every receive() call.
     * Only allocated when actual decoding begins (after declaration).
     */
    private CharBuffer charBuffer;
    
    /**
     * Tokenizer that consumes decoded characters.
     */
    private final Tokenizer tokenizer;
    
    // ===== State =====
    
    /**
     * Decoder state for tracking processing phase.
     */
    private enum State {
        INIT,           // Initial state, checking for BOM
        SEEN_BOM,       // BOM detected (or no BOM), ready to check for XMLDecl/TextDecl
        CONTENT,        // Processing content with main tokenizer
        CLOSED          // Decoder has been closed, cannot receive more data
    }
    
    private State state = State.INIT;
    
    
    /**
     * XMLDecl or TextDecl parser.
     */
    private final DeclParser declParser;
    
    /**
     * Byte position at start of document (after BOM, if any).
     */
    private int startDecl;
    
    /**
     * The last character read for line-ending normalization.
     */
    private char lastChar = '\u0000';
    
    // ===== Constructor =====
    
    /**
     * Creates a new external entity decoder.
     * 
     * @param tokenizer the tokenizer to receive decoded characters
     * @param publicId public identifier for this entity (may be null)
     * @param systemId system identifier for this entity (may be null)
     * @param isExternalEntity true if this is an external parsed entity, false for document entity
     */
    public ExternalEntityDecoder(Tokenizer tokenizer, String publicId, String systemId, boolean isExternalEntity) {
        this.tokenizer = Objects.requireNonNull(tokenizer);
        tokenizer.publicId = publicId;
        tokenizer.systemId = systemId;
        this.isExternalEntity = isExternalEntity;
        declParser = isExternalEntity ? new TextDeclParser() : new XMLDeclParser();
    }
    
    // ===== Accessors =====
    
    /**
     * Returns whether this decoder is for an external parsed entity.
     */
    public boolean isExternalEntity() {
        return isExternalEntity;
    }
    
    // ===== Public API =====
    
    /**
     * Receives a buffer of bytes to decode and tokenize.
     * 
     * <p>The buffer must be in read mode on entry. On return, the buffer's position
     * will be set to the first unconsumed byte.
     * 
     * @param data the byte buffer to process (position will be updated)
     * @throws SAXException if a parsing error occurs
     */
    public void receive(ByteBuffer data) throws SAXException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Decoder is closed");
        }
        
        if (!data.hasRemaining()) {
            return;
        }
        
        // Process based on state
        switch (state) {
            case INIT:
                // Detect BOM
                if (!parseBOM(data)) {
                    return; // Need more bytes
                }
                // Fall through to SEEN_BOM
                
            case SEEN_BOM:
                // Parse XMLDecl/TextDecl directly from bytes
                if (!parseDeclaration(data)) {
                    return; // Need more data
                }
                // Declaration parsed (or not present), now in CONTENT state
                // Fall through to CONTENT
                
            case CONTENT:
                // Decode and send to tokenizer
                decodeAndTokenize(data);
                break;
                
            case CLOSED:
                throw new IllegalStateException("Decoder is closed");
        }
    }
    
    /**
     * Closes the decoder and flushes any remaining data to the tokenizer.
     */
    public void close() throws SAXException {
        if (state == State.CLOSED) {
            return;
        }
        tokenizer.close();
        state = State.CLOSED;
    }
    
    /**
     * Resets the decoder to its initial state for reuse.
     * Preserves allocated buffers to avoid reallocation.
     */
    public void reset() {
        state = State.INIT;
        charset = null;
        bom = BOM.NONE;
        xml11 = false;
        // Reset decoder but keep the object if charset stays the same
        if (decoder != null) {
            decoder.reset();
        }
        decoder = null;  // Will be recreated for new charset
        // Keep charBuffer allocated - just clear it
        if (charBuffer != null) {
            charBuffer.clear();
        }
        lastChar = '\u0000';
    }
    
    /**
     * Sets the initial charset hint (e.g., from HTTP headers).
     * Must be called before receive() if the charset is known externally.
     */
    public void setInitialCharset(Charset initialCharset) {
        if (state != State.INIT) {
            throw new IllegalStateException("Cannot set initial charset after decoding has started");
        }
        // Map charset to byte encoding for declaration parsing
        if (initialCharset != null) {
            if (initialCharset.equals(StandardCharsets.UTF_16LE)) {
                bom = BOM.UTF16LE;
            } else if (initialCharset.equals(StandardCharsets.UTF_16BE)) {
                bom = BOM.UTF16BE;
            } else {
                bom = BOM.NONE;
            }
        }
    }
    
    // ===== BOM Detection =====
    
    /**
     * Detects and consumes a BOM if present, setting the byte encoding accordingly.
     * 
     * @return true if BOM detection is complete, false if more data is needed
     */
    private boolean parseBOM(ByteBuffer data) throws SAXException {
        int startPos = data.position();
        
        if (data.remaining() < 2) {
            return false; // Need at least 2 bytes for BOM detection
        }
        
        int b0 = data.get() & 0xFF;
        int b1 = data.get() & 0xFF;
        
        if (b0 == 0xFE && b1 == 0xFF) {
            // UTF-16 Big Endian BOM
            bom = BOM.UTF16BE;
            startDecl = data.position();
            tokenizer.charPosition = 1; // BOM is 1 character
            tokenizer.columnNumber = 1;
        } else if (b0 == 0xFF && b1 == 0xFE) {
            // UTF-16 Little Endian BOM
            bom = BOM.UTF16LE;
            startDecl = data.position();
            tokenizer.charPosition = 1;
            tokenizer.columnNumber = 1;
        } else if (b0 == 0xEF && b1 == 0xBB) {
            // Potential UTF-8 BOM, need third byte
            if (!data.hasRemaining()) {
                data.position(startPos);
                return false;
            }
            int b2 = data.get() & 0xFF;
            if (b2 == 0xBF) {
                // UTF-8 BOM
                bom = BOM.UTF8;
                startDecl = data.position();
                tokenizer.charPosition = 1;
                tokenizer.columnNumber = 1;
            } else {
                // Not a BOM, restore position
                data.position(startPos);
                startDecl = startPos;
            }
        } else {
            // No BOM, restore position
            data.position(startPos);
            startDecl = startPos;
        }
        
        // Set encoding on declaration parser
        declParser.setBOM(bom);
        
        state = State.SEEN_BOM;
        return true;
    }
    
    // ===== Declaration Parsing =====
    
    /**
     * Parses the XML/text declaration directly from bytes.
     * 
     * @return true if declaration parsing is complete, false if more data is needed
     */
    private boolean parseDeclaration(ByteBuffer data) throws SAXException {
        int savedPos = data.position();
        
        try {
            ReadResult result = declParser.receive(data);
            
            switch (result) {
                case UNDERFLOW:
                    // Need more data, position already restored by declParser
                    data.position(savedPos);
                    return false;
                    
                case FAILURE:
                    // No declaration present, position restored by declParser
                    // Use default charset (UTF-8 or BOM-indicated charset)
                    setupCharsetDecoder(null);
                    state = State.CONTENT;
                    return true;
                    
                case OK:
                    // Declaration parsed successfully
                    String declEncoding = declParser.getEncoding();
                    String declVersion = declParser.getVersion();
                    Boolean declStandalone = declParser.getStandalone();
                    
                    // Handle version
                    if (declVersion != null) {
                        boolean entityXml11 = "1.1".equals(declVersion);
                        
                        if (!isExternalEntity) {
                            // Main document - sets the processor mode
                            tokenizer.version = declVersion;
                            tokenizer.documentVersion = declVersion;
                            xml11 = entityXml11;
                            tokenizer.xml11 = xml11;
                            tokenizer.notifyXmlVersion(xml11);
                        } else {
                            // External entity - check version compatibility
                            boolean documentXml11 = "1.1".equals(tokenizer.documentVersion);
                            
                            if (!documentXml11 && entityXml11) {
                                throw tokenizer.fatalError(
                                    "XML 1.0 document cannot include XML 1.1 entity (version " + declVersion + ")");
                            }
                            
                            tokenizer.version = declVersion;
                            xml11 = documentXml11 ? entityXml11 : false;
                            tokenizer.xml11 = xml11;
                        }
                    }
                    
                    // Handle standalone (document entity only)
                    if (declStandalone != null) {
                        tokenizer.standalone = declStandalone;
                    }
                    
                    // Setup charset decoder with declared encoding
                    setupCharsetDecoder(declEncoding);
                    
                    // Update position tracking for declaration
                    int declChars = declParser.getCharsConsumed();
                    tokenizer.charPosition += declChars;
                    // Count newlines in declaration for line number tracking
                    // (declarations typically don't have newlines, but handle it correctly)
                    tokenizer.columnNumber += declChars;
                    
                    state = State.CONTENT;
                    return true;
            }
        } catch (IllegalArgumentException e) {
            // Non-ASCII byte in declaration
            throw tokenizer.fatalError(e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Sets up the CharsetDecoder based on the declared encoding.
     * Validates BOM/encoding compatibility.
     */
    private void setupCharsetDecoder(String declEncoding) throws SAXException {
        if (declEncoding != null) {
            // Declared encoding specified
            try {
                charset = Charset.forName(declEncoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw tokenizer.fatalError("Invalid or unsupported encoding: " + declEncoding);
            }
            
            // Validate BOM/encoding compatibility (only if BOM was present)
            if (bom.requiresCharsetValidation()) {
                validateBOMEncodingCompatibility(declEncoding);
            }
            
            tokenizer.encoding = declEncoding;
        } else {
            // No declared encoding - use BOM-indicated charset or default to UTF-8
            charset = bom.defaultCharset;
            tokenizer.encoding = charset.name();
        }
        
        // Create the CharsetDecoder
        decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    }
    
    /**
     * Validates that the declared encoding is compatible with the BOM.
     */
    private void validateBOMEncodingCompatibility(String declEncoding) throws SAXException {
        String normalized = declEncoding.toUpperCase().replace("-", "").replace("_", "");
        
        switch (bom) {
            case UTF16BE:
                if (!normalized.contains("UTF16")) {
                    throw tokenizer.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-16 BE BOM");
                }
                break;
                
            case UTF16LE:
                if (!normalized.contains("UTF16")) {
                    throw tokenizer.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-16 LE BOM");
                }
                break;
                
            case UTF8:
                if (normalized.startsWith("UTF16")) {
                    throw tokenizer.fatalError(
                        "Encoding '" + declEncoding + "' is incompatible with UTF-8 BOM");
                }
                break;
                
            case NONE:
                // No BOM, any encoding is acceptable
                break;
        }
    }
    
    // ===== Content Decoding =====
    
    /**
     * Maximum CharBuffer size. Processing is done incrementally to avoid
     * allocating a buffer sized for the entire input.
     */
    private static final int MAX_CHAR_BUFFER = 8192;
    
    /**
     * Decodes bytes to characters and sends to tokenizer.
     * Processes incrementally to avoid large buffer allocations.
     */
    private void decodeAndTokenize(ByteBuffer data) throws SAXException {
        if (!data.hasRemaining()) {
            return;
        }
        
        // Allocate CharBuffer if needed (capped at MAX_CHAR_BUFFER)
        if (charBuffer == null) {
            charBuffer = CharBuffer.allocate(MAX_CHAR_BUFFER);
        }
        
        // Process in chunks - decode, tokenize, compact, repeat
        while (data.hasRemaining()) {
            // Decode into charBuffer (from current position to limit)
            CoderResult result = decoder.decode(data, charBuffer, false);
            
            // Check for decoding errors
            if (result.isError()) {
                if (result.isMalformed()) {
                    throw tokenizer.fatalError("Malformed byte sequence in encoding " + charset.name() + 
                        " (length: " + result.length() + ")");
                } else if (result.isUnmappable()) {
                    throw tokenizer.fatalError("Unmappable byte sequence in encoding " + charset.name() + 
                        " (length: " + result.length() + ")");
                }
            }
            
            // Normalize line endings
            normalizeLineEndings();
            
            // Flip to read mode before passing to tokenizer
            charBuffer.flip();
            
            // Pass to tokenizer
            tokenizer.receive(charBuffer);
            
            // Compact to preserve any unconsumed data (underflow)
            charBuffer.compact();
            
            // If OVERFLOW, the charBuffer was full - continue loop to process more
            // If UNDERFLOW, we've consumed all we can from data - loop will exit
        }
    }
    
    /**
     * Normalizes line endings in the character buffer according to XML spec.
     * 
     * <p>XML line ending normalization rules:
     * <ul>
     *   <li>CR (\r) alone → LF (\n)</li>
     *   <li>CR LF (\r\n) → LF (\n) - the LF is removed</li>
     *   <li>LF (\n) alone → LF (unchanged)</li>
     *   <li>XML 1.1 only: NEL (\u0085) → LF</li>
     *   <li>XML 1.1 only: LS (\u2028) → LF</li>
     * </ul>
     * 
     * <p>This implementation uses a single-pass O(n) algorithm with separate
     * read and write positions, avoiding the O(n²) cost of shifting data
     * for each CRLF encountered.
     */
    private void normalizeLineEndings() {
        // Buffer is in write mode: normalize from 0 to position
        int end = charBuffer.position();
        if (end == 0) return;
        
        int writePos = 0;  // Where to write the next character
        
        for (int readPos = 0; readPos < end; readPos++) {
            char c = charBuffer.get(readPos);
            
            if (c == '\r') {
                // Replace CR with LF
                charBuffer.put(writePos++, '\n');
            } else if (c == '\n' && lastChar == '\r') {
                // CR LF: skip the LF entirely (the CR was already converted to LF)
                // Don't write anything, don't increment writePos
            } else if (xml11 && (c == '\u0085' || c == '\u2028')) {
                // XML 1.1 line endings: replace with LF
                charBuffer.put(writePos++, '\n');
            } else {
                // Normal character: copy it to write position
                charBuffer.put(writePos++, c);
            }
            lastChar = c;
        }
        
        // Adjust buffer position to reflect any removed CRLF sequences
        // Note that this is the *end* of the data normalized not the start
        charBuffer.position(writePos);
    }

}
