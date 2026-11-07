/*
 * NntpException.java
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

import java.io.IOException;

/**
 * A command failed. If the server refused it, {@link #getCode()} is the
 * response code; if the connection failed, it is 0.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int code;

    /**
     * Creates an exception.
     *
     * @param code the response code, or 0 for a connection failure
     * @param message the reason
     */
    public NntpException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the response code.
     *
     * @return the code, or 0 if the connection failed
     */
    public int getCode() {
        return code;
    }

}
