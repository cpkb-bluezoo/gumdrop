/*
 * HTTPAuthenticationManager.java
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Manages HTTP authentication for client connections, including automatic
 * challenge handling and retry logic.
 *
 * <p>The authentication manager coordinates between the HTTP client framework
 * and authentication implementations to provide seamless authentication
 * experiences. It handles:
 *
 * <ul>
 * <li><strong>Proactive Authentication</strong>: Applies authentication before requests</li>
 * <li><strong>Challenge Handling</strong>: Processes 401/407 responses automatically</li>
 * <li><strong>Automatic Retry</strong>: Re-sends requests after successful authentication</li>
 * <li><strong>Multiple Auth Types</strong>: Supports fallback authentication schemes</li>
 * </ul>
 *
 * <p><strong>Authentication Flow:</strong>
 * <ol>
 * <li>Apply proactive authentication to outgoing requests</li>
 * <li>Monitor responses for authentication challenges (401, 407)</li>
 * <li>Handle challenges using appropriate authentication schemes</li>
 * <li>Automatically retry requests with updated authentication</li>
 * <li>Report authentication failures to the application</li>
 * </ol>
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe and can be
 * shared across multiple connections and requests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPAuthenticationManager {

    private static final Logger logger = Logger.getLogger(HTTPAuthenticationManager.class.getName());

    // Authentication schemes in priority order
    private final List<HTTPAuthentication> authentications = new CopyOnWriteArrayList<>();
    
    // Configuration
    private volatile boolean enableProactiveAuth = true;
    private volatile boolean enableChallengeHandling = true;
    private volatile int maxRetries = 3;

    /**
     * Creates a new authentication manager with default settings.
     */
    public HTTPAuthenticationManager() {
        // Default configuration allows both proactive and challenge-based auth
    }

    /**
     * Adds an authentication scheme to this manager.
     *
     * <p>Authentication schemes are tried in the order they are added.
     * The first ready scheme will be used for proactive authentication,
     * and all schemes will be consulted for challenge handling.
     *
     * @param authentication the authentication scheme to add
     * @throws IllegalArgumentException if authentication is null
     */
    public void addAuthentication(HTTPAuthentication authentication) {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication cannot be null");
        }
        
        authentications.add(authentication);
        logger.fine("Added authentication: " + authentication.getDescription());
    }

    /**
     * Removes an authentication scheme from this manager.
     *
     * @param authentication the authentication scheme to remove
     * @return true if the authentication was removed, false if not found
     */
    public boolean removeAuthentication(HTTPAuthentication authentication) {
        boolean removed = authentications.remove(authentication);
        if (removed) {
            logger.fine("Removed authentication: " + authentication.getDescription());
        }
        return removed;
    }

    /**
     * Removes all authentication schemes.
     */
    public void clearAuthentications() {
        int count = authentications.size();
        authentications.clear();
        logger.fine("Cleared " + count + " authentication schemes");
    }

    /**
     * Applies proactive authentication to a request if available and enabled.
     *
     * <p>This method is called before sending a request to add authentication
     * headers. It uses the first ready authentication scheme that doesn't
     * require a server challenge.
     *
     * @param request the HTTP request to authenticate
     * @return a new HTTPRequest with authentication applied, or the original request if no authentication was applied
     */
    public HTTPRequest applyProactiveAuthentication(HTTPRequest request) {
        if (!enableProactiveAuth || authentications.isEmpty()) {
            return request;
        }

        // Find the first ready authentication that doesn't require a challenge
        for (HTTPAuthentication auth : authentications) {
            if (auth.isReady() && !auth.requiresChallenge()) {
                try {
                    // Create a mutable copy of headers
                    java.util.Map<String, String> headers = new java.util.HashMap<>(request.getHeaders());
                    
                    // Apply authentication
                    auth.applyAuthentication(headers);
                    
                    // Create new request with authentication headers
                    HTTPRequest authenticatedRequest = new HTTPRequest(
                        request.getMethod(),
                        request.getUri(),
                        request.getVersion(),
                        headers
                    );
                    
                    logger.fine("Applied proactive authentication: " + auth.getDescription());
                    return authenticatedRequest;
                    
                } catch (Exception e) {
                    logger.warning("Failed to apply proactive authentication (" + auth.getDescription() + "): " + e.getMessage());
                    // Continue to next authentication scheme
                }
            }
        }

        return request; // No authentication applied
    }

    /**
     * Handles an authentication challenge from the server.
     *
     * <p>This method is called when a 401 (Unauthorized) or 407 (Proxy Authentication Required)
     * response is received. It attempts to handle the challenge using available
     * authentication schemes.
     *
     * @param response the HTTP response containing the authentication challenge
     * @param originalRequest the original request that received the challenge
     * @return an AuthenticationResult indicating how to proceed
     */
    public AuthenticationResult handleChallenge(HTTPResponse response, HTTPRequest originalRequest) {
        if (!enableChallengeHandling) {
            return AuthenticationResult.failed("Challenge handling is disabled");
        }

        int statusCode = response.getStatusCode();
        if (statusCode != 401 && statusCode != 407) {
            return AuthenticationResult.failed("Not an authentication challenge: " + statusCode);
        }

        logger.fine("Handling authentication challenge: " + statusCode + " " + response.getStatusMessage());

        // Try each authentication scheme to handle the challenge
        for (HTTPAuthentication auth : authentications) {
            try {
                if (auth.handleChallenge(response)) {
                    logger.fine("Challenge handled by: " + auth.getDescription());
                    
                    // Create authenticated request
                    HTTPRequest authenticatedRequest = createAuthenticatedRequest(originalRequest, auth);
                    if (authenticatedRequest != null) {
                        return AuthenticationResult.retry(authenticatedRequest, auth);
                    }
                }
            } catch (Exception e) {
                logger.warning("Authentication challenge handling failed (" + auth.getDescription() + "): " + e.getMessage());
                // Continue to next authentication scheme
            }
        }

        return AuthenticationResult.failed("No authentication scheme could handle the challenge");
    }

    /**
     * Creates an authenticated version of the original request.
     */
    private HTTPRequest createAuthenticatedRequest(HTTPRequest originalRequest, HTTPAuthentication auth) {
        try {
            // Create a mutable copy of headers
            java.util.Map<String, String> headers = new java.util.HashMap<>(originalRequest.getHeaders());
            
            // Apply authentication
            auth.applyAuthentication(headers);
            
            // Create new request with authentication headers
            return new HTTPRequest(
                originalRequest.getMethod(),
                originalRequest.getUri(),
                originalRequest.getVersion(),
                headers
            );
            
        } catch (Exception e) {
            logger.warning("Failed to create authenticated request: " + e.getMessage());
            return null;
        }
    }

    /**
     * Result of authentication challenge handling.
     */
    public static class AuthenticationResult {
        private final Type type;
        private final HTTPRequest retryRequest;
        private final HTTPAuthentication usedAuthentication;
        private final String failureReason;

        public enum Type {
            /** Authentication succeeded, retry with the provided request */
            RETRY,
            /** Authentication failed, do not retry */
            FAILED
        }

        private AuthenticationResult(Type type, HTTPRequest retryRequest, HTTPAuthentication usedAuthentication, String failureReason) {
            this.type = type;
            this.retryRequest = retryRequest;
            this.usedAuthentication = usedAuthentication;
            this.failureReason = failureReason;
        }

        public static AuthenticationResult retry(HTTPRequest request, HTTPAuthentication authentication) {
            return new AuthenticationResult(Type.RETRY, request, authentication, null);
        }

        public static AuthenticationResult failed(String reason) {
            return new AuthenticationResult(Type.FAILED, null, null, reason);
        }

        public Type getType() { return type; }
        public HTTPRequest getRetryRequest() { return retryRequest; }
        public HTTPAuthentication getUsedAuthentication() { return usedAuthentication; }
        public String getFailureReason() { return failureReason; }

        public boolean isRetry() { return type == Type.RETRY; }
        public boolean isFailed() { return type == Type.FAILED; }
    }

    // Configuration methods

    /**
     * Enables or disables proactive authentication.
     *
     * @param enable true to enable proactive authentication, false to disable
     */
    public void setProactiveAuthEnabled(boolean enable) {
        this.enableProactiveAuth = enable;
        logger.fine("Proactive authentication " + (enable ? "enabled" : "disabled"));
    }

    /**
     * Enables or disables automatic challenge handling.
     *
     * @param enable true to enable challenge handling, false to disable
     */
    public void setChallengeHandlingEnabled(boolean enable) {
        this.enableChallengeHandling = enable;
        logger.fine("Challenge handling " + (enable ? "enabled" : "disabled"));
    }

    /**
     * Sets the maximum number of authentication retries.
     *
     * @param maxRetries the maximum number of retries (must be >= 0)
     */
    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must be >= 0");
        }
        this.maxRetries = maxRetries;
        logger.fine("Max authentication retries set to: " + maxRetries);
    }

    // Status methods

    /**
     * Returns true if proactive authentication is enabled.
     */
    public boolean isProactiveAuthEnabled() {
        return enableProactiveAuth;
    }

    /**
     * Returns true if challenge handling is enabled.
     */
    public boolean isChallengeHandlingEnabled() {
        return enableChallengeHandling;
    }

    /**
     * Returns the maximum number of authentication retries.
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Returns the number of configured authentication schemes.
     */
    public int getAuthenticationCount() {
        return authentications.size();
    }

    /**
     * Returns true if any authentication schemes are configured.
     */
    public boolean hasAuthentication() {
        return !authentications.isEmpty();
    }

    /**
     * Returns a description of the configured authentication schemes.
     */
    public String getDescription() {
        if (authentications.isEmpty()) {
            return "No authentication configured";
        }
        
        StringBuilder desc = new StringBuilder("Authentication: ");
        for (int i = 0; i < authentications.size(); i++) {
            if (i > 0) desc.append(", ");
            desc.append(authentications.get(i).getScheme());
        }
        
        return desc.toString();
    }
}
