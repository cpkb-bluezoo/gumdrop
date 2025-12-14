/*
 * BodyType.java
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
 * SMTP message body type as declared via the BODY parameter.
 * 
 * <p>The BODY parameter is specified in the MAIL FROM command and indicates
 * the type of content encoding used in the message body:
 * 
 * <ul>
 *   <li>{@link #SEVEN_BIT} - Traditional 7-bit ASCII content (default)</li>
 *   <li>{@link #EIGHT_BIT_MIME} - 8-bit MIME content (RFC 6152)</li>
 *   <li>{@link #BINARY_MIME} - Raw binary content (RFC 3030)</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>
 * MAIL FROM:&lt;sender@example.com&gt; BODY=8BITMIME
 * MAIL FROM:&lt;sender@example.com&gt; BODY=BINARYMIME
 * </pre>
 * 
 * <p><b>Important:</b> When BODY=BINARYMIME is specified, the message
 * MUST be transmitted using the BDAT command (CHUNKING extension).
 * The DATA command is not permitted for binary content because
 * dot-stuffing could corrupt binary data.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6152">RFC 6152 - 8BITMIME</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3030">RFC 3030 - BINARYMIME</a>
 */
public enum BodyType {

    /**
     * Traditional 7-bit ASCII content.
     * 
     * <p>This is the default body type when no BODY parameter is specified.
     * All content must be US-ASCII (characters 0-127), with lines no longer
     * than 998 characters.
     */
    SEVEN_BIT("7BIT"),

    /**
     * 8-bit MIME content (RFC 6152).
     * 
     * <p>Allows octets with values 128-255 in the message body. This is
     * commonly used for messages with non-ASCII text or binary attachments
     * that have been encoded with base64 or quoted-printable.
     */
    EIGHT_BIT_MIME("8BITMIME"),

    /**
     * Raw binary content (RFC 3030).
     * 
     * <p>Allows arbitrary binary content without any encoding. When this
     * body type is declared, the message MUST be transmitted using the
     * BDAT command because the DATA command's dot-stuffing could corrupt
     * binary data.
     */
    BINARY_MIME("BINARYMIME");

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private final String keyword;

    BodyType(String keyword) {
        this.keyword = keyword;
    }

    /**
     * Returns the SMTP keyword for this body type.
     * 
     * @return the keyword used in BODY= parameter
     */
    public String getKeyword() {
        return keyword;
    }

    /**
     * Returns true if this body type requires BDAT for transmission.
     * 
     * <p>Only {@link #BINARY_MIME} requires BDAT; the other types
     * can use either DATA or BDAT.
     * 
     * @return true if BDAT is required
     */
    public boolean requiresBdat() {
        return this == BINARY_MIME;
    }

    /**
     * Parses a BODY parameter value.
     * 
     * @param keyword the keyword to parse (case-insensitive)
     * @return the corresponding BodyType value
     * @throws IllegalArgumentException if the keyword is not recognized
     */
    public static BodyType parse(String keyword) {
        if (keyword == null) {
            throw new IllegalArgumentException(L10N.getString("err.null_body_type"));
        }
        switch (keyword.toUpperCase()) {
            case "7BIT":
                return SEVEN_BIT;
            case "8BITMIME":
                return EIGHT_BIT_MIME;
            case "BINARYMIME":
                return BINARY_MIME;
            default:
                throw new IllegalArgumentException(MessageFormat.format(
                        L10N.getString("err.unknown_body_type"), keyword));
        }
    }

    @Override
    public String toString() {
        return keyword;
    }
}

