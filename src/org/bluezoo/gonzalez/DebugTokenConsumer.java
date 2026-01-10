/*
 * DebugTokenConsumer.java
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
import org.xml.sax.ext.Locator2;

/**
 * A debug proxy for TokenConsumer that logs all token events to stdout
 * while delegating to the underlying consumer.
 * 
 * <p>Useful for debugging tokenization issues by seeing the exact sequence
 * of tokens being emitted.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DebugTokenConsumer implements TokenConsumer {
    
    private final TokenConsumer delegate;
    private String prefix;
    
    /**
     * Creates a debug consumer that wraps another consumer.
     * 
     * @param delegate the underlying consumer to delegate to
     * @param prefix a prefix for debug output (e.g., "[MAIN]" or "[DTD]")
     */
    public DebugTokenConsumer(TokenConsumer delegate, String prefix) {
        this.delegate = delegate;
        this.prefix = prefix;
    }
    
    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        // Log the token
        if (data != null && data.hasRemaining()) {
            // Save buffer state
            int pos = data.position();
            int lim = data.limit();
            
            // Extract string without modifying buffer
            StringBuilder sb = new StringBuilder();
            for (int i = pos; i < lim && i < pos + 50; i++) {
                char c = data.get(i);
                if (c == '\n') sb.append("\\n");
                else if (c == '\r') sb.append("\\r");
                else if (c == '\t') sb.append("\\t");
                else sb.append(c);
            }
            if (lim - pos > 50) sb.append("...");
            
            System.out.println(prefix + " " + token + " [" + sb + "]");
        } else {
            System.out.println(prefix + " " + token);
        }
        
        // Delegate to the underlying consumer
        delegate.receive(token, data);
    }
    
    @Override
    public void tokenizerState(TokenizerState state) {
        System.out.println(prefix + " STATE_CHANGE -> " + state);
        delegate.tokenizerState(state);
    }
    
    @Override
    public void xmlVersion(boolean isXML11) {
        System.out.println(prefix + " XML_VERSION -> " + (isXML11 ? "1.1" : "1.0"));
        delegate.xmlVersion(isXML11);
    }
    
    @Override
    public SAXException fatalError(String message) throws SAXException {
        System.out.println(prefix + " FATAL_ERROR: " + message);
        return delegate.fatalError(message);
    }
    
    @Override
    public void setLocator(Locator locator) {
        System.out.println(prefix + " SET_LOCATOR: " + locator);
        delegate.setLocator(locator);
        String systemId = ((Locator2) locator).getSystemId();
        if (systemId != null) {
            prefix = systemId;
        }
    }
}

