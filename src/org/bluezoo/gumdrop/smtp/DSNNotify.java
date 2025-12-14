/*
 * DSNNotify.java
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
 * DSN notification types as defined in RFC 3461.
 * 
 * <p>These values indicate when the sender wants to receive
 * Delivery Status Notifications for a recipient:
 * 
 * <ul>
 *   <li>{@link #NEVER} - Never send DSN (mutually exclusive with others)</li>
 *   <li>{@link #SUCCESS} - Send DSN on successful delivery</li>
 *   <li>{@link #FAILURE} - Send DSN on delivery failure</li>
 *   <li>{@link #DELAY} - Send DSN if delivery is delayed</li>
 * </ul>
 * 
 * <p>Multiple values (except NEVER) can be combined, e.g., 
 * {@code NOTIFY=SUCCESS,FAILURE}.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 - SMTP DSN</a>
 */
public enum DSNNotify {

    /**
     * Never send a DSN for this recipient.
     * This is mutually exclusive with all other values.
     */
    NEVER,

    /**
     * Send a DSN when the message is successfully delivered.
     */
    SUCCESS,

    /**
     * Send a DSN when delivery fails permanently.
     */
    FAILURE,

    /**
     * Send a DSN when delivery is delayed (temporary failure).
     */
    DELAY;

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    /**
     * Parses a DSN notify keyword.
     * 
     * @param keyword the keyword to parse (case-insensitive)
     * @return the corresponding DSNNotify value
     * @throws IllegalArgumentException if the keyword is not recognized
     */
    public static DSNNotify parse(String keyword) {
        if (keyword == null) {
            throw new IllegalArgumentException(L10N.getString("err.null_dsn_notify"));
        }
        switch (keyword.toUpperCase()) {
            case "NEVER":
                return NEVER;
            case "SUCCESS":
                return SUCCESS;
            case "FAILURE":
                return FAILURE;
            case "DELAY":
                return DELAY;
            default:
                throw new IllegalArgumentException(MessageFormat.format(
                        L10N.getString("err.unknown_dsn_notify"), keyword));
        }
    }
}

