/*
 * MockTokenConsumer.java
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
import java.util.ArrayList;
import java.util.List;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * Mock TokenConsumer for testing.
 * <p>
 * This implementation logs all tokens received and provides access to them
 * for test assertions. It can also optionally print tokens to System.out
 * for debugging.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MockTokenConsumer implements TokenConsumer {

    /**
     * Represents a single token event with its type and optional data.
     */
    static class TokenEvent {
        public final Token token;
        public final String data;
        public final int lineNumber;
        public final int columnNumber;

        public TokenEvent(Token token, String data, int lineNumber, int columnNumber) {
            this.token = token;
            this.data = data;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
        }

        @Override
        public String toString() {
            if (data != null) {
                return token + "(" + data + ") [" + lineNumber + ":" + columnNumber + "]";
            } else {
                return token.toString() + " [" + lineNumber + ":" + columnNumber + "]";
            }
        }
    }

    private final List<TokenEvent> events = new ArrayList<>();
    private Locator locator;
    private boolean printToConsole;

    /**
     * Constructs a new MockTokenConsumer.
     */
    public MockTokenConsumer() {
        this(false);
    }

    /**
     * Constructs a new MockTokenConsumer.
     * @param printToConsole if true, tokens will be printed to System.out as they are received
     */
    public MockTokenConsumer(boolean printToConsole) {
        this.printToConsole = printToConsole;
    }

    @Override
    public void setLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void tokenizerState(TokenizerState state) {
    }
    
    @Override
    public void xmlVersion(boolean isXML11) {
        // No-op for testing - tests don't need to track version
    }

    @Override
    public void receive(Token token, CharBuffer data) throws SAXException {
        String dataString = null;
        if (data != null) {
            // Extract the data from the CharBuffer
            StringBuilder sb = new StringBuilder();
            while (data.hasRemaining()) {
                sb.append(data.get());
            }
            dataString = sb.toString();
        }

        int line = locator != null ? locator.getLineNumber() : -1;
        int column = locator != null ? locator.getColumnNumber() : -1;

        TokenEvent event = new TokenEvent(token, dataString, line, column);
        events.add(event);

        if (printToConsole) {
            System.out.println(event);
        }
    }
    
    @Override
    public SAXException fatalError(String message) throws SAXException {
        int line = locator != null ? locator.getLineNumber() : -1;
        int column = locator != null ? locator.getColumnNumber() : -1;
        
        if (printToConsole) {
            System.err.println("FATAL ERROR at [" + line + ":" + column + "]: " + message);
        }
        
        return new org.xml.sax.SAXParseException(message, locator);
    }

    /**
     * Returns all token events received.
     * @return the list of token events
     */
    public List<TokenEvent> getEvents() {
        return events;
    }

    /**
     * Clears all recorded token events.
     */
    public void clear() {
        events.clear();
    }

    /**
     * Returns the number of token events received.
     * @return the event count
     */
    public int getEventCount() {
        return events.size();
    }

    /**
     * Returns a specific token event by index.
     * @param index the event index
     * @return the token event
     */
    public TokenEvent getEvent(int index) {
        return events.get(index);
    }

    /**
     * Prints all recorded token events to System.out.
     */
    public void printEvents() {
        System.out.println("=== Token Events ===");
        for (int i = 0; i < events.size(); i++) {
            System.out.println(i + ": " + events.get(i));
        }
        System.out.println("=== Total: " + events.size() + " events ===");
    }

    /**
     * Returns a compact string representation of all tokens.
     * Useful for test assertions.
     * Format: "TOKEN TOKEN(data) TOKEN ..."
     * @return the token sequence string
     */
    public String getTokenSequence() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(" ");
            }
            TokenEvent event = events.get(i);
            sb.append(event.token);
            if (event.data != null) {
                sb.append("(").append(event.data).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * Asserts that the token sequence matches the expected string.
     * @param expected the expected token sequence
     * @throws AssertionError if the sequences don't match
     */
    public void assertTokenSequence(String expected) {
        String actual = getTokenSequence();
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Token sequence mismatch:\n" +
                "Expected: " + expected + "\n" +
                "Actual:   " + actual
            );
        }
    }

}

