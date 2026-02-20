/*
 * LineParser.java
 * Copyright (C) 2026 Chris Burdess
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

/**
 * Standalone push-parser for CRLF-terminated line protocols.
 *
 * <p>This utility class provides composable line-parsing logic that any
 * {@link ProtocolHandler} can use without requiring a specific base class.
 *
 * <h3>Usage</h3>
 * <pre>
 * public class SMTPHandler implements ProtocolHandler, LineParser.Callback {
 *
 *     public void receive(ByteBuffer data) {
 *         if (receivingMessageData) {
 *             receiveMessageData(data);
 *         } else {
 *             LineParser.parse(data, this);
 *             if (receivingMessageData &amp;&amp; data.hasRemaining()) {
 *                 receiveMessageData(data);
 *             }
 *         }
 *     }
 *
 *     public void lineReceived(ByteBuffer line) {
 *         // Process the complete CRLF-terminated line
 *     }
 *
 *     public boolean continueLineProcessing() {
 *         return !receivingMessageData;
 *     }
 * }
 * </pre>
 *
 * <h3>Buffer Contract</h3>
 * <p>The input buffer must be in read mode (position at data start, limit
 * at data end). After {@link #parse(ByteBuffer, Callback)} returns, the
 * buffer's position indicates where unconsumed data begins. The caller
 * is responsible for compacting the buffer before reading more data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 */
public final class LineParser {

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    /**
     * Callback interface for receiving parsed lines.
     */
    public interface Callback {

        /**
         * Called when a complete CRLF-terminated line has been parsed.
         *
         * <p>The buffer contains exactly one line including its CRLF
         * terminator, with position at the start of the line and limit
         * immediately after the terminating LF.
         *
         * @param line a buffer containing the complete line including CRLF
         */
        void lineReceived(ByteBuffer line);

        /**
         * Called after each line to determine whether parsing should continue.
         *
         * <p>Return false when the protocol transitions out of line-based
         * mode (e.g., SMTP DATA, IMAP APPEND). When this returns false,
         * {@link LineParser#parse(ByteBuffer, Callback)} stops and returns,
         * leaving remaining data unconsumed in the buffer.
         *
         * @return true to continue parsing lines, false to stop
         */
        boolean continueLineProcessing();
    }

    private LineParser() {
    }

    /**
     * Parses CRLF-terminated lines from the buffer.
     *
     * <p>For each complete line found, calls
     * {@link Callback#lineReceived(ByteBuffer)} with a windowed view of
     * the buffer containing that line (including the CRLF terminator).
     *
     * <p>Parsing stops when:
     * <ul>
     * <li>No more complete lines are available (partial line at end)</li>
     * <li>{@link Callback#continueLineProcessing()} returns false</li>
     * </ul>
     *
     * <p>After this method returns, the buffer's position is at the start
     * of unconsumed data (partial line or remaining data after stop).
     *
     * @param data the byte data in read mode
     * @param callback the callback to receive parsed lines
     */
    public static void parse(ByteBuffer data, Callback callback) {
        int start = data.position();
        int end = data.limit();
        int pos = start;
        byte last = 0;

        while (pos < end) {
            byte c = data.get(pos++);

            if (c == LF && last == CR) {
                data.limit(pos);
                data.position(start);

                callback.lineReceived(data);

                data.limit(end);
                start = pos;

                if (!callback.continueLineProcessing()) {
                    break;
                }
            }

            last = c;
        }

        data.position(start);
    }
}
