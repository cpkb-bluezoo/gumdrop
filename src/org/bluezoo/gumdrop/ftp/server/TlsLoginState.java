/*
 * TlsLoginState.java
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

/**
 * Operations after AUTH TLS succeeds on the control connection (RFC 4217).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface TlsLoginState {

    /**
     * Continues the login phase after TLS; sends {@code 234} if not already sent.
     *
     * @param handler receives USER / PASS commands on the encrypted channel
     */
    void continueLogin(NotAuthenticatedHandler handler);

    /**
     * Sends {@code 530} when TLS client certificate authentication failed.
     */
    void rejectCertificateLogin();

}
