/*
 * ServletPrincipal.java
 * Copyright (C) 2005 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.security.Principal;

/**
 * An authenticated user associated with a request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletPrincipal implements Principal {

    /**
     * The context that manages this principal.
     */
    final Context context;

    /**
     * The realm of this principal.
     */
    final String realm;

    /**
     * The name of this principal.
     */
    final String username;

    ServletPrincipal(Context context, String realm, String username) {
        this.context = context;
        this.realm = realm;
        this.username = username;
    }

    public String getName() {
        return username;
    }

    public String getRealm() {
        return realm;
    }

    boolean hasRole(String roleName) {
        if (username.equals(roleName)) {
            return true;
        }
        return context.isUserInRole(realm, username, roleName);
    }

    public int hashCode() {
        return username.hashCode();
    }

    public boolean equals(Object other) {
        if (other instanceof ServletPrincipal) {
            ServletPrincipal p = (ServletPrincipal) other;
            return (p.username.equals(username));
        }
        return false;
    }

    public String toString() {
        return username;
    }

}
