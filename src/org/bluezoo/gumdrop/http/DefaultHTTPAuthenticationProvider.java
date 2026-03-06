/*
 * DefaultHTTPAuthenticationProvider.java
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

import javax.servlet.http.HttpServletRequest;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;

/**
 * An {@link HTTPAuthenticationProvider} that delegates to a
 * {@link Realm} for credential verification.
 *
 * <p>This bridges the SASL/mail-protocol {@code Realm} abstraction to
 * HTTP authentication, allowing the same realm configuration to be
 * shared across SMTP, IMAP, POP3, FTP, and HTTP services.
 *
 * <p>The authentication method is chosen automatically based on what
 * the realm supports:
 * <ul>
 *   <li>If the realm supports {@link SASLMechanism#DIGEST_MD5},
 *       HTTP Digest is used (strongest password-based).</li>
 *   <li>If the realm supports {@link SASLMechanism#OAUTHBEARER},
 *       Bearer token authentication is used.</li>
 *   <li>Otherwise, HTTP Basic is used as the fallback.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPAuthenticationProvider
 * @see Realm
 */
public class DefaultHTTPAuthenticationProvider
        extends HTTPAuthenticationProvider {

    private final Realm realm;
    private final String realmName;
    private final String authMethod;

    /**
     * Creates a provider backed by the given realm.
     *
     * @param realm the authentication realm
     * @param realmName the realm name to include in HTTP challenges
     */
    public DefaultHTTPAuthenticationProvider(Realm realm,
                                            String realmName) {
        this.realm = realm;
        this.realmName = realmName;
        this.authMethod = detectAuthMethod(realm);
    }

    /**
     * Creates a provider backed by the given realm, using
     * {@code "gumdrop"} as the default realm name.
     *
     * @param realm the authentication realm
     */
    public DefaultHTTPAuthenticationProvider(Realm realm) {
        this(realm, "gumdrop");
    }

    private static String detectAuthMethod(Realm realm) {
        if (realm.getSupportedSASLMechanisms()
                .contains(SASLMechanism.OAUTHBEARER)) {
            return HTTPAuthenticationMethods.BEARER_AUTH;
        }
        if (realm.getSupportedSASLMechanisms()
                .contains(SASLMechanism.DIGEST_MD5)) {
            return HttpServletRequest.DIGEST_AUTH;
        }
        return HttpServletRequest.BASIC_AUTH;
    }

    @Override
    protected String getAuthMethod() {
        return authMethod;
    }

    @Override
    protected String getRealmName() {
        return realmName;
    }

    @Override
    protected boolean passwordMatch(String realm, String username,
                                    String password) {
        return this.realm.passwordMatch(username, password);
    }

    @Override
    protected String getDigestHA1(String realm, String username) {
        return this.realm.getDigestHA1(username, realm);
    }

    @Override
    protected Realm.TokenValidationResult validateBearerToken(
            String token) {
        return realm.validateBearerToken(token);
    }

    @Override
    protected Realm.TokenValidationResult validateOAuthToken(
            String accessToken) {
        return realm.validateOAuthToken(accessToken);
    }

    @Override
    protected boolean supportsDigestAuth() {
        return realm.getSupportedSASLMechanisms()
                .contains(SASLMechanism.DIGEST_MD5);
    }

}
