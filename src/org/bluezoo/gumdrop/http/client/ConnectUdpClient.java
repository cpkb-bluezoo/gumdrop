/*
 * ConnectUdpClient.java
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

package org.bluezoo.gumdrop.http.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;

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
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.ConnectUdpTarget;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * High-level RFC 9298 (Proxying UDP in HTTP) CONNECT-UDP client facade.
 *
 * <p>Provides a simple API for opening a UDP tunnel through an HTTP
 * proxy. The handler interface is {@link ConnectUdpEventHandler} --
 * structurally the CONNECT-UDP counterpart of {@code
 * org.bluezoo.gumdrop.websocket.client.WebSocketClient}, whose transport
 * negotiation (DNS HTTPS-record discovery, cached Alt-Svc, HTTP/2 ALPN,
 * HTTP/1.1 fallback) this class mirrors exactly -- the only difference is
 * what happens once a transport is chosen: an Extended CONNECT or HTTP
 * Upgrade request shaped by RFC 9298 (target host/port encoded into the
 * path, {@code Capsule-Protocol: ?1}) rather than RFC 6455/8441/9220's
 * WebSocket handshake.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * ConnectUdpClient client = new ConnectUdpClient("proxy.example.com", 443);
 * client.setSecure(true);
 * client.connect("target.example.com", 53, new ConnectUdpEventHandler() {
 *
 *     public void opened(ConnectUdpSession session) {
 *         session.sendDatagram(ByteBuffer.wrap(dnsQuery));
 *     }
 *
 *     public void datagramReceived(ByteBuffer payload) {
 *         System.out.println("Received " + payload.remaining() + " bytes");
 *     }
 *
 *     public void closed() {
 *         System.out.println("Closed");
 *     }
 *
 *     public void error(Throwable cause) {
 *         cause.printStackTrace();
 *     }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectUdpEventHandler
 * @see ConnectUdpSession
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public class ConnectUdpClient implements AltSvcListener {

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final String socketPath;
    private final SelectorLoop selectorLoop;

    // Configuration (set before connect)
    private boolean secure;
    private boolean verifyPeer = true;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;
    private String keystoreFormat;
    private boolean h3Enabled;
    private boolean h2Enabled = true;
    private boolean h2WithPriorKnowledge;
    private boolean dnsHttpsRecordEnabled = true;

    // Internal transport components (created at connect time) -- TCP/H1.1/H2 path
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private ConnectUdpClientProtocolHandler protocolHandler;
    private ConnectUdpSession h2Session;

    // Internal transport components (created at connect time) -- HTTP/3 path
    private HTTPClient httpClient;
    private ConnectUdpSession h3Session;

    /**
     * Creates a CONNECT-UDP client for the given proxy host and port.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param host the proxy's hostname or IP address
     * @param port the proxy's port
     */
    public ConnectUdpClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a CONNECT-UDP client with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the proxy's hostname or IP address
     * @param port the proxy's port
     */
    public ConnectUdpClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a CONNECT-UDP client for the given proxy address and port.
     *
     * @param host the proxy's host address
     * @param port the proxy's port
     */
    public ConnectUdpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a CONNECT-UDP client with an explicit selector loop and
     * proxy address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the proxy's host address
     * @param port the proxy's port
     */
    public ConnectUdpClient(SelectorLoop selectorLoop, InetAddress host,
                            int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a CONNECT-UDP client for a proxy reached over a UNIX domain
     * socket, mirroring {@link org.bluezoo.gumdrop.TCPListener#setPath}
     * on the server side. Only the proxy connection itself may be a UNIX
     * domain socket -- the UDP target requested through the tunnel (see
     * {@link #connect}) is always a network host/port, per RFC 9298.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance. Incompatible with {@link #setH3Enabled(boolean)}
     * -- HTTP/3 is inherently QUIC/UDP and has no filesystem-socket
     * equivalent -- and with DNS/Alt-Svc transport negotiation, both
     * skipped entirely for a path-based client.
     *
     * @param socketPath the proxy's UNIX domain socket path
     */
    public ConnectUdpClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates a CONNECT-UDP client for a proxy reached over a UNIX
     * domain socket, with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. See {@link
     * #ConnectUdpClient(String)} for the incompatibilities that apply to
     * every UNIX-domain-socket client.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the proxy's UNIX domain socket path
     */
    public ConnectUdpClient(SelectorLoop selectorLoop, String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = null;
        this.port = -1;
        this.socketPath = socketPath;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets whether this client uses TLS.
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
     * Sets whether the proxy's TLS certificate is verified. Verified by
     * default; disabling this accepts any certificate (e.g. for a
     * self-signed test proxy) unless a specific {@link #setTrustManager}
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
     * RFC 9298 -- forces CONNECT-UDP-over-HTTP/3 (Extended CONNECT over
     * QUIC), bypassing automatic transport negotiation.
     *
     * <p>By default (this not called), {@link #connect} negotiates the
     * transport automatically: a DNS HTTPS record advertising "h3" support
     * (see {@link #setDnsHttpsRecordEnabled(boolean)}), then a cached
     * Alt-Svc discovery ({@link AltSvcCache}), then HTTP/2 Extended CONNECT
     * or the RFC 9110 section 7.8 HTTP/1.1 Upgrade handshake, whichever
     * the connection negotiates. Calling this with {@code true} skips all
     * of that and uses Extended CONNECT directly, with no fallback. The
     * {@link ConnectUdpEventHandler}/{@link ConnectUdpSession} contract
     * {@link #connect} hands the application is identical either way.
     *
     * @param enabled true to force HTTP/3
     */
    public void setH3Enabled(boolean enabled) {
        this.h3Enabled = enabled;
    }

    /**
     * RFC 9298 -- enables or disables attempting CONNECT-UDP-over-HTTP/2
     * (Extended CONNECT) when the underlying TCP+TLS connection negotiates
     * "h2" via ALPN. Enabled by default.
     *
     * <p>Unlike {@link #setH3Enabled(boolean)}, this is not a forcing
     * override: h2 rides the same TCP+TLS connection attempt as HTTP/1.1
     * (there is no separate transport to discover in advance, unlike h3's
     * QUIC/UDP), so this only controls whether "h2" is offered in the ALPN
     * list at all. If the proxy doesn't support h2 (or this is disabled),
     * the connection falls back to the RFC 9110 section 7.8 HTTP/1.1
     * Upgrade handshake automatically. Has no effect when {@link
     * #setH3Enabled(boolean)} is set, or for cleartext (non-secure)
     * connections, which have no ALPN step at all -- see {@link
     * #setH2WithPriorKnowledge(boolean)} for h2 over cleartext.
     *
     * @param enabled true to allow CONNECT-UDP-over-HTTP/2
     */
    public void setH2Enabled(boolean enabled) {
        this.h2Enabled = enabled;
    }

    /**
     * RFC 9113 section 3.3 -- forces HTTP/2 over a cleartext (non-secure)
     * connection with no negotiation at all: the client sends the h2
     * connection preface immediately and assumes the proxy already speaks
     * h2, by prior arrangement (matching {@link
     * HTTPClient#setH2WithPriorKnowledge(boolean)}, the equivalent
     * setting for plain HTTP requests, and {@code
     * WebSocketClient#setH2WithPriorKnowledge}, the equivalent for
     * WebSocket). Combined with {@link #setSecure(boolean)}{@code
     * (false)}, this is what enables CONNECT-UDP-over-h2c.
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
     * record for the proxy host via gumdrop's async {@link DNSResolver}
     * before choosing a transport; if it advertises "h3" ALPN support, the
     * connection uses Extended CONNECT over QUIC directly. This is the
     * first tier of automatic negotiation, checked ahead of the {@link
     * AltSvcCache}.
     *
     * @param enabled true to enable DNS HTTPS-record discovery
     */
    public void setDnsHttpsRecordEnabled(boolean enabled) {
        this.dnsHttpsRecordEnabled = enabled;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 9298 section 3 -- connects to the proxy and requests a
     * CONNECT-UDP tunnel to the given UDP target. Once the proxy accepts
     * the request, the handler receives {@link ConnectUdpEventHandler#opened}.
     *
     * @param targetHost the UDP target's host (hostname or literal
     *                    address), encoded into the request path per RFC
     *                    9298 section 3's URI Template
     * @param targetPort the UDP target's port
     * @param handler the handler to receive CONNECT-UDP events
     */
    public void connect(String targetHost, int targetPort, final ConnectUdpEventHandler handler) {
        if (socketPath != null) {
            if (h3Enabled) {
                handler.error(new IOException(
                        "CONNECT-UDP-over-HTTP/3 is not supported over a UNIX domain socket"));
                return;
            }
            connectTcp(targetHost, targetPort, handler);
            return;
        }
        if (h3Enabled) {
            connectH3(targetHost, targetPort, handler);
            return;
        }
        discoverAndConnect(targetHost, targetPort, handler);
    }

    /**
     * Automatic transport negotiation, tier 1 (DNS HTTPS record) and tier
     * 2 (cached Alt-Svc discovery), falling through to {@link
     * #connectTcp} (HTTP/2 Extended CONNECT or the HTTP/1.1 Upgrade
     * handshake) when neither applies.
     *
     * <p>Skipped entirely -- straight to {@link #connectTcp} -- when
     * there is no proxy hostname to query: a literal {@link InetAddress}
     * was given at construction, {@link #host} is itself a literal IP, or
     * it's {@code localhost} (matching {@link DNSResolver#resolve}'s own
     * loopback fast-path).
     */
    private void discoverAndConnect(final String targetHost, final int targetPort,
            final ConnectUdpEventHandler handler) {
        if (hostAddress != null || host == null) {
            connectTcp(targetHost, targetPort, handler);
            return;
        }
        if (!dnsHttpsRecordEnabled || isUndiscoverableHost(host)) {
            connectViaAltSvcCacheOrTcp(targetHost, targetPort, handler);
            return;
        }

        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            gumdrop.start();
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            connectTcp(targetHost, targetPort, handler);
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
                        connectH3(targetHost, targetPort, handler);
                        return;
                    }
                }
                connectViaAltSvcCacheOrTcp(targetHost, targetPort, handler);
            }

            @Override
            public void onError(String error) {
                connectViaAltSvcCacheOrTcp(targetHost, targetPort, handler);
            }
        });
    }

    private void connectViaAltSvcCacheOrTcp(String targetHost, int targetPort, ConnectUdpEventHandler handler) {
        if (AltSvcCache.get(host, port) != null) {
            connectH3(targetHost, targetPort, handler);
            return;
        }
        connectTcp(targetHost, targetPort, handler);
    }

    /**
     * Returns true if {@code hostname} isn't worth issuing a DNS
     * HTTPS-record query for: a literal IPv4/IPv6 address, or loopback.
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
     * The TCP+TLS path. Negotiates HTTP/2 via ALPN when {@link
     * #setH2Enabled(boolean)} allows it (the default) and the connection
     * is secure, and uses RFC 9298 Extended CONNECT over it; otherwise
     * falls back to the RFC 9110 section 7.8 HTTP/1.1 Upgrade handshake.
     * Both outcomes are decided from {@code onConnected}, once {@code
     * negotiatedVersion} is known.
     *
     * @param targetHost the UDP target's host
     * @param targetPort the UDP target's port
     * @param handler the handler to receive CONNECT-UDP events
     */
    private void connectTcp(final String targetHost, final int targetPort, final ConnectUdpEventHandler handler) {
        final String path = ConnectUdpTarget.encode(targetHost, targetPort);

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
        // RFC 9298's Extended CONNECT rides the same TCP+TLS attempt as
        // HTTP/1.1 -- offer h2 via ALPN so the already-negotiated version
        // is known by the time onConnected fires below.
        if (secure && h2Enabled && !h2WithPriorKnowledge) {
            transportFactory.setApplicationProtocols("h2", "http/1.1");
        }
        transportFactory.start();

        HTTPClientHandler internalHandler = new HTTPClientHandler() {

            @Override
            public void onConnected(Endpoint endpoint) {
                if (protocolHandler.getVersion() == HTTPVersion.HTTP_2_0) {
                    // RFC 9298 section 3: must not attempt Extended CONNECT
                    // before knowing the proxy advertised support for it --
                    // which, unlike this onConnected callback itself, isn't
                    // known until the proxy's own (asynchronous) initial
                    // SETTINGS frame arrives.
                    protocolHandler.whenConnectProtocolKnown(new Runnable() {
                        @Override
                        public void run() {
                            if (!protocolHandler.isConnectProtocolEnabled()) {
                                handler.error(new IOException("Proxy does not support Extended CONNECT "
                                        + "(RFC 9298): SETTINGS_ENABLE_CONNECT_PROTOCOL was not advertised"));
                                return;
                            }
                            connectExtendedConnect(path, handler);
                        }
                    });
                    return;
                }
                // RFC 9110 section 7.8 -- HTTP/1.1 Upgrade handshake
                HTTPRequest request = protocolHandler.get(path);
                request.header("connection", "upgrade");
                request.header("upgrade", "connect-udp");
                request.header(Capsule.PROTOCOL_HEADER, "?1");
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
                // Handled by ConnectUdpClientProtocolHandler.disconnected()
            }
        };

        // RFC 9110 section 7.2 / RFC 9113 section 8.3.1: a UNIX domain
        // socket has no hostname of its own -- "localhost" matches
        // HTTPClient's own default for the same case.
        protocolHandler = (socketPath != null)
                ? new ConnectUdpClientProtocolHandler(
                        internalHandler, handler, "localhost", secure ? 443 : 80, secure)
                : new ConnectUdpClientProtocolHandler(
                        internalHandler, handler, host, port, secure);

        protocolHandler.setH2Enabled(h2Enabled);
        if (h2WithPriorKnowledge) {
            protocolHandler.setH2WithPriorKnowledge(true);
        }
        // RFC 9113 section 3.1's HTTP/1.1-Upgrade-header h2c bootstrap has
        // no CONNECT-UDP equivalent, for the same reason WebSocketClient
        // disables it: h2c's own first request must be a plain HTTP/1.1
        // request distinct from the eventual Extended CONNECT, which
        // doesn't compose with bootstrapping the tunnel handshake in the
        // same exchange. Prior knowledge (see setH2WithPriorKnowledge) is
        // the supported cleartext path.
        protocolHandler.setH2cUpgradeEnabled(false);

        // Populate AltSvcCache for later connections to this origin (this
        // session itself never reactively upgrades mid-connection -- see
        // altSvcReceived).
        protocolHandler.setAltSvcListener(this);

        try {
            if (socketPath != null) {
                clientEndpoint = (selectorLoop != null)
                        ? new ClientEndpoint(transportFactory, selectorLoop, socketPath)
                        : new ClientEndpoint(transportFactory, socketPath);
            } else if (host != null) {
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
     * Populates {@link AltSvcCache} for later, separate {@code connect()}
     * calls (from this class or {@link HTTPClient}) to the same origin.
     *
     * @param value the raw Alt-Svc header value
     */
    @Override
    public void altSvcReceived(String value) {
        if (socketPath != null) {
            // Alt-Svc advertises an alternate network address/port for
            // this origin to upgrade to (typically HTTP/3) -- meaningless
            // for a UNIX-domain-socket-addressed origin, which has
            // neither a network address to cache one against nor a QUIC
            // upgrade path available at all (see the connect() guard).
            return;
        }
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
     * RFC 9298 section 3 -- sends the Extended CONNECT request that opens
     * the CONNECT-UDP tunnel over HTTP/2, via {@link
     * H2ConnectUdpResponseHandler}.
     *
     * <p>Builds the request through the same generic {@link HTTPRequest}
     * API any other h2 request uses -- {@code :protocol} is just another
     * header from this layer's perspective, matching {@code
     * WebSocketClient#connectExtendedConnect}.
     */
    private void connectExtendedConnect(String path, final ConnectUdpEventHandler handler) {
        HTTPRequest request = protocolHandler.request("CONNECT", path);
        request.header(":protocol", "connect-udp");
        request.header(Capsule.PROTOCOL_HEADER, "?1");
        request.startRequestBody(new H2ConnectUdpResponseHandler(
                request, new H2ConnectUdpEventHandlerBridge(handler)));
    }

    /**
     * Forwards {@link ConnectUdpEventHandler} callbacks to the
     * application's handler, capturing the {@link ConnectUdpSession}
     * once the tunnel opens -- the h2 counterpart of {@link
     * H3ConnectUdpEventHandlerBridge}.
     */
    private class H2ConnectUdpEventHandlerBridge implements ConnectUdpEventHandler {

        private final ConnectUdpEventHandler handler;

        H2ConnectUdpEventHandlerBridge(ConnectUdpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(ConnectUdpSession session) {
            h2Session = session;
            handler.opened(session);
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            handler.datagramReceived(payload);
        }

        @Override
        public void closed() {
            handler.closed();
        }

        @Override
        public void error(Throwable cause) {
            handler.error(cause);
        }
    }

    /**
     * RFC 9298 section 3 -- connects and requests the CONNECT-UDP tunnel
     * over HTTP/3 Extended CONNECT, via an internally-managed {@link
     * HTTPClient}.
     */
    private void connectH3(final String targetHost, final int targetPort, final ConnectUdpEventHandler handler) {
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
        // verifyPeer -- trustManager/keystoreFile are therefore not wired
        // through here; matches the same, pre-existing gap in
        // WebSocketClient#connectH3.
        httpClient.setVerifyPeer(verifyPeer);

        httpClient.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                httpClient.connectUdp(targetHost, targetPort,
                        new H3ConnectUdpEventHandlerBridge(handler));
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
     * Forwards {@link ConnectUdpEventHandler} callbacks to the
     * application's handler, capturing the {@link ConnectUdpSession}
     * once the tunnel opens, so {@link #isOpen}/{@link #close} work the
     * same way for the HTTP/3 path as they already do for HTTP/1.1's
     * {@code ConnectUdpClientProtocolHandler} and HTTP/2's {@link
     * H2ConnectUdpEventHandlerBridge}.
     */
    private class H3ConnectUdpEventHandlerBridge implements ConnectUdpEventHandler {

        private final ConnectUdpEventHandler handler;

        H3ConnectUdpEventHandlerBridge(ConnectUdpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(ConnectUdpSession session) {
            h3Session = session;
            handler.opened(session);
        }

        @Override
        public void datagramReceived(ByteBuffer payload) {
            handler.datagramReceived(payload);
        }

        @Override
        public void closed() {
            handler.closed();
        }

        @Override
        public void error(Throwable cause) {
            handler.error(cause);
        }
    }

    /**
     * Returns whether the CONNECT-UDP tunnel is open.
     *
     * @return true if connected and the tunnel has been accepted
     */
    public boolean isOpen() {
        return getSession() != null;
    }

    /**
     * Closes the CONNECT-UDP tunnel.
     */
    public void close() {
        ConnectUdpSession session = getSession();
        if (session != null) {
            session.close();
        }
        if (protocolHandler != null) {
            protocolHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

    /**
     * Returns the active tunnel session, or null if none has opened yet.
     */
    private ConnectUdpSession getSession() {
        if (h3Session != null) {
            return h3Session;
        }
        if (h2Session != null) {
            return h2Session;
        }
        if (protocolHandler != null) {
            return protocolHandler.getConnectUdpSession();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upgrade response handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimal response handler for the HTTP/1.1 upgrade request. In the
     * normal case, the 101 response is intercepted by {@link
     * ConnectUdpClientProtocolHandler#handleProtocolSwitch} before any of
     * these callbacks fire. This handler only exists to catch non-101
     * responses (proxy refused the tunnel) and errors.
     */
    private static class UpgradeResponseHandler extends DefaultHTTPResponseHandler {

        private final ConnectUdpEventHandler handler;

        UpgradeResponseHandler(ConnectUdpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void ok(HTTPResponse response) {
            // A 2xx response means the proxy did not upgrade
            handler.error(new IOException(
                    "Proxy did not upgrade to connect-udp: "
                    + response.getStatus()));
        }

        @Override
        public void error(HTTPResponse response) {
            handler.error(new IOException(
                    "CONNECT-UDP upgrade failed: " + response.getStatus()));
        }

        @Override
        public void failed(Exception ex) {
            handler.error(ex);
        }
    }
}
