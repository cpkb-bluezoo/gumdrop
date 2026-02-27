/*
 * POP3Response.java
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

package org.bluezoo.gumdrop.pop3.client;

/**
 * Represents a parsed POP3 server response.
 *
 * <p>POP3 responses consist of a status indicator ({@code +OK} or
 * {@code -ERR}) followed by optional text. SASL continuation responses
 * use {@code + } followed by challenge data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class POP3Response {

    enum Status {
        OK,
        ERR,
        CONTINUATION
    }

    private final Status status;
    private final String message;

    POP3Response(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    Status getStatus() {
        return status;
    }

    String getMessage() {
        return message;
    }

    boolean isOk() {
        return status == Status.OK;
    }

    boolean isErr() {
        return status == Status.ERR;
    }

    boolean isContinuation() {
        return status == Status.CONTINUATION;
    }

    /**
     * Parses a POP3 response line.
     *
     * @param line the response line (without CRLF)
     * @return the parsed response, or null if the line is not a valid
     *         POP3 response
     */
    static POP3Response parse(String line) {
        if (line.startsWith("+OK")) {
            String msg = line.length() > 3 ? line.substring(4) : "";
            return new POP3Response(Status.OK, msg);
        } else if (line.startsWith("-ERR")) {
            String msg = line.length() > 4 ? line.substring(5) : "";
            return new POP3Response(Status.ERR, msg);
        } else if (line.startsWith("+ ")) {
            return new POP3Response(Status.CONTINUATION,
                    line.substring(2));
        } else if (line.equals("+")) {
            return new POP3Response(Status.CONTINUATION, "");
        }
        return null;
    }

    @Override
    public String toString() {
        switch (status) {
            case OK:
                return "+OK " + message;
            case ERR:
                return "-ERR " + message;
            case CONTINUATION:
                return "+ " + message;
            default:
                return message;
        }
    }
}
