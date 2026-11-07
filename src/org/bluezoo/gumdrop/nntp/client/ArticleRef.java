/*
 * ArticleRef.java
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

package org.bluezoo.gumdrop.nntp.client;

/**
 * The reply to STAT (RFC 3977 section 6.2.4): an article's number and
 * message-id.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ArticleRef {

    private final long number;
    private final String messageId;

    ArticleRef(long number, String messageId) {
        this.number = number;
        this.messageId = messageId;
    }

    /**
     * Parses the text of a 223 response.
     *
     * @param text the text after the code
     * @return the reference, or null if it is malformed
     */
    static ArticleRef parse(String text) {
        String[] f = text.trim().split("[ \t]+");
        if (f.length < 2) {
            return null;
        }
        try {
            return new ArticleRef(Long.parseLong(f[0]), f[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the article number (0 if it was identified by message-id).
     *
     * @return the number
     */
    public long getNumber() {
        return number;
    }

    /**
     * Returns the message-id, with angle brackets.
     *
     * @return the message-id
     */
    public String getMessageId() {
        return messageId;
    }

}
