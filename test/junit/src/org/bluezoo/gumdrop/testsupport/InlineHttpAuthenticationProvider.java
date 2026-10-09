/*
 * InlineHttpAuthenticationProvider.java
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

package org.bluezoo.gumdrop.testsupport;

import static org.junit.Assert.assertNotNull;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;

/**
 * Test base for {@link HttpAuthenticationProvider} subclasses whose
 * credential checks are plain in-memory lookups. A test implements the four
 * synchronous hooks; this class adapts them to the asynchronous provider
 * contract (completing the callback inline) and offers
 * {@link #authenticate(String)} / {@link #authenticate(String, String, String)}
 * that return the result directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class InlineHttpAuthenticationProvider extends HttpAuthenticationProvider {

    protected abstract boolean passwordMatch(String realm, String username, String password);

    protected abstract String getDigestHA1(String realm, String username);

    protected abstract Realm.TokenValidationResult validateBearerToken(String token);

    protected abstract Realm.TokenValidationResult validateOAuthToken(String accessToken);

    @Override
    protected final void passwordMatch(SelectorLoop loop, String realm, String username,
            String password, RealmCallback<Boolean> callback) {
        callback.completed(Boolean.valueOf(passwordMatch(realm, username, password)));
    }

    @Override
    protected final void getDigestHA1(SelectorLoop loop, String realm, String username,
            RealmCallback<String> callback) {
        callback.completed(getDigestHA1(realm, username));
    }

    @Override
    protected final void validateBearerToken(SelectorLoop loop, String token,
            RealmCallback<Realm.TokenValidationResult> callback) {
        callback.completed(validateBearerToken(token));
    }

    @Override
    protected final void validateOAuthToken(SelectorLoop loop, String accessToken,
            RealmCallback<Realm.TokenValidationResult> callback) {
        callback.completed(validateOAuthToken(accessToken));
    }

    /** Authenticates a request for {@code GET /}. */
    public AuthenticationResult authenticate(String authorizationHeader) {
        return authenticate(authorizationHeader, "GET", "/");
    }

    /** Authenticates, requiring the in-memory hooks to have answered inline. */
    public AuthenticationResult authenticate(String authorizationHeader, String method, String uri) {
        final AuthenticationResult[] out = new AuthenticationResult[1];
        authenticate(null, authorizationHeader, method, uri, new AuthenticationCallback() {
            @Override
            public void completed(AuthenticationResult result) {
                out[0] = result;
            }
        });
        assertNotNull("authentication should complete inline", out[0]);
        return out[0];
    }
}
