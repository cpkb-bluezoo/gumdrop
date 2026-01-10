/*
 * TextDeclParser.java
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

/**
 * Specialized parser for text declarations (external parsed entities).
 * 
 * <p>Grammar: TextDecl ::= '<?xml' VersionInfo? EncodingDecl S? '?>'
 * 
 * <p>Differences from XMLDecl:
 * <ul>
 * <li>version is optional (but if present, must come first)</li>
 * <li>encoding is REQUIRED</li>
 * <li>standalone is FORBIDDEN (early failure if seen)</li>
 * </ul>
 * 
 * <p>This parser operates directly on the ByteBuffer (no CharsetDecoder needed),
 * saving/restoring position to handle failures gracefully. On success, the buffer
 * position is left at the end of the declaration (after "?>").
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class TextDeclParser extends DeclParser {
    
    @Override
    public ReadResult receive(ByteBuffer data) {
        attributes.clear();
        
        // Save position at start for restoration on failure
        int startPos = data.position();
        
        switch (tryRead(data, "<?xml")) {
            case FAILURE:
                data.position(startPos);
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
            case OK:
                break;
        }
        switch (tryReadAttribute(data, "version")) {
            case FAILURE:
                // version is optional for TextDecl
                break;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
            case OK:
                break;
        }
        switch (tryReadAttribute(data, "encoding")) {
            case FAILURE:
                data.position(startPos);
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
            case OK:
                break;
        }
        ignoreWhitespace(data);
        switch (tryRead(data, "?>")) {
            case FAILURE:
                data.position(startPos);
                return ReadResult.FAILURE;
            case UNDERFLOW:
                return ReadResult.UNDERFLOW;
            case OK:
                break;
        }
        String version = getVersion();
        if (version != null) {
            // Validate version matches pattern: [0-9]+\.[0-9]+
            if (!isValidVersionNumber(version)) {
                data.position(startPos);
                return ReadResult.FAILURE; // Invalid version format
            }
            // Validate version is 1.x (XML versions must be 1.0, 1.1, etc.)
            if (!version.startsWith("1.")) {
                data.position(startPos);
                return ReadResult.FAILURE; // Only 1.x versions are valid XML versions
            }
        }
        if (getEncoding() == null) {
            // encoding is required
            data.position(startPos);
            return ReadResult.FAILURE;
        }
        
        // Calculate characters consumed for byte position calculation
        charsConsumed = (data.position() - startPos) / bom.bytesPerChar;
        
        return ReadResult.OK;
    }

}
