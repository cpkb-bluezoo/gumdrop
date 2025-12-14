/*
 * DotStuffer.java
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
import java.nio.channels.WritableByteChannel;
import java.io.IOException;

/**
 * Handles SMTP dot stuffing across message content chunks.
 * 
 * <p>Per RFC 5321, lines beginning with a dot must have an additional dot prepended.
 * This class maintains minimal state across chunk boundaries to properly handle dots that
 * appear at the start of lines when data is streamed in arbitrary chunks.
 * 
 * <p>The state machine tracks:
 * <ul>
 * <li>NORMAL - processing normal content</li>
 * <li>SAW_CR - saw carriage return, buffered waiting for LF</li>
 * <li>SAW_CRLF - saw CRLF sequence, buffered waiting to check for dot</li>
 * </ul>
 * 
 * <p>Only buffers bytes when chunk boundaries split CRLF sequences (max 2 bytes).
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class DotStuffer {
    
    /**
     * Internal state for dot stuffing state machine.
     */
    private enum State {
        /** Processing normal content - write bytes immediately. */
        NORMAL,
        
        /** Saw CR, buffered waiting to see if LF follows. */
        SAW_CR,
        
        /** Saw CRLF, buffered waiting to see if dot follows. */
        SAW_CRLF
    }
    
    private State state = State.NORMAL;
    
    // Pre-allocated buffer for boundary cases: contains CRLF.CRLF
    private final ByteBuffer boundaryBuffer = ByteBuffer.allocate(5);
    
    /**
     * Creates dot stuffer.
     */
    public DotStuffer() {
        // Pre-populate boundary buffer with CRLF.CRLF
        boundaryBuffer.put((byte) '\r');  // 0
        boundaryBuffer.put((byte) '\n');  // 1  
        boundaryBuffer.put((byte) '.');   // 2
        boundaryBuffer.put((byte) '\r');  // 3
        boundaryBuffer.put((byte) '\n');  // 4
    }
    
    /**
     * Processes a chunk of message content, performing dot stuffing as needed.
     * Uses efficient buffer manipulation to minimize allocations.
     * 
     * @param input message content chunk
     * @param output channel to write processed content to
     * @throws IOException if writing to channel fails
     */
    public void processChunk(ByteBuffer input, WritableByteChannel output) throws IOException {
        int startPos = input.position();
        int currentPos;
        int saveLimit;
        
        while (input.hasRemaining()) {
            byte b = input.get();
            
            switch (state) {
                case NORMAL:
                    if (b == '\r') {
                        state = State.SAW_CR;
                    } else {
                        // Continue processing normally
                    }
                    break;
                    
                case SAW_CR:
                    if (b == '\n') {
                        state = State.SAW_CRLF;
                    } else {
                        // CR not followed by LF - need to emit pending CR
                        // Write everything processed so far including the CR
                        currentPos = input.position();
                        input.position(startPos);
                        saveLimit = input.limit();
                        input.limit(currentPos);
                        output.write(input);
                        input.limit(saveLimit);
                        
                        // Reset for next chunk starting with current byte
                        startPos = currentPos - 1;
                        input.position(startPos);
                        state = (b == '\r') ? State.SAW_CR : State.NORMAL;
                    }
                    break;
                    
                case SAW_CRLF:
                    // Common setup for both branches
                    currentPos = input.position();
                    input.position(startPos);
                    saveLimit = input.limit();
                    input.limit(currentPos);
                    output.write(input);
                    
                    if (b == '.') {
                        // Found CRLF. - need dot stuffing
                        // Reposition to the dot and write it again (dot stuffing)
                        input.position(currentPos - 1);
                        input.limit(currentPos);
                        output.write(input);
                        input.limit(saveLimit);
                        
                        // Reset for next chunk
                        startPos = currentPos;
                        state = State.NORMAL;
                    } else {
                        // CRLF not followed by dot - write normally
                        input.limit(saveLimit);
                        
                        // Reset for next chunk starting with current byte
                        startPos = currentPos - 1;
                        input.position(startPos);
                        state = (b == '\r') ? State.SAW_CR : State.NORMAL;
                    }
                    break;
            }
        }
        
        // Write any remaining complete data  
        if (input.position() > startPos) {
            input.position(startPos);
            output.write(input);
        }
    }
    
    /**
     * Completes message transmission by sending final CRLF.CRLF sequence.
     * Emits any pending bytes and resets state for next message.
     * 
     * @param output channel to write end sequence to
     * @throws IOException if writing to channel fails
     */
    public void endMessage(WritableByteChannel output) throws IOException {
        // Check if we already have CRLF pending before flushing
        boolean hasCRLF = (state == State.SAW_CRLF);
        
        // Emit any pending bytes first  
        flush(output);
        
        // Send terminating sequence in single write
        if (hasCRLF) {
            // Already wrote CRLF, just need .\r\n
            boundaryBuffer.position(2);
            boundaryBuffer.limit(5);
        } else {
            // Need complete \r\n.\r\n sequence  
            boundaryBuffer.position(0);
            boundaryBuffer.limit(5);
        }
        output.write(boundaryBuffer);
        
        // Reset state for next message
        reset();
    }
    
    /**
     * Emits any pending bytes without ending message.
     * Use this to ensure all processed content is sent before closing connection.
     * 
     * @param output channel to write pending bytes to
     * @throws IOException if writing to channel fails
     */
    public void flush(WritableByteChannel output) throws IOException {
        switch (state) {
            case SAW_CR:
                // Write just the CR: position 0, limit 1
                boundaryBuffer.position(0);
                boundaryBuffer.limit(1);
                output.write(boundaryBuffer);
                break;
            case SAW_CRLF:
                // Write CR and LF: position 0, limit 2  
                boundaryBuffer.position(0);
                boundaryBuffer.limit(2);
                output.write(boundaryBuffer);
                break;
            case NORMAL:
                // No pending bytes
                break;
        }
        state = State.NORMAL;
    }
    
    /**
     * Resets dot stuffer state for processing a new message.
     */
    public void reset() {
        state = State.NORMAL;
    }
}
