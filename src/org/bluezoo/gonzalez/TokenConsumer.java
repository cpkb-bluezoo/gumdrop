/*
 * TokenConsumer.java
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
import org.xml.sax.SAXException;

/**
 * Consumer of a stream of XML tokens.
 * <p>
 * This interface is implemented by components that process tokenized XML,
 * such as parsers or mock consumers for testing. The tokenizer calls these
 * methods to deliver tokens as they are identified in the input stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
interface TokenConsumer {

    /**
     * Sets the document locator for reporting position information.
     * This is called by the tokenizer before any tokens are emitted to
     * provide access to line and column information for error reporting.
     * 
     * @param locator the document locator
     */
    void setLocator(Locator locator);

    /**
     * Receives a token from the tokenizer.
     * <p>
     * In most cases the token type is all that is important and the data
     * parameter will be null. For NAME, S, CDATA, and ENTITYREF token types,
     * the data parameter will contain a CharBuffer in read mode with its
     * position set to the beginning of the characters to read and the limit
     * set to the end.
     * <p>
     * The consumer should process the data immediately or copy it, as the
     * buffer may be reused by the tokenizer after this method returns.
     * 
     * @param token the token type
     * @param data the token data (CharBuffer in read mode for NAME, S, CDATA,
     *             ENTITYREF tokens; null for all other token types)
     * @throws SAXException if an error occurs processing the token
     */
    void receive(Token token, CharBuffer data) throws SAXException;
    
    /**
     * Reports a fatal tokenizer-level error.
     * <p>
     * This method is called by the tokenizer when it encounters a fatal error
     * that prevents further processing, such as illegal XML characters or
     * encoding errors. The consumer should report this error through the
     * appropriate error handling mechanism (e.g., SAX ErrorHandler).
     * <p>
     * The method returns the exception so callers can throw it:
     * <pre>throw consumer.fatalError("message");</pre>
     * 
     * @param message the error message
     * @return the SAXException to throw
     * @throws SAXException if the ErrorHandler itself throws
     */
    SAXException fatalError(String message) throws SAXException;

    /**
     * Notifies the consumer of the tokenizer's current top-level state.
     * <p>
     * This is called by the tokenizer whenever it transitions to a new top-level
     * state. This allows consumers to track the tokenizer state for purposes such
     * as creating nested tokenizers with the correct initial state when expanding
     * internal entity references.
     * 
     * @param state the current tokenizer state
     */
    void tokenizerState(TokenizerState state);

    /**
     * Notifies the consumer of the XML version detected from the XML declaration.
     * <p>
     * This is called by the tokenizer when it parses the XML declaration and
     * determines the XML version (1.0 or 1.1). This allows consumers to track
     * the version for creating nested tokenizers/decoders with the correct
     * XML version when expanding entity references.
     * 
     * @param isXML11 true if XML 1.1, false if XML 1.0
     */
    void xmlVersion(boolean isXML11);

}

