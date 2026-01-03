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

package org.bluezoo.gumdrop.auth;

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OAuth 2.0 Realm implementation for token-based authentication.
 * 
 * <p>This realm validates OAuth 2.0 access tokens using the token introspection
 * endpoint (RFC 7662) and maps OAuth scopes to servlet security roles.
 * 
 * <h3>Features</h3>
 * <ul>
 *   <li>Token introspection with OAuth authorization server</li>
 *   <li>Configurable scope-to-role mapping</li>
 *   <li>Optional token caching for performance</li>
 *   <li>Event-driven streaming JSON parsing of introspection responses</li>
 *   <li>Support for multiple OAuth providers</li>
 * </ul>
 *
 * <h3>Configuration Properties</h3>
 * <table border="1" cellpadding="5">
 *   <caption>Required Properties</caption>
 *   <tr><th>Property</th><th>Description</th></tr>
 *   <tr><td>oauth.authorization.server.url</td><td>Base URL of the OAuth server (e.g., https://auth.example.com)</td></tr>
 *   <tr><td>oauth.client.id</td><td>Client ID for token introspection</td></tr>
 *   <tr><td>oauth.client.secret</td><td>Client secret for token introspection</td></tr>
 * </table>
 *
 * <table border="1" cellpadding="5">
 *   <caption>Optional Properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>oauth.token.introspection.endpoint</td><td>/oauth/introspect</td><td>Path to introspection endpoint</td></tr>
 *   <tr><td>oauth.cache.enabled</td><td>false</td><td>Enable token result caching</td></tr>
 *   <tr><td>oauth.cache.ttl</td><td>60</td><td>Cache TTL in seconds</td></tr>
 *   <tr><td>oauth.cache.max.size</td><td>1000</td><td>Maximum cache entries</td></tr>
 *   <tr><td>oauth.http.timeout</td><td>5000</td><td>HTTP timeout in milliseconds</td></tr>
 *   <tr><td>oauth.scope.mapping.{role}</td><td></td><td>Comma-separated scopes for role</td></tr>
 * </table>
 *
 * <h3>Configuration Example</h3>
 * <pre>{@code
 * oauth.authorization.server.url=https://auth.example.com
 * oauth.client.id=my-client
 * oauth.client.secret=secret123
 * oauth.token.introspection.endpoint=/oauth2/introspect
 * oauth.cache.enabled=true
 * oauth.cache.ttl=300
 * oauth.scope.mapping.admin=admin,superuser
 * oauth.scope.mapping.user=read,write
 * }</pre>
 *
 * <h3>Gumdrop Configuration</h3>
 * <pre>{@code
 * <realm id="oauth" class="org.bluezoo.gumdrop.auth.OAuthRealm"
 *        configFile="oauth.properties"/>
 * 
 * <server class="org.bluezoo.gumdrop.imap.IMAPServer"
 *         port="993" secure="true"
 *         realm="#oauth"/>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7662">RFC 7662 - OAuth 2.0 Token Introspection</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6749">RFC 6749 - OAuth 2.0 Framework</a>
 */
public class OAuthRealm implements Realm {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.auth.L10N");   
    private static final Logger LOGGER = Logger.getLogger(OAuthRealm.class.getName());
    
    /**
     * Supported SASL mechanisms for OAuth realm.
     */
    private static final Set<SASLMechanism> SUPPORTED_MECHANISMS =
        Collections.unmodifiableSet(EnumSet.of(SASLMechanism.OAUTHBEARER));
    
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
    
    // Server connection info
    private final String serverHost;
    private final int serverPort;
    private final boolean useHttps;
    private final String basicAuthHeader;
    
    // Optional token cache
    private final Map<String, CachedTokenResult> tokenCache;
    
    // SelectorLoop binding
    private final SelectorLoop selectorLoop;
    
    // Stored config for forSelectorLoop
    private final Properties config;
    
    /**
     * Creates a new OAuthRealm with the specified configuration.
     *
     * @param config Properties containing OAuth configuration
     */
    public OAuthRealm(Properties config) {
        this(config, null);
    }
    
    /**
     * Creates a new OAuthRealm bound to a specific SelectorLoop.
     *
     * @param config Properties containing OAuth configuration
     * @param loop the SelectorLoop for HTTP client connections
     */
    private OAuthRealm(Properties config, SelectorLoop loop) {
        this.config = config;
        this.selectorLoop = loop;
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
        int port = serverUri.getPort();
        this.serverPort = port != -1 ? port : (useHttps ? 443 : 80);
        // Pre-compute Basic auth header
        String credentials = clientId + ":" + clientSecret;
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        // Initialize cache if enabled
        this.tokenCache = cacheEnabled ? new ConcurrentHashMap<String, CachedTokenResult>() : null;
        // Configure logging level
        String logLevel = config.getProperty("oauth.log.level", "INFO");
        try {
            LOGGER.setLevel(Level.parse(logLevel));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid log level '" + logLevel + "', using INFO");
        }
        String msg = MessageFormat.format(L10N.getString("info.oauth.init"), authorizationServerUrl, serverHost, serverPort, useHttps, cacheEnabled, roleScopeMapping.size());
        LOGGER.info(msg);
    }
    
    // ─────────────────────────────────────────────────────────────────────────────
    // Realm Interface Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public Realm forSelectorLoop(SelectorLoop loop) {
        if (loop == null || loop == this.selectorLoop) {
            return this;
        }
        // Create a new realm instance bound to the specified loop
        return new OAuthRealm(config, loop);
    }
    
    @Override
    public Set<SASLMechanism> getSupportedSASLMechanisms() {
        return SUPPORTED_MECHANISMS;
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
                LOGGER.fine(L10N.getString("debug.token_result_from_cache"));
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
            LOGGER.log(Level.WARNING, L10N.getString("warn.oauth_token_failed"), e);
            return TokenValidationResult.failure();
        }
    }

    @Override
    public TokenValidationResult validateBearerToken(String token) {
        // For OAuth realm, Bearer tokens are treated as OAuth access tokens
        return validateOAuthToken(token);
    }

    @Override
    public boolean isUserInRole(String username, String role) {
        // Get required scopes for this role
        String[] requiredScopes = roleScopeMapping.get(role);
        if (requiredScopes == null || requiredScopes.length == 0) {
            String msg = MessageFormat.format(L10N.getString("debug.no_scope_mapping"), role);
            LOGGER.fine(msg);
            return false;
        }
        // FIXME
        // Note: In practice, the caller should have the token validation result
        // with scopes available. This method cannot re-validate without the token.
        String msg = MessageFormat.format(L10N.getString("debug.need_token_scope"), username, role);
        LOGGER.fine(msg);
        return false;
    }
    
    /**
     * Checks if a user has any of the required scopes.
     * 
     * @param userScopes the scopes granted to the user
     * @param requiredScopes the scopes required for access
     * @return true if the user has at least one required scope
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
    
    /**
     * Checks if a user has a role based on their granted scopes.
     * 
     * @param userScopes the scopes granted to the user
     * @param role the role to check
     * @return true if the user's scopes satisfy the role requirement
     */
    public boolean hasRoleByScopes(String[] userScopes, String role) {
        String[] requiredScopes = roleScopeMapping.get(role);
        return hasRequiredScopes(userScopes, requiredScopes);
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
        throw new UnsupportedOperationException("OAuth realm does not support password retrieval");
    }
    
    // ─────────────────────────────────────────────────────────────────────────────
    // Token Introspection
    // ─────────────────────────────────────────────────────────────────────────────
    
    /**
     * Performs OAuth 2.0 token introspection (RFC 7662) using Gumdrop HTTP client.
     * 
     * <p>Uses event-driven streaming JSON parsing - the response body is parsed
     * incrementally as it arrives, without intermediate buffering.
     */
    private TokenValidationResult performTokenIntrospection(String accessToken) throws Exception {
        
        // Prepare request body for token introspection
        String requestBody = "token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8.name()) +
                           "&token_type_hint=access_token";
        byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
        // Create HTTP client
        HTTPClient client;
        try {
            if (selectorLoop != null) {
                client = new HTTPClient(selectorLoop, serverHost, serverPort);
            } else {
                client = new HTTPClient(serverHost, serverPort);
            }
        } catch (UnknownHostException e) {
            String msg = MessageFormat.format(L10N.getString("err.unknown_host"), serverHost);
            LOGGER.warning(msg);
            return TokenValidationResult.failure();
        }
        client.setSecure(useHttps);
        // Use credentials for automatic authentication
        client.credentials(clientId, clientSecret);
        // Synchronization for async response
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<TokenValidationResult> result = new AtomicReference<TokenValidationResult>();
        // Create response handler with streaming JSON parsing
        DefaultHTTPResponseHandler responseHandler = new DefaultHTTPResponseHandler() {
            private final IntrospectionResponseHandler jsonHandler = new IntrospectionResponseHandler();
            private final JSONParser jsonParser = new JSONParser();
            private boolean parserInitialized = false;
            private Exception parseError = null;
            private int statusCode = 0;
            
            @Override
            public void ok(HTTPResponse response) {
                statusCode = response.getStatus().code;
                initParser();
            }
            
            @Override
            public void error(HTTPResponse response) {
                statusCode = response.getStatus().code;
                String msg = MessageFormat.format(L10N.getString("err.oauth_token_introspection_error"), statusCode);
                LOGGER.warning(msg);
            }
            
            private void initParser() {
                if (!parserInitialized) {
                    jsonParser.setContentHandler(jsonHandler);
                    parserInitialized = true;
                }
            }
            
            @Override
            public void responseBodyContent(ByteBuffer data) {
                if (parseError != null) {
                    return;
                }
                
                initParser();
                
                try {
                    jsonParser.receive(data);
                } catch (JSONException e) {
                    LOGGER.log(Level.WARNING, L10N.getString("err.json_parse_streaming"), e);
                    parseError = e;
                }
            }
            
            @Override
            public void close() {
                // Close the JSON parser to finalize parsing
                try {
                    if (parserInitialized) {
                        jsonParser.close();
                    }
                } catch (JSONException e) {
                    LOGGER.log(Level.WARNING, L10N.getString("err.json_parse_close"), e);
                    if (parseError == null) {
                        parseError = e;
                    }
                }
                
                // Check for errors
                if (parseError != null || statusCode != 200) {
                    result.set(TokenValidationResult.failure());
                } else {
                    result.set(extractValidationResult(jsonHandler));
                }
                latch.countDown();
            }
            
            @Override
            public void failed(Exception ex) {
                LOGGER.log(Level.WARNING, L10N.getString("err.http_request_failed"), ex);
                result.set(TokenValidationResult.failure());
                latch.countDown();
            }
        };
        
        // Connect and make request
        client.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(ConnectionInfo info) {
                LOGGER.fine(L10N.getString("debug.oauth_connected"));
                
                // Create and send the POST request
                HTTPRequest request = client.post(introspectionEndpoint);
                request.header("Content-Type", "application/x-www-form-urlencoded");
                request.header("Accept", "application/json");
                request.header("Authorization", basicAuthHeader);
                
                // Send request with body
                request.startRequestBody(responseHandler);
                request.requestBodyContent(ByteBuffer.wrap(bodyBytes));
                request.endRequestBody();
            }
            
            @Override
            public void onError(Exception cause) {
                LOGGER.log(Level.WARNING, L10N.getString("err.connection"), cause);
                result.set(TokenValidationResult.failure());
                latch.countDown();
            }
            
            @Override
            public void onDisconnected() {
                LOGGER.fine(L10N.getString("debug.oauth_disconnected"));
            }
            
            @Override
            public void onTLSStarted(TLSInfo info) {
                String msg = MessageFormat.format(L10N.getString("debug.oauth_tls_started"), info.getProtocol());
                LOGGER.fine(msg);
            }
        });
        // Wait for response with timeout
        boolean completed = latch.await(httpTimeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            LOGGER.warning(L10N.getString("err.oauth_timeout"));
            return TokenValidationResult.failure();
        }
        TokenValidationResult validationResult = result.get();
        return validationResult != null ? validationResult : TokenValidationResult.failure();
    }
    
    /**
     * Extracts the token validation result from the parsed JSON handler.
     * 
     * @param handler the handler containing parsed JSON fields
     * @return the validation result
     */
    private TokenValidationResult extractValidationResult(IntrospectionResponseHandler handler) {
        // Check if token is active
        if (!handler.isActive()) {
            LOGGER.fine(L10N.getString("debug.oauth_token_not_active"));
            return TokenValidationResult.failure();
        }
        // Get username (prefer 'username' field, fallback to 'sub')
        String username = handler.getUsername();
        if (username == null || username.isEmpty()) {
            username = handler.getSubject();
        }
        if (username == null || username.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.oauth_no_subject"));
            return TokenValidationResult.failure();
        }
        // Get scopes and expiration
        String[] scopes = handler.getScopes();
        long exp = handler.getExpiration();
        String msg = MessageFormat.format(L10N.getString("debug.oauth_token_success"), username, String.join(",", scopes), exp);
        LOGGER.fine(msg);
        return TokenValidationResult.success(username, scopes, "OAuth", exp);
    }
    
    // ─────────────────────────────────────────────────────────────────────────────
    // Configuration and Caching
    // ─────────────────────────────────────────────────────────────────────────────
    
    /**
     * Parses role-to-scope mapping from configuration.
     */
    private Map<String, String[]> parseRoleScopeMapping(Properties config) {
        Map<String, String[]> mapping = new HashMap<String, String[]>();
        String prefix = "oauth.scope.mapping.";
        
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String role = key.substring(prefix.length());
                String scopesString = config.getProperty(key);
                
                // Count scopes to allocate array
                int scopeCount = 1;
                for (int i = 0; i < scopesString.length(); i++) {
                    if (scopesString.charAt(i) == ',') {
                        scopeCount++;
                    }
                }
                
                // Parse comma-separated scopes
                String[] scopes = new String[scopeCount];
                int scopeIndex = 0;
                int start = 0;
                int length = scopesString.length();
                while (start <= length) {
                    int end = scopesString.indexOf(',', start);
                    if (end < 0) {
                        end = length;
                    }
                    scopes[scopeIndex++] = scopesString.substring(start, end).trim();
                    start = end + 1;
                }
                
                mapping.put(role, scopes);
                String msg = MessageFormat.format(L10N.getString("info.oauth_mapped_role"), role, joinStrings(scopes, ", "));
                LOGGER.info(msg);
            }
        }
        
        return mapping;
    }
    
    /**
     * Joins an array of strings with a delimiter.
     */
    private static String joinStrings(String[] strings, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.length; i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(strings[i]);
        }
        return sb.toString();
    }
    
    /**
     * Gets a required property from configuration, throwing exception if missing.
     */
    private String getRequiredProperty(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            String msg = MessageFormat.format(L10N.getString("err.oauth_required_config"), key);
            throw new IllegalArgumentException(msg);
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
        String msg = MessageFormat.format(L10N.getString("debug.oauth_cache_expired"), removedCount);
        LOGGER.fine(msg);
        // If cache is still too large, remove oldest entries
        if (tokenCache.size() >= maxCacheSize) {
            int toRemove = tokenCache.size() - (maxCacheSize / 2);
            int removed = 0;
            for (String key : tokenCache.keySet()) {
                if (removed >= toRemove) {
                    break;
                }
                tokenCache.remove(key);
                removed++;
            }
            msg = MessageFormat.format(L10N.getString("debug.oauth_cache_overflow"), removed);
            LOGGER.fine(msg);
        }
    }
    
    /**
     * Returns configuration information for debugging.
     * 
     * @return a summary of the realm configuration
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
    
    // ─────────────────────────────────────────────────────────────────────────────
    // Inner Classes
    // ─────────────────────────────────────────────────────────────────────────────
    
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
     * Event-driven JSON content handler for OAuth token introspection responses.
     * 
     * <p>This handler processes the JSON response from an OAuth 2.0 token introspection
     * endpoint (RFC 7662) in a streaming manner, extracting the relevant fields:
     * <ul>
     *   <li>active: boolean indicating if the token is valid</li>
     *   <li>username: the authenticated user's name</li>
     *   <li>sub: the subject identifier (fallback for username)</li>
     *   <li>scope: space-separated list of granted scopes</li>
     *   <li>exp: token expiration timestamp (Unix epoch)</li>
     * </ul>
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
            if (currentKey == null) {
                return;
            }
            if ("username".equals(currentKey)) {
                this.username = value;
            } else if ("sub".equals(currentKey)) {
                this.subject = value;
            } else if ("scope".equals(currentKey)) {
                this.scopeString = value;
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
        
        boolean isActive() {
            return active;
        }
        
        String getUsername() {
            return username;
        }
        
        String getSubject() {
            return subject;
        }
        
        String[] getScopes() {
            if (scopeString == null || scopeString.trim().isEmpty()) {
                return new String[0];
            }
            String trimmed = scopeString.trim();
            
            // Count whitespace-separated tokens
            int tokenCount = 0;
            boolean inToken = false;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (Character.isWhitespace(c)) {
                    inToken = false;
                } else if (!inToken) {
                    tokenCount++;
                    inToken = true;
                }
            }
            
            if (tokenCount == 0) {
                return new String[0];
            }
            
            // Parse tokens
            String[] scopes = new String[tokenCount];
            int tokenIndex = 0;
            int start = 0;
            int length = trimmed.length();
            
            // Skip leading whitespace
            while (start < length && Character.isWhitespace(trimmed.charAt(start))) {
                start++;
            }
            
            while (start < length && tokenIndex < tokenCount) {
                // Find end of token
                int end = start;
                while (end < length && !Character.isWhitespace(trimmed.charAt(end))) {
                    end++;
                }
                scopes[tokenIndex++] = trimmed.substring(start, end);
                
                // Skip whitespace to next token
                start = end;
                while (start < length && Character.isWhitespace(trimmed.charAt(start))) {
                    start++;
                }
            }
            
            return scopes;
        }
        
        long getExpiration() {
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
