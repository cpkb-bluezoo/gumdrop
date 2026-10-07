/*
 * HqRequestParser.java
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

package org.bluezoo.gumdrop.quic.interop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Push parser for the HTTP/0.9 request the quic-interop-runner's
 * {@code hq-interop} protocol sends on each bidirectional stream:
 * {@code GET <path>\r\n}, optionally followed by nothing but the stream's
 * FIN. Bytes are fed as they arrive, in any chunking; the request target
 * is reported as soon as the line terminator (or the FIN, for peers that
 * omit the terminator) has been seen. Anything after the first line is
 * ignored, since HTTP/0.9 requests have no body.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HqRequestParser {

    /**
     * Receives the parse result.
     */
    interface Listener {

        /** The request line was complete and well formed. */
        void requestTarget(String target);

        /** The request line could not be understood. */
        void malformed(String reason);

    }

    /** Longest request line accepted; the runner's 0-RTT case uses 250-byte names. */
    static final int MAX_LINE_LENGTH = 4096;

    private final Listener listener;
    private final byte[] line = new byte[MAX_LINE_LENGTH];
    private int length;
    private boolean done;

    HqRequestParser(Listener listener) {
        this.listener = listener;
    }

    /**
     * Feeds the next chunk. Consumes the whole buffer: bytes beyond the
     * request line have no meaning in HTTP/0.9.
     */
    void receive(ByteBuffer data) {
        while (!done && data.hasRemaining()) {
            byte b = data.get();
            if (b == '\n') {
                done = true;
                complete();
            } else if (length == MAX_LINE_LENGTH) {
                done = true;
                listener.malformed("request line longer than " + MAX_LINE_LENGTH + " bytes");
            } else {
                line[length++] = b;
            }
        }
        // Whatever follows the request line has no meaning in HTTP/0.9.
        data.position(data.limit());
    }

    /**
     * The peer finished its side of the stream. A request line that was
     * never terminated is completed from what arrived.
     */
    void readFinished() {
        if (done) {
            return;
        }
        done = true;
        if (length == 0) {
            listener.malformed("empty request");
        } else {
            complete();
        }
    }

    boolean isDone() {
        return done;
    }

    private void complete() {
        int end = length;
        if (end > 0 && line[end - 1] == '\r') {
            end--;
        }
        String text = new String(line, 0, end, StandardCharsets.US_ASCII);
        // Tolerate the optional "HTTP/0.9" some clients append: the target
        // is the second whitespace-separated token either way.
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length < 2 || !"GET".equals(tokens[0]) || tokens[1].length() == 0) {
            listener.malformed("not an HTTP/0.9 GET: " + text);
            return;
        }
        listener.requestTarget(tokens[1]);
    }

}
