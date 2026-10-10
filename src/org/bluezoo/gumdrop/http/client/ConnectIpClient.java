/*
 * ConnectIpClient.java
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

import org.bluezoo.gumdrop.http.HttpMethod;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;
import org.bluezoo.gumdrop.http.ConnectIpTarget;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * High-level RFC 9484 (Proxying IP in HTTP) CONNECT-IP client facade.
 *
 * <p>Provides a simple API for opening an IP tunnel through an HTTP
 * proxy. The handler interface is {@link ConnectIpEventHandler} --
 * structurally the CONNECT-IP counterpart of {@link ConnectUdpClient},
 * whose transport negotiation (DNS HTTPS-record discovery, cached
 * Alt-Svc, HTTP/2 ALPN, HTTP/1.1 fallback) this class mirrors exactly --
 * the only difference is what happens once a transport is chosen: an
 * Extended CONNECT or HTTP Upgrade request shaped by RFC 9484 (target
 * scope hint encoded into the path, {@code Capsule-Protocol: ?1}) rather
 * than RFC 9298's CONNECT-UDP request.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * ConnectIpClient client = new ConnectIpClient("proxy.example.com", 443);
 * client.secure(true);
 * client.connect(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD,
 *         new ConnectIpEventHandler() {
 *
 *     public void opened(ConnectIpClientSession session) {
 *         session.sendAddressRequest(List.of(
 *                 new ConnectIpAddress(1, InetAddress.getByName("0.0.0.0"), 0)));
 *     }
 *
 *     public void packetReceived(ByteBuffer packet) {
 *         System.out.println("Received " + packet.remaining() + " bytes");
 *     }
 *
 *     public void addressAssigned(List<ConnectIpAddress> assignments) {
 *         System.out.println("Assigned: " + assignments);
 *     }
 *
 *     public void routeAdvertised(List<ConnectIpRoute> routes) {
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
 * @see ConnectIpEventHandler
 * @see ConnectIpClientSession
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public class ConnectIpClient implements AltSvcListener {

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final String socketPath;
    private final SelectorLoop selectorLoop;

    // Configuration (set before connect)
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;
    private EnumSet<HttpVersion> versions = EnumSet.of(
            HttpVersion.HTTP_3, HttpVersion.HTTP_2_0, HttpVersion.HTTP_1_1);
    private boolean h2WithPriorKnowledge;
    private DnsResolver dnsResolver;
    private long quicHandshakeTimeoutMs = HttpClient.DEFAULT_QUIC_HANDSHAKE_TIMEOUT_MS;
    private boolean dnsHttpsRecordEnabled = true;

    // Internal transport components (created at connect time) -- TCP/H1.1/H2 path
    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private ConnectIpClientProtocolHandler protocolHandler;
    private ConnectIpClientSession h2Session;

    // Internal transport components (created at connect time) -- HTTP/3 path
    private HttpClient httpClient;
    private ConnectIpClientSession h3Session;

    private Gumdrop gumdrop;

    /**
     * Creates a CONNECT-IP client for the given proxy host and port.
     *
     * <p>DNS resolution is deferred until {@link #connect} is called.
     *
     * @param host the proxy's hostname or IP address
     * @param port the proxy's port
     */
    public ConnectIpClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a CONNECT-IP client with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the proxy's hostname or IP address
     * @param port the proxy's port
     */
    public ConnectIpClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a CONNECT-IP client for the given proxy address and port.
     *
     * @param host the proxy's host address
     * @param port the proxy's port
     */
    public ConnectIpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates a CONNECT-IP client with an explicit selector loop and
     * proxy address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the proxy's host address
     * @param port the proxy's port
     */
    public ConnectIpClient(SelectorLoop selectorLoop, InetAddress host,
                           int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates a CONNECT-IP client for a proxy reached over a UNIX domain
     * socket, mirroring {@link org.bluezoo.gumdrop.TcpListener#path(java.nio.file.Path)}
     * on the server side. Only the proxy connection itself may be a UNIX
     * domain socket -- the IP target requested through the tunnel (see
     * {@link #connect}) is a network-scope hint, per RFC 9484.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance. Incompatible with a version list permitting only HTTP/3
     * -- HTTP/3 is inherently QUIC/UDP and has no filesystem-socket
     * equivalent -- and with DNS/Alt-Svc transport negotiation, both
     * skipped entirely for a path-based client.
     *
     * @param socketPath the proxy's UNIX domain socket path
     */
    public ConnectIpClient(String socketPath) {
        this(null, socketPath);
    }

    /**
     * Creates a CONNECT-IP client for a proxy reached over a UNIX
     * domain socket, with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. See {@link
     * #ConnectIpClient(String)} for the incompatibilities that apply to
     * every UNIX-domain-socket client.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param socketPath the proxy's UNIX domain socket path
     */
    public ConnectIpClient(SelectorLoop selectorLoop, String socketPath) {
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
     * @return this client
     */
    public ConnectIpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Sets this client's TLS settings (certificates, trust, ECH and so on). The
     * settings are copied, so later changes to {@code source} are not seen.
     * Whether TLS is used at all is decided by {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public ConnectIpClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    /**
     * Sets the HTTP versions this client may use for the CONNECT-IP tunnel, as
     * {@link HttpClient#versions(HttpVersion...)} does for requests. The
     * default is HTTP/3, HTTP/2 and HTTP/1.1, negotiated automatically: a
     * DNS HTTPS record advertising "h3" (see {@link
     * #dnsHttpsRecordEnabled(boolean)}), then a cached Alt-Svc discovery
     * ({@link AltSvcCache}), then TCP, where HTTP/2 Extended CONNECT is
     * offered via ALPN and the HTTP/1.1 Upgrade handshake is the fallback.
     * The event handler and session contract {@link #connect} hands the
     * application is identical whichever is used.
     *
     * <p>Permitting only {@code HTTP_3} uses Extended CONNECT over QUIC
     * directly, skipping discovery. Leaving out {@code HTTP_3} means QUIC
     * is never tried; leaving out {@code HTTP_2_0} means {@code h2} is not
     * offered.
     *
     * @param permitted the permitted versions
     * @return this client
     * @throws IllegalArgumentException if the list is invalid, see {@link
     *         HttpVersion#clientVersions}
     */
    public ConnectIpClient versions(HttpVersion... permitted) {
        this.versions = HttpVersion.clientVersions(permitted);
        return this;
    }

    private boolean permitsH3() {
        return versions.contains(HttpVersion.HTTP_3);
    }

    private boolean permitsH2() {
        return versions.contains(HttpVersion.HTTP_2_0);
    }

    private boolean forcesH3() {
        return permitsH3() && !permitsH2() && !versions.contains(HttpVersion.HTTP_1_1);
    }

    /**
     * RFC 9113 section 3.3 -- forces HTTP/2 over a cleartext (non-secure)
     * connection with no negotiation at all: the client sends the h2
     * connection preface immediately and assumes the proxy already speaks
     * h2, by prior arrangement (matching {@link
     * HttpClient#h2WithPriorKnowledge(boolean)} and {@link
     * ConnectUdpClient#h2WithPriorKnowledge(boolean)}, the equivalent
     * settings elsewhere). Combined with {@link #secure(boolean)}{@code
     * (false)}, this is what enables CONNECT-IP-over-h2c.
     *
     * <p>Has no effect when only HTTP/3 is permitted, or for secure
     * connections (which negotiate h2 via ALPN instead, see {@link
     * #versions(HttpVersion...)}). Requires {@code HTTP_2_0} to be
     * permitted.
     *
     * @param enabled true to force HTTP/2 over cleartext with no negotiation
     * @return this client
     */
    public ConnectIpClient h2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
        return this;
    }

    /**
     * Enables or disables DNS HTTPS-record discovery (RFC 9460) of HTTP/3
     * support, checked before connecting.
     *
     * <p>When enabled (the default), {@link #connect} queries an HTTPS
     * record for the proxy host via gumdrop's async {@link DnsResolver}
     * before choosing a transport; if it advertises "h3" ALPN support, the
     * connection uses Extended CONNECT over QUIC directly. This is the
     * first tier of automatic negotiation, checked ahead of the {@link
     * AltSvcCache}.
     *
     * @param enabled true to enable DNS HTTPS-record discovery
     * @return this client
     */
    public ConnectIpClient dnsHttpsRecordEnabled(boolean enabled) {
        this.dnsHttpsRecordEnabled = enabled;
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * RFC 9484 section 4 -- connects to the proxy and requests a
     * CONNECT-IP tunnel scoped to the given target/ipproto. Once the
     * proxy accepts the request, the handler receives {@link
     * ConnectIpEventHandler#opened}.
     *
     * @param target the target scope hint ({@link
     *               ConnectIpTarget#WILDCARD} for "unspecified", the
     *               common case, or a hostname/IP prefix), encoded into
     *               the request path per RFC 9484 section 3's URI Template
     * @param ipProto the IP protocol scope hint ({@link
     *                ConnectIpTarget#WILDCARD} for "unspecified", or a
     *                decimal Internet Protocol Number)
     * @param gumdrop the runtime this connection is made under
     * @param handler the handler to receive CONNECT-IP events
     */
    public void connect(Gumdrop gumdrop, String target, String ipProto, final ConnectIpEventHandler handler) {
        this.gumdrop = gumdrop;
        if (socketPath != null) {
            if (forcesH3()) {
                handler.error(new IOException(
                        "CONNECT-IP-over-HTTP/3 is not supported over a UNIX domain socket"));
                return;
            }
            connectTcp(target, ipProto, handler);
            return;
        }
        if (forcesH3()) {
            connectH3(target, ipProto, handler, false);
            return;
        }
        discoverAndConnect(target, ipProto, handler);
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
     * it's {@code localhost} (matching {@link DnsResolver#resolve}'s own
     * loopback fast-path).
     */
    private void discoverAndConnect(final String target, final String ipProto,
            final ConnectIpEventHandler handler) {
        if (hostAddress != null || host == null) {
            connectTcp(target, ipProto, handler);
            return;
        }
        if (!dnsHttpsRecordEnabled || ClientConnect.isUndiscoverableHost(host)) {
            connectViaAltSvcCacheOrTcp(target, ipProto, handler);
            return;
        }

        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            connectTcp(target, ipProto, handler);
            return;
        }

        DnsResolver resolver = resolverFor(loop);
        resolver.queryHTTPS(host, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                for (DnsResourceRecord rr : response.getAnswers()) {
                    if (rr.getType() != DnsType.HTTPS || rr.isSVCBAliasForm()) {
                        continue;
                    }
                    if (permitsH3() && rr.getSVCBAlpnProtocols().contains("h3")) {
                        connectH3(target, ipProto, handler, true);
                        return;
                    }
                }
                connectViaAltSvcCacheOrTcp(target, ipProto, handler);
            }

            @Override
            public void onError(String error) {
                connectViaAltSvcCacheOrTcp(target, ipProto, handler);
            }
        });
    }

    /**
     * Resolver used for the HTTPS record query. Package-private so tests
     * can substitute a resolver with canned answers.
     */
    DnsResolver resolverFor(SelectorLoop loop) {
        return dnsResolver != null ? dnsResolver : DnsResolver.forLoop(loop);
    }

    /**
     * Sets the resolver used to look up the proxy's host name and its DNS
     * HTTPS record, for TCP and HTTP/3 alike. When unset, the resolver of
     * the connection's selector loop ({@link DnsResolver#forLoop}) is used.
     *
     * @param dnsResolver the resolver, or {@code null} for the default
     * @return this client
     */
    public ConnectIpClient dnsResolver(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    /**
     * Returns the configured DNS resolver, or {@code null} if the default
     * will be used.
     */
    public DnsResolver getDnsResolver() {
        return dnsResolver;
    }

    private void connectViaAltSvcCacheOrTcp(String target, String ipProto, ConnectIpEventHandler handler) {
        if (permitsH3() && AltSvcCache.get(host, port) != null) {
            connectH3(target, ipProto, handler, true);
            return;
        }
        connectTcp(target, ipProto, handler);
    }


    /**
     * The TCP+TLS path. Negotiates HTTP/2 via ALPN when {@link
     * #versions(HttpVersion...)} permits it (the default) and the connection
     * is secure, and uses RFC 9484 Extended CONNECT over it; otherwise
     * falls back to the RFC 9110 section 7.8 HTTP/1.1 Upgrade handshake.
     * Both outcomes are decided from {@code onConnected}, once {@code
     * negotiatedVersion} is known.
     *
     * @param target the target scope hint
     * @param ipProto the IP protocol scope hint
     * @param handler the handler to receive CONNECT-IP events
     */
    /**
     * Creates the transport factory for one connection attempt. Package-private
     * so tests can substitute a factory whose connect fails deterministically.
     */
    TcpTransportFactory newTransportFactory() {
        return new TcpTransportFactory();
    }

    private void connectTcp(final String target, final String ipProto, final ConnectIpEventHandler handler) {
        final String path = ConnectIpTarget.encode(target, ipProto);

        transportFactory = newTransportFactory();
        // RFC 9484's Extended CONNECT rides the same TCP+TLS attempt as
        // HTTP/1.1 -- offer h2 via ALPN so the already-negotiated version
        // is known by the time onConnected fires below.
        if (secure && permitsH2() && !h2WithPriorKnowledge) {
            transportFactory.setApplicationProtocols("h2", "http/1.1");
        }
        ClientConnect.prepareTls(secure, tls, transportFactory);

        HttpClientHandler internalHandler = new HttpClientHandler() {

            @Override
            public void onConnected(Endpoint endpoint) {
                if (protocolHandler.getVersion() == HttpVersion.HTTP_2_0) {
                    // RFC 9484 section 4: must not attempt Extended CONNECT
                    // before knowing the proxy advertised support for it --
                    // which, unlike this onConnected callback itself, isn't
                    // known until the proxy's own (asynchronous) initial
                    // SETTINGS frame arrives.
                    protocolHandler.whenConnectProtocolKnown(new Runnable() {
                        @Override
                        public void run() {
                            if (!protocolHandler.isConnectProtocolEnabled()) {
                                handler.error(new IOException("Proxy does not support Extended CONNECT "
                                        + "(RFC 9484): SETTINGS_ENABLE_CONNECT_PROTOCOL was not advertised"));
                                return;
                            }
                            connectExtendedConnect(path, handler);
                        }
                    });
                    return;
                }
                // RFC 9110 section 7.8 -- HTTP/1.1 Upgrade handshake
                HttpRequest request = protocolHandler.get(path, new UpgradeResponseHandler(handler));
                request.header("connection", "upgrade");
                request.header("upgrade", "connect-ip");
                request.header(Capsule.PROTOCOL_HEADER, "?1");
                request.endMessage();
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
                // Handled by ConnectIpClientProtocolHandler.disconnected()
            }
        };

        // RFC 9110 section 7.2 / RFC 9113 section 8.3.1: a UNIX domain
        // socket has no hostname of its own -- "localhost" matches
        // HttpClient's own default for the same case.
        protocolHandler = (socketPath != null)
                ? new ConnectIpClientProtocolHandler(
                        internalHandler, handler, "localhost", secure ? 443 : 80, secure)
                : new ConnectIpClientProtocolHandler(
                        internalHandler, handler, cacheKeyHost(), port, secure);

        protocolHandler.setH2Enabled(permitsH2());
        if (h2WithPriorKnowledge && permitsH2()) {
            protocolHandler.setH2WithPriorKnowledge(true);
        }
        // RFC 9113 section 3.1's HTTP/1.1-Upgrade-header h2c bootstrap has
        // no CONNECT-IP equivalent, for the same reason ConnectUdpClient
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
            connectEndpointForTesting(protocolHandler);
        } catch (IOException e) {
            handler.error(e);
        }
    }

    /**
     * Test seam: creates the client endpoint for the configured target and
     * connects it to {@code ph}. Production behaviour opens a real socket;
     * unit tests override this to attach an in-memory endpoint instead.
     *
     * @param ph the protocol handler that receives the connection
     * @throws IOException if the endpoint cannot be created
     */
    void connectEndpointForTesting(ConnectIpClientProtocolHandler ph) throws IOException {
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
        if (dnsResolver != null) {
            clientEndpoint.setDnsResolver(dnsResolver);
        }
        clientEndpoint.connect(gumdrop, ph);
    }

    /**
     * Populates {@link AltSvcCache} for later, separate {@code connect()}
     * calls (from this class or {@link HttpClient}) to the same origin.
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
     * RFC 9484 section 4 -- sends the Extended CONNECT request that opens
     * the CONNECT-IP tunnel over HTTP/2, via {@link
     * H2ConnectIpResponseHandler}.
     *
     * <p>Builds the request through the same generic {@link HttpRequest}
     * API any other h2 request uses -- {@code :protocol} is just another
     * header from this layer's perspective, matching {@link
     * ConnectUdpClient#connectExtendedConnect} (private, but the same shape).
     */
    private void connectExtendedConnect(String path, final ConnectIpEventHandler handler) {
        H2ConnectIpResponseHandler responseHandler = new H2ConnectIpResponseHandler(
                new H2ConnectIpEventHandlerBridge(handler));
        HttpRequest request = protocolHandler.request(HttpMethod.CONNECT, path, responseHandler);
        responseHandler.bindRequest(request);
        request.header(":protocol", "connect-ip");
        request.header(Capsule.PROTOCOL_HEADER, "?1");
        request.endHeaders();
    }

    /**
     * Forwards {@link ConnectIpEventHandler} callbacks to the
     * application's handler, capturing the {@link ConnectIpClientSession}
     * once the tunnel opens -- the h2 counterpart of {@link
     * H3ConnectIpEventHandlerBridge}.
     */
    private class H2ConnectIpEventHandlerBridge implements ConnectIpEventHandler {

        private final ConnectIpEventHandler handler;

        H2ConnectIpEventHandlerBridge(ConnectIpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(ConnectIpClientSession session) {
            h2Session = session;
            handler.opened(session);
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            handler.packetReceived(packet);
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            handler.addressAssigned(assignments);
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
            handler.routeAdvertised(routes);
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
     * Test seam: creates the internal client used for the HTTP/3 attempt.
     */
    HttpClient createH3ClientForTesting() {
        if (host != null) {
            return (selectorLoop != null)
                    ? new HttpClient(selectorLoop, host, port) : new HttpClient(host, port);
        }
        return (selectorLoop != null)
                ? new HttpClient(selectorLoop, hostAddress, port) : new HttpClient(hostAddress, port);
    }

    /** Gives up the HTTP/3 attempt, ahead of connecting over TCP instead. */
    private void abandonH3() {
        HttpClient abandoned = httpClient;
        httpClient = null;
        if (abandoned != null) {
            abandoned.close();
        }
    }

    /**
     * Sets how long an HTTP/3 attempt may take to establish. When discovery
     * chose HTTP/3 and TCP is permitted, the client then connects over TCP
     * instead; when only HTTP/3 is permitted, it reports an error. The
     * default is three seconds; 0 disables the deadline.
     *
     * @param ms the deadline in milliseconds
     * @return this client
     */
    public ConnectIpClient quicHandshakeTimeoutMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("ms must not be negative");
        }
        this.quicHandshakeTimeoutMs = ms;
        return this;
    }

    /**
     * RFC 9484 section 4 -- connects and requests the CONNECT-IP tunnel
     * over HTTP/3 Extended CONNECT, via an internally-managed {@link
     * HttpClient}.
     */
    private void connectH3(final String target, final String ipProto, final ConnectIpEventHandler handler, final boolean fallback) {
        httpClient = createH3ClientForTesting();
        httpClient.quicHandshakeTimeoutMs(quicHandshakeTimeoutMs);
        final AtomicBoolean h3Established = new AtomicBoolean(false);
        httpClient.versions(HttpVersion.HTTP_3);
        if (dnsResolver != null) {
            httpClient.dnsResolver(dnsResolver);
        }
        httpClient.tls(tls);

        httpClient.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                h3Established.set(true);
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                h3Established.set(true);
                httpClient.connectIp(target, ipProto,
                        new H3ConnectIpEventHandlerBridge(handler));
            }

            @Override
            public void onError(Exception cause) {
                if (fallback && !h3Established.get()) {
                    abandonH3();
                    connectTcp(target, ipProto, handler);
                    return;
                }
                handler.error(cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
    }

    /**
     * Forwards {@link ConnectIpEventHandler} callbacks to the
     * application's handler, capturing the {@link ConnectIpClientSession}
     * once the tunnel opens, so {@link #isOpen}/{@link #close} work the
     * same way for the HTTP/3 path as they already do for HTTP/1.1's
     * {@code ConnectIpClientProtocolHandler} and HTTP/2's {@link
     * H2ConnectIpEventHandlerBridge}.
     */
    private class H3ConnectIpEventHandlerBridge implements ConnectIpEventHandler {

        private final ConnectIpEventHandler handler;

        H3ConnectIpEventHandlerBridge(ConnectIpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void opened(ConnectIpClientSession session) {
            h3Session = session;
            handler.opened(session);
        }

        @Override
        public void packetReceived(ByteBuffer packet) {
            handler.packetReceived(packet);
        }

        @Override
        public void addressAssigned(List<ConnectIpAddress> assignments) {
            handler.addressAssigned(assignments);
        }

        @Override
        public void routeAdvertised(List<ConnectIpRoute> routes) {
            handler.routeAdvertised(routes);
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
     * Returns whether the CONNECT-IP tunnel is open.
     *
     * @return true if connected and the tunnel has been accepted
     */
    public boolean isOpen() {
        return getSession() != null;
    }

    /**
     * Closes the CONNECT-IP tunnel.
     */
    public void close() {
        ConnectIpClientSession session = getSession();
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
    private ConnectIpClientSession getSession() {
        if (h3Session != null) {
            return h3Session;
        }
        if (h2Session != null) {
            return h2Session;
        }
        if (protocolHandler != null) {
            return protocolHandler.getConnectIpSession();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upgrade response handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimal response handler for the HTTP/1.1 upgrade request. In the
     * normal case, the 101 response is intercepted by {@link
     * ConnectIpClientProtocolHandler#handleProtocolSwitch} before any of
     * these callbacks fire. This handler only exists to catch non-101
     * responses (proxy refused the tunnel) and errors.
     */
    private static class UpgradeResponseHandler extends DefaultHttpResponseHandler {

        private final ConnectIpEventHandler handler;

        UpgradeResponseHandler(ConnectIpEventHandler handler) {
            this.handler = handler;
        }

        @Override
        public void status(int code) {
            HttpStatus status = HttpStatus.fromCode(code);
            if (status.isSuccess()) {
            // A 2xx response means the proxy did not upgrade
            handler.error(new IOException(
                    "Proxy did not upgrade to connect-ip: "
                    + status));
            } else {
            handler.error(new IOException(
                    "CONNECT-IP upgrade failed: " + status));
            }
        }

        @Override
        public void failed(Exception ex) {
            handler.error(ex);
        }
    }
}
