/*
 * NntpStatus.java
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
 * A response status line (RFC 3977 section 3.2): a three digit code and
 * text.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NntpStatus {

    private final int code;
    private final String text;

    NntpStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * Returns the response code.
     *
     * @return the code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the text after the code.
     *
     * @return the text, possibly empty
     */
    public String getText() {
        return text;
    }

    /**
     * Returns whether the code is a success (1xx, 2xx or 3xx).
     *
     * @return true for success
     */
    public boolean isSuccess() {
        return code >= 100 && code < 400;
    }

    @Override
    public String toString() {
        return code + " " + text;
    }

}
