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

import org.bluezoo.gumdrop.http.HTTPAuthenticationProvider;
import org.bluezoo.gumdrop.Realm;

/**
 * HTTP authentication provider for servlet applications.
 * 
 * This class extends HTTPAuthenticationProvider to provide authentication
 * services for servlet-based HTTP servers. It delegates to the servlet Context
 * and Realm for credential verification and configuration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletAuthenticationProvider extends HTTPAuthenticationProvider {

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
    protected boolean passwordMatch(String realm, String username, String password) {
        return context.passwordMatch(realm, username, password);
    }

    @Override
    protected String getDigestHA1(String realm, String username) {
        return context.getDigestHA1(realm, username);
    }

    @Override
    protected Realm.TokenValidationResult validateBearerToken(String token) {
        String realmName = getRealmName();
        if (realmName == null) {
            return null;
        }
        
        Realm realm = context.getRealm(realmName);
        if (realm == null) {
            return null;
        }
        
        return realm.validateBearerToken(token);
    }

    @Override
    protected Realm.TokenValidationResult validateOAuthToken(String accessToken) {
        String realmName = getRealmName();
        if (realmName == null) {
            return null;
        }
        
        Realm realm = context.getRealm(realmName);
        if (realm == null) {
            return null;
        }
        
        return realm.validateOAuthToken(accessToken);
    }
}
