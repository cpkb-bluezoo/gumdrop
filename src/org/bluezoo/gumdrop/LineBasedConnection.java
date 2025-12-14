/*
 * LineBasedConnection.java
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

package org.bluezoo.gumdrop;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;

/**
 * Abstract base class for line-based TCP protocol handlers.
 *
 * <p>This class provides a push-parser mechanism for protocols that operate
 * on CRLF-terminated lines, such as HTTP/1.x headers, SMTP commands, POP3
 * commands, and IMAP commands. It extends {@link Connection} and provides
 * the {@link #receiveLine(ByteBuffer)} method for parsing complete lines
 * from incoming data.
 *
 * <h3>Buffer Management Contract</h3>
 * <p>The {@link #receiveLine(ByteBuffer)} method operates on standard
 * {@link ByteBuffer} with a non-blocking stream contract:
 * <ul>
 *   <li>The caller provides a buffer in read mode (ready for get operations)</li>
 *   <li>The parser consumes as many complete CRLF-terminated lines as possible</li>
 *   <li>For each complete line, {@link #lineReceived(ByteBuffer)} is called
 *       with a buffer slice containing the line including its CRLF terminator</li>
 *   <li>After {@link #receiveLine(ByteBuffer)} returns, the buffer's position
 *       indicates where unconsumed data begins</li>
 *   <li>If there is unconsumed data (partial line), the caller MUST call
 *       {@link ByteBuffer#compact()} before reading more data into the buffer</li>
 * </ul>
 *
 * <h3>State Transitions</h3>
 * <p>Some protocols (SMTP, IMAP) may transition from line-based command mode
 * to a different mode (e.g., receiving message data) after processing a command.
 * Subclasses can override {@link #continueLineProcessing()} to return false
 * when the protocol transitions out of line-based mode. This causes
 * {@code receiveLine()} to stop processing and return, leaving unconsumed
 * data in the buffer for the caller to handle appropriately.
 *
 * <h3>Typical Usage Pattern</h3>
 * <p>Subclasses override {@link #receive(ByteBuffer)} to check state and
 * delegate to the appropriate handler:
 * <pre>
 * public void receive(ByteBuffer data) {
 *     if (receivingMessageData()) {
 *         // Handle non-line data (e.g., message body)
 *         receiveMessageData(data);
 *     } else {
 *         // Line-based command mode
 *         receiveLine(data);
 *         // Check if state changed during line processing
 *         if (receivingMessageData() &amp;&amp; data.hasRemaining()) {
 *             receiveMessageData(data);
 *         }
 *     }
 * }
 *
 * protected void lineReceived(ByteBuffer line) {
 *     // Process the complete line including CRLF
 *     processCommand(line);
 * }
 *
 * protected boolean continueLineProcessing() {
 *     // Return false when transitioning to message data mode
 *     return !receivingMessageData();
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Connection
 */
public abstract class LineBasedConnection extends Connection {

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    /**
     * Constructor for an unencrypted line-based connection.
     */
    protected LineBasedConnection() {
        super();
    }

    /**
     * Constructor for a line-based connection with optional SSL.
     *
     * @param engine the SSL engine, or null for plaintext only
     * @param secure true if SSL should be active immediately
     */
    protected LineBasedConnection(SSLEngine engine, boolean secure) {
        super(engine, secure);
    }

    /**
     * Parses CRLF-terminated lines from the buffer and calls
     * {@link #lineReceived(ByteBuffer)} for each complete line.
     *
     * <p>This method processes as many complete lines as possible from the
     * input buffer. For each line ending with CRLF, the buffer is temporarily
     * windowed to contain just that line (including the CRLF terminator) and
     * passed to {@link #lineReceived(ByteBuffer)}.
     *
     * <p>After processing, the buffer's position is set to the start of any
     * unconsumed data (a partial line without CRLF terminator). The caller
     * should call {@link ByteBuffer#compact()} before reading more data if
     * there is unconsumed data remaining.
     *
     * @param data the byte data in read mode (position at data start, limit at data end)
     */
    protected void receiveLine(ByteBuffer data) {
        int start = data.position();
        int end = data.limit();
        int pos = start;
        byte last = 0;

        while (pos < end) {
            byte c = data.get(pos++);

            if (c == LF && last == CR) {
                // Found CRLF - process this line
                // Set buffer window to line including CRLF: position(start), limit(pos)
                data.limit(pos);
                data.position(start);

                lineReceived(data);

                // Restore limit and move to next line
                data.limit(end);
                start = pos;

                // Check if subclass wants to stop processing lines
                // (e.g., state transition to message data mode)
                if (!continueLineProcessing()) {
                    break;
                }
            }

            last = c;
        }

        // Position buffer at start of unconsumed data (partial line or remaining data)
        data.position(start);
    }

    /**
     * Called after each line is processed to determine if line-based
     * processing should continue.
     *
     * <p>Subclasses can override this method to return false when the
     * protocol transitions to a non-line-based mode (e.g., receiving
     * message data in SMTP DATA or IMAP APPEND). When this method returns
     * false, {@link #receiveLine(ByteBuffer)} stops processing and returns,
     * leaving any remaining data unconsumed in the buffer.
     *
     * <p>The default implementation returns true, meaning all complete
     * lines in the buffer will be processed.
     *
     * @return true to continue processing lines, false to stop
     */
    protected boolean continueLineProcessing() {
        return true;
    }

    /**
     * Called when a complete CRLF-terminated line has been received.
     *
     * <p>The buffer contains exactly one line including its CRLF terminator,
     * with position at the start of the line and limit immediately after the
     * terminating LF.
     *
     * <p>Implementations should consume the buffer contents as needed. The
     * buffer state after this method returns is ignored; the caller manages
     * buffer positioning.
     *
     * @param line a buffer containing the complete line including CRLF terminator
     */
    protected abstract void lineReceived(ByteBuffer line);
}

