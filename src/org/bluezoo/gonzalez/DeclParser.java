/*
 * DeclParser.java
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
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for parsers that handle XML declarations (XMLDecl) and text declarations (TextDecl).
 * These parsers extract version, encoding, and standalone information before the main tokenizer starts.
 * 
 * <p>The parser operates directly on the ByteBuffer, reading 7-bit ASCII characters according
 * to the byte encoding scheme (UTF-8, UTF-16LE, or UTF-16BE). This avoids the overhead of
 * creating a CharsetDecoder and CharBuffer for declaration parsing.
 * 
 * <p>XML/text declarations only contain 7-bit ASCII characters, so this direct approach is
 * both safe and efficient. The actual CharsetDecoder is only created after the declaration
 * has been parsed and the final encoding is known.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class DeclParser {
    
    /** Map of attribute names to values extracted from the declaration */
    protected Map<String,String> attributes = new HashMap<>();
    
    /** The BOM detected at start of document (determines byte encoding) */
    protected BOM bom = BOM.NONE;
    
    /** Number of characters consumed by successful parse (for byte position calculation) */
    protected int charsConsumed = 0;

    /**
     * Sets the BOM for reading characters.
     * Must be called before receive() based on BOM detection.
     * 
     * @param bom the detected BOM
     */
    void setBOM(BOM bom) {
        this.bom = bom;
    }
    
    /**
     * Returns the number of characters consumed by a successful parse.
     * This can be used to calculate the byte position after the declaration.
     * 
     * @return the number of characters consumed
     */
    int getCharsConsumed() {
        return charsConsumed;
    }

    /**
     * Parses the declaration from incoming byte data.
     * Can be called multiple times if more data is needed.
     * 
     * <p>The parser saves the buffer position at the start and restores it on failure.
     * On success, the buffer position is left at the end of the declaration.
     * 
     * @param data the byte buffer containing data to parse (positioned at start of potential declaration)
     * @return {@link ReadResult#OK} if declaration was successfully parsed,
     *         {@link ReadResult#FAILURE} if no declaration present,
     *         {@link ReadResult#UNDERFLOW} if more data is needed
     */
    abstract ReadResult receive(ByteBuffer data);
    
    /**
     * Try to read the specified chars from the ByteBuffer.
     * @param data the byte buffer to read from
     * @param test the characters to read
     * @return OK if all characters matched, FAILURE if mismatch, UNDERFLOW if need more data
     */
    protected ReadResult tryRead(ByteBuffer data, String test) {
        for (int i = 0; i < test.length(); i++) {
            int c = bom.nextChar(data);
            if (c == -1) {
                return ReadResult.UNDERFLOW;
            }
            if (c != test.charAt(i)) {
                return ReadResult.FAILURE;
            }
        }
        return ReadResult.OK;
    }

    /**
     * Read an entire attribute in an XMLDecl or TextDecl.
     * Saves position at start and restores it on failure.
     * @param data the byte buffer to read from
     * @param attributeName the name of the attribute we expect
     * @return OK if attribute read successfully, FAILURE if not found, UNDERFLOW if need more data
     */
    protected ReadResult tryReadAttribute(ByteBuffer data, String attributeName) {
        // Save position at start of attribute attempt
        int savedPos = data.position();
        ReadResult ret = requireWhitespace(data);
        switch (ret) {
            case UNDERFLOW:
                return ret; // Can't restore on UNDERFLOW - need more data
            case FAILURE:
                data.position(savedPos); // No whitespace - restore position
                return ret;
            case OK:
                break;
        }
        ret = tryRead(data, attributeName);
        switch (ret) {
            case UNDERFLOW:
                return ret; // Can't restore on UNDERFLOW
            case FAILURE:
                data.position(savedPos); // Attribute name doesn't match - restore position
                return ret;
            case OK:
                break;
        }
        ignoreWhitespace(data);
        ret = tryRead(data, "=");
        switch (ret) {
            case UNDERFLOW:
                return ret;
            case FAILURE:
                data.position(savedPos); // No '=' - restore position
                return ret;
            case OK:
                break;
        }
        ignoreWhitespace(data);
        int quoteChar = bom.nextChar(data);
        if (quoteChar == -1) {
            return ReadResult.UNDERFLOW;
        }
        if (quoteChar != '"' && quoteChar != '\'') {
            data.position(savedPos); // No quote - restore position
            return ReadResult.FAILURE;
        }
        int otherQuote = (quoteChar == '"') ? '\'' : '"';
        StringBuilder buf = new StringBuilder();
        while (true) {
            int c = bom.nextChar(data);
            if (c == -1) {
                return ReadResult.UNDERFLOW;
            }
            if (c == quoteChar) {
                // end of attribute value
                attributes.put(attributeName, buf.toString());
                return ReadResult.OK;
            } else if (c == otherQuote || isInvalidInDeclValue(c)) {
                // Invalid character in declaration attribute value:
                // - Mismatched quotes (e.g., version='1.0")
                // - Whitespace (e.g., version='1.0 ?>')  
                // - Markup characters (< > ?)
                // These indicate malformed declaration syntax
                data.position(savedPos);
                return ReadResult.FAILURE;
            } else {
                buf.append((char) c);
            }
        }
    }
    
    /**
     * Checks if a character is invalid inside an XML/text declaration attribute value.
     * Declaration attribute values (version, encoding, standalone) cannot contain
     * whitespace, markup delimiters, or control characters.
     */
    private boolean isInvalidInDeclValue(int c) {
        // Whitespace is invalid inside declaration attribute values
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            return true;
        }
        // Markup characters that can't appear in version/encoding/standalone values
        if (c == '<' || c == '>' || c == '?') {
            return true;
        }
        return false;
    }

    /**
     * Read as much whitespace as possible.
     * Must read at least some whitespace.
     * @return OK if at least one whitespace was consumed, FAILURE if no whitespace, UNDERFLOW if need more data
     */
    protected ReadResult requireWhitespace(ByteBuffer data) {
        int c = bom.peekChar(data);
        if (c == -1) {
            return ReadResult.UNDERFLOW;
        }
        if (!isWhitespace(c)) {
            return ReadResult.FAILURE;
        }
        bom.nextChar(data); // consume first whitespace
        // Consume as much whitespace as possible
        while (true) {
            c = bom.peekChar(data);
            if (c == -1 || !isWhitespace(c)) {
                return ReadResult.OK;
            }
            bom.nextChar(data); // consume
        }
    }

    /**
     * Ignore any whitespace.
     */
    protected void ignoreWhitespace(ByteBuffer data) {
        while (true) {
            int c = bom.peekChar(data);
            if (c == -1 || !isWhitespace(c)) {
                return;
            }
            bom.nextChar(data); // consume
        }
    }
    
    /**
     * Checks if a character is XML whitespace.
     */
    private boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
    
    /**
     * Returns the character encoding specified in the declaration.
     */
    public String getEncoding() {
        return attributes.get("encoding");
    }
    
    /**
     * Returns the XML version specified in the declaration.
     */
    public String getVersion() {
        return attributes.get("version");
    }
    
    /**
     * Returns the standalone flag specified in the declaration.
     */
    public Boolean getStandalone() {
        String val = attributes.get("standalone");
        return (val == null) ? null : Boolean.valueOf("yes".equals(val));
    }
    
    /**
     * Validates that a version number matches the production: [0-9]+\.[0-9]+
     * Examples: "1.0", "1.1", "2.0" are valid; "1", "1.", "1.0a", "_#1.0" are invalid.
     * 
     * @param version the version string to validate
     * @return true if the version matches the required pattern
     */
    protected static boolean isValidVersionNumber(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        
        int dotPos = version.indexOf('.');
        if (dotPos <= 0 || dotPos >= version.length() - 1) {
            return false; // No dot, or dot at start/end
        }
        
        // Check that all characters before dot are digits
        for (int i = 0; i < dotPos; i++) {
            char c = version.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        
        // Check that all characters after dot are digits
        for (int i = dotPos + 1; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        
        return true;
    }

}
