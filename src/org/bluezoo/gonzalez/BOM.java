/*
 * BOM.java
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents the Byte Order Mark (BOM) detected at the start of an XML document.
 * 
 * <p>XML/text declarations only contain 7-bit ASCII characters, so we can
 * read them directly from the ByteBuffer without creating a CharsetDecoder.
 * The BOM determines how bytes map to characters during declaration parsing:
 * <ul>
 * <li>NONE: No BOM present, assume 1 byte per ASCII character (UTF-8 compatible)</li>
 * <li>UTF8: UTF-8 BOM (EF BB BF), 1 byte per ASCII character</li>
 * <li>UTF16LE: UTF-16 LE BOM (FF FE), 2 bytes per character (little-endian)</li>
 * <li>UTF16BE: UTF-16 BE BOM (FE FF), 2 bytes per character (big-endian)</li>
 * </ul>
 * 
 * <p>After parsing the declaration, the actual Charset and CharsetDecoder
 * can be created based on the encoding attribute (if present). The BOM value
 * is used to validate that any declared encoding is compatible with the BOM.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum BOM {
    
    /**
     * No BOM present.
     * Assumes UTF-8 compatible encoding (1 byte per ASCII character).
     * Any declared encoding is acceptable.
     */
    NONE(1, StandardCharsets.UTF_8),
    
    /**
     * UTF-8 BOM: 0xEF 0xBB 0xBF
     * 1 byte per ASCII character.
     * Declared encoding must be UTF-8 compatible.
     */
    UTF8(1, StandardCharsets.UTF_8),
    
    /**
     * UTF-16 Little Endian BOM: 0xFF 0xFE
     * 2 bytes per character, low byte first.
     * Declared encoding must be UTF-16 compatible.
     */
    UTF16LE(2, StandardCharsets.UTF_16LE),
    
    /**
     * UTF-16 Big Endian BOM: 0xFE 0xFF
     * 2 bytes per character, high byte first.
     * Declared encoding must be UTF-16 compatible.
     */
    UTF16BE(2, StandardCharsets.UTF_16BE);
    
    /** Number of bytes per character for 7-bit ASCII */
    final int bytesPerChar;
    
    /** The corresponding Java charset (default if no encoding declared) */
    final Charset defaultCharset;
    
    BOM(int bytesPerChar, Charset defaultCharset) {
        this.bytesPerChar = bytesPerChar;
        this.defaultCharset = defaultCharset;
    }
    
    /**
     * Reads the next 7-bit ASCII character from the ByteBuffer.
     * 
     * @param data the ByteBuffer to read from
     * @return the character (0-127), or -1 if not enough bytes available
     * @throws IllegalArgumentException if the byte sequence is not valid 7-bit ASCII
     */
    int nextChar(ByteBuffer data) {
        switch (this) {
            case NONE:
            case UTF8:
                if (!data.hasRemaining()) {
                    return -1;
                }
                int b = data.get() & 0xFF;
                if (b > 0x7F) {
                    throw new IllegalArgumentException(
                        "Non-ASCII byte in XML/text declaration: 0x" + Integer.toHexString(b));
                }
                return b;
                
            case UTF16LE:
                if (data.remaining() < 2) {
                    return -1;
                }
                int lo = data.get() & 0xFF;
                int hi = data.get() & 0xFF;
                if (hi != 0 || lo > 0x7F) {
                    throw new IllegalArgumentException(
                        "Non-ASCII character in XML/text declaration: 0x" + 
                        Integer.toHexString((hi << 8) | lo));
                }
                return lo;
                
            case UTF16BE:
                if (data.remaining() < 2) {
                    return -1;
                }
                hi = data.get() & 0xFF;
                lo = data.get() & 0xFF;
                if (hi != 0 || lo > 0x7F) {
                    throw new IllegalArgumentException(
                        "Non-ASCII character in XML/text declaration: 0x" + 
                        Integer.toHexString((hi << 8) | lo));
                }
                return lo;
                
            default:
                throw new IllegalStateException("Unknown BOM: " + this);
        }
    }
    
    /**
     * Peeks at the next 7-bit ASCII character without consuming it.
     * 
     * @param data the ByteBuffer to peek from
     * @return the character (0-127), or -1 if not enough bytes available
     * @throws IllegalArgumentException if the byte sequence is not valid 7-bit ASCII
     */
    int peekChar(ByteBuffer data) {
        int pos = data.position();
        int c = nextChar(data);
        data.position(pos);
        return c;
    }
    
    /**
     * Calculates the byte position for a given character offset.
     * 
     * @param charOffset the character offset
     * @return the byte offset
     */
    int byteOffset(int charOffset) {
        return charOffset * bytesPerChar;
    }
    
    /**
     * Returns whether this BOM requires charset validation.
     * NONE does not require validation; all other BOMs do.
     * 
     * @return true if charset validation is required
     */
    boolean requiresCharsetValidation() {
        return this != NONE;
    }
}

