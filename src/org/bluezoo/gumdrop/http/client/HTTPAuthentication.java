/*
 * HTTPAuthentication.java
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

package org.bluezoo.gumdrop.http.client;

import java.util.Map;

/**
 * Interface for HTTP authentication mechanisms.
 *
 * <p>This interface defines the contract for all HTTP authentication schemes
 * supported by the Gumdrop HTTP client. Implementations handle the creation
 * of authentication headers and can respond to server authentication challenges.
 *
 * <p>Supported authentication schemes include:
 * <ul>
 * <li><strong>Basic</strong>: RFC 7617 - Simple username/password encoding</li>
 * <li><strong>Digest</strong>: RFC 7616 - Challenge-response with hash</li>
 * <li><strong>Bearer</strong>: RFC 6750 - Token-based authentication</li>
 * <li><strong>OAuth 2.0</strong>: RFC 6749 - OAuth authorization flows</li>
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 * <ol>
 * <li>Create authentication instance with credentials</li>
 * <li>Apply to requests via {@link #applyAuthentication}</li>
 * <li>Handle server challenges via {@link #handleChallenge}</li>
 * <li>Clone for reuse across multiple requests</li>
 * </ol>
 *
 * <p><strong>Thread Safety:</strong> Implementations should be thread-safe
 * for read-only operations but may require synchronization for challenge handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see BasicAuthentication
 * @see DigestAuthentication
 * @see BearerAuthentication
 */
public interface HTTPAuthentication {

    /**
     * Returns the authentication scheme name (e.g., "Basic", "Digest", "Bearer").
     *
     * @return the authentication scheme identifier
     */
    String getScheme();

    /**
     * Applies authentication headers to an HTTP request.
     *
     * <p>This method adds the appropriate {@code Authorization} header
     * (or other authentication headers) to the provided header map.
     * The implementation should handle all necessary credential encoding
     * and header formatting.
     *
     * <p><strong>Examples:</strong>
     * <ul>
     * <li>Basic: {@code Authorization: Basic dXNlcjpwYXNz}</li>
     * <li>Digest: {@code Authorization: Digest username="user", realm="..."}</li>
     * <li>Bearer: {@code Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...}</li>
     * </ul>
     *
     * @param headers the mutable map of request headers to modify
     */
    void applyAuthentication(Map<String, String> headers);

    /**
     * Handles an authentication challenge from the server.
     *
     * <p>This method is called when the server responds with a 401 (Unauthorized)
     * or 407 (Proxy Authentication Required) status code. The implementation
     * should parse the challenge, update its internal state, and return whether
     * it can handle the challenge.
     *
     * <p>Common challenge scenarios:
     * <ul>
     * <li><strong>Digest</strong>: Parse nonce, realm, qop from WWW-Authenticate header</li>
     * <li><strong>OAuth</strong>: Handle token expiration and refresh</li>
     * <li><strong>Basic</strong>: Typically no challenge handling needed</li>
     * </ul>
     *
     * @param response the HTTP response containing the authentication challenge
     * @return true if the challenge can be handled and the request should be retried,
     *         false if authentication failed permanently
     */
    boolean handleChallenge(HTTPResponse response);

    /**
     * Creates a copy of this authentication instance for reuse.
     *
     * <p>Authentication objects may maintain internal state (e.g., nonce counters
     * for Digest authentication). This method creates a fresh copy suitable
     * for use with a different request or connection while preserving the
     * original credentials.
     *
     * <p>The clone should be independent of the original instance to avoid
     * race conditions in concurrent usage.
     *
     * @return a new authentication instance with the same credentials
     */
    HTTPAuthentication clone();

    /**
     * Checks if this authentication requires a server challenge.
     *
     * <p>Some authentication schemes (like Digest) require an initial server
     * challenge to obtain parameters like nonce and realm. Others (like Basic)
     * can authenticate proactively without a challenge.
     *
     * @return true if a server challenge is required before authentication,
     *         false if authentication can be applied immediately
     */
    boolean requiresChallenge();

    /**
     * Checks if this authentication instance is ready to authenticate requests.
     *
     * <p>For challenge-based authentication, this returns true only after
     * a successful challenge has been processed. For proactive authentication,
     * this typically returns true if credentials are available.
     *
     * @return true if authentication can be applied to requests, false otherwise
     */
    boolean isReady();

    /**
     * Returns a description of this authentication for logging and debugging.
     *
     * <p>The description should not contain sensitive information like
     * passwords or tokens. It should include the scheme and any non-sensitive
     * parameters like username or realm.
     *
     * @return a safe description of this authentication
     */
    String getDescription();
}
