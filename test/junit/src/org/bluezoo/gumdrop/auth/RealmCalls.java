/*
 * RealmCalls.java
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
package org.bluezoo.gumdrop.auth;

/**
 * Synchronous-looking views of {@link Realm} calls for tests whose realm
 * completes inline (an in-memory or scripted realm) or on another thread.
 * Each helper waits for the callback; a failed lookup is reported the way a caller
 * that fails closed would treat it: {@code false}, {@code null} or an invalid
 * result.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RealmCalls {

    private RealmCalls() {
    }

    private static <T> T result(CapturedCallback<T> cb) {
        try {
            if (!cb.awaitDone(30000)) {
                throw new AssertionError("realm call did not complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
        return cb.value();
    }

    public static boolean passwordMatch(Realm r, String user, String password) {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        r.passwordMatch(user, password, cb);
        return Boolean.TRUE.equals(result(cb));
    }

    public static boolean userExists(Realm r, String user) {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        r.userExists(user, cb);
        return Boolean.TRUE.equals(result(cb));
    }

    public static boolean isUserInRole(Realm r, String user, String role) {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        r.isUserInRole(user, role, cb);
        return Boolean.TRUE.equals(result(cb));
    }

    public static String getDigestHA1(Realm r, String user, String realmName) {
        CapturedCallback<String> cb = new CapturedCallback<String>();
        r.getDigestHA1(user, realmName, cb);
        return result(cb);
    }

    public static Realm.TokenValidationResult validateOAuthToken(Realm r, String token) {
        CapturedCallback<Realm.TokenValidationResult> cb =
                new CapturedCallback<Realm.TokenValidationResult>();
        r.validateOAuthToken(token, cb);
        Realm.TokenValidationResult v = result(cb);
        return (v == null && cb.failure() != null) ? Realm.TokenValidationResult.failure() : v;
    }

    public static Realm.TokenValidationResult validateBearerToken(Realm r, String token) {
        CapturedCallback<Realm.TokenValidationResult> cb =
                new CapturedCallback<Realm.TokenValidationResult>();
        r.validateBearerToken(token, cb);
        Realm.TokenValidationResult v = result(cb);
        return (v == null && cb.failure() != null) ? Realm.TokenValidationResult.failure() : v;
    }
}
