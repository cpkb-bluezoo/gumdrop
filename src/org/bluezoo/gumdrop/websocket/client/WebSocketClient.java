/*
 * WebSocketClient.java
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

package org.bluezoo.gumdrop.websocket.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;

/**
 * High-level WebSocket client facade.
 *
 * <p>Provides a simple API for connecting to a WebSocket server. The
 * handler interface is the same {@link WebSocketEventHandler} used on the
 * server side, so application code can be reused in both roles.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * WebSocketClient client = new WebSocketClient("echo.example.com", 443);
 * client.setSecure(true);
 * client.connect("/ws", new DefaultWebSocketEventHandler() {
 *
 *     public void opened(WebSocketSession session) {
 *         session.sendText("Hello!");
 *     }
 *
 *     public void textMessageReceived(WebSocketSession session,
 *                                     String message) {
 *         System.out.println("Received: " + message);
 *     }
 *
 *     public void closed(int code, String reason) {
 *         System.out.println("Closed: " + code);
 *     }
 *
 *     public void error(Throwable cause) {
 *         cause.printStackTrace();
 *     }
 * });
 * }</pre>
 *
 * <h4>With explicit SelectorLoop (server integration)</h4>
 * <pre>{@code
 * WebSocketClient client = new WebSocketClient(selectorLoop,
 *         "echo.example.com", 443);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see WebSocketEventHandler
 * @see org.bluezoo.gumdrop.websocket.WebSocketSession
 */
public class WebSocketClient {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketClient.class.getName());

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    // Configuration (set before connect)
    private boolean secure;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;
    private String subprotocol;
    private boolean deflateEnabled = true;
    private final List<WebSocketExtension> requestedExtensions = new ArrayList<>();

    // Internal transport components (created at connect time)
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private WebSocketClientProtocolHandler protocolHandler;

    /**
     * Creates a WebSocket client for the given host and port.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public WebSocketClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a WebSocket client with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public WebSocketClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
    }

    /**
     * Creates a WebSocket client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public WebSocketClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a WebSocket client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote host address
     * @param port the remote port
     */
    public WebSocketClient(SelectorLoop selectorLoop, InetAddress host,
                           int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses TLS (wss:// scheme).
     * RFC 6455 §11.1.2 defines the "wss" URI scheme for secure WebSocket.
     *
     * @param secure true for TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets an externally-configured SSL context.
     *
     * @param context the SSL context
     */
    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * @param trustManager the trust manager, or null to use defaults
     * @see org.bluezoo.gumdrop.util.PinnedCertTrustManager
     * @see org.bluezoo.gumdrop.util.EmptyX509TrustManager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    /**
     * Sets the keystore file for client certificate authentication.
     *
     * @param path the keystore file path
     */
    public void setKeystoreFile(Path path) {
        this.keystoreFile = path;
    }

    public void setKeystoreFile(String path) {
        this.keystoreFile = Path.of(path);
    }

    /**
     * Sets the keystore password.
     *
     * @param password the keystore password
     */
    public void setKeystorePass(String password) {
        this.keystorePass = password;
    }

    /**
     * Sets the keystore format (e.g. JKS, PKCS12).
     *
     * @param format the keystore format
     */
    public void setKeystoreFormat(String format) {
        this.keystoreFormat = format;
    }

    /**
     * RFC 6455 §4.1 — sets the WebSocket subprotocol to include in
     * the {@code Sec-WebSocket-Protocol} header during the handshake.
     *
     * @param subprotocol the subprotocol name (e.g. "graphql-ws")
     */
    public void setSubprotocol(String subprotocol) {
        this.subprotocol = subprotocol;
    }

    /**
     * RFC 7692 — enables or disables permessage-deflate for this client.
     * Enabled by default.
     *
     * @param enabled true to request permessage-deflate
     */
    public void setDeflateEnabled(boolean enabled) {
        this.deflateEnabled = enabled;
    }

    /**
     * RFC 6455 §9 — adds a custom extension to request during the handshake.
     *
     * @param extension the extension to request
     */
    public void addExtension(WebSocketExtension extension) {
        this.requestedExtensions.add(extension);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 6455 §4.1 — connects to the WebSocket server and initiates the
     * client opening handshake. On a valid 101 Switching Protocols response,
     * the connection transitions to WebSocket mode and the handler receives
     * {@link WebSocketEventHandler#opened}.
     *
     * @param path the request path (e.g. "/ws" or "/chat")
     * @param handler the handler to receive WebSocket events
     */
    public void connect(String path, final WebSocketEventHandler handler) {
        final String key = WebSocketHandshake.generateKey();

        // RFC 6455 §9 — build extension offer list
        List<WebSocketExtension> allExtensions = new ArrayList<>(requestedExtensions);
        if (deflateEnabled) {
            allExtensions.add(0, new PerMessageDeflateExtension());
        }
        String extOffer = WebSocketHandshake.formatOffers(allExtensions);

        final Headers upgradeHeaders =
                WebSocketHandshake.createUpgradeRequest(key, subprotocol, extOffer);

        transportFactory = new TCPTransportFactory();
        transportFactory.setSecure(secure);
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        if (trustManager != null) {
            transportFactory.setTrustManager(trustManager);
        }
        if (keystoreFile != null) {
            transportFactory.setKeystoreFile(keystoreFile);
        }
        if (keystorePass != null) {
            transportFactory.setKeystorePass(keystorePass);
        }
        if (keystoreFormat != null) {
            transportFactory.setKeystoreFormat(keystoreFormat);
        }
        transportFactory.start();

        HTTPClientHandler internalHandler = new HTTPClientHandler() {

            @Override
            public void onConnected(Endpoint endpoint) {
                // Send the upgrade GET request
                HTTPRequest request = protocolHandler.get(path);
                for (Header h : upgradeHeaders) {
                    request.header(h.getName(), h.getValue());
                }
                request.send(new UpgradeResponseHandler(handler));
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // TLS handshake complete; connection proceeds to onConnected
            }

            @Override
            public void onError(Exception cause) {
                handler.error(cause);
            }

            @Override
            public void onDisconnected() {
                // Handled by WebSocketClientProtocolHandler.disconnected()
            }
        };

        protocolHandler = new WebSocketClientProtocolHandler(
                internalHandler, handler, host, port, secure);
        protocolHandler.setWebSocketKey(key);
        protocolHandler.setRequestedExtensions(allExtensions);

        // Disable h2c upgrade: the 101 must be for WebSocket, not HTTP/2
        protocolHandler.setH2Enabled(false);
        protocolHandler.setH2cUpgradeEnabled(false);

        try {
            if (host != null) {
                if (selectorLoop != null) {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, selectorLoop,
                            host, port);
                } else {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, host, port);
                }
            } else {
                if (selectorLoop != null) {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, selectorLoop,
                            hostAddress, port);
                } else {
                    clientEndpoint = new ClientEndpoint(
                            transportFactory, hostAddress, port);
                }
            }
            clientEndpoint.connect(protocolHandler);
        } catch (IOException e) {
            handler.error(e);
        }
    }

    /**
     * Returns whether the WebSocket connection is open.
     *
     * @return true if connected and in WebSocket mode
     */
    public boolean isOpen() {
        WebSocketConnection conn = getConnection();
        return conn != null && conn.isOpen();
    }

    /**
     * Closes the WebSocket connection gracefully and deregisters from
     * Gumdrop's lifecycle tracking.
     *
     * <p>Sends a close frame with code 1000 (normal closure), then
     * shuts down the underlying transport.
     */
    public void close() {
        WebSocketConnection conn = getConnection();
        if (conn != null) {
            try {
                conn.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error during WebSocket close", e);
            }
        }
        if (protocolHandler != null) {
            protocolHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

    /**
     * Returns the underlying WebSocket connection, or null if the
     * upgrade has not yet completed.
     *
     * @return the WebSocket connection
     */
    private WebSocketConnection getConnection() {
        if (protocolHandler != null) {
            return protocolHandler.getWebSocketConnection();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upgrade response handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimal response handler for the upgrade request. In the normal
     * case, the 101 response is intercepted by
     * {@link WebSocketClientProtocolHandler#handleProtocolSwitch} before
     * any of these callbacks fire. This handler only exists to catch
     * non-101 responses (server refused the upgrade) and errors.
     */
    private static class UpgradeResponseHandler
            extends DefaultHTTPResponseHandler {

        private final WebSocketEventHandler handler;

        UpgradeResponseHandler(WebSocketEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void ok(HTTPResponse response) {
            // A 2xx response means the server did not upgrade
            handler.error(new IOException(
                    "Server did not upgrade to WebSocket: "
                    + response.getStatus()));
        }

        @Override
        public void error(HTTPResponse response) {
            handler.error(new IOException(
                    "WebSocket upgrade failed: " + response.getStatus()));
        }

        @Override
        public void failed(Exception ex) {
            handler.error(ex);
        }
    }
}
