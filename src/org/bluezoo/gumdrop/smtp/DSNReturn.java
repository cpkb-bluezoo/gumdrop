/*
 * DSNReturn.java
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

package org.bluezoo.gumdrop.smtp;

import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * DSN return type as defined in RFC 3461.
 * 
 * <p>This value is specified in the MAIL FROM command via the RET parameter
 * and indicates how much of the original message should be included in
 * any Delivery Status Notification that is generated:
 * 
 * <ul>
 *   <li>{@link #FULL} - Include the full original message</li>
 *   <li>{@link #HDRS} - Include only the message headers</li>
 * </ul>
 * 
 * <p>Example: {@code MAIL FROM:<sender@example.com> RET=HDRS}
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 - SMTP DSN</a>
 */
public enum DSNReturn {

    /**
     * Include the full original message in the DSN.
     * This may result in large DSN messages for large original messages.
     */
    FULL,

    /**
     * Include only the headers of the original message in the DSN.
     * This is the more bandwidth-efficient option.
     */
    HDRS;

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    /**
     * Parses a DSN return keyword.
     * 
     * @param keyword the keyword to parse (case-insensitive)
     * @return the corresponding DSNReturn value
     * @throws IllegalArgumentException if the keyword is not recognized
     */
    public static DSNReturn parse(String keyword) {
        if (keyword == null) {
            throw new IllegalArgumentException(L10N.getString("err.null_dsn_return"));
        }
        switch (keyword.toUpperCase()) {
            case "FULL":
                return FULL;
            case "HDRS":
                return HDRS;
            default:
                throw new IllegalArgumentException(MessageFormat.format(
                        L10N.getString("err.unknown_dsn_return"), keyword));
        }
    }
}

