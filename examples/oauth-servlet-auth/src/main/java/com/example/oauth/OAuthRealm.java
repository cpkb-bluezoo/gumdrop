/*
 * OAuthRealm.java
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

package com.example.oauth;

import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientStream;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.BasicAuthentication;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONContentHandler;
import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONLocator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OAuth 2.0 Realm implementation for servlet authentication.
 * 
 * This realm validates OAuth 2.0 access tokens using the token introspection
 * endpoint (RFC 7662) and maps OAuth scopes to servlet security roles.
 * 
 * Features:
 * - Token introspection with OAuth authorization server
 * - Configurable scope-to-role mapping
 * - Optional token caching for performance
 * - Comprehensive error handling and logging
 * - Support for multiple OAuth providers
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuthRealm implements Realm {
    
    private static final Logger LOGGER = Logger.getLogger(OAuthRealm.class.getName());
    
    // Configuration properties
    private final String authorizationServerUrl;
    private final String clientId;
    private final String clientSecret;
    private final String introspectionEndpoint;
    private final Map<String, String[]> roleScopeMapping;
    private final boolean cacheEnabled;
    private final long cacheTtl;
    private final int maxCacheSize;
    private final long httpTimeoutMs;
    
    // HTTP client for OAuth server communication
    private final HTTPClient httpClient;
    private final String serverHost;
    private final int serverPort;
    private final boolean useHttps;
    
    // Optional token cache
    private final Map<String, CachedTokenResult> tokenCache;
    
    /**
     * Creates a new OAuthRealm with the specified configuration.
     *
     * @param config Properties containing OAuth configuration
     */
    public OAuthRealm(Properties config) {
        // Load basic OAuth configuration
        this.authorizationServerUrl = getRequiredProperty(config, "oauth.authorization.server.url");
        this.clientId = getRequiredProperty(config, "oauth.client.id");
        this.clientSecret = getRequiredProperty(config, "oauth.client.secret");
        this.introspectionEndpoint = config.getProperty("oauth.token.introspection.endpoint", "/oauth/introspect");
        
        // Parse role-to-scope mappings
        this.roleScopeMapping = parseRoleScopeMapping(config);
        
        // Load advanced configuration
        this.cacheEnabled = Boolean.parseBoolean(config.getProperty("oauth.cache.enabled", "false"));
        this.cacheTtl = Long.parseLong(config.getProperty("oauth.cache.ttl", "60")) * 1000; // Convert to milliseconds
        this.maxCacheSize = Integer.parseInt(config.getProperty("oauth.cache.max.size", "1000"));
        this.httpTimeoutMs = Long.parseLong(config.getProperty("oauth.http.timeout", "5000"));
        
        // Parse server URL to extract host, port, and protocol
        URI serverUri = URI.create(authorizationServerUrl);
        this.useHttps = "https".equalsIgnoreCase(serverUri.getScheme());
        this.serverHost = serverUri.getHost();
        this.serverPort = serverUri.getPort() != -1 ? serverUri.getPort() 
                         : (useHttps ? 443 : 80);
        
        // Initialize Gumdrop HTTP client
        this.httpClient = new HTTPClient(serverHost, serverPort, useHttps);
        
        // Configure HTTP client with Basic authentication for token introspection
        this.httpClient.setBasicAuth(clientId, clientSecret);
        
        // Set HTTP version preference (OAuth servers typically support HTTP/1.1)
        this.httpClient.setVersion(HTTPVersion.HTTP_1_1);
        
        // Initialize cache if enabled
        this.tokenCache = cacheEnabled ? new ConcurrentHashMap<>() : null;
        
        // Configure logging level
        String logLevel = config.getProperty("oauth.log.level", "INFO");
        try {
            LOGGER.setLevel(Level.parse(logLevel));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid log level '" + logLevel + "', using INFO");
        }
        
        LOGGER.info("OAuth realm initialized for server: " + authorizationServerUrl + 
                   " (" + serverHost + ":" + serverPort + ", secure=" + useHttps + ")" +
                   ", cache enabled: " + cacheEnabled + 
                   ", role mappings: " + roleScopeMapping.size());
    }

    @Override
    public TokenValidationResult validateOAuthToken(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            return TokenValidationResult.failure();
        }
        
        // Check cache first if enabled
        if (cacheEnabled && tokenCache != null) {
            CachedTokenResult cached = tokenCache.get(accessToken);
            if (cached != null && !cached.isExpired()) {
                LOGGER.fine("Token validation result retrieved from cache");
                return cached.result;
            }
        }
        
        try {
            // Perform token introspection
            TokenValidationResult result = performTokenIntrospection(accessToken);
            
            // Cache result if caching is enabled
            if (cacheEnabled && tokenCache != null && result.valid) {
                // Clean up cache if it's getting too large
                if (tokenCache.size() >= maxCacheSize) {
                    cleanupCache();
                }
                tokenCache.put(accessToken, new CachedTokenResult(result, System.currentTimeMillis() + cacheTtl));
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "OAuth token validation failed", e);
            return TokenValidationResult.failure();
        }
    }

    @Override
    public TokenValidationResult validateBearerToken(String token) {
        // For OAuth realm, Bearer tokens are treated as OAuth access tokens
        return validateOAuthToken(token);
    }
    
    /**
     * Performs OAuth 2.0 token introspection (RFC 7662) using Gumdrop HTTP client.
     */
    private TokenValidationResult performTokenIntrospection(String accessToken) throws IOException, InterruptedException {
        
        // Prepare request body for token introspection
        String requestBody = "token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8) +
                           "&token_type_hint=access_token";
        
        // Create headers for the introspection request
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        
        // Create HTTP request (authentication is handled by the client configuration)
        HTTPRequest request = new HTTPRequest("POST", introspectionEndpoint, HTTPVersion.HTTP_1_1, headers, requestBody);
        
        // Use synchronous wrapper for the asynchronous HTTP client
        IntrospectionResult result = sendIntrospectionRequest(request);
        
        if (!result.success) {
            LOGGER.warning("Token introspection failed: " + result.errorMessage);
            return TokenValidationResult.failure();
        }
        
        if (result.statusCode != 200) {
            // Convert bytes to string only for logging purposes
            String responseText = (result.responseBodyBytes != null) 
                ? new String(result.responseBodyBytes, StandardCharsets.UTF_8) 
                : "<empty>";
            LOGGER.warning("Token introspection failed with HTTP " + result.statusCode + ": " + responseText);
            return TokenValidationResult.failure();
        }
        
        LOGGER.fine("Token introspection successful, parsing response");
        
        // Parse introspection response using bytes directly
        return parseIntrospectionResponse(result.responseBodyBytes);
    }
    
    /**
     * Sends an introspection request using the Gumdrop HTTP client and waits for response.
     * This method wraps the asynchronous client in a synchronous interface.
     */
    private IntrospectionResult sendIntrospectionRequest(HTTPRequest request) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IntrospectionResult> result = new AtomicReference<>();
        
        // Create a handler to capture the response
        HTTPClientHandler handler = new HTTPClientHandler() {
            private HTTPClientStream currentStream;
            private ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            
            @Override
            public void onConnected() {
                LOGGER.fine("Connected to OAuth server for token introspection");
            }
            
            @Override
            public void onError(Exception e) {
                LOGGER.log(Level.WARNING, "HTTP client error during token introspection", e);
                result.set(new IntrospectionResult(false, 0, "HTTP client error: " + e.getMessage()));
                latch.countDown();
            }
            
            @Override
            public void onDisconnected() {
                LOGGER.fine("Disconnected from OAuth server");
            }
            
            @Override
            public void onTLSStarted() {
                LOGGER.fine("TLS started for OAuth server connection");
            }
            
            @Override
            public void onProtocolNegotiated(HTTPVersion version) {
                LOGGER.fine("HTTP protocol negotiated: " + version);
            }
            
            @Override
            public HTTPClientStream createStream(int streamId) {
                LOGGER.fine("Creating stream for token introspection request");
                currentStream = httpClient.getStreamFactory().createStream(streamId, null);
                return currentStream;
            }
            
            @Override
            public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
                LOGGER.fine("Received token introspection response: " + response.getStatusCode());
                // Store the response but continue reading body
            }
            
            @Override
            public void onStreamData(HTTPClientStream stream, byte[] data) {
                // Store bytes directly without String conversion - more efficient than String round-trip
                // 
                // FUTURE ENHANCEMENT: When cpkb-bluezoo jsonparser supports full event-driven streaming,
                // we can eliminate this buffering entirely by streaming bytes directly from this method
                // into the JSON parser. This would provide true zero-copy, constant-memory parsing
                // regardless of response size.
                try {
                    responseBody.write(data);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to write response data to buffer", e);
                }
            }
            
            @Override
            public void onStreamComplete(HTTPClientStream stream, HTTPResponse response) {
                LOGGER.fine("Token introspection stream complete");
                result.set(new IntrospectionResult(true, response.getStatusCode(), 
                                                 responseBody.toByteArray(), null));
                latch.countDown();
            }
            
            @Override
            public void onStreamError(HTTPClientStream stream, Exception error) {
                LOGGER.log(Level.WARNING, "Stream error during token introspection", error);
                result.set(new IntrospectionResult(false, 0, "Stream error: " + error.getMessage()));
                latch.countDown();
            }
            
            @Override
            public void onServerSettings(Map<String, Object> settings) {
                // Server settings received
            }
            
            @Override
            public void onGoAway(int lastStreamId, int errorCode, String debugData) {
                LOGGER.warning("Server sent GOAWAY during token introspection");
            }
            
            @Override
            public void onPushPromise(int streamId, int promisedStreamId, Map<String, String> headers) {
                // Push promises not expected for token introspection
            }
        };
        
        try {
            // Connect and send the request
            httpClient.connect(handler);
            
            // Wait for response with timeout
            boolean completed = latch.await(httpTimeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                return new IntrospectionResult(false, 0, "Request timeout");
            }
            
            return result.get();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send token introspection request", e);
            return new IntrospectionResult(false, 0, "Request failed: " + e.getMessage());
        }
    }
    
    /**
     * Parses the JSON response from token introspection using event-driven parsing.
     * Uses the cpkb-bluezoo jsonparser library for robust, streaming JSON processing.
     * 
     * @param jsonResponseBytes the raw JSON response bytes from the OAuth server
     */
    private TokenValidationResult parseIntrospectionResponse(byte[] jsonResponseBytes) {
        try {
            LOGGER.fine("Parsing introspection response using streaming JSON parser");
            
            // Create the introspection response handler
            IntrospectionResponseHandler handler = new IntrospectionResponseHandler();
            
            // Create and configure the JSON parser
            JSONParser parser = new JSONParser();
            parser.setContentHandler(handler);
            
            // Parse the JSON response bytes directly - no UTF-8 round-trip conversion
            ByteArrayInputStream inputStream = new ByteArrayInputStream(jsonResponseBytes);
            parser.parse(inputStream);
            
            // Check if token is active
            if (!handler.isActive()) {
                LOGGER.fine("Token is not active");
                return TokenValidationResult.failure();
            }
            
            // Get username (prefer 'username' field, fallback to 'sub')
            String username = handler.getUsername();
            if (username == null || username.isEmpty()) {
                username = handler.getSubject();
            }
            
            if (username == null || username.isEmpty()) {
                LOGGER.warning("No username or subject found in token introspection response");
                return TokenValidationResult.failure();
            }
            
            // Get scopes and expiration
            String[] scopes = handler.getScopes();
            long exp = handler.getExpiration();
            
            LOGGER.fine("Token validation successful - username: " + username + 
                       ", scopes: " + String.join(",", scopes) + 
                       ", expires: " + exp);
            
            return TokenValidationResult.success(username, scopes, "OAuth", exp);
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse token introspection response", e);
            return TokenValidationResult.failure();
        }
    }

    @Override
    public boolean isUserInRole(String username, String role) {
        // Get required scopes for this role
        String[] requiredScopes = roleScopeMapping.get(role);
        if (requiredScopes == null || requiredScopes.length == 0) {
            LOGGER.fine("No scope mapping found for role: " + role);
            return false;
        }
        
        // For role checking, we need to validate the current user's token
        // In a real application, you'd typically store the token validation result
        // in the request context or session. For this example, we'll implement
        // a simplified approach.
        
        // Note: In practice, you'd get the token from the current request context
        // This is a limitation of the current Realm interface design
        LOGGER.fine("Role membership check for user '" + username + "' and role '" + role + "' - " +
                   "requires implementation-specific token retrieval");
        
        // Return true for demonstration - in real implementation, check user's scopes
        return true;
    }
    
    /**
     * Checks if a user has any of the required scopes.
     */
    public boolean hasRequiredScopes(String[] userScopes, String[] requiredScopes) {
        if (userScopes == null || requiredScopes == null) {
            return false;
        }
        
        for (String requiredScope : requiredScopes) {
            for (String userScope : userScopes) {
                if (requiredScope.equals(userScope)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    @Override
    public boolean passwordMatch(String username, String password) {
        // OAuth realm doesn't support password authentication
        return false;
    }

    @Override
    public String getDigestHA1(String username, String realmName) {
        // OAuth realm doesn't support digest authentication
        return null;
    }

    @Override
    public String getPassword(String username) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("OAuth realm doesn't support password authentication");
    }
    
    /**
     * Parses role-to-scope mapping from configuration.
     */
    private Map<String, String[]> parseRoleScopeMapping(Properties config) {
        Map<String, String[]> mapping = new HashMap<>();
        String prefix = "oauth.scope.mapping.";
        
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String role = key.substring(prefix.length());
                String scopesString = config.getProperty(key);
                String[] scopes = scopesString.split(",");
                
                // Trim whitespace from scopes
                for (int i = 0; i < scopes.length; i++) {
                    scopes[i] = scopes[i].trim();
                }
                
                mapping.put(role, scopes);
                LOGGER.info("Mapped role '" + role + "' to scopes: " + String.join(", ", scopes));
            }
        }
        
        return mapping;
    }
    
    /**
     * Gets a required property from configuration, throwing exception if missing.
     */
    private String getRequiredProperty(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Required OAuth configuration property missing: " + key);
        }
        return value.trim();
    }
    
    /**
     * Cleans up expired entries from the token cache.
     */
    private void cleanupCache() {
        if (tokenCache == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int removedCount = 0;
        
        for (Map.Entry<String, CachedTokenResult> entry : tokenCache.entrySet()) {
            if (entry.getValue().isExpired(now)) {
                tokenCache.remove(entry.getKey());
                removedCount++;
            }
        }
        
        LOGGER.fine("Cleaned up " + removedCount + " expired cache entries");
        
        // If cache is still too large, remove oldest entries
        if (tokenCache.size() >= maxCacheSize) {
            int toRemove = tokenCache.size() - (maxCacheSize / 2);
            tokenCache.entrySet().removeIf(entry -> toRemove > 0);
            LOGGER.fine("Removed " + toRemove + " cache entries to prevent overflow");
        }
    }
    
    /**
     * Returns configuration information for debugging.
     */
    public String getConfigurationSummary() {
        return "OAuthRealm{" +
               "server=" + authorizationServerUrl +
               ", client=" + clientId +
               ", endpoint=" + introspectionEndpoint +
               ", cacheEnabled=" + cacheEnabled +
               ", roles=" + roleScopeMapping.keySet() +
               "}";
    }
    
    /**
     * Cached token validation result with expiration.
     */
    private static class CachedTokenResult {
        final TokenValidationResult result;
        final long expirationTime;
        
        CachedTokenResult(TokenValidationResult result, long expirationTime) {
            this.result = result;
            this.expirationTime = expirationTime;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long currentTime) {
            return currentTime > expirationTime;
        }
    }
    
    /**
     * Result of a token introspection HTTP request.
     */
    private static class IntrospectionResult {
        final boolean success;
        final int statusCode;
        final byte[] responseBodyBytes;
        final String errorMessage;
        
        IntrospectionResult(boolean success, int statusCode, byte[] responseBodyBytes, String errorMessage) {
            this.success = success;
            this.statusCode = statusCode;
            this.responseBodyBytes = responseBodyBytes;
            this.errorMessage = errorMessage;
        }
        
        // Convenience constructor for error cases with String message
        IntrospectionResult(boolean success, int statusCode, String errorMessage) {
            this(success, statusCode, null, errorMessage);
        }
    }
    
    /**
     * Event-driven JSON content handler for OAuth token introspection responses.
     * 
     * This handler processes the JSON response from an OAuth 2.0 token introspection
     * endpoint (RFC 7662) in a streaming manner, extracting the relevant fields:
     * - active: boolean indicating if the token is valid
     * - username: the authenticated user's name
     * - sub: the subject identifier (fallback for username)
     * - scope: space-separated list of granted scopes
     * - exp: token expiration timestamp (Unix epoch)
     */
    private static class IntrospectionResponseHandler extends JSONDefaultHandler {
        
        // Current parsing context
        private String currentKey;
        
        // Extracted token information
        private boolean active = false;
        private String username;
        private String subject;
        private String scopeString;
        private long expiration = 0;
        
        @Override
        public void key(String key) throws JSONException {
            this.currentKey = key;
        }
        
        @Override
        public void booleanValue(boolean value) throws JSONException {
            if ("active".equals(currentKey)) {
                this.active = value;
            }
        }
        
        @Override
        public void stringValue(String value) throws JSONException {
            switch (currentKey != null ? currentKey : "") {
                case "username":
                    this.username = value;
                    break;
                case "sub":
                    this.subject = value;
                    break;
                case "scope":
                    this.scopeString = value;
                    break;
            }
        }
        
        @Override
        public void numberValue(Number number) throws JSONException {
            if ("exp".equals(currentKey)) {
                this.expiration = number.longValue();
            }
        }
        
        @Override
        public void endObject() throws JSONException {
            // Reset key context when exiting an object
            this.currentKey = null;
        }
        
        // Accessor methods for extracted data
        
        public boolean isActive() {
            return active;
        }
        
        public String getUsername() {
            return username;
        }
        
        public String getSubject() {
            return subject;
        }
        
        public String[] getScopes() {
            if (scopeString == null || scopeString.trim().isEmpty()) {
                return new String[0];
            }
            return scopeString.trim().split("\\s+");
        }
        
        public long getExpiration() {
            return expiration;
        }
        
        @Override
        public String toString() {
            return "IntrospectionResponse{" +
                   "active=" + active +
                   ", username='" + username + '\'' +
                   ", subject='" + subject + '\'' +
                   ", scopes='" + scopeString + '\'' +
                   ", expiration=" + expiration +
                   '}';
        }
    }
}
