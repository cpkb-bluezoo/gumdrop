/*
 * NntpGreeting.java
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

import java.util.List;

/**
 * What the server said when the session became usable.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NntpGreeting {

    private final NntpStatus status;
    private final List<String> capabilities;
    private final boolean secure;
    private final boolean authenticated;

    NntpGreeting(NntpStatus status, List<String> capabilities, boolean secure,
                 boolean authenticated) {
        this.status = status;
        this.capabilities = capabilities;
        this.secure = secure;
        this.authenticated = authenticated;
    }

    /**
     * Returns the greeting line.
     *
     * @return 200 or 201 and its text
     */
    public NntpStatus getStatus() {
        return status;
    }

    /**
     * Returns whether the server allows posting (it greeted with 200).
     *
     * @return true if posting is allowed
     */
    public boolean isPostingAllowed() {
        return status.getCode() == 200;
    }

    /**
     * Returns the CAPABILITIES lines, upper case, as they stood after TLS
     * was set up and before authentication.
     *
     * @return the capability lines
     */
    public List<String> getCapabilities() {
        return capabilities;
    }

    /**
     * Returns whether the connection is encrypted.
     *
     * @return true if TLS is in use
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Returns whether the client has logged in.
     *
     * @return true if AUTHINFO succeeded
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

}
