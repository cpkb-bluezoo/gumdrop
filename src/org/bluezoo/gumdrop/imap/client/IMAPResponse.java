/*
 * IMAPResponse.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Represents a parsed IMAP server response line.
 *
 * <p>IMAP responses have three forms:
 * <ul>
 *   <li><b>Tagged:</b> {@code tag OK/NO/BAD [response-code] text}</li>
 *   <li><b>Untagged:</b> {@code * response-data}</li>
 *   <li><b>Continuation:</b> {@code + text}</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class IMAPResponse {

    enum Type {
        TAGGED,
        UNTAGGED,
        CONTINUATION
    }

    enum Status {
        OK,
        NO,
        BAD
    }

    private final Type type;
    private final String tag;
    private final Status status;
    private final String responseCode;
    private final String message;

    IMAPResponse(Type type, String tag, Status status,
            String responseCode, String message) {
        this.type = type;
        this.tag = tag;
        this.status = status;
        this.responseCode = responseCode;
        this.message = message;
    }

    Type getType() {
        return type;
    }

    String getTag() {
        return tag;
    }

    Status getStatus() {
        return status;
    }

    String getResponseCode() {
        return responseCode;
    }

    String getMessage() {
        return message;
    }

    boolean isTagged() {
        return type == Type.TAGGED;
    }

    boolean isUntagged() {
        return type == Type.UNTAGGED;
    }

    boolean isContinuation() {
        return type == Type.CONTINUATION;
    }

    boolean isOk() {
        return status == Status.OK;
    }

    boolean isNo() {
        return status == Status.NO;
    }

    boolean isBad() {
        return status == Status.BAD;
    }

    /**
     * Parses an IMAP response line.
     *
     * @param line the response line (without CRLF)
     * @return the parsed response, or null if the line cannot be parsed
     */
    static IMAPResponse parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        if (line.startsWith("+ ")) {
            return new IMAPResponse(Type.CONTINUATION, null, null,
                    null, line.substring(2));
        }
        if (line.equals("+")) {
            return new IMAPResponse(Type.CONTINUATION, null, null,
                    null, "");
        }

        if (line.startsWith("* ")) {
            return parseUntagged(line.substring(2));
        }

        int sp = line.indexOf(' ');
        if (sp > 0) {
            String tag = line.substring(0, sp);
            String rest = line.substring(sp + 1);
            return parseTagged(tag, rest);
        }

        return null;
    }

    private static IMAPResponse parseUntagged(String rest) {
        Status status = parseStatus(rest);
        if (status != null) {
            String afterStatus = rest.substring(
                    status.name().length()).trim();
            String code = parseResponseCode(afterStatus);
            String msg = stripResponseCode(afterStatus);
            return new IMAPResponse(Type.UNTAGGED, null, status,
                    code, msg);
        }
        return new IMAPResponse(Type.UNTAGGED, null, null,
                null, rest);
    }

    private static IMAPResponse parseTagged(String tag, String rest) {
        Status status = parseStatus(rest);
        if (status != null) {
            String afterStatus = rest.substring(
                    status.name().length()).trim();
            String code = parseResponseCode(afterStatus);
            String msg = stripResponseCode(afterStatus);
            return new IMAPResponse(Type.TAGGED, tag, status,
                    code, msg);
        }
        return new IMAPResponse(Type.TAGGED, tag, null, null, rest);
    }

    private static Status parseStatus(String text) {
        if (text.startsWith("OK") &&
                (text.length() == 2 || text.charAt(2) == ' ')) {
            return Status.OK;
        }
        if (text.startsWith("NO") &&
                (text.length() == 2 || text.charAt(2) == ' ')) {
            return Status.NO;
        }
        if (text.startsWith("BAD") &&
                (text.length() == 3 || text.charAt(3) == ' ')) {
            return Status.BAD;
        }
        return null;
    }

    private static String parseResponseCode(String text) {
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end > 0) {
                return text.substring(1, end);
            }
        }
        return null;
    }

    private static String stripResponseCode(String text) {
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end > 0) {
                String after = text.substring(end + 1).trim();
                return after;
            }
        }
        return text;
    }

    /**
     * Extracts a literal size marker from a response line.
     * Looks for a trailing {@code {size}} pattern.
     *
     * @param text the text to search
     * @return the literal size, or -1 if no literal marker
     */
    static long parseLiteralSize(String text) {
        if (text == null) {
            return -1;
        }
        int open = text.lastIndexOf('{');
        if (open >= 0 && text.endsWith("}")) {
            try {
                return Long.parseLong(
                        text.substring(open + 1, text.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case TAGGED:
                sb.append(tag).append(' ');
                if (status != null) {
                    sb.append(status.name());
                }
                break;
            case UNTAGGED:
                sb.append("* ");
                if (status != null) {
                    sb.append(status.name());
                }
                break;
            case CONTINUATION:
                sb.append("+");
                break;
        }
        if (responseCode != null) {
            sb.append(" [").append(responseCode).append(']');
        }
        if (message != null && !message.isEmpty()) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(message);
        }
        return sb.toString();
    }
}
