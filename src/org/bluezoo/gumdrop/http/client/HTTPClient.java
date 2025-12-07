/*
 * HTTPClient.java
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

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPVersion;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;

/**
 * HTTP client implementation that creates and manages HTTP client connections.
 * 
 * <p>This class extends {@link Client} to provide HTTP-specific client functionality
 * for connecting to HTTP servers and creating connection instances that support
 * both HTTP/1.1 and HTTP/2 protocols.
 * 
 * <p>The client follows the same event-driven, non-blocking architecture as other
 * Gumdrop client implementations, with protocol-specific handlers receiving events
 * for connection lifecycle and stream-based request/response interactions.
 * 
 * <p><strong>Basic Usage:</strong>
 * <pre>
 * HTTPClient client = new HTTPClient("example.com", 80);
 * client.connect(new HTTPClientHandler() {
 *     public void onProtocolNegotiated(HTTPVersion version, HTTPClientConnection conn) {
 *         // Create streams after protocol is negotiated
 *         conn.createStream(); // Will trigger onStreamCreated
 *     }
 *     
 *     public void onStreamCreated(HTTPClientStream stream) {
 *         HTTPRequest request = new HTTPRequest("GET", "/api/data");
 *         stream.sendRequest(request);
 *     }
 *     
 *     public void onStreamResponse(HTTPClientStream stream, HTTPResponse response) {
 *         System.out.println("Status: " + response.getStatusCode());
 *     }
 *     
 *     // ... other handler methods
 * });
 * </pre>
 * 
 * <p><strong>Custom Stream Factory:</strong>
 * <pre>
 * HTTPClient client = new HTTPClient("example.com", 80);
 * client.setStreamFactory(new HTTPClientStreamFactory() {
 *     public HTTPClientStream createStream(int streamId, HTTPClientConnection connection) {
 *         return new MyCustomHTTPClientStream(streamId, connection);
 *     }
 * });
 * </pre>
 * 
 * <p><strong>HTTPS Support:</strong> Use the secure constructor for HTTPS connections:
 * <pre>
 * HTTPClient httpsClient = new HTTPClient("example.com", 443, true);
 * </pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler
 * @see HTTPClientConnection
 */
public class HTTPClient extends Client {

    private static final Logger logger = Logger.getLogger(HTTPClient.class.getName());

    protected static final int HTTP_DEFAULT_PORT = 80;
    protected static final int HTTPS_DEFAULT_PORT = 443;

    /**
     * Default factory for creating HTTPClientStream instances.
     */
    private static final HTTPClientStreamFactory DEFAULT_STREAM_FACTORY = new HTTPClientStreamFactory() {
        @Override
        public HTTPClientStream createStream(int streamId, HTTPClientConnection connection) {
            return new DefaultHTTPClientStream(streamId, connection);
        }
    };

    private HTTPClientStreamFactory streamFactory = DEFAULT_STREAM_FACTORY;
    private HTTPVersion preferredVersion = HTTPVersion.HTTP_2_0; // Default to HTTP/2
    private HTTPAuthenticationManager authenticationManager = new HTTPAuthenticationManager();

    /**
     * Creates an HTTP client that will connect to the specified host and port.
     * 
     * <p>This creates an insecure (HTTP) connection. For HTTPS connections,
     * use {@link #HTTPClient(String, int, boolean)} with secure=true.
     * 
     * @param host the HTTP server host as a String
     * @param port the HTTP server port number (typically 80 for HTTP, 443 for HTTPS)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(String host, int port) throws UnknownHostException {
        this(host, port, false);
    }

    /**
     * Creates an HTTP client that will connect to the specified host and port.
     * 
     * @param host the HTTP server host as a String
     * @param port the HTTP server port number (typically 80 for HTTP, 443 for HTTPS)
     * @param secure whether to use HTTPS (SSL/TLS) for the connection
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(String host, int port, boolean secure) throws UnknownHostException {
        super(host, port);
        this.secure = secure;
        
        // Use default ports if not specified
        if (port <= 0) {
            this.port = secure ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT;
        }
    }

    /**
     * Creates an HTTP client that will connect to the specified host and port.
     * 
     * @param host the HTTP server host as an InetAddress
     * @param port the HTTP server port number (typically 80 for HTTP, 443 for HTTPS)
     * @param secure whether to use HTTPS (SSL/TLS) for the connection
     */
    public HTTPClient(InetAddress host, int port, boolean secure) {
        super(host, port);
        this.secure = secure;
        
        // Use default ports if not specified
        if (port <= 0) {
            this.port = secure ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT;
        }
    }

    /**
     * Creates a new HTTP client connection with a handler.
     * 
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler to receive HTTP events
     * @return a new HTTPClientConnection instance
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler) {
        if (!(handler instanceof HTTPClientHandler)) {
            throw new IllegalArgumentException("HTTPClient requires an HTTPClientHandler");
        }
        
        return new HTTPClientConnection(this, channel, engine, secure, (HTTPClientHandler) handler);
    }

    /**
     * Initiates a type-safe HTTP connection with the specified handler.
     * 
     * <p>This method provides type safety by accepting only HTTPClientHandler
     * instances, avoiding the need for casting in client code.
     * 
     * @param handler the HTTP client handler to receive connection and stream events
     * @throws java.io.IOException if the connection cannot be initiated
     */
    public void connect(HTTPClientHandler handler) throws java.io.IOException {
        super.connect(handler);
    }

    /**
     * Returns the stream factory used to create HTTPClientStream instances.
     * 
     * @return the current stream factory
     */
    public HTTPClientStreamFactory getStreamFactory() {
        return streamFactory;
    }

    /**
     * Sets a custom stream factory for creating HTTPClientStream instances.
     *
     * <p>This allows users to provide custom stream implementations while
     * maintaining integration with the standard connection and handler architecture.
     *
     * <p><strong>Example:</strong>
     * <pre>
     * HTTPClient client = new HTTPClient("example.com", 80);
     * client.setStreamFactory(new HTTPClientStreamFactory() {
     *     public HTTPClientStream createStream(int streamId, HTTPClientConnection connection) {
     *         return new MyCustomHTTPClientStream(streamId, connection);
     *     }
     * });
     * </pre>
     *
     * @param streamFactory the factory to use for creating streams
     * @throws IllegalArgumentException if streamFactory is null
     */
    public void setStreamFactory(HTTPClientStreamFactory streamFactory) {
        if (streamFactory == null) {
            throw new IllegalArgumentException("Stream factory cannot be null");
        }
        this.streamFactory = streamFactory;
    }

    /**
     * Returns the preferred HTTP version for this client.
     *
     * @return the preferred HTTP version
     */
    public HTTPVersion getVersion() {
        return preferredVersion;
    }

    /**
     * Sets the preferred HTTP version for this client.
     *
     * <p>This determines protocol negotiation behavior:
     * <ul>
     * <li><strong>For TLS connections</strong>: Whether to include "h2" in ALPN negotiation</li>
     * <li><strong>For plaintext connections</strong>: Whether to attempt HTTP/2 cleartext (h2c) upgrade</li>
     * <li><strong>Default</strong>: {@link HTTPVersion#HTTP_2_0} (attempt HTTP/2)</li>
     * </ul>
     *
     * <p><strong>Examples:</strong>
     * <pre>
     * // Prefer HTTP/2 (default)
     * client.setVersion(HTTPVersion.HTTP_2_0);
     *
     * // Force HTTP/1.1 (no HTTP/2 negotiation)
     * client.setVersion(HTTPVersion.HTTP_1_1);
     *
     * // Allow HTTP/1.0 (minimal features)
     * client.setVersion(HTTPVersion.HTTP_1_0);
     * </pre>
     *
     * <p><strong>Note:</strong> The actual protocol used depends on server support.
     * If the server doesn't support the preferred version, the client will
     * negotiate the best available alternative.
     *
     * @param version the preferred HTTP version
     * @throws IllegalArgumentException if version is null or UNKNOWN
     */
    public void setVersion(HTTPVersion version) {
        if (version == null || version == HTTPVersion.UNKNOWN) {
            throw new IllegalArgumentException("HTTP version cannot be null or UNKNOWN");
        }
        this.preferredVersion = version;
    }

    @Override
    protected void configureSSLEngine(SSLEngine engine) {
        super.configureSSLEngine(engine); // Call parent for common configuration
        
        // Configure ALPN based on preferred HTTP version
        if (preferredVersion == HTTPVersion.HTTP_2_0) {
            // Prefer HTTP/2, fall back to HTTP/1.1
            setAlpnProtocols(engine, new String[]{"h2", "http/1.1"});
        } else if (preferredVersion == HTTPVersion.HTTP_1_1) {
            // Only HTTP/1.1
            setAlpnProtocols(engine, new String[]{"http/1.1"});
        } else if (preferredVersion == HTTPVersion.HTTP_1_0) {
            // HTTP/1.0 - no ALPN needed, will negotiate at HTTP level
            // Leave ALPN protocols empty
        }
    }

    /**
     * Sets ALPN protocols on the SSL engine.
     * This uses reflection to maintain compatibility across Java versions.
     */
    private void setAlpnProtocols(SSLEngine engine, String[] protocols) {
        try {
            // Use reflection to call SSLParameters.setApplicationProtocols()
            // This method exists in Java 9+ but we want to maintain compatibility
            javax.net.ssl.SSLParameters params = engine.getSSLParameters();
            
            // Try to get the setApplicationProtocols method
            java.lang.reflect.Method setProtocols = params.getClass().getMethod("setApplicationProtocols", String[].class);
            setProtocols.invoke(params, (Object) protocols);
            
            engine.setSSLParameters(params);
            
            logger.fine("ALPN protocols configured: " + java.util.Arrays.toString(protocols));
            
        } catch (Exception e) {
            // ALPN not supported on this Java version - will fall back to HTTP/1.1
            logger.warning("ALPN not supported: " + e.getMessage() + " (will use HTTP/1.1)");
        }
    }

    /**
     * Returns the authentication manager for this client.
     *
     * @return the authentication manager
     */
    public HTTPAuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    /**
     * Sets the authentication manager for this client.
     *
     * @param authenticationManager the authentication manager to use
     * @throws IllegalArgumentException if authenticationManager is null
     */
    public void setAuthenticationManager(HTTPAuthenticationManager authenticationManager) {
        if (authenticationManager == null) {
            throw new IllegalArgumentException("Authentication manager cannot be null");
        }
        this.authenticationManager = authenticationManager;
    }

    /**
     * Adds an authentication scheme to this client.
     *
     * <p>This is a convenience method that delegates to the authentication manager.
     * Authentication schemes are tried in the order they are added.
     *
     * @param authentication the authentication scheme to add
     * @throws IllegalArgumentException if authentication is null
     */
    public void setAuthentication(HTTPAuthentication authentication) {
        authenticationManager.clearAuthentications();
        authenticationManager.addAuthentication(authentication);
    }

    /**
     * Adds an authentication scheme to this client.
     *
     * <p>This is a convenience method that delegates to the authentication manager.
     * Multiple authentication schemes can be added for fallback support.
     *
     * @param authentication the authentication scheme to add
     * @throws IllegalArgumentException if authentication is null
     */
    public void addAuthentication(HTTPAuthentication authentication) {
        authenticationManager.addAuthentication(authentication);
    }

    /**
     * Removes all authentication schemes from this client.
     */
    public void clearAuthentication() {
        authenticationManager.clearAuthentications();
    }

    /**
     * Sets basic authentication credentials for this client.
     *
     * <p>This is a convenience method for simple username/password authentication.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @throws IllegalArgumentException if username or password is null
     */
    public void setBasicAuth(String username, String password) {
        setAuthentication(new BasicAuthentication(username, password));
    }

    /**
     * Sets bearer token authentication for this client.
     *
     * <p>This is a convenience method for token-based authentication.
     *
     * @param token the bearer token (access token, API key, JWT, etc.)
     * @throws IllegalArgumentException if token is null or empty
     */
    public void setBearerAuth(String token) {
        setAuthentication(new BearerAuthentication(token));
    }

    /**
     * Sets digest authentication credentials for this client.
     *
     * <p>This is a convenience method for digest authentication.
     * Note that digest authentication requires a server challenge before it can be used.
     *
     * @param username the username for authentication
     * @param password the password for authentication
     * @throws IllegalArgumentException if username or password is null
     */
    public void setDigestAuth(String username, String password) {
        setAuthentication(new DigestAuthentication(username, password));
    }

    @Override
    public String getDescription() {
        String protocol = secure ? "HTTPS" : "HTTP";
        String auth = authenticationManager.hasAuthentication() ? 
                     " [" + authenticationManager.getDescription() + "]" : "";
        return protocol + " Client (" + host.getHostAddress() + ":" + port + ", " + preferredVersion + ")" + auth;
    }
}
