/*
 * ResponseParser.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses SMTP server responses from incoming data stream.
 * 
 * <p>SMTP responses can be single-line or multi-line:
 * <ul>
 * <li>Single-line: "250 OK"</li>
 * <li>Multi-line: "250-First line\r\n250-Second line\r\n250 Last line"</li>
 * </ul>
 * 
 * <p>Multi-line responses use a hyphen after the code for continuation lines
 * and a space after the code for the final line.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResponseParser {
    
    private final ClientLineReader lineReader;
    private List<String> responseLines;
    private int expectedCode = -1;
    private boolean inMultiLineResponse = false;
    
    /**
     * Creates response parser with default line reader configuration.
     */
    public ResponseParser() {
        this.lineReader = new ClientLineReader();
        this.responseLines = new ArrayList<String>();
    }
    
    /**
     * Processes incoming data and extracts complete SMTP responses.
     * 
     * @param data incoming data buffer
     * @return complete response if available, null if more data needed
     * @throws SMTPException if response format is invalid
     */
    public SMTPResponse parseResponse(ByteBuffer data) throws SMTPException {
        String line;
        
        while ((line = lineReader.readLine(data)) != null) {
            // Parse response line format: "NNN MESSAGE" or "NNN-MESSAGE"
            if (line.length() < 4) {
                throw new SMTPException("Invalid SMTP response line: " + line);
            }
            
            // Extract response code
            String codeStr = line.substring(0, 3);
            int code;
            try {
                code = Integer.parseInt(codeStr);
            } catch (NumberFormatException e) {
                throw new SMTPException("Invalid SMTP response code: " + codeStr, e);
            }
            
            // Check for valid separator
            char separator = line.charAt(3);
            if (separator != ' ' && separator != '-') {
                throw new SMTPException("Invalid SMTP response separator: " + separator);
            }
            
            // Extract message text
            String message = (line.length() > 4) ? line.substring(4) : "";
            
            // Handle multi-line response logic
            if (!inMultiLineResponse) {
                // Starting new response
                expectedCode = code;
                responseLines.clear();
                inMultiLineResponse = (separator == '-');
            } else {
                // Continuing multi-line response
                if (code != expectedCode) {
                    throw new SMTPException("Response code mismatch in multi-line response: " +
                                          "expected " + expectedCode + ", got " + code);
                }
            }
            
            responseLines.add(message);
            
            // Check if response is complete
            if (separator == ' ') {
                // Response complete (final line uses space separator)
                SMTPResponse response = new SMTPResponse(expectedCode, new ArrayList<String>(responseLines));
                
                // Reset state for next response
                inMultiLineResponse = false;
                expectedCode = -1;
                responseLines.clear();
                
                return response;
            }
            // else: separator == '-', continue reading more lines
        }
        
        // No complete response yet
        return null;
    }
    
    /**
     * Checks if parser is currently in the middle of a multi-line response.
     * 
     * @return true if expecting more lines for current response
     */
    public boolean isInMultiLineResponse() {
        return inMultiLineResponse;
    }
    
    /**
     * Gets the expected response code for current multi-line response.
     * 
     * @return response code, or -1 if not in multi-line response
     */
    public int getExpectedCode() {
        return expectedCode;
    }
    
    /**
     * Resets parser state. Call this when connection is reset or closed.
     */
    public void reset() {
        lineReader.reset();
        responseLines.clear();
        inMultiLineResponse = false;
        expectedCode = -1;
    }
    
    /**
     * Simple line reader for SMTP client responses.
     * Reads CRLF-terminated lines from incoming byte streams.
     */
    private static class ClientLineReader {
        
        private ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        /**
         * Attempts to read a complete line from input data.
         * 
         * @param input incoming data
         * @return complete line (without CRLF), or null if no complete line available
         */
        public String readLine(ByteBuffer input) {
            // Append new data to our buffer
            while (input.hasRemaining() && buffer.hasRemaining()) {
                buffer.put(input.get());
            }
            
            // Look for CRLF in buffer
            buffer.flip();
            
            int crlfPos = findCRLF(buffer);
            if (crlfPos >= 0) {
                // Extract line (without CRLF)
                byte[] lineBytes = new byte[crlfPos];
                buffer.get(lineBytes);
                
                // Skip CRLF
                buffer.get(); // CR
                buffer.get(); // LF
                
                // Compact buffer for remaining data
                buffer.compact();
                
                return new String(lineBytes);
            } else {
                // No complete line yet, compact and continue
                buffer.compact();
                return null;
            }
        }
        
        /**
         * Finds position of CRLF sequence in buffer.
         * 
         * @param buffer buffer to search
         * @return position of CR in CRLF sequence, or -1 if not found
         */
        private int findCRLF(ByteBuffer buffer) {
            int pos = buffer.position();
            int limit = buffer.limit();
            
            for (int i = pos; i < limit - 1; i++) {
                if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n') {
                    return i - pos;
                }
            }
            
            return -1;
        }
        
        /**
         * Resets line reader state.
         */
        public void reset() {
            buffer.clear();
        }
    }
}
