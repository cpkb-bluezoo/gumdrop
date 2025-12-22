/*
 * JSONTokenizer.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.json;

import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A JSON tokenizer that operates on CharBuffer for streaming parsing.
 * This follows the rules given in ECMA 404 / RFC 8259.
 * <p>
 * The tokenizer calls JSONContentHandler methods directly as tokens are recognized.
 * Character data is provided via CharBuffer, allowing efficient extraction of
 * string values using position/limit without intermediate copies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class JSONTokenizer implements JSONLocator {

    private final JSONContentHandler handler;
    private StringBuilder escapeBuilder; // Lazily allocated for strings with escapes
    private boolean needsWhitespace; // Whether handler needs whitespace events
    
    private int lineNumber = 1;
    private int columnNumber = 0;
    private boolean closed = false;
    
    // Track context for key vs value
    private Deque<Context> contextStack = new ArrayDeque<>();
    private State state = State.EXPECT_VALUE;  // Root level expects a value
    
    // Minimal parsing state for structural validation
    Deque<Token> depthStack = new ArrayDeque<>();  // Track nesting depth
    boolean seenAnyToken = false;  // Track if we've seen any non-whitespace token
    boolean afterComma = false;  // Track if we just saw a comma
    
    private enum Context {
        ARRAY, OBJECT
    }
    
    private enum State {
        EXPECT_VALUE,      // Expecting any value
        EXPECT_KEY,        // Expecting a key (string in object)
        EXPECT_COLON,      // Expecting colon after key
        AFTER_VALUE        // After value, expecting comma or close bracket
    }

    /**
     * Creates a tokenizer with the given handler.
     *
     * @param handler the content handler to call with parsing events
     */
    JSONTokenizer(JSONContentHandler handler) {
        this.handler = handler;
        
        if (handler != null) {
            handler.setLocator(this);
            this.needsWhitespace = handler.needsWhitespace();
        }
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public int getColumnNumber() {
        return columnNumber;
    }

    /**
     * Signal that the input stream is closed.
     */
    void setClosed(boolean closed) {
        this.closed = closed;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Process tokens from the CharBuffer, calling handler methods.
     * Buffer must be in read mode (position at start, limit at end).
     * Returns when buffer is exhausted or an incomplete token is encountered.
     * Buffer position will be updated to reflect consumed characters.
     */
    void receive(CharBuffer data) throws JSONException {
        while (data.hasRemaining()) {
            // Save position for backtracking
            int saveLineNumber = lineNumber;
            int saveColumnNumber = columnNumber;
            int startPos = data.position();
            data.mark();
            
            char c = data.get();
            
            // Check if this is whitespace (which handles its own line/column tracking)
            boolean isWhitespace = (c == ' ' || c == '\n' || c == '\t' || c == '\r');
            
            boolean processed = processToken(c, data);
            
            if (!processed) {
                // Incomplete token - backtrack
                data.reset();
                lineNumber = saveLineNumber;
                columnNumber = saveColumnNumber;
                return;
            }
            
            // Update column number based on characters consumed
            // (but not for whitespace, which handles its own line/column tracking)
            if (!isWhitespace) {
                int charsConsumed = data.position() - startPos;
                columnNumber += charsConsumed;
            }
        }
    }

    /**
     * Process a single token starting with char c.
     * Returns true if token was complete, false if need more data.
     */
    private boolean processToken(char c, CharBuffer data) throws JSONException {
        switch (c) {
            case '"':
                // String can be a key or a value
                if (state == State.EXPECT_COLON || state == State.AFTER_VALUE) {
                    throw new JSONException("Unexpected string");
                }
                boolean isKey = (state == State.EXPECT_KEY);
                if (!processString(data, isKey)) {
                    return false;
                }
                seenAnyToken = true;
                afterComma = false;
                if (isKey) {
                    state = State.EXPECT_COLON;
                } else {
                    state = State.AFTER_VALUE;
                }
                return true;
                
            case ',':
                if (state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected ','");
                }
                if (contextStack.isEmpty()) {
                    throw new JSONException("Unexpected comma at root level");
                }
                seenAnyToken = true;
                afterComma = true;
                // After comma: expect key in object, value in array
                state = (contextStack.peek() == Context.OBJECT) ? State.EXPECT_KEY : State.EXPECT_VALUE;
                return true;
                
            case ':':
                if (state != State.EXPECT_COLON) {
                    throw new JSONException("Unexpected ':'");
                }
                seenAnyToken = true;
                afterComma = false;
                state = State.EXPECT_VALUE;
                return true;
                
            case '{':
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected '{'");
                }
                handler.startObject();
                contextStack.push(Context.OBJECT);
                depthStack.push(Token.START_OBJECT);
                seenAnyToken = true;
                afterComma = false;
                state = State.EXPECT_KEY;  // Objects start expecting key (or '}')
                return true;
                
            case '}':
                if (contextStack.isEmpty() || contextStack.peek() != Context.OBJECT) {
                    throw new JSONException("Unexpected '}'");
                }
                // Can close empty object (EXPECT_KEY) or after a value (AFTER_VALUE)
                if (state != State.EXPECT_KEY && state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected '}'");
                }
                // Cannot close after comma
                if (afterComma) {
                    throw new JSONException("Trailing comma before '}'");
                }
                handler.endObject();
                contextStack.pop();
                depthStack.pop();
                seenAnyToken = true;
                afterComma = false;
                state = contextStack.isEmpty() ? State.AFTER_VALUE : State.AFTER_VALUE;
                return true;
                
            case '[':
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected '['");
                }
                handler.startArray();
                contextStack.push(Context.ARRAY);
                depthStack.push(Token.START_ARRAY);
                seenAnyToken = true;
                afterComma = false;
                state = State.EXPECT_VALUE;  // Arrays start expecting value (or ']')
                return true;
                
            case ']':
                if (contextStack.isEmpty() || contextStack.peek() != Context.ARRAY) {
                    throw new JSONException("Unexpected ']'");
                }
                // Can close empty array (EXPECT_VALUE) or after a value (AFTER_VALUE)
                if (state != State.EXPECT_VALUE && state != State.AFTER_VALUE) {
                    throw new JSONException("Unexpected ']'");
                }
                // Cannot close after comma
                if (afterComma) {
                    throw new JSONException("Trailing comma before ']'");
                }
                handler.endArray();
                contextStack.pop();
                depthStack.pop();
                seenAnyToken = true;
                afterComma = false;
                state = contextStack.isEmpty() ? State.AFTER_VALUE : State.AFTER_VALUE;
                return true;
                
            case ' ':
            case '\n':
            case '\t':
            case '\r':
                return processWhitespace(c, data);
                
            case 't': // true
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'true'");
                }
                if (!processLiteral(data, "rue", true)) {
                    return false;
                }
                seenAnyToken = true;
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;
                
            case 'f': // false
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'false'");
                }
                if (!processLiteral(data, "alse", false)) {
                    return false;
                }
                seenAnyToken = true;
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;
                
            case 'n': // null
                if (state != State.EXPECT_VALUE) {
                    throw new JSONException("Unexpected 'null'");
                }
                if (!processLiteral(data, "ull", null)) {
                    return false;
                }
                seenAnyToken = true;
                afterComma = false;
                state = State.AFTER_VALUE;
                return true;
                
            default:
                // number
                if (c == '-' || (c >= '0' && c <= '9')) {
                    if (state != State.EXPECT_VALUE) {
                        throw new JSONException("Unexpected number");
                    }
                    if (!processNumber(c, data)) {
                        return false;
                    }
                    seenAnyToken = true;
                    afterComma = false;
                    state = State.AFTER_VALUE;
                    return true;
                }
                throw new JSONException("Unexpected character: " + c);
        }
    }

    /**
     * Process a string token.
     * Opening quote already consumed.
     */
    private boolean processString(CharBuffer data, boolean isKey) throws JSONException {
        // We'll use direct extraction for strings without escapes
        // Once we hit an escape, we'll switch to StringBuilder
        int startPos = data.position();
        StringBuilder builder = null;
        
        while (data.hasRemaining()) {
            char c = data.get();
            
            if (c == '"') {
                // Complete string - extract value
                String value;
                if (builder != null) {
                    // Had escapes - use builder
                    value = builder.toString();
                } else {
                    // No escapes - extract directly from CharBuffer
                    int endPos = data.position() - 1; // Before closing quote
                    if (endPos > startPos) {
                        // Save current state
                        int savedPos = data.position();
                        int savedLimit = data.limit();
                        // Extract substring
                        data.limit(endPos).position(startPos);
                        value = data.toString();
                        // Restore state
                        data.limit(savedLimit).position(savedPos);
                    } else {
                        // Empty string
                        value = "";
                    }
                }
                
                // Call appropriate handler method
                if (isKey) {
                    handler.key(value);
                } else {
                    handler.stringValue(value);
                }
                return true;
            } else if (c == '\\') {
                // Hit an escape - need to use StringBuilder from now on
                if (builder == null) {
                    // Allocate builder with smart capacity management
                    if (escapeBuilder == null) {
                        escapeBuilder = new StringBuilder(256);
                    } else if (escapeBuilder.capacity() > 16384) {
                        // StringBuilder grew too large, reallocate to avoid keeping large buffer
                        escapeBuilder = new StringBuilder(256);
                    } else {
                        escapeBuilder.setLength(0);
                    }
                    builder = escapeBuilder;
                    
                    // Copy any characters read so far (before this backslash)
                    int endPos = data.position() - 1; // Position of backslash
                    if (endPos > startPos) {
                        int savedPos = data.position();
                        int savedLimit = data.limit();
                        data.limit(endPos).position(startPos);
                        builder.append(data);
                        data.limit(savedLimit).position(savedPos);
                    }
                }
                
                // Process escape sequence
                Character escaped = processEscapeSequence(data);
                if (escaped == null) {
                    return false; // Need more data
                }
                builder.append(escaped);
            } else if (c < 0x20) {
                throw new JSONException("Unescaped control character in string");
            } else {
                // Regular character
                if (builder != null) {
                    // Already building - append
                    builder.append(c);
                }
                // Otherwise will be extracted from buffer later
            }
        }
        
        // Ran out of buffer
        if (!closed) {
            return false; // Need more data
        }
        
        throw new JSONException("Unclosed string");
    }

    /**
     * Process an escape sequence.
     * Backslash already consumed.
     */
    private Character processEscapeSequence(CharBuffer data) throws JSONException {
        if (!data.hasRemaining()) {
            if (!closed) {
                return null; // Need more data
            }
            throw new JSONException("Unexpected EOF in escape sequence");
        }
        
        char c = data.get();
        
        switch (c) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return processUnicodeEscape(data);
            default:
                throw new JSONException("Invalid escape sequence: \\" + c);
        }
    }

    /**
     * Process a Unicode escape sequence \\uXXXX.
     * The \\u already consumed.
     */
    private Character processUnicodeEscape(CharBuffer data) throws JSONException {
        int value = 0;
        
        for (int i = 0; i < 4; i++) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return null; // Need more data
                }
                throw new JSONException("Incomplete Unicode escape");
            }
            
            char c = data.get();
            
            int digit = unhex(c);
            value = (value << 4) | digit;
        }
        
        return (char) value;
    }

    /**
     * Convert a hex digit to its numeric value.
     */
    private int unhex(char c) throws JSONException {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        throw new JSONException("Invalid hex digit: " + c);
    }

    /**
     * Process whitespace starting with first char.
     */
    private boolean processWhitespace(char first, CharBuffer data) throws JSONException {
        // Update line/column for first character
        if (first == '\n') {
            lineNumber++;
            columnNumber = 0;
        } else if (first == '\r') {
            lineNumber++;
            columnNumber = 0;
        }
        
        if (!needsWhitespace) {
            // Handler doesn't need whitespace - just consume it
            while (data.hasRemaining()) {
                int savedPos = data.position();
                char c = data.get();
                
                if (c == ' ' || c == '\t') {
                    columnNumber++;
                } else if (c == '\n') {
                    lineNumber++;
                    columnNumber = 0;
                } else if (c == '\r') {
                    lineNumber++;
                    columnNumber = 0;
                } else {
                    // Not whitespace - backtrack
                    data.position(savedPos);
                    break;
                }
            }
            return true;
        }
        
        // Handler needs whitespace - extract string
        int startPos = data.position() - 1; // Before first char
        
        while (data.hasRemaining()) {
            int savedPos = data.position();
            char c = data.get();
            
            if (c == ' ' || c == '\t') {
                columnNumber++;
            } else if (c == '\n') {
                lineNumber++;
                columnNumber = 0;
            } else if (c == '\r') {
                lineNumber++;
                columnNumber = 0;
            } else {
                // Not whitespace - backtrack
                data.position(savedPos);
                break;
            }
        }
        
        // Extract whitespace string from CharBuffer
        int endPos = data.position();
        int savedPos = data.position();
        int savedLimit = data.limit();
        data.limit(endPos).position(startPos);
        String ws = data.toString();
        data.limit(savedLimit).position(savedPos);
        
        handler.whitespace(ws);
        return true;
    }

    /**
     * Process a literal (true, false, null).
     * First character already consumed, remaining contains rest of literal.
     */
    private boolean processLiteral(CharBuffer data, String remaining, Boolean boolValue) throws JSONException {
        // First character already consumed and counted
        for (int i = 0; i < remaining.length(); i++) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return false; // Need more data
                }
                throw new JSONException("Incomplete literal");
            }
            
            char c = data.get();
            if (c != remaining.charAt(i)) {
                throw new JSONException("Invalid literal");
            }
        }
        
        // Call appropriate handler method
        if (boolValue != null) {
            handler.booleanValue(boolValue);
        } else {
            handler.nullValue();
        }
        return true;
    }

    /**
     * Process a number token.
     * First character already consumed.
     * DO NOT call mark() - the mark is set by receive() at the start of the token.
     */
    private boolean processNumber(char first, CharBuffer data) throws JSONException {
        int startPos = data.position() - 1; // Before first char
        
        boolean negativeNumber = (first == '-');
        
        if (negativeNumber) {
            if (!data.hasRemaining()) {
                if (!closed) {
                    return false; // Need more data
                }
                throw new JSONException("Invalid number: just '-'");
            }
            first = data.get();
        }
        
        // Integer part
        if (first == '0') {
            // After 0, next must not be a digit (no leading zeros)
            if (data.hasRemaining()) {
                int savedPos = data.position();
                char next = data.get();
                if (next >= '0' && next <= '9') {
                    throw new JSONException("Numbers cannot have leading zeros");
                }
                // Backtrack - we'll re-read for fractional/exponent check
                data.position(savedPos);
            } else if (!closed) {
                return false; // Might be 0.5 coming
            }
        } else if (first >= '1' && first <= '9') {
            // Consume remaining digits
            boolean sawNonDigit = false;
            while (data.hasRemaining()) {
                int savedPos = data.position();
                char c = data.get();
                if (c >= '0' && c <= '9') {
                    // Continue
                } else {
                    // Non-digit - backtrack
                    data.position(savedPos);
                    sawNonDigit = true;
                    break;
                }
            }
            
            if (!sawNonDigit && !closed) {
                return false; // Might be more digits or . or e coming
            }
        } else {
            throw new JSONException("Invalid number format");
        }
        
        // Optional fractional part
        if (data.hasRemaining()) {
            int savedPos = data.position();
            char c = data.get();
            if (c == '.') {
                
                // Must have at least one digit after decimal
                if (!data.hasRemaining()) {
                    if (!closed) {
                        return false;
                    }
                    throw new JSONException("Decimal point must be followed by digit");
                }
                
                char digit = data.get();
                if (digit < '0' || digit > '9') {
                    throw new JSONException("Decimal point must be followed by digit");
                }
                
                // Consume remaining fractional digits
                while (data.hasRemaining()) {
                    int pos = data.position();
                    char d = data.get();
                    if (d >= '0' && d <= '9') {
                        // Continue
                    } else {
                        data.position(pos);
                        break;
                    }
                }
                
                // After decimal digits, check for exponent
                if (!data.hasRemaining()) {
                    if (!closed) {
                        return false; // Might be 'e' coming
                    }
                    // No exponent, number is complete - fall through to end
                } else {
                    // Peek at next character for exponent
                    savedPos = data.position();
                    c = data.get();
                    
                    // Check for exponent
                    if (c == 'e' || c == 'E') {
                        
                        if (!data.hasRemaining()) {
                            if (!closed) {
                                return false;
                            }
                            throw new JSONException("Incomplete exponent");
                        }
                        
                        char sign = data.get();
                        if (sign == '+' || sign == '-') {
                            
                            if (!data.hasRemaining()) {
                                if (!closed) {
                                    return false;
                                }
                                throw new JSONException("Exponent must have digit");
                            }
                            sign = data.get();
                        }
                        
                        // Must have at least one digit
                        if (sign < '0' || sign > '9') {
                            throw new JSONException("Exponent must have digit");
                        }
                        
                        // Consume remaining exponent digits
                        while (data.hasRemaining()) {
                            int pos = data.position();
                            char d = data.get();
                            if (d >= '0' && d <= '9') {
                                // Continue
                            } else {
                                data.position(pos);
                                break;
                            }
                        }
                        
                        if (!data.hasRemaining() && !closed) {
                            return false; // Might be more digits
                        }
                    } else {
                        // Not exponent - backtrack
                        data.position(savedPos);
                    }
                }
            } else {
                // Not decimal point - backtrack
                data.position(savedPos);
                
                // Check for exponent (without decimal)
                if (c == 'e' || c == 'E') {
                    // Consume the 'e'/'E' that we peeked at
                    data.get();
                    
                    if (!data.hasRemaining()) {
                        if (!closed) {
                            return false;
                        }
                        throw new JSONException("Incomplete exponent");
                    }
                    
                    char sign = data.get();
                    if (sign == '+' || sign == '-') {
                        
                        if (!data.hasRemaining()) {
                            if (!closed) {
                                return false;
                            }
                            throw new JSONException("Exponent must have digit");
                        }
                        sign = data.get();
                    }
                    
                    // Must have at least one digit
                    if (sign < '0' || sign > '9') {
                        throw new JSONException("Exponent must have digit");
                    }
                    
                    // Consume remaining exponent digits
                    while (data.hasRemaining()) {
                        int pos = data.position();
                        char d = data.get();
                        if (d >= '0' && d <= '9') {
                            // Continue
                        } else {
                            data.position(pos);
                            break;
                        }
                    }
                    
                    if (!data.hasRemaining() && !closed) {
                        return false; // Might be more digits
                    }
                }
                // else: not decimal or exponent, number is complete as integer
            }
        } else if (!closed) {
            return false; // Might be . or e coming
        }
        
        // Extract and parse number
        Number num = parseNumberDirect(data, startPos, negativeNumber);
        handler.numberValue(num);
        return true;
    }

    /**
     * Extract string from CharBuffer between startPos and current position.
     */
    private String extractString(CharBuffer data, int startPos) {
        int endPos = data.position();
        int savedPos = data.position();
        int savedLimit = data.limit();
        data.position(startPos).limit(endPos);
        String result = data.toString();
        data.position(savedPos).limit(savedLimit);
        return result;
    }

    /**
     * Parse a number directly from CharBuffer without creating intermediate String.
     * Falls back to String-based parsing for very large integers or floating point.
     */
    private Number parseNumberDirect(CharBuffer data, int startPos, boolean negative) throws JSONException {
        int endPos = data.position();
        int length = endPos - startPos;
        
        // Check if it contains decimal point or exponent
        boolean hasDecimalOrExponent = false;
        for (int i = startPos; i < endPos; i++) {
            char c = data.get(i);
            if (c == '.' || c == 'e' || c == 'E') {
                hasDecimalOrExponent = true;
                break;
            }
        }
        
        if (hasDecimalOrExponent) {
            // Floating point or scientific notation - use String parsing
            String numStr = extractString(data, startPos);
            try {
                return Double.valueOf(numStr);
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid number: " + numStr, e);
            }
        }
        
        // Integer - try to parse directly
        // Fast path for integers that fit in long (most common case)
        if (length <= 19) { // Max long is 19 digits
            try {
                long value = 0;
                int pos = startPos;
                
                // Skip negative sign if present (already tracked)
                if (data.get(pos) == '-') {
                    pos++;
                }
                
                // Parse digits
                while (pos < endPos) {
                    char c = data.get(pos);
                    if (c < '0' || c > '9') {
                        throw new JSONException("Invalid number character: " + c);
                    }
                    
                    int digit = c - '0';
                    
                    // Check for overflow
                    if (value > (Long.MAX_VALUE - digit) / 10) {
                        // Would overflow, fall back to BigInteger
                        String numStr = extractString(data, startPos);
                        return new BigInteger(numStr);
                    }
                    
                    value = value * 10 + digit;
                    pos++;
                }
                
                if (negative) {
                    value = -value;
                }
                
                // Return Integer if it fits, otherwise Long
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) value);
                }
                return Long.valueOf(value);
                
            } catch (JSONException e) {
                throw e;
            } catch (Exception e) {
                // Unexpected error, fall back to String parsing
                String numStr = extractString(data, startPos);
                throw new JSONException("Invalid number: " + numStr, e);
            }
        } else {
            // Very large integer - use BigInteger via String
            String numStr = extractString(data, startPos);
            try {
                return new BigInteger(numStr);
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid number: " + numStr, e);
            }
        }
    }

    /**
     * Parse a number string into appropriate Number type.
     * This method is now deprecated in favour of parseNumberDirect.
     */
    private Number parseNumber(String s) throws JSONException {
        try {
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return Double.valueOf(s);
            } else {
                try {
                    long l = Long.parseLong(s);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return Integer.valueOf((int) l);
                    }
                    return Long.valueOf(l);
                } catch (NumberFormatException e) {
                    return new BigInteger(s);
                }
            }
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid number: " + s, e);
        }
    }
}
