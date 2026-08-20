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
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.HostsFile;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.http.client.AltSvcListener;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.bluezoo.gumdrop.websocket.PerMessageDeflateExtension;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

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
public class WebSocketClient implements AltSvcListener {

    private static final Logger LOGGER =
            Logger.getLogger(WebSocketClient.class.getName());

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    // Configuration (set before connect)
    private boolean secure;
    private boolean verifyPeer = true;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;
    private String subprotocol;
    private boolean deflateEnabled = true;
    private boolean h3Enabled;
    private boolean h2Enabled = true;
    private boolean h2WithPriorKnowledge;
    private boolean dnsHttpsRecordEnabled = true;
    private final List<WebSocketExtension> requestedExtensions = new ArrayList<>();

    // Internal transport components (created at connect time) -- TCP/H1.1/H2 path
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private WebSocketClientProtocolHandler protocolHandler;
    private WebSocketConnection h2WebSocketConnection;

    // Internal transport components (created at connect time) -- HTTP/3 path
    private HTTPClient httpClient;
    private WebSocketConnection h3WebSocketConnection;

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
     * Sets whether the server's TLS certificate is verified. Verified by
     * default; disabling this accepts any certificate (e.g. for a
     * self-signed test server) unless a specific {@link #setTrustManager}
     * is also set, which always takes precedence.
     *
     * @param verify false to accept any certificate
     */
    public void setVerifyPeer(boolean verify) {
        this.verifyPeer = verify;
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
     * RFC 9220 — forces WebSocket-over-HTTP/3 (Extended CONNECT over
     * QUIC), bypassing automatic transport negotiation.
     *
     * <p>By default (this not called), {@link #connect} negotiates the
     * transport automatically: a DNS HTTPS record advertising "h3" support
     * (see {@link #setDnsHttpsRecordEnabled(boolean)}), then a cached
     * Alt-Svc discovery ({@link AltSvcCache}), then the RFC 6455 HTTP/1.1
     * upgrade handshake. Calling this with {@code true} skips all of that
     * and uses Extended CONNECT directly, with no fallback. The
     * {@link WebSocketEventHandler}/{@link org.bluezoo.gumdrop.websocket.WebSocketSession}
     * contract {@link #connect} hands the application is identical either
     * way.
     *
     * @param enabled true to force HTTP/3
     */
    public void setH3Enabled(boolean enabled) {
        this.h3Enabled = enabled;
    }

    /**
     * RFC 8441 — enables or disables attempting WebSocket-over-HTTP/2
     * (Extended CONNECT) when the underlying TCP+TLS connection negotiates
     * "h2" via ALPN. Enabled by default.
     *
     * <p>Unlike {@link #setH3Enabled(boolean)}, this is not a forcing
     * override: h2 rides the same TCP+TLS connection attempt as HTTP/1.1
     * (there is no separate transport to discover in advance, unlike h3's
     * QUIC/UDP), so this only controls whether "h2" is offered in the ALPN
     * list at all. If the server doesn't support h2 (or this is disabled),
     * the connection falls back to the RFC 6455 HTTP/1.1 upgrade handshake
     * automatically. Has no effect when {@link #setH3Enabled(boolean)} is
     * set, or for cleartext (non-secure) connections, which have no ALPN
     * step at all -- see {@link #setH2WithPriorKnowledge(boolean)} for h2
     * over cleartext.
     *
     * @param enabled true to allow WebSocket-over-HTTP/2
     */
    public void setH2Enabled(boolean enabled) {
        this.h2Enabled = enabled;
    }

    /**
     * RFC 9113 §3.3 — forces HTTP/2 over a cleartext (non-secure)
     * connection with no negotiation at all: the client sends the h2
     * connection preface immediately and assumes the server already
     * speaks h2, by prior arrangement (matching
     * {@link HTTPClient#setH2WithPriorKnowledge(boolean)}, the equivalent
     * setting for plain HTTP requests). Combined with
     * {@link #setSecure(boolean)}{@code (false)}, this is what enables
     * WebSocket-over-h2c: {@code onConnected} branches on the negotiated
     * version the same way as the TLS+ALPN path, so once the preface is
     * sent, RFC 8441 Extended CONNECT proceeds exactly as it would over a
     * secure h2 connection.
     *
     * <p>This is deliberately the <em>only</em> supported route to h2c.
     * The older HTTP/1.1-{@code Upgrade}-header bootstrap for h2c (RFC
     * 7540 §3.2) is deprecated by RFC 9113 §3.1 itself ("This usage was
     * never widely deployed and is deprecated by this document") and is
     * not implemented here for WebSocket: its first request must be a
     * plain HTTP/1.1 request distinct from the eventual Extended CONNECT,
     * which doesn't compose with also bootstrapping a WebSocket handshake
     * in the same exchange, and building it would mean investing in a
     * mechanism the current spec itself disclaims -- this class has no
     * WebSocket equivalent of {@link org.bluezoo.gumdrop.http.client
     * .HTTPClientProtocolHandler#setH2cUpgradeEnabled} for that reason
     * and never will; prior knowledge is the supported cleartext path.
     *
     * <p>Has no effect when {@link #setH3Enabled(boolean)} is set, or for
     * secure connections (which negotiate h2 via ALPN instead, see
     * {@link #setH2Enabled(boolean)}).
     *
     * @param enabled true to force HTTP/2 over cleartext with no negotiation
     */
    public void setH2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
    }

    /**
     * Enables or disables DNS HTTPS-record discovery (RFC 9460) of HTTP/3
     * support, checked before connecting.
     *
     * <p>When enabled (the default), {@link #connect} queries an HTTPS
     * record for the target host via gumdrop's async {@link DNSResolver}
     * before choosing a transport; if it advertises "h3" ALPN support, the
     * connection uses Extended CONNECT over QUIC directly. This is the
     * first tier of automatic negotiation, checked ahead of the
     * {@link AltSvcCache}.
     *
     * @param enabled true to enable DNS HTTPS-record discovery
     */
    public void setDnsHttpsRecordEnabled(boolean enabled) {
        this.dnsHttpsRecordEnabled = enabled;
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
        if (h3Enabled) {
            connectH3(path, handler);
            return;
        }
        discoverAndConnect(path, handler);
    }

    /**
     * Automatic transport negotiation, tier 1 (DNS HTTPS record) and tier 2
     * (cached Alt-Svc discovery), falling through to {@link #connectTcp}
     * (the RFC 6455 HTTP/1.1 upgrade handshake) when neither applies.
     *
     * <p>Skipped entirely -- straight to {@link #connectTcp} -- when there
     * is no hostname to query: a literal {@link InetAddress} was given at
     * construction, {@link #host} is itself a literal IP, or it's
     * {@code localhost} (matching {@link DNSResolver#resolve}'s own
     * loopback fast-path).
     */
    private void discoverAndConnect(final String path, final WebSocketEventHandler handler) {
        if (hostAddress != null || host == null) {
            connectTcp(path, handler);
            return;
        }
        if (!dnsHttpsRecordEnabled || isUndiscoverableHost(host)) {
            // Skip only the DNS round trip -- the AltSvcCache tier is a
            // fast, in-memory lookup, worth checking even for localhost/
            // literal-IP targets.
            connectViaAltSvcCacheOrTcp(path, handler);
            return;
        }

        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            gumdrop.start();
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            connectTcp(path, handler);
            return;
        }

        DNSResolver resolver = DNSResolver.forLoop(loop);
        resolver.queryHTTPS(host, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                for (DNSResourceRecord rr : response.getAnswers()) {
                    if (rr.getType() != DNSType.HTTPS || rr.isSVCBAliasForm()) {
                        continue;
                    }
                    if (rr.getSVCBAlpnProtocols().contains("h3")) {
                        connectH3(path, handler);
                        return;
                    }
                }
                connectViaAltSvcCacheOrTcp(path, handler);
            }

            @Override
            public void onError(String error) {
                connectViaAltSvcCacheOrTcp(path, handler);
            }
        });
    }

    private void connectViaAltSvcCacheOrTcp(String path, WebSocketEventHandler handler) {
        if (AltSvcCache.get(host, port) != null) {
            connectH3(path, handler);
            return;
        }
        connectTcp(path, handler);
    }

    /**
     * Returns true if {@code hostname} isn't worth issuing a DNS HTTPS-record
     * query for: a literal IPv4/IPv6 address, or loopback.
     */
    private static boolean isUndiscoverableHost(String hostname) {
        if ("localhost".equalsIgnoreCase(hostname)
                || "localhost.".equalsIgnoreCase(hostname)) {
            return true;
        }
        return HostsFile.parseLiteralIPv4(hostname) != null
                || HostsFile.parseLiteralIPv6(hostname) != null;
    }

    /**
     * The TCP+TLS path, today's default behaviour before
     * {@link #discoverAndConnect} existed. Negotiates HTTP/2 via ALPN when
     * {@link #setH2Enabled(boolean)} allows it (the default) and the
     * connection is secure, and uses RFC 8441 Extended CONNECT over it;
     * otherwise falls back to the RFC 6455 HTTP/1.1 upgrade handshake.
     * Both outcomes are decided from {@code onConnected}, once
     * {@code negotiatedVersion} is known (reliably true there for both
     * secure and cleartext connections, per
     * {@code HTTPClientProtocolHandler.connected()}/{@code securityEstablished()}).
     *
     * @param path the request path (e.g. "/ws" or "/chat")
     * @param handler the handler to receive WebSocket events
     */
    private void connectTcp(String path, final WebSocketEventHandler handler) {
        final String key = WebSocketHandshake.generateKey();

        // RFC 6455 §9 — build extension offer list
        final List<WebSocketExtension> allExtensions = buildExtensionOffers();
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
        } else if (!verifyPeer) {
            transportFactory.setTrustManager(new EmptyX509TrustManager());
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
        // RFC 8441 rides the same TCP+TLS attempt as HTTP/1.1 -- offer h2
        // via ALPN (mirroring HTTPClient.connectTcp's own offer) so the
        // already-negotiated version is known by the time onConnected
        // fires below, with no separate discovery tier needed the way h3
        // needs one. Prior knowledge (see setH2WithPriorKnowledge) is a
        // cleartext path and does not use ALPN.
        if (secure && h2Enabled && !h2WithPriorKnowledge) {
            transportFactory.setApplicationProtocols("h2", "http/1.1");
        }
        transportFactory.start();

        HTTPClientHandler internalHandler = new HTTPClientHandler() {

            @Override
            public void onConnected(Endpoint endpoint) {
                if (protocolHandler.getVersion() == HTTPVersion.HTTP_2_0) {
                    // RFC 8441 section 4: must not attempt Extended CONNECT
                    // before knowing the server advertised support for it --
                    // which, unlike this onConnected callback itself, isn't
                    // known until the server's own (asynchronous) initial
                    // SETTINGS frame arrives.
                    protocolHandler.whenConnectProtocolKnown(new Runnable() {
                        @Override
                        public void run() {
                            if (!protocolHandler.isConnectProtocolEnabled()) {
                                handler.error(new IOException("Server does not support Extended CONNECT "
                                        + "(RFC 8441): SETTINGS_ENABLE_CONNECT_PROTOCOL was not advertised"));
                                return;
                            }
                            connectExtendedConnect(path, allExtensions, handler);
                        }
                    });
                    return;
                }
                // RFC 6455 §4.1 -- classic HTTP/1.1 upgrade handshake
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

        protocolHandler.setH2Enabled(h2Enabled);
        if (h2WithPriorKnowledge) {
            protocolHandler.setH2WithPriorKnowledge(true);
        }
        // The HTTP/1.1-Upgrade-header h2c bootstrap has no WebSocket
        // equivalent -- always disabled, regardless of h2Enabled (which
        // only governs the TLS+ALPN path above); see
        // setH2WithPriorKnowledge's javadoc for why.
        protocolHandler.setH2cUpgradeEnabled(false);

        // Populate AltSvcCache for later connections to this origin (this
        // session itself never reactively upgrades mid-connection -- see
        // altSvcReceived).
        protocolHandler.setAltSvcListener(this);

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
     * RFC 6455 §9 — builds the extension offer list from the requested
     * extensions plus permessage-deflate, if enabled. Shared by both the
     * HTTP/1.1 and HTTP/3 connect paths.
     */
    private List<WebSocketExtension> buildExtensionOffers() {
        List<WebSocketExtension> allExtensions = new ArrayList<>(requestedExtensions);
        if (deflateEnabled) {
            allExtensions.add(0, new PerMessageDeflateExtension());
        }
        return allExtensions;
    }

    /**
     * Populates {@link AltSvcCache} for later, separate {@code connect()}
     * calls (from this class or {@link HTTPClient}) to the same origin.
     *
     * <p>Unlike {@link HTTPClient}, this does not attempt a same-instance
     * reactive upgrade -- a WebSocket connection is one long-lived stream,
     * not a reusable request/response client, so there is no "next
     * request" on this instance to upgrade.
     *
     * @param value the raw Alt-Svc header value
     */
    @Override
    public void altSvcReceived(String value) {
        AltSvcListener.H3Entry parsed = AltSvcListener.parseAltSvcH3(value);
        if (parsed == null) {
            return;
        }
        String altHost = parsed.hostLength > 0
                ? AltSvcListener.extractAltSvcHost(value, parsed.hostLength) : null;
        AltSvcCache.put(cacheKeyHost(), port, altHost, parsed.port, parsed.maxAgeSeconds);
    }

    private String cacheKeyHost() {
        return host != null ? host : hostAddress.getHostAddress();
    }

    /**
     * RFC 8441 — sends the Extended CONNECT request that bootstraps
     * WebSocket-over-HTTP/2 on the already-established (h2-negotiated)
     * connection, via {@link H2WebSocketResponseHandler}.
     *
     * <p>Builds the request through the same generic {@link HTTPRequest}
     * API any other h2 request uses -- {@code :protocol} is just another
     * header from this layer's perspective (added before any regular
     * header, so it's HPACK-encoded in the correct pseudo-header position);
     * no dedicated stream-open method was needed in
     * {@code HTTPClientProtocolHandler} for this.
     */
    private void connectExtendedConnect(String path,
            List<WebSocketExtension> allExtensions, final WebSocketEventHandler handler) {
        HTTPRequest request = protocolHandler.request("CONNECT", path);
        request.header(":protocol", "websocket");
        if (subprotocol != null && !subprotocol.isEmpty()) {
            request.header("sec-websocket-protocol", subprotocol);
        }
        String extOffer = WebSocketHandshake.formatOffers(allExtensions);
        if (extOffer != null && !extOffer.isEmpty()) {
            request.header("sec-websocket-extensions", extOffer);
        }
        request.startRequestBody(new H2WebSocketResponseHandler(
                request, allExtensions, new H2WebSocketEventHandlerBridge(handler)));
    }

    /**
     * Forwards {@link WebSocketEventHandler} callbacks to the
     * application's handler, capturing the {@link WebSocketConnection}
     * once the upgrade completes -- the h2 counterpart of
     * {@link H3WebSocketEventHandlerBridge}.
     */
    private class H2WebSocketEventHandlerBridge implements WebSocketEventHandler {

        private final WebSocketEventHandler handler;

        H2WebSocketEventHandlerBridge(WebSocketEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(WebSocketSession session) {
            if (session instanceof WebSocketConnection) {
                h2WebSocketConnection = (WebSocketConnection) session;
            }
            handler.opened(session);
        }

        @Override
        public void textMessageReceived(WebSocketSession session, String message) {
            handler.textMessageReceived(session, message);
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
            handler.binaryMessageReceived(session, data);
        }

        @Override
        public void closed(int code, String reason) {
            handler.closed(code, reason);
        }

        @Override
        public void error(Throwable cause) {
            handler.error(cause);
        }
    }

    /**
     * RFC 9220 — connects and initiates the WebSocket handshake over
     * HTTP/3 Extended CONNECT, via an internally-managed {@link HTTPClient}.
     */
    private void connectH3(String path, final WebSocketEventHandler handler) {
        final List<WebSocketExtension> allExtensions = buildExtensionOffers();

        if (host != null) {
            httpClient = (selectorLoop != null)
                    ? new HTTPClient(selectorLoop, host, port) : new HTTPClient(host, port);
        } else {
            httpClient = (selectorLoop != null)
                    ? new HTTPClient(selectorLoop, hostAddress, port) : new HTTPClient(hostAddress, port);
        }
        httpClient.setH3Enabled(true);
        // Note: HTTPClient's QUIC/H3 path (unlike its TCP/H1.1 path)
        // doesn't consult a custom X509TrustManager at all today, only
        // verifyPeer -- trustManager/keystoreFile are therefore not
        // wired through here; a follow-up alongside HTTPClient's own gap.
        httpClient.setVerifyPeer(verifyPeer);

        httpClient.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                httpClient.connectWebSocket(path, subprotocol, allExtensions,
                        new H3WebSocketEventHandlerBridge(handler));
            }

            @Override
            public void onError(Exception cause) {
                handler.error(cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
    }

    /**
     * Forwards {@link WebSocketEventHandler} callbacks to the
     * application's handler, capturing the {@link WebSocketConnection}
     * (the same object also implements {@code WebSocketSession}, exactly
     * like the server-side adapter) once the upgrade completes, so
     * {@link #isOpen}/{@link #close}/{@link #getConnection} work the same
     * way for the HTTP/3 path as they already do for HTTP/1.1's
     * {@code WebSocketClientProtocolHandler#getWebSocketConnection}.
     */
    private class H3WebSocketEventHandlerBridge implements WebSocketEventHandler {

        private final WebSocketEventHandler handler;

        H3WebSocketEventHandlerBridge(WebSocketEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(WebSocketSession session) {
            if (session instanceof WebSocketConnection) {
                h3WebSocketConnection = (WebSocketConnection) session;
            }
            handler.opened(session);
        }

        @Override
        public void textMessageReceived(WebSocketSession session, String message) {
            handler.textMessageReceived(session, message);
        }

        @Override
        public void binaryMessageReceived(WebSocketSession session, ByteBuffer data) {
            handler.binaryMessageReceived(session, data);
        }

        @Override
        public void closed(int code, String reason) {
            handler.closed(code, reason);
        }

        @Override
        public void error(Throwable cause) {
            handler.error(cause);
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
        if (h3WebSocketConnection != null) {
            return h3WebSocketConnection;
        }
        if (h2WebSocketConnection != null) {
            return h2WebSocketConnection;
        }
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
