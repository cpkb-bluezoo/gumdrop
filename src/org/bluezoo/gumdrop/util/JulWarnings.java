/*
 * JulWarnings.java
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

package org.bluezoo.gumdrop.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * java.util.logging helpers that keep WARNING/SEVERE lines readable in
 * production without printing a full stack trace for expected client or
 * protocol errors (stack detail remains at FINE when a cause is present).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class JulWarnings {

    private JulWarnings() {
    }

    public static void warn(Logger logger, String message, Throwable cause) {
        log(logger, Level.WARNING, message, cause);
    }

    public static void severe(Logger logger, String message, Throwable cause) {
        log(logger, Level.SEVERE, message, cause);
    }

    private static void log(Logger logger, Level level, String message,
            Throwable cause) {
        if (!logger.isLoggable(level)) {
            return;
        }
        if (cause != null && logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, message, cause);
        }
        if (cause != null) {
            logger.log(level, message + ": " + cause.getMessage());
        } else {
            logger.log(level, message);
        }
    }
}
