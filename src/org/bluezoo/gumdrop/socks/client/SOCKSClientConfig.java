/*
 * SOCKSClientConfig.java
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

package org.bluezoo.gumdrop.socks.client;

/**
 * Configuration for a SOCKS client connection.
 *
 * <p>Holds the SOCKS protocol version preference, optional
 * authentication credentials, and timeout settings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SOCKSClientHandler
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>
 */
public final class SOCKSClientConfig {

    /**
     * SOCKS version preference for the client.
     */
    public enum Version {
        /** Auto-detect: prefer SOCKS5, fall back to SOCKS4. */
        AUTO,
        /** SOCKS4 protocol (de facto standard). Force SOCKS4 (or SOCKS4a if hostname is used). */
        SOCKS4,
        /** RFC 1928. Force SOCKS5. */
        SOCKS5
    }

    private Version version = Version.SOCKS5;
    private String username;
    private String password;
    private long handshakeTimeoutMs = 30_000;

    /**
     * Creates a config with default settings (SOCKS5, no auth).
     */
    public SOCKSClientConfig() {
    }

    /**
     * Creates a config with username/password authentication.
     *
     * @param username the username
     * @param password the password
     */
    public SOCKSClientConfig(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Returns the SOCKS version preference.
     *
     * @return the version preference
     */
    public Version getVersion() {
        return version;
    }

    /**
     * Sets the SOCKS version preference.
     *
     * @param version the version preference
     * @return this config for chaining
     */
    public SOCKSClientConfig setVersion(Version version) {
        this.version = version;
        return this;
    }

    /**
     * Returns the authentication username.
     * Used for SOCKS5 username/password authentication (RFC 1929 §2).
     *
     * @return the username, or null if no authentication
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the authentication username.
     * Used for SOCKS5 username/password authentication (RFC 1929 §2).
     *
     * @param username the username
     * @return this config for chaining
     */
    public SOCKSClientConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    /**
     * Returns the authentication password.
     * Used for SOCKS5 username/password authentication (RFC 1929 §2).
     *
     * @return the password, or null if no authentication
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the authentication password.
     * Used for SOCKS5 username/password authentication (RFC 1929 §2).
     *
     * @param password the password
     * @return this config for chaining
     */
    public SOCKSClientConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Returns the handshake timeout in milliseconds.
     *
     * @return the timeout
     */
    public long getHandshakeTimeoutMs() {
        return handshakeTimeoutMs;
    }

    /**
     * Sets the handshake timeout for the SOCKS negotiation.
     *
     * @param timeoutMs the timeout in milliseconds
     * @return this config for chaining
     */
    public SOCKSClientConfig setHandshakeTimeoutMs(long timeoutMs) {
        this.handshakeTimeoutMs = timeoutMs;
        return this;
    }

    /**
     * Returns whether credentials are configured.
     *
     * @return true if both username and password are non-null
     */
    public boolean hasCredentials() {
        return username != null && password != null;
    }

}
