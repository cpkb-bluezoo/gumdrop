/*
 * ServletAuthenticationProvider.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.Set;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;

/**
 * HTTP authentication provider for servlet applications.
 * 
 * This class extends HttpAuthenticationProvider to provide authentication
 * services for servlet-based HTTP servers. It delegates to the servlet Context
 * and Realm for credential verification and configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletAuthenticationProvider extends HttpAuthenticationProvider {

    private final Context context;

    /**
     * Creates a new ServletAuthenticationProvider for the given context.
     *
     * @param context the servlet context containing authentication configuration
     */
    public ServletAuthenticationProvider(Context context) {
        this.context = context;
    }

    @Override
    protected String getAuthMethod() {
        return context.getAuthMethod();
    }

    @Override
    protected String getRealmName() {
        return context.getRealmName();
    }

    @Override
    protected void passwordMatch(SelectorLoop loop, String realm, String username,
                                 String password, RealmCallback<Boolean> callback) {
        Realm resolved = context.getRealm(realm);
        if (resolved == null) {
            callback.completed(Boolean.FALSE);
            return;
        }
        resolved.forSelectorLoop(loop).passwordMatch(username, password, callback);
    }

    @Override
    protected void getDigestHA1(SelectorLoop loop, String realm, String username,
                                RealmCallback<String> callback) {
        Realm resolved = context.getRealm(realm);
        if (resolved == null) {
            callback.completed(null);
            return;
        }
        resolved.forSelectorLoop(loop).getDigestHA1(username, realm, callback);
    }

    @Override
    protected boolean supportsDigestAuth() {
        String realmName = getRealmName();
        if (realmName == null) {
            return false;
        }

        Realm realm = context.getRealm(realmName);
        if (realm == null) {
            return false;
        }

        // HTTP Digest requires the same HA1 computation as SASL DIGEST-MD5
        Set<SaslMechanism> supported = realm.getSupportedSASLMechanisms();
        return supported.contains(SaslMechanism.DIGEST_MD5);
    }

    @Override
    protected void validateBearerToken(SelectorLoop loop, String token,
            RealmCallback<Realm.TokenValidationResult> callback) {
        Realm realm = contextRealm();
        if (realm == null) {
            callback.completed(null);
            return;
        }
        realm.forSelectorLoop(loop).validateBearerToken(token, callback);
    }

    @Override
    protected void validateOAuthToken(SelectorLoop loop, String accessToken,
            RealmCallback<Realm.TokenValidationResult> callback) {
        Realm realm = contextRealm();
        if (realm == null) {
            callback.completed(null);
            return;
        }
        realm.forSelectorLoop(loop).validateOAuthToken(accessToken, callback);
    }

    /** The context's configured realm, or null. */
    private Realm contextRealm() {
        String realmName = getRealmName();
        return (realmName == null) ? null : context.getRealm(realmName);
    }
}
