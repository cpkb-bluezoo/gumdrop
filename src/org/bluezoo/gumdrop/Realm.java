/*
 * Realm.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

/**
 * A realm is a collection of authenticatable principals.
 * These principals have passwords, and may be organised into
 * groups or roles.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Realm {

    /**
     * Verifies that the given password matches the stored credentials for the user.
     * This is the preferred authentication method as it allows realm implementations
     * to use password hashing without exposing plaintext passwords.
     *
     * @param username the username to authenticate
     * @param password the password to verify
     * @return true if the password matches, false if the password is incorrect or user doesn't exist
     */
    boolean passwordMatch(String username, String password);

    /**
     * Computes the H(A1) hash for HTTP Digest Authentication (RFC 2617).
     * H(A1) = MD5(username:realm:password)
     * 
     * This allows realm implementations to either:
     * - Store plaintext passwords and compute H(A1) on demand
     * - Pre-compute and store H(A1) hashes directly (more secure)
     * 
     * @param username the username
     * @param realmName the realm name used in the digest computation
     * @return the H(A1) hash as a lowercase hex string, or null if user doesn't exist
     */
    String getDigestHA1(String username, String realmName);

    /**
     * Returns the password for the given user, or null if the user does not exist.
     * 
     * @deprecated This method exposes plaintext passwords and should be avoided.
     * Use {@link #passwordMatch(String, String)} for simple authentication or
     * {@link #getDigestHA1(String, String)} for HTTP Digest Authentication.
     * 
     * @param username the username
     * @return the plaintext password, or null if the user does not exist
     * @throws UnsupportedOperationException if this realm only supports hashed passwords
     */
    @Deprecated
    String getPassword(String username) throws UnsupportedOperationException;

    /**
     * Indicates whether the specified user is a member of the given role.
     */
    boolean isMember(String username, String role);

}
