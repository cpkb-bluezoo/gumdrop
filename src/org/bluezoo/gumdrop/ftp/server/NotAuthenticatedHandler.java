/*
 * NotAuthenticatedHandler.java
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

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.SecurityInfo;

/**
 * Handler for the FTP login phase before authentication completes (RFC 959
 * §4.1.1).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface NotAuthenticatedHandler {

    /**
     * Called when the client sends USER.
     *
     * @param state operations for responding to USER
     * @param username the username argument
     */
    void user(LoginState state, String username);

    /**
     * Called after a successful AUTH TLS upgrade (RFC 4217).
     *
     * @param state operations for continuing login after TLS
     * @param securityInfo TLS session details
     */
    void tlsEstablished(TlsLoginState state, SecurityInfo securityInfo);

}
