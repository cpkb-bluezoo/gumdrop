/*
 * ClientNotAuthenticatedState.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Operations available in NOT AUTHENTICATED state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ClientNotAuthenticatedState {

    void capability(CapabilityReplyHandler callback);

    void login(String username, String password, LoginReplyHandler callback);

    void authenticate(String mechanism, byte[] initialResponse, AuthReplyHandler callback);

    /**
     * Authenticates with an OAuth 2.0 access token using SASL XOAUTH2
     * (Google) or OAUTHBEARER (RFC 7628), sending the initial response
     * inline (RFC 4959). If the server answers with an error challenge
     * the client acknowledges it itself, so the callback only sees
     * {@code handleAuthSuccess} or {@code handleAuthFailed}.
     *
     * <p>The connection must already be protected by TLS (implicit TLS
     * on port 993, or after STARTTLS). Obtaining the access token is the
     * application's responsibility; Gmail requires the
     * {@code https://mail.google.com/} scope.
     *
     * @param mechanism {@code "XOAUTH2"} or {@code "OAUTHBEARER"}, or
     *        null to choose from the server's {@code AUTH=} capabilities
     *        (XOAUTH2 preferred); capabilities must have been received
     *        from the greeting or a CAPABILITY command
     * @param account the account identity (email address)
     * @param accessToken the OAuth 2.0 access token
     * @param callback receives the result; fails immediately if no
     *        mechanism can be chosen
     */
    void authenticateWithAccessToken(String mechanism, String account,
                                     String accessToken,
                                     AuthReplyHandler callback);

    void starttls(StarttlsReplyHandler callback);

    void logout();
}
