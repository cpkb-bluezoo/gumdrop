/*
 * NodeAdapter.java
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Which commands wait on the server on purpose, and for how long, so that a
 * command timeout does not cut short a blocking pop or a blocking stream
 * read.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CommandTimeouts {

    /** A per-command timeout meaning "use the connection's default". */
    static final long DEFAULT = -1;

    /** A per-command timeout meaning "none". */
    static final long NONE = 0;

    private CommandTimeouts() {
    }

    /**
     * How long the server may hold back the reply to a command by design.
     *
     * @return -1 if the command does not block, 0 if it may block forever,
     *         otherwise the longest wait in milliseconds
     */
    static long blockingMillis(String command, String[] text, byte[][] binary) {
        int n = text != null ? text.length : binary != null ? binary.length : 0;
        String name = command.toUpperCase(Locale.ROOT);
        if (name.equals("BLPOP") || name.equals("BRPOP") || name.equals("BZPOPMIN")
                || name.equals("BZPOPMAX") || name.equals("BRPOPLPUSH") || name.equals("BLMOVE")) {
            return seconds(arg(text, binary, n - 1));
        } else if (name.equals("BLMPOP") || name.equals("BZMPOP")) {
            return seconds(arg(text, binary, 0));
        } else if (name.equals("WAIT") || name.equals("WAITAOF")) {
            return millis(arg(text, binary, n - 1));
        } else if (name.equals("XREAD") || name.equals("XREADGROUP")) {
            for (int i = 0; i < n - 1; i++) {
                if ("BLOCK".equalsIgnoreCase(arg(text, binary, i))) {
                    return millis(arg(text, binary, i + 1));
                }
            }
        }
        return -1;
    }

    private static String arg(String[] text, byte[][] binary, int i) {
        if (i < 0) {
            return null;
        }
        if (text != null) {
            return i < text.length ? text[i] : null;
        }
        if (binary != null && i < binary.length) {
            return new String(binary[i], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static long seconds(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Math.round(Double.parseDouble(value.trim()) * 1000.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long millis(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
