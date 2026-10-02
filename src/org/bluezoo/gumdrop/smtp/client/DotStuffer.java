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
 * Content is streamed straight through to the output as each chunk arrives;
 * the only thing remembered between chunks is where the previous chunk left
 * off relative to the line structure, so input may be split at arbitrary
 * byte boundaries without changing the output.
 * 
 * <p>The state machine tracks:
 * <ul>
 * <li>NORMAL - in the middle of a line</li>
 * <li>SAW_CR - the last byte written was a carriage return</li>
 * <li>SAW_CRLF - at the start of a line (initially, or after CRLF); a dot
 * here must be doubled</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321#section-4.5.2">RFC 5321 §4.5.2</a>
 */
class DotStuffer {
    
    /**
     * Internal state for dot stuffing state machine.
     */
    private enum State {
        /** In the middle of a line. */
        NORMAL,
        
        /** The previous byte was CR. */
        SAW_CR,
        
        /** At the start of a line. */
        SAW_CRLF
    }
    
    private State state = State.SAW_CRLF;
    
    // Terminator pieces: ".\r\n" is used when the content already ended a line
    private final ByteBuffer terminator = ByteBuffer.allocate(5);
    private final ByteBuffer dot = ByteBuffer.allocate(1);
    
    /**
     * Creates dot stuffer.
     */
    public DotStuffer() {
        terminator.put((byte) '\r');  // 0
        terminator.put((byte) '\n');  // 1
        terminator.put((byte) '.');   // 2
        terminator.put((byte) '\r');  // 3
        terminator.put((byte) '\n');  // 4
        dot.put((byte) '.');
    }
    
    /**
     * Processes a chunk of message content, performing dot stuffing as needed.
     * The chunk is consumed entirely and written to the output without copying.
     * 
     * @param input message content chunk
     * @param output channel to write processed content to
     * @throws IOException if writing to channel fails
     */
    public void processChunk(ByteBuffer input, WritableByteChannel output) throws IOException {
        int segmentStart = input.position();
        int end = input.limit();
        for (int pos = segmentStart; pos < end; pos++) {
            byte b = input.get(pos);
            switch (state) {
                case NORMAL:
                    if (b == '\r') {
                        state = State.SAW_CR;
                    }
                    break;
                    
                case SAW_CR:
                    if (b == '\n') {
                        state = State.SAW_CRLF;
                    } else if (b != '\r') {
                        state = State.NORMAL;
                    }
                    break;
                    
                case SAW_CRLF:
                    if (b == '.') {
                        // Line starts with a dot: emit everything before it,
                        // then an extra dot; the dot itself starts the next segment
                        input.position(segmentStart);
                        input.limit(pos);
                        output.write(input);
                        input.limit(end);
                        dot.clear();
                        output.write(dot);
                        segmentStart = pos;
                        state = State.NORMAL;
                    } else if (b == '\r') {
                        state = State.SAW_CR;
                    } else {
                        state = State.NORMAL;
                    }
                    break;
            }
        }
        input.position(segmentStart);
        input.limit(end);
        if (input.hasRemaining()) {
            output.write(input);
        }
        input.position(end);
    }
    
    /**
     * Completes message transmission by sending the terminating sequence.
     * If the content already ended a line only ".CRLF" is sent, otherwise
     * "CRLF.CRLF". Resets state for the next message.
     * 
     * @param output channel to write end sequence to
     * @throws IOException if writing to channel fails
     */
    public void endMessage(WritableByteChannel output) throws IOException {
        boolean atLineStart = (state == State.SAW_CRLF);
        terminator.limit(5);
        terminator.position(atLineStart ? 2 : 0);
        output.write(terminator);
        reset();
    }
    
    /**
     * Retained for callers that want to make sure all processed content has
     * been emitted before closing the connection. Content is written as it
     * is processed, so nothing is ever pending and this does nothing.
     * 
     * @param output channel (unused)
     * @throws IOException never thrown
     */
    public void flush(WritableByteChannel output) throws IOException {
        // Nothing is buffered between chunks.
    }
    
    /**
     * Resets dot stuffer state for processing a new message.
     */
    public void reset() {
        state = State.SAW_CRLF;
    }
}
