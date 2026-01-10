/*
 * TokenStreamEvent.java
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

import java.nio.CharBuffer;
import org.xml.sax.Locator;

/**
 * Represents a token event that can be buffered for deferred processing.
 * 
 * <p>This class is used to buffer tokens when forward parameter entity references
 * are encountered in the external DTD subset. The token data and locator information
 * are copied at the time the event occurs, since the original CharBuffer and Locator
 * are transient.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class TokenStreamEvent {
    
    /** The token type. */
    final Token token;
    
    /** The token data as a String (copied from CharBuffer at event time). */
    final String data;
    
    /** The line number at the time the event occurred. */
    final int lineNumber;
    
    /** The column number at the time the event occurred. */
    final int columnNumber;
    
    /**
     * Creates a new TokenStreamEvent.
     * 
     * @param token the token type
     * @param data the token data (extracted from CharBuffer as String)
     * @param lineNumber the line number from the locator
     * @param columnNumber the column number from the locator
     */
    TokenStreamEvent(Token token, String data, int lineNumber, int columnNumber) {
        this.token = token;
        this.data = data;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }
    
    /**
     * Creates a TokenStreamEvent from a token and CharBuffer, extracting data
     * and locator information.
     * 
     * @param token the token type
     * @param data the CharBuffer containing token data (will be extracted as String)
     * @param locator the locator for line/column information
     */
    TokenStreamEvent(Token token, CharBuffer data, Locator locator) {
        this.token = token;
        // Extract data from CharBuffer as String (CharBuffer is transient)
        this.data = (data == null) ? null : data.toString();
        // Copy locator information (locator is transient)
        this.lineNumber = locator != null ? locator.getLineNumber() : -1;
        this.columnNumber = locator != null ? locator.getColumnNumber() : -1;
    }
    
    @Override
    public String toString() {
        if (data != null) {
            return token + "(" + data + ") [" + lineNumber + ":" + columnNumber + "]";
        } else {
            return token + " [" + lineNumber + ":" + columnNumber + "]";
        }
    }
}

