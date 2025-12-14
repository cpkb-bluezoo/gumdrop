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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.Client;
import org.bluezoo.gumdrop.ClientHandler;
import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HTTPVersion;

/**
 * HTTP client implementation that creates and manages HTTP connections.
 *
 * <p>This class extends {@link Client} to provide HTTP-specific client
 * functionality for connecting to HTTP servers, creating connections,
 * and making HTTP requests.
 *
 * <p>An HTTPClient represents a connection (or pool of connections) to a single
 * server. It provides factory methods for creating requests, manages connection
 * state, and supports both HTTP/1.1 and HTTP/2 protocols.
 *
 * <h3>Simple Usage</h3>
 *
 * <p>For most use cases, simply create the client and make requests. The connection
 * is established automatically when the first request is made:
 * <pre>{@code
 * HTTPClient client = new HTTPClient("api.example.com", 443);
 * client.setSecure(true);
 *
 * client.get("/users").send(new DefaultHTTPResponseHandler() {
 *     public void ok(HTTPResponse response) {
 *         System.out.println("Success: " + response.getStatus());
 *     }
 *     public void responseBodyContent(ByteBuffer data) {
 *         // Process response body
 *     }
 *     public void close() {
 *         client.close();
 *     }
 *     public void failed(Exception ex) {
 *         // Connection or request error
 *         ex.printStackTrace();
 *     }
 * });
 * }</pre>
 *
 * <h3>With Connection Events</h3>
 *
 * <p>If you need to receive connection lifecycle events (e.g., to log TLS
 * negotiation or handle disconnects), use {@link #connect(HTTPClientHandler)}:
 * <pre>{@code
 * HTTPClient client = new HTTPClient("api.example.com", 443);
 * client.setSecure(true);
 * client.connect(new HTTPClientHandler() {
 *     public void onConnected(ConnectionInfo info) {
 *         client.get("/users").send(responseHandler);
 *     }
 *     public void onTLSStarted(TLSInfo info) {
 *         System.out.println("TLS: " + info.getProtocol());
 *     }
 *     public void onError(Exception e) { e.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * <h3>Server Integration (with SelectorLoop affinity)</h3>
 * <pre>{@code
 * // Use the same SelectorLoop as your server connection for efficiency
 * HTTPClient client = new HTTPClient(connection.getSelectorLoop(), "api.example.com", 443);
 * client.setSecure(true);
 * client.get("/data").send(handler);
 * }</pre>
 *
 * <h3>POST with Request Body</h3>
 * <pre>{@code
 * HTTPRequest request = client.post("/api/users");
 * request.header("Content-Type", "application/json");
 * request.startRequestBody(handler);
 * request.requestBodyContent(ByteBuffer.wrap(jsonBytes));
 * request.endRequestBody();
 * }</pre>
 *
 * <h3>HTTP/2 Concurrent Requests</h3>
 *
 * <p>When connected via HTTP/2, multiple requests can be in flight simultaneously:
 * <pre>{@code
 * // Check if multiplexing is supported
 * if (client.getVersion() != null &amp;&amp; client.getVersion().supportsMultiplexing()) {
 *     // Fire off multiple requests concurrently
 *     for (String path : paths) {
 *         client.get(path).send(handler);
 *     }
 * }
 * }</pre>
 *
 * <h3>Authentication</h3>
 * <pre>{@code
 * client.credentials("admin", "secret");
 * client.get("/protected/resource").send(handler);
 * // If server returns 401, client automatically retries with auth
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPClientHandler
 * @see HTTPRequest
 * @see HTTPResponseHandler
 */
public class HTTPClient extends Client {

    /** Default HTTP port. */
    public static final int DEFAULT_PORT = 80;

    /** Default HTTPS port. */
    public static final int DEFAULT_SECURE_PORT = 443;

    // The current connection (single connection mode)
    private HTTPClientConnection connection;

    // Authentication credentials
    private String username;
    private String password;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors without SelectorLoop (standalone usage)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an HTTP client that will connect to the specified host and port.
     *
     * <p>The Gumdrop infrastructure is managed automatically - it starts
     * when {@link #connect} is called and stops when all connections close.
     *
     * @param host the HTTP server host as a String
     * @param port the HTTP server port number (typically 80 or 443)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(String host, int port) throws UnknownHostException {
        super(host, port);
    }

    /**
     * Creates an HTTP client that will connect to the default port (80).
     *
     * @param host the HTTP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(String host) throws UnknownHostException {
        super(host, DEFAULT_PORT);
    }

    /**
     * Creates an HTTP client that will connect to the specified host and port.
     *
     * @param host the HTTP server host as an InetAddress
     * @param port the HTTP server port number (typically 80 or 443)
     */
    public HTTPClient(InetAddress host, int port) {
        super(host, port);
    }

    /**
     * Creates an HTTP client that will connect to the default port (80).
     *
     * @param host the HTTP server host as an InetAddress
     */
    public HTTPClient(InetAddress host) {
        super(host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors with SelectorLoop (server integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates an HTTP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where you want the
     * client to share a SelectorLoop with server connections for efficiency.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the HTTP server host as a String
     * @param port the HTTP server port number (typically 80 or 443)
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(SelectorLoop selectorLoop, String host, int port) throws UnknownHostException {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an HTTP client that will connect to the default port (80),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the HTTP server host as a String
     * @throws UnknownHostException if the host cannot be resolved
     */
    public HTTPClient(SelectorLoop selectorLoop, String host) throws UnknownHostException {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    /**
     * Creates an HTTP client that will connect to the specified host and port,
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the HTTP server host as an InetAddress
     * @param port the HTTP server port number (typically 80 or 443)
     */
    public HTTPClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }

    /**
     * Creates an HTTP client that will connect to the default port (80),
     * using the provided SelectorLoop for I/O.
     *
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the HTTP server host as an InetAddress
     */
    public HTTPClient(SelectorLoop selectorLoop, InetAddress host) {
        super(selectorLoop, host, DEFAULT_PORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new HTTP client connection with a handler.
     *
     * @param channel the socket channel for the connection
     * @param engine optional SSL engine for secure connections
     * @param handler the client handler (must be HTTPClientHandler)
     * @return a new HTTPClientConnection instance
     * @throws ClassCastException if handler is not an HTTPClientHandler
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine, ClientHandler handler) {
        HTTPClientConnection conn = new HTTPClientConnection(this, channel, engine, secure,
                (HTTPClientHandler) handler);
        this.connection = conn;

        // Transfer credentials to connection
        if (username != null) {
            conn.credentials(username, password);
        }

        return conn;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request Factory Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a GET request for the specified path.
     *
     * @param path the request path (e.g., "/api/users")
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest get(String path) {
        return getConnection().get(path);
    }

    /**
     * Creates a POST request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest post(String path) {
        return getConnection().post(path);
    }

    /**
     * Creates a PUT request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest put(String path) {
        return getConnection().put(path);
    }

    /**
     * Creates a DELETE request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest delete(String path) {
        return getConnection().delete(path);
    }

    /**
     * Creates a HEAD request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest head(String path) {
        return getConnection().head(path);
    }

    /**
     * Creates an OPTIONS request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest options(String path) {
        return getConnection().options(path);
    }

    /**
     * Creates a PATCH request for the specified path.
     *
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest patch(String path) {
        return getConnection().patch(path);
    }

    /**
     * Creates a request with a custom HTTP method.
     *
     * <p>Use this for non-standard methods or methods not covered by the
     * convenience methods (e.g., "PROPFIND" for WebDAV).
     *
     * @param method the HTTP method
     * @param path the request path
     * @return a new request
     * @throws IllegalStateException if not connected
     */
    public HTTPRequest request(String method, String path) {
        return getConnection().request(method, path);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authentication
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets credentials for HTTP authentication.
     *
     * <p>When credentials are configured, the client handles authentication
     * challenges (401/407 responses) automatically:
     * <ol>
     * <li>If the server responds with 401, the client examines the
     *     {@code WWW-Authenticate} header</li>
     * <li>The client computes the appropriate authorization (Basic or Digest)</li>
     * <li>The request is automatically retried with the authorization header</li>
     * <li>The handler only receives the final response (success or failure)</li>
     * </ol>
     *
     * <p>Example:
     * <pre>{@code
     * client.credentials("admin", "secret");
     * client.get("/protected/resource").send(handler);
     * // If server returns 401, client automatically retries with auth
     * }</pre>
     *
     * @param username the username
     * @param password the password
     */
    public void credentials(String username, String password) {
        this.username = username;
        this.password = password;
        if (connection != null) {
            connection.credentials(username, password);
        }
    }

    /**
     * Clears any configured credentials.
     *
     * <p>After calling this method, authentication challenges will not be
     * handled automatically.
     */
    public void clearCredentials() {
        this.username = null;
        this.password = null;
        if (connection != null) {
            connection.clearCredentials();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the client is connected and can accept new requests.
     *
     * <p>Returns false if not connected, after the client is closed,
     * after receiving a GOAWAY frame (HTTP/2), or after a fatal connection error.
     *
     * @return true if the client is open and connected
     */
    public boolean isOpen() {
        return connection != null && connection.isOpen();
    }

    /**
     * Returns the negotiated HTTP version, or null if not yet connected.
     *
     * <p>For TLS connections, the version is determined by ALPN negotiation
     * during the TLS handshake. For plaintext connections, it depends on
     * HTTP/2 upgrade negotiation or the server's response.
     *
     * <p>Use this to check if multiplexing is available:
     * <pre>{@code
     * if (client.getVersion() != null && client.getVersion().supportsMultiplexing()) {
     *     // Safe to make concurrent requests
     * }
     * }</pre>
     *
     * @return the HTTP version, or null if not yet known
     */
    public HTTPVersion getVersion() {
        if (connection == null) {
            return null;
        }
        return connection.getVersion();
    }

    /**
     * Returns the current connection, or null if not connected.
     *
     * <p>This method provides access to the underlying connection for
     * advanced usage. Most applications should use the request factory
     * methods directly on this client instead.
     *
     * @return the current connection, or null
     */
    public HTTPClientConnection getActiveConnection() {
        return connection;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Closes the client gracefully.
     *
     * <p>For HTTP/2, this sends a GOAWAY frame allowing outstanding requests
     * to complete. For HTTP/1.x, this closes the connection after any
     * in-progress request completes.
     *
     * <p>Any requests that have not yet received a response will have their
     * handler's {@link HTTPResponseHandler#failed(Exception)} method called.
     */
    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current connection, connecting if necessary.
     *
     * <p>If not already connected, this method initiates a connection with
     * a null handler. Any connection errors will be delivered to the
     * response handler when a request is made.
     *
     * @return the connection
     * @throws IllegalStateException if connection cannot be initiated
     */
    private HTTPClientConnection getConnection() {
        if (connection == null) {
            try {
                connect(null);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to connect: " + e.getMessage(), e);
            }
        }
        return connection;
    }

    @Override
    public String getDescription() {
        return "HTTP Client (" + host.getHostAddress() + ":" + port + ")";
    }

}
