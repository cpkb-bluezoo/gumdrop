/*
 * HTTPPrincipal.java
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

package org.bluezoo.gumdrop.http;

import java.security.Principal;

/**
 * A {@link Principal} representing an authenticated HTTP user.
 *
 * <p>Created by the HTTP authentication pipeline when a request's
 * {@code Authorization} header is successfully verified against a
 * {@link HTTPAuthenticationProvider}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPPrincipal implements Principal {

    private final String username;

    /**
     * Creates a new HTTP principal.
     *
     * @param username the authenticated username
     */
    public HTTPPrincipal(String username) {
        this.username = username;
    }

    @Override
    public String getName() {
        return username;
    }

    @Override
    public int hashCode() {
        return username.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof HTTPPrincipal) {
            return username.equals(((HTTPPrincipal) other).username);
        }
        return false;
    }

    @Override
    public String toString() {
        return username;
    }

}
