/*
 * SynchronousRealm.java
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

import java.security.cert.X509Certificate;
import java.util.concurrent.Callable;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Convenience interface for a {@link Realm} whose answers are already in memory, so that
 * no operation has to wait for anything.
 *
 * <p>An implementation supplies plain methods that return their answer
 * ({@link #passwordMatch(String, String)}, {@link #isUserInRole}, ...) and
 * this class adapts them to the asynchronous {@link Realm} contract by
 * calling the {@link RealmCallback} before returning. A runtime exception
 * thrown by a lookup is reported to the callback as a failure.
 *
 * <p>Use it only for lookups that are quick and never block on I/O: a map, a
 * configuration file read at start-up. A lookup that does real work (a
 * network round trip, a password hash with many iterations) should implement
 * {@link Realm} directly and complete from where that work finishes, as
 * {@link BasicRealm}'s loop-bound view does for its password hashes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface SynchronousRealm extends Realm {

    /**
     * Verifies a password. Called by {@link #passwordMatch(String, String,
     * RealmCallback)}.
     *
     * @param username the username to authenticate
     * @param password the password to verify
     * @return true if the password matches
     */
    boolean passwordMatch(String username, String password);

    /**
     * Computes H(A1) = SHA-256(username:realm:password) for HTTP Digest.
     *
     * @param username the username
     * @param realmName the realm name used in the digest computation
     * @return the hash as lowercase hex, or null if the user does not exist
     */
    String getDigestHA1(String username, String realmName);

    /**
     * Indicates whether a user has a role.
     *
     * @param username the username
     * @param role the role name
     * @return true if the user has the role
     */
    boolean isUserInRole(String username, String role);

    /**
     * Checks whether a user exists.
     *
     * @param username the username
     * @return true if the user exists; false by default
     */
    default boolean userExists(String username) {
        return false;
    }

    /**
     * Gets the SCRAM credentials for a user.
     *
     * @param username the username
     * @return the credentials, or null if the user does not exist
     * @throws UnsupportedOperationException if not supported (the default)
     */
    default Realm.ScramCredentials getScramCredentials(String username) {
        throw new UnsupportedOperationException("SCRAM not supported by this realm");
    }

    /**
     * Validates a Bearer token.
     *
     * @param token the token
     * @return the result, or null if bearer tokens are not supported (the default)
     */
    default Realm.TokenValidationResult validateBearerToken(String token) {
        return null;
    }

    /**
     * Validates an OAuth access token.
     *
     * @param accessToken the token
     * @return the result, or null if OAuth tokens are not supported (the default)
     */
    default Realm.TokenValidationResult validateOAuthToken(String accessToken) {
        return null;
    }

    /**
     * Maps a client certificate to a user.
     *
     * @param certificate the client's certificate
     * @return the result, or null if certificate authentication is not
     *         supported (the default)
     */
    default Realm.CertificateAuthenticationResult authenticateCertificate(
            X509Certificate certificate) {
        return null;
    }

    /**
     * Checks proxy authorisation.
     *
     * @param authenticatedUser the authenticated identity
     * @param requestedUser the identity to act as
     * @return true if allowed; by default only when the two are equal
     */
    default boolean authorizeAs(String authenticatedUser, String requestedUser) {
        return authenticatedUser.equals(requestedUser);
    }

    /**
     * Maps a GSS-API principal name to a local username.
     *
     * @param gssName the GSS-API principal name
     * @return the local username; by default the name without its Kerberos
     *         realm
     */
    default String mapKerberosPrincipal(String gssName) {
        if (gssName == null) {
            return null;
        }
        int atIndex = gssName.indexOf('@');
        if (atIndex > 0) {
            return gssName.substring(0, atIndex);
        }
        return gssName;
    }

    /** A realm that needs no loop returns itself. */
    @Override
    default Realm forSelectorLoop(SelectorLoop loop) {
        return this;
    }

    // ── asynchronous contract, completed inline ──

    @Override
    default void passwordMatch(final String username, final String password,
            RealmCallback<Boolean> callback) {
        complete(callback, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(passwordMatch(username, password));
            }
        });
    }

    @Override
    default void getDigestHA1(final String username, final String realmName,
            RealmCallback<String> callback) {
        complete(callback, new Callable<String>() {
            @Override
            public String call() {
                return getDigestHA1(username, realmName);
            }
        });
    }

    @Override
    default void isUserInRole(final String username, final String role,
            RealmCallback<Boolean> callback) {
        complete(callback, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(isUserInRole(username, role));
            }
        });
    }

    @Override
    default void userExists(final String username,
            RealmCallback<Boolean> callback) {
        complete(callback, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(userExists(username));
            }
        });
    }

    @Override
    default void getScramCredentials(final String username,
            RealmCallback<Realm.ScramCredentials> callback) {
        complete(callback, new Callable<Realm.ScramCredentials>() {
            @Override
            public Realm.ScramCredentials call() {
                return getScramCredentials(username);
            }
        });
    }

    @Override
    default void validateBearerToken(final String token,
            RealmCallback<Realm.TokenValidationResult> callback) {
        complete(callback, new Callable<Realm.TokenValidationResult>() {
            @Override
            public Realm.TokenValidationResult call() {
                return validateBearerToken(token);
            }
        });
    }

    @Override
    default void validateOAuthToken(final String accessToken,
            RealmCallback<Realm.TokenValidationResult> callback) {
        complete(callback, new Callable<Realm.TokenValidationResult>() {
            @Override
            public Realm.TokenValidationResult call() {
                return validateOAuthToken(accessToken);
            }
        });
    }

    @Override
    default void authenticateCertificate(final X509Certificate certificate,
            RealmCallback<Realm.CertificateAuthenticationResult> callback) {
        complete(callback, new Callable<Realm.CertificateAuthenticationResult>() {
            @Override
            public Realm.CertificateAuthenticationResult call() {
                return authenticateCertificate(certificate);
            }
        });
    }

    @Override
    default void authorizeAs(final String authenticatedUser,
            final String requestedUser, RealmCallback<Boolean> callback) {
        complete(callback, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(authorizeAs(authenticatedUser, requestedUser));
            }
        });
    }

    @Override
    default void mapKerberosPrincipal(final String gssName,
            RealmCallback<String> callback) {
        complete(callback, new Callable<String>() {
            @Override
            public String call() {
                return mapKerberosPrincipal(gssName);
            }
        });
    }

    /**
     * Runs a lookup and delivers its outcome: the value to
     * {@code completed}, a thrown exception to {@code failed}. A failure of
     * the callback itself is not caught, so that it is not delivered twice.
     */
    private static <T> void complete(RealmCallback<T> callback,
            Callable<T> lookup) {
        T result;
        try {
            result = lookup.call();
        } catch (Exception e) {
            callback.failed(e);
            return;
        }
        callback.completed(result);
    }
}
