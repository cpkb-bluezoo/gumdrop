/*
 * HttpClient.java
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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.tls.KeystoreFormat;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.ClientEndpointPool;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDefaults;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.HttpsRecordEch;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;
import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.http.client.AltSvcListener;
import org.bluezoo.gumdrop.http.client.ConnectIpEventHandler;
import org.bluezoo.gumdrop.http.client.ConnectUdpEventHandler;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpClientProtocolHandler;
import org.bluezoo.gumdrop.http.client.HttpMethodSafety;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.h3.Http3ClientHandler;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * High-level HTTP client facade.
 *
 * <p>This class provides a simple, concrete API for making HTTP requests.
 * It internally creates either a {@link TcpTransportFactory} (for
 * HTTP/1.1 and HTTP/2) or a {@link QuicTransportFactory} (for HTTP/3),
 * wiring the appropriate protocol handler and forwarding lifecycle
 * events to the caller's {@link HttpClientHandler}.
 *
 * <p>HTTP/2 connection modes (RFC 9113 section 3):
 * <ul>
 *   <li>TLS with ALPN "h2" (section 3.2) -- default for secure connections</li>
 *   <li>h2c cleartext upgrade (section 3.1, deprecated by RFC 9113 but
 *       intentionally retained) -- see {@link #setH2cUpgradeEnabled}</li>
 *   <li>Prior knowledge (section 3.3) -- see {@link #setH2WithPriorKnowledge}</li>
 * </ul>
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * HttpClient client = new HttpClient("api.example.com", 443);
 * client.setSecure(true);
 * client.connect(new HttpClientHandler() {
 *     public void onConnected(Endpoint endpoint) {
 *         HttpRequest req = client.get("/users", responseHandler);
 *         req.endMessage();
 *     }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * <h4>With explicit SelectorLoop (server integration)</h4>
 * <pre>{@code
 * HttpClient client = new HttpClient(selectorLoop, "api.example.com", 443);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpClientHandler
 * @see HttpRequest
 */
public class HttpClient implements AltSvcListener {

    private static final Logger LOGGER =
            Logger.getLogger(HttpClient.class.getName());

    private EventLogger events() {
        // a client used without a runtime, as in tests, reports through
        // a configuration of its own
        TelemetryConfig telemetry = gumdrop != null ? gumdrop.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(HttpClient.class, L10N);
    }
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");

    /**
     * How long a QUIC attempt chosen by discovery may take to establish
     * before the client gives up on it and connects over TCP instead.
     */
    public static final long DEFAULT_QUIC_HANDSHAKE_TIMEOUT_MS = 3000L;

    private static EnumSet<HttpVersion> defaultVersions() {
        return EnumSet.of(HttpVersion.HTTP_3, HttpVersion.HTTP_2_0,
                HttpVersion.HTTP_1_1);
    }

    private String host;
    private int port;
    private String socketPath;
    private SelectorLoop selectorLoop;
    private InetAddress hostAddress;
    private DnsResolver dnsResolver;

    // Configuration (set before connect)
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;
    private String username;
    private String password;
    private EnumSet<HttpVersion> versions = defaultVersions();
    private boolean h2WithPriorKnowledge;
    private long quicHandshakeTimeoutMs = DEFAULT_QUIC_HANDSHAKE_TIMEOUT_MS;
    private boolean altSvcEnabled = true;
    private boolean dnsHttpsRecordEnabled = true;
    /** {@code ech} SvcParam from the last DNS HTTPS lookup, if any. */
    private byte[] dnsDiscoveredEchConfigList;
    private boolean blockPrivateAddresses;
    private long idleTimeoutMs;
    private boolean sendAcceptEncodingHeader = ContentEncoding.isContentCodingEnabled();
    private boolean decodeResponseContentCoding = ContentEncoding.isContentCodingEnabled();
    private boolean encodeRequestBodyContentCoding = ContentEncoding.isContentCodingEnabled();
    private ClientEndpointPool connectionPool;

    /** Trace context for automatic traceparent propagation on outbound requests. */
    private Trace traceContext;
    private ClientEndpointPool.PoolEntry poolEntry;

    // Internal transport components (created at connect time)
    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private HttpClientProtocolHandler endpointHandler;

    // HTTP/3 transport components (created at connect time)
    private QuicTransportFactory quicTransportFactory;
    private QuicEngine quicEngine;
    private Http3ClientHandler h3Handler;

    // Alt-Svc upgrade state
    private volatile boolean h3UpgradeInProgress;
    private HttpClientHandler connectHandler;

    private Gumdrop gumdrop;

    /**
     * Creates a client for fluent dial configuration before {@link #connect}.
     */
    public HttpClient() {
        this.port = 443;
    }

    /**
     * Creates an HTTP client for the given host and port.
     *
     * <p>Uses the next available worker loop from the global
     * {@link Gumdrop} instance. DNS resolution is deferred until
     * {@link #connect} is called.
     *
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public HttpClient(String host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an HTTP client with an explicit selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote hostname or IP address
     * @param port the remote port
     */
    public HttpClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates an HTTP client for the given address and port.
     *
     * @param host the remote host address
     * @param port the remote port
     */
    public HttpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    /**
     * Creates an HTTP client with an explicit selector loop and address.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param host the remote host address
     * @param port the remote port
     */
    public HttpClient(SelectorLoop selectorLoop, InetAddress host,
                      int port) {
        this.selectorLoop = selectorLoop;
        this.host = host.getHostAddress();
        this.hostAddress = host;
        this.port = port;
        this.socketPath = null;
    }

    /**
     * Creates an HTTP client for a UNIX domain socket, mirroring {@link
     * org.bluezoo.gumdrop.TcpListener#setPath} on the server side.
     *
     * <p>Uses the next available worker loop from the global {@link
     * Gumdrop} instance. Incompatible with {@link #setH3Enabled(boolean)}
     * -- HTTP/3 is inherently QUIC/UDP and has no filesystem-socket
     * equivalent -- and with DNS/Alt-Svc transport negotiation, both
     * skipped entirely for a path-based client. The {@code Host} header
     * (HTTP/1.1) and {@code :authority} pseudo-header (HTTP/2) sent on
     * requests default to {@code localhost}, matching common convention
     * for clients dialing a UNIX domain socket (e.g. curl's
     * {@code --unix-socket}); set an explicit header on individual
     * requests to override.
     *
     * @param path the UNIX domain socket path
     */
    public HttpClient(String path) {
        this(null, path);
    }

    /**
     * Creates an HTTP client for a UNIX domain socket with an explicit
     * selector loop.
     *
     * <p>Use this constructor when integrating with server-side code
     * that has its own selector loop management. See {@link #HttpClient(
     * String)} for the incompatibilities/defaults that apply to every
     * UNIX-domain-socket client.
     *
     * @param selectorLoop the selector loop, or null to use a Gumdrop worker
     * @param path the UNIX domain socket path
     */
    public HttpClient(SelectorLoop selectorLoop, String path) {
        if (path == null) {
            throw new NullPointerException("path");
        }
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.port = -1;
        this.socketPath = path;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Configuration (before connect)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets HTTP Basic Authentication credentials.
     *
     * @param username the username
     * @param password the password
     * @return this client
     */
    public HttpClient credentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    private void checkNotPrivate(InetAddress addr) throws IOException {
        if (!blockPrivateAddresses) {
            return;
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            throw new IOException("SSRF protection: connection to "
                    + addr.getHostAddress() + " is not permitted");
        }
        // The IPv4 cloud metadata address (169.254.169.254) is link-local and
        // so already rejected above. IPv6 unique local addresses (fc00::/7,
        // RFC 4193) are not covered by isSiteLocalAddress() but are the
        // private IPv6 range, and include the AWS IPv6 metadata endpoint
        // (fd00:ec2::254), so block them explicitly.
        byte[] raw = addr.getAddress();
        if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) {
            throw new IOException("SSRF protection: connection to "
                    + addr.getHostAddress() + " is not permitted");
        }
    }

    private void applyContentCodingSettings(HttpClientProtocolHandler handler) {
        handler.setSendAcceptEncodingHeader(sendAcceptEncodingHeader);
        handler.setDecodeResponseContentCoding(decodeResponseContentCoding);
        handler.setEncodeRequestBodyContentCoding(encodeRequestBodyContentCoding);
    }

    private void applyContentCodingSettings(Http3ClientHandler handler) {
        handler.setSendAcceptEncodingHeader(sendAcceptEncodingHeader);
        handler.setDecodeResponseContentCoding(decodeResponseContentCoding);
        handler.setEncodeRequestBodyContentCoding(encodeRequestBodyContentCoding);
    }

    public HttpClient host(String host) {
        this.host = host;
        this.hostAddress = null;
        this.socketPath = null;
        return this;
    }

    public HttpClient host(InetAddress hostAddress) {
        if (hostAddress == null) {
            throw new NullPointerException("hostAddress");
        }
        this.hostAddress = hostAddress;
        this.host = hostAddress.getHostAddress();
        this.socketPath = null;
        return this;
    }

    public HttpClient port(int port) {
        this.port = port;
        return this;
    }

    public HttpClient socketPath(String socketPath) {
        if (socketPath == null) {
            throw new NullPointerException("socketPath");
        }
        this.socketPath = socketPath;
        this.host = null;
        this.hostAddress = null;
        return this;
    }

    public HttpClient selectorLoop(SelectorLoop selectorLoop) {
        this.selectorLoop = selectorLoop;
        return this;
    }

    public HttpClient dnsResolver(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
        return this;
    }

    public DnsResolver getDnsResolver() {
        return dnsResolver;
    }

    /**
     * Sets whether this client uses TLS. Returns {@code this} for fluent
     * configuration.
     */
    public HttpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /** @return this client */
    public HttpClient trace(Trace trace) {
        this.traceContext = trace;
        if (endpointHandler != null) {
            endpointHandler.setTraceContext(trace);
        }
        return this;
    }

    /**
     * Sets the HTTP versions this client may use. The client always prefers
     * the highest permitted version, so the order of the arguments does not
     * matter. The default is {@link HttpVersion#HTTP_3}, {@link
     * HttpVersion#HTTP_2_0} and {@link HttpVersion#HTTP_1_1}.
     *
     * <p>When HTTP/3 and a TCP version are both permitted, the client
     * discovers HTTP/3 (a DNS HTTPS record, then a cached {@code Alt-Svc})
     * and falls back to TCP if the QUIC attempt fails or does not establish
     * within {@link #quicHandshakeTimeoutMs(long)}. Permitting only
     * {@code HTTP_3} connects over QUIC straight away, with no discovery
     * and no fallback. Leaving out {@code HTTP_3} means QUIC is never used;
     * leaving out {@code HTTP_2_0} stops {@code h2} being offered over TLS
     * and the h2c upgrade being attempted over cleartext.
     *
     * @param permitted the permitted versions, at least one of
     *        {@code HTTP_3}, {@code HTTP_2_0} and {@code HTTP_1_1}
     * @return this client
     * @throws IllegalArgumentException if the list is null, empty, contains
     *         null, or contains a version this client cannot speak
     */
    public HttpClient versions(HttpVersion... permitted) {
        this.versions = HttpVersion.clientVersions(permitted);
        return this;
    }

    /**
     * Returns the HTTP versions this client may use.
     *
     * @return an unmodifiable set
     */
    public Set<HttpVersion> getVersions() {
        return Collections.unmodifiableSet(EnumSet.copyOf(versions));
    }

    /**
     * Forces HTTP/2 over cleartext with no negotiation: the client sends the
     * HTTP/2 connection preface immediately (RFC 9113 section 3.3). Requires
     * {@link HttpVersion#HTTP_2_0} to be permitted by {@link
     * #versions(HttpVersion...)}, and has no effect over TLS, where ALPN
     * decides.
     *
     * @return this client
     */
    public HttpClient h2WithPriorKnowledge(boolean enabled) {
        this.h2WithPriorKnowledge = enabled;
        return this;
    }

    /**
     * Sets how long a QUIC attempt may take to establish. When discovery
     * chose QUIC and TCP is permitted, the client then connects over TCP
     * instead; when only HTTP/3 is permitted, the connection fails with an
     * error. The default is three seconds; 0 disables the deadline, so only
     * a prompt failure ends the attempt.
     *
     * @return this client
     */
    public HttpClient quicHandshakeTimeoutMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("ms must not be negative");
        }
        this.quicHandshakeTimeoutMs = ms;
        return this;
    }

    private boolean permitsH3() {
        return versions.contains(HttpVersion.HTTP_3);
    }

    private boolean permitsH2() {
        return versions.contains(HttpVersion.HTTP_2_0);
    }

    private boolean permitsH11() {
        return versions.contains(HttpVersion.HTTP_1_1);
    }

    private boolean permitsTcp() {
        return permitsH2() || permitsH11();
    }

    /** Only HTTP/3 is permitted: connect over QUIC with no discovery and no fallback. */
    private boolean forcesH3() {
        return permitsH3() && !permitsTcp();
    }

    /** @return this client */
    public HttpClient altSvcEnabled(boolean enabled) {
        this.altSvcEnabled = enabled;
        return this;
    }

    /** @return this client */
    public HttpClient dnsHttpsRecordEnabled(boolean enabled) {
        this.dnsHttpsRecordEnabled = enabled;
        return this;
    }

    /** @return this client */
    public HttpClient blockPrivateAddresses(boolean block) {
        this.blockPrivateAddresses = block;
        return this;
    }

    /** @return this client */
    public HttpClient idleTimeoutMs(long ms) {
        this.idleTimeoutMs = ms;
        return this;
    }

    /** @return this client */
    public HttpClient sendAcceptEncodingHeader(boolean send) {
        this.sendAcceptEncodingHeader = send;
        if (endpointHandler != null) {
            endpointHandler.setSendAcceptEncodingHeader(send);
        }
        if (h3Handler != null) {
            h3Handler.setSendAcceptEncodingHeader(send);
        }
        return this;
    }

    /** @return this client */
    public HttpClient decodeResponseContentCoding(boolean decode) {
        this.decodeResponseContentCoding = decode;
        if (endpointHandler != null) {
            endpointHandler.setDecodeResponseContentCoding(decode);
        }
        if (h3Handler != null) {
            h3Handler.setDecodeResponseContentCoding(decode);
        }
        return this;
    }

    /** @return this client */
    public HttpClient encodeRequestBodyContentCoding(boolean encode) {
        this.encodeRequestBodyContentCoding = encode;
        if (endpointHandler != null) {
            endpointHandler.setEncodeRequestBodyContentCoding(encode);
        }
        if (h3Handler != null) {
            h3Handler.setEncodeRequestBodyContentCoding(encode);
        }
        return this;
    }

    /** @return this client */
    public HttpClient connectionPool(ClientEndpointPool pool) {
        this.connectionPool = pool;
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Connects to the remote server.
     *
     * <p>Creates the transport factory, endpoint handler, and client
     * endpoint, then initiates the connection. Lifecycle events are
     * forwarded to the given handler.
     *
     * <p>Which transport and HTTP version is used follows {@link
     * #versions(HttpVersion...)}.
     *
     * @param gumdrop the runtime this connection is made under
     * @param handler the handler to receive connection lifecycle events
     */
    public void connect(Gumdrop gumdrop, final HttpClientHandler handler) {
        this.gumdrop = gumdrop;
        this.connectHandler = handler;
        if (socketPath == null && host == null && hostAddress == null) {
            handler.onError(new IllegalStateException(
                    "host, host address, or socketPath is required"));
            return;
        }
        if (Boolean.getBoolean("gumdrop.http.debug")) {
            Logger.getLogger(HttpClient.class.getName()).info(
                "[HttpClient] connect() "
                + (socketPath != null ? socketPath : (host != null ? host : hostAddress) + ":" + port));
        }

        if (h2WithPriorKnowledge && !permitsH2()) {
            handler.onError(new IllegalStateException(
                    "h2WithPriorKnowledge requires HTTP_2_0 in versions"));
            return;
        }

        if (socketPath != null) {
            if (forcesH3()) {
                handler.onError(new IOException(
                        "HTTP/3 is not supported over a UNIX domain socket"));
                return;
            }
            connectTcp(handler);
            return;
        }

        if (forcesH3()) {
            if (hostAddress != null) {
                try {
                    checkNotPrivate(hostAddress);
                } catch (IOException e) {
                    handler.onError(e);
                    return;
                }
                connectH3(hostAddress, port, host, handler, QUIC_FORCED);
            } else {
                resolveAndConnectH3(host, port, handler, QUIC_FORCED);
            }
            return;
        }

        discoverAndConnect(handler);
    }

    private DnsResolver effectiveResolver(SelectorLoop loop) {
        return dnsResolver != null ? dnsResolver : DnsResolver.forLoop(loop);
    }

    /**
     * Automatic transport negotiation, tier 1 (DNS HTTPS record) and tier 2
     * (cached Alt-Svc discovery), falling through to {@link #connectTcp}
     * (today's HTTP/2-via-ALPN-or-h2c / HTTP/1.1 behaviour) when neither
     * applies.
     *
     * <p>Skipped entirely -- straight to {@link #connectTcp} -- when there
     * is no hostname to query: a literal {@link InetAddress} was given at
     * construction, {@link #host} is itself a literal IP, or it's
     * {@code localhost} (matching {@link DnsResolver#resolve}'s own
     * loopback fast-path; a real DNS round trip for loopback targets would
     * otherwise slow down/break every test and tool connecting locally).
     */
    private void discoverAndConnect(final HttpClientHandler handler) {
        if (hostAddress != null || host == null) {
            connectTcp(handler);
            return;
        }
        if (!dnsHttpsRecordEnabled || ClientConnect.isUndiscoverableHost(host)) {
            // Skip only the DNS round trip -- the AltSvcCache tier is a
            // fast, in-memory lookup, worth checking even for localhost/
            // literal-IP targets.
            connectViaAltSvcCacheOrTcp(handler);
            return;
        }

        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            connectTcp(handler);
            return;
        }

        DnsResolver resolver = effectiveResolver(loop);
        resolver.queryHTTPS(host, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                byte[] echFromDns = HttpsRecordEch.firstEchConfigListFromAnswers(response.getAnswers());
                if (echFromDns != null) {
                    dnsDiscoveredEchConfigList = echFromDns;
                }
                for (DnsResourceRecord rr : response.getAnswers()) {
                    if (rr.getType() != DnsType.HTTPS || rr.isSVCBAliasForm()) {
                        continue;
                    }
                    if (permitsH3() && rr.getSVCBAlpnProtocols().contains("h3")) {
                        int svcbPort = rr.getSVCBPort();
                        int targetPort = svcbPort > 0 ? svcbPort : port;
                        resolveAndConnectH3(host, targetPort, handler, QUIC_DISCOVERED);
                        return;
                    }
                }
                connectViaAltSvcCacheOrTcp(handler);
            }

            @Override
            public void onError(String error) {
                connectViaAltSvcCacheOrTcp(handler);
            }
        });
    }

    private void connectViaAltSvcCacheOrTcp(HttpClientHandler handler) {
        AltSvcCache.Entry cached = permitsH3() ? AltSvcCache.get(host, port) : null;
        if (cached != null) {
            String altHost = cached.getH3Host();
            if (altHost != null) {
                resolveAndConnectH3(altHost, cached.getH3Port(), handler, QUIC_DISCOVERED);
            } else {
                resolveAndConnectH3(host, cached.getH3Port(), handler, QUIC_DISCOVERED);
            }
            return;
        }
        connectTcp(handler);
    }

    /**
     * Sets this client's TLS settings (certificates, trust, ECH and so on). The
     * settings are copied, so later changes to {@code source} are not seen.
     * Whether TLS is used at all is decided by {@link #secure(boolean)}.
     *
     * @param source the TLS configuration
     * @return this client
     */
    public HttpClient tls(TlsConfig source) {
        tls.copyFrom(source);
        return this;
    }

    public TlsConfig getTls() {
        return tls;
    }

    /**
     * Supplies DNS-discovered {@code ech} for a delegated client (e.g. WebSocket over HTTP/3).
     */
    public void setDnsDiscoveredEchConfigList(byte[] dnsDiscoveredEchConfigList) {
        this.dnsDiscoveredEchConfigList = dnsDiscoveredEchConfigList;
    }

    /**
     * Today's TCP-first behaviour: HTTP/2 via ALPN (secure) or h2c upgrade
     * (cleartext), else HTTP/1.1 -- all already automatic, plus the
     * existing reactive Alt-Svc upgrade once connected.
     */
    private void connectTcp(final HttpClientHandler handler) {
        transportFactory = new TcpTransportFactory();
        TlsConfig effectiveTls = ClientConnect.prepareTls(secure, tls, transportFactory);
        ClientConnect.applyTcpClientEch(transportFactory, dnsDiscoveredEchConfigList, effectiveTls);
        // RFC 9113 section 3.2 / RFC 7301: advertise HTTP/2 via ALPN on TLS so
        // the server can negotiate "h2". Without this the ClientHello carries
        // no ALPN protocols and the connection always falls back to HTTP/1.1,
        // even against an h2-capable server. "http/1.1" is offered as the
        // mandatory fallback token. HttpClientProtocolHandler.securityEstablished()
        // adopts whichever protocol the server selects. (h2c prior knowledge is
        // a cleartext path and does not use ALPN.)
        final boolean priorKnowledge = permitsH2()
                && (h2WithPriorKnowledge || (!secure && !permitsH11()));
        if (secure && permitsH2() && !priorKnowledge) {
            if (permitsH11()) {
                transportFactory.setApplicationProtocols("h2", "http/1.1");
            } else {
                transportFactory.setApplicationProtocols("h2");
            }
        }

        HttpClientHandler poolAwareHandler = connectionPool != null
                ? wrapHandlerForPool(handler) : handler;

        // RFC 9110 section 7.2 / RFC 9113 section 8.3.1: a UNIX domain
        // socket has no hostname of its own to put in the Host header /
        // :authority pseudo-header -- "localhost" matches common
        // convention for clients dialing a UNIX domain socket (e.g.
        // curl's --unix-socket). The matching default port keeps
        // sendHTTP11Request's port-suffix check from adding one.
        endpointHandler = (socketPath != null)
                ? new HttpClientProtocolHandler(
                        poolAwareHandler, "localhost", secure ? 443 : 80, secure)
                : new HttpClientProtocolHandler(
                        poolAwareHandler, host, port, secure);
        if (traceContext != null) {
            endpointHandler.setTraceContext(traceContext);
        }
        if (altSvcEnabled) {
            endpointHandler.setAltSvcListener(this);
        }
        if (username != null) {
            endpointHandler.credentials(username, password);
        }
        endpointHandler.setH2Enabled(permitsH2());
        endpointHandler.setH2cUpgradeEnabled(permitsH2() && permitsH11());
        if (priorKnowledge) {
            endpointHandler.setH2WithPriorKnowledge(true);
        }
        if (idleTimeoutMs > 0) {
            endpointHandler.setIdleTimeoutMs(idleTimeoutMs);
        }
        applyContentCodingSettings(endpointHandler);

        try {
            if (socketPath == null && hostAddress != null) {
                checkNotPrivate(hostAddress);
            }
            connectEndpointForTesting(endpointHandler);
        } catch (IOException e) {
            handler.onError(e);
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
    void connectEndpointForTesting(HttpClientProtocolHandler ph) throws IOException {
        if (socketPath != null) {
            clientEndpoint = (selectorLoop != null)
                    ? new ClientEndpoint(transportFactory, selectorLoop, socketPath)
                    : new ClientEndpoint(transportFactory, socketPath);
        } else if (hostAddress != null) {
            if (selectorLoop != null) {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, selectorLoop,
                        hostAddress, port);
            } else {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, hostAddress, port);
            }
        } else {
            if (selectorLoop != null) {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, selectorLoop,
                        host, port);
            } else {
                clientEndpoint = new ClientEndpoint(
                        transportFactory, host, port);
            }
        }
        applyDnsResolver(clientEndpoint);
        clientEndpoint.connect(gumdrop, ph);
    }

    private void applyDnsResolver(ClientEndpoint endpoint) {
        if (dnsResolver != null) {
            endpoint.setDnsResolver(dnsResolver);
        }
    }

    private HttpClientHandler wrapHandlerForPool(
            final HttpClientHandler delegate) {
        final AtomicBoolean registered = new AtomicBoolean(false);
        return new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                if (registered.compareAndSet(false, true)) {
                    registerWithPool(endpoint);
                }
                delegate.onConnected(endpoint);
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                delegate.onSecurityEstablished(info);
            }

            @Override
            public void onError(Exception cause) {
                delegate.onError(cause);
            }

            @Override
            public void onDisconnected() {
                delegate.onDisconnected();
            }
        };
    }

    private void registerWithPool(Endpoint ep) {
        if (connectionPool == null) {
            return;
        }
        InetAddress resolved = hostAddress;
        if (resolved == null && clientEndpoint != null) {
            resolved = clientEndpoint.getHost();
        }
        if (resolved != null) {
            SelectorLoop loop = selectorLoop;
            if (loop == null && clientEndpoint != null) {
                loop = clientEndpoint.getSelectorLoop();
            }
            ClientEndpointPool.PoolTarget target =
                    new ClientEndpointPool.PoolTarget(
                            resolved, port, secure, loop);
            poolEntry = connectionPool.register(target, ep);
        }
    }

    private void releaseToPool() {
        if (poolEntry != null) {
            connectionPool.release(poolEntry);
            poolEntry = null;
        }
    }

    /**
     * Connects to a target host using HTTP/3 over QUIC.
     *
     * @param targetAddress the address to connect to (may differ from origin)
     * @param targetPort the port to connect to
     * @param serverName the TLS SNI hostname (the original origin)
     * @param handler the handler to receive connection lifecycle events
     * @param mode {@link #QUIC_DISCOVERED} if discovery chose QUIC and TCP is
     *        permitted, so that a failed or stalled attempt connects over TCP
     *        instead of reporting an error; {@link #QUIC_FORCED} if only
     *        HTTP/3 is permitted, where a stalled attempt is an error;
     *        {@link #QUIC_UPGRADE} for the reactive Alt-Svc upgrade of a
     *        connection that already works, which has no deadline
     */
    private void connectH3(final InetAddress targetAddress,
                           final int targetPort,
                           final String serverName,
                           final HttpClientHandler handler,
                           final int mode) {
        final boolean fallback = mode == QUIC_DISCOVERED;
        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            quicFailed(fallback, handler, new IOException(
                    "No SelectorLoop available for HTTP/3"));
            return;
        }

        quicTransportFactory = new QuicTransportFactory();
        quicTransportFactory.setApplicationProtocols("h3");
        TlsConfig effective = ClientDefaults.effectiveTls(tls);
        ClientConnect.applyToQuicFactory(effective, quicTransportFactory);
        quicTransportFactory.setEarlyDataEnabled(tls.isEarlyDataEnabled());
        ClientConnect.applyQuicClientEch(quicTransportFactory, dnsDiscoveredEchConfigList, effective);

        try {
            quicTransportFactory.start();
        } catch (RuntimeException e) {
            quicFailed(fallback, handler, new IOException(
                    "Failed to start QUIC transport: " + e.getMessage()));
            return;
        }

        // PENDING until the handshake (or 0-RTT) commits to QUIC, or the
        // deadline abandons it for TCP; whichever comes first wins.
        final AtomicInteger attempt = new AtomicInteger(QUIC_PENDING);
        final AtomicReference<TimerHandle> deadline = new AtomicReference<TimerHandle>();

        try {
            quicEngine = openQuicForTesting(
                    targetAddress, targetPort,
                    new QuicEngine.ConnectionAcceptedHandler() {
                        @Override
                        public void connectionAccepted(
                                QuicConnection connection) {
                            if (!commitQuic(attempt, deadline)) {
                                return;
                            }
                            // Idempotent: if 0-RTT already constructed
                            // h3Handler and told the application the
                            // connection is ready (see EarlyDataHandler
                            // below), don't do so again here -- just flush
                            // anything deferred pending establishment.
                            // Either way, this callback is the one place
                            // that reports the handshake itself as done.
                            if (h3Handler == null) {
                                h3Handler = new Http3ClientHandler(connection);
                                applyContentCodingSettings(h3Handler);
                                handler.onConnected(null);
                            } else {
                                h3Handler.runDeferredRequests();
                            }
                            handler.onSecurityEstablished(
                                    connection.getSecurityInfo());
                        }
                    },
                    new QuicEngine.EarlyDataHandler() {
                        @Override
                        public void earlyDataReady(QuicConnection connection) {
                            if (!commitQuic(attempt, deadline)) {
                                return;
                            }
                            // RFC 9001 section 4.6.1: 0-RTT send keys are
                            // ready, well before the handshake completes.
                            // Construct h3Handler and let the application
                            // start issuing requests now -- H3Request gates
                            // any non-0-RTT-eligible method behind full
                            // establishment (see HttpMethodSafety), so this
                            // is safe even if the application immediately
                            // issues a POST.
                            h3Handler = new Http3ClientHandler(connection);
                            applyContentCodingSettings(h3Handler);
                            handler.onConnected(null);
                        }
                    },
                    loop, serverName);
        } catch (IOException e) {
            quicFailed(fallback, handler, e);
            return;
        }

        if (mode != QUIC_UPGRADE && quicHandshakeTimeoutMs > 0
                && attempt.get() == QUIC_PENDING) {
            deadline.set(scheduleQuicDeadlineForTesting(loop, quicHandshakeTimeoutMs,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (attempt.compareAndSet(QUIC_PENDING, QUIC_ABANDONED)) {
                                abandonQuic();
                                if (fallback) {
                                    connectTcp(handler);
                                } else {
                                    handler.onError(new IOException(
                                            "QUIC handshake did not complete within "
                                            + quicHandshakeTimeoutMs + " ms"));
                                }
                            }
                        }
                    }));
        }
    }

    /** QUIC chosen by discovery, TCP permitted: failure or a stall falls back to TCP. */
    private static final int QUIC_DISCOVERED = 0;
    /** Only HTTP/3 permitted: failure or a stall is an error. */
    private static final int QUIC_FORCED = 1;
    /** Reactive Alt-Svc upgrade of a working connection: no deadline. */
    private static final int QUIC_UPGRADE = 2;

    private static final int QUIC_PENDING = 0;
    private static final int QUIC_COMMITTED = 1;
    private static final int QUIC_ABANDONED = 2;

    /**
     * Marks the QUIC attempt as established, stopping its deadline. Returns
     * false if the deadline already abandoned the attempt for TCP, in which
     * case a late callback from the abandoned connection is ignored.
     */
    private static boolean commitQuic(AtomicInteger attempt,
                                      AtomicReference<TimerHandle> deadline) {
        if (attempt.get() == QUIC_ABANDONED) {
            return false;
        }
        attempt.set(QUIC_COMMITTED);
        TimerHandle timer = deadline.get();
        if (timer != null) {
            timer.cancel();
        }
        return true;
    }

    private void quicFailed(boolean fallback, HttpClientHandler handler,
                            IOException cause) {
        if (fallback) {
            abandonQuic();
            connectTcp(handler);
        } else {
            handler.onError(cause);
        }
    }

    private void abandonQuic() {
        QuicEngine engine = quicEngine;
        quicEngine = null;
        h3Handler = null;
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Test seam: opens the QUIC connection. Production behaviour opens a
     * real datagram socket; unit tests override this to script the outcome.
     */
    QuicEngine openQuicForTesting(InetAddress target, int targetPort,
            QuicEngine.ConnectionAcceptedHandler accepted,
            QuicEngine.EarlyDataHandler early, SelectorLoop loop,
            String serverName) throws IOException {
        return quicTransportFactory.connect(target, targetPort, accepted, early,
                loop, serverName);
    }

    /**
     * Test seam: schedules the deadline for a QUIC attempt that has a TCP
     * fallback. The task runs on the connection's loop thread.
     */
    TimerHandle scheduleQuicDeadlineForTesting(SelectorLoop loop, long delayMs,
                                               Runnable task) {
        QuicEngine engine = quicEngine;
        return engine != null ? gumdrop.scheduleTimer(engine, delayMs, task) : null;
    }

    private void resolveAndConnectH3(final String targetHost,
                                     final int targetPort,
                                     final HttpClientHandler handler,
                                     final int mode) {
        final boolean fallback = mode == QUIC_DISCOVERED;
        SelectorLoop loop = selectorLoop;
        if (loop == null) {
            loop = gumdrop.nextWorkerLoop();
        }
        if (loop == null) {
            handler.onError(new IOException(
                    "No SelectorLoop available for DNS resolution"));
            return;
        }
        DnsResolver resolver = effectiveResolver(loop);
        resolver.resolve(targetHost, new ResolveCallback() {
            @Override
            public void onResolved(List<InetAddress> addresses) {
                InetAddress resolved = addresses.get(0);
                try {
                    checkNotPrivate(resolved);
                } catch (IOException e) {
                    handler.onError(e);
                    return;
                }
                // With a TCP fallback the origin is still to be dialled by
                // name, so the alternative's address is not recorded.
                if (!fallback) {
                    hostAddress = resolved;
                }
                connectH3(resolved, targetPort, targetHost, handler, mode);
            }

            @Override
            public void onError(String error) {
                IOException cause = new IOException(
                        "DNS resolution failed for " + targetHost
                        + ": " + error);
                if (fallback) {
                    connectTcp(handler);
                } else {
                    handler.onError(cause);
                }
            }
        });
    }

    /**
     * Returns whether the connection is open and ready for requests.
     *
     * @return true if connected and open
     */
    public boolean isOpen() {
        if (h3Handler != null) {
            return !h3Handler.isGoaway();
        }
        return endpointHandler != null && endpointHandler.isOpen();
    }

    /**
     * Closes the connection and deregisters from Gumdrop's lifecycle
     * tracking.
     *
     * <p>The connection belongs to its selector loop, so the close
     * (GOAWAY, TLS {@code close_notify}, QUIC {@code CONNECTION_CLOSE}) is
     * handed to that loop and runs on its thread; this method returns
     * without waiting for it and may be called from any thread. If the loop
     * has already terminated it has closed the connection, and only the
     * lifecycle bookkeeping is done here.
     */
    public void close() {
        SelectorLoop loop = null;
        if (quicEngine != null) {
            loop = quicEngine.getSelectorLoop();
        } else if (clientEndpoint != null) {
            loop = clientEndpoint.getSelectorLoop();
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                closeConnection();
            }
        };
        if (loop == null) {
            // nothing network-side exists yet, so there is no loop to ask
            task.run();
        } else if (!loop.tryInvokeLater(task)) {
            deregisterAfterLoopTerminated();
        }
    }

    /** The close itself; runs on the connection's loop thread. */
    private void closeConnection() {
        if (h3Handler != null) {
            h3Handler.close();
        }
        if (quicEngine != null) {
            quicEngine.close();
        }
        if (poolEntry != null) {
            releaseToPool();
        } else {
            if (endpointHandler != null) {
                endpointHandler.close();
            }
            if (clientEndpoint != null) {
                clientEndpoint.close();
            }
        }
    }

    private void deregisterAfterLoopTerminated() {
        if (poolEntry != null) {
            releaseToPool();
        } else if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }

    /**
     * Returns the negotiated HTTP version.
     *
     * @return the HTTP version, or null if not yet negotiated
     */
    public HttpVersion getVersion() {
        if (h3Handler != null) {
            return HttpVersion.HTTP_3;
        }
        if (endpointHandler == null) {
            return null;
        }
        return endpointHandler.getVersion();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Request factory (delegates to endpoint handler)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a GET request.
     *
     * @param path the request path
     * @return the HTTP request
     */
    public HttpRequest get(String path, HttpResponseHandler handler) {
        return request(HttpMethod.GET, path, handler);
    }

    public HttpRequest post(String path, HttpResponseHandler handler) {
        return request(HttpMethod.POST, path, handler);
    }

    public HttpRequest put(String path, HttpResponseHandler handler) {
        return request(HttpMethod.PUT, path, handler);
    }

    public HttpRequest delete(String path, HttpResponseHandler handler) {
        return request(HttpMethod.DELETE, path, handler);
    }

    public HttpRequest head(String path, HttpResponseHandler handler) {
        return request(HttpMethod.HEAD, path, handler);
    }

    public HttpRequest options(String path, HttpResponseHandler handler) {
        return request(HttpMethod.OPTIONS, path, handler);
    }

    public HttpRequest patch(String path, HttpResponseHandler handler) {
        return request(HttpMethod.PATCH, path, handler);
    }

    public HttpRequest request(HttpMethod method, String path, HttpResponseHandler handler) {
        if (h3Handler != null) {
            String scheme = "https";
            String authority = host;
            if (port != 443) {
                authority = host + ":" + port;
            }
            return new org.bluezoo.gumdrop.http.h3.H3Request(
                    h3Handler, method.name(), path, authority, scheme, traceContext, handler);
        }
        return endpointHandler.request(method, path, handler);
    }

    /**
     * Initiates a WebSocket-over-HTTP/3 connection via Extended CONNECT
     * (RFC 9220 section 3). Requires {@link #setH3Enabled(boolean)} and a
     * completed connection (called after {@link HttpClientHandler#onSecurityEstablished}).
     *
     * @param path the request path
     * @param subprotocol the WebSocket subprotocol to request, or null
     * @param extensions the extensions to offer, or null/empty for none
     * @param wsHandler the handler to receive WebSocket events
     */
    public void connectWebSocket(String path, String subprotocol,
            List<WebSocketExtension> extensions, WebSocketEventHandler wsHandler) {
        if (h3Handler == null) {
            wsHandler.error(new IllegalStateException(
                    "WebSocket-over-HTTP/3 requires setH3Enabled(true) and an established connection"));
            return;
        }
        String authority = host;
        if (port != 443) {
            authority = host + ":" + port;
        }
        h3Handler.connectWebSocket(authority, path, subprotocol, extensions, wsHandler);
    }

    /**
     * Initiates a CONNECT-UDP tunnel over HTTP/3 Extended CONNECT (RFC
     * 9298 section 3). Requires {@link #setH3Enabled(boolean)} and a
     * completed connection (called after {@link HttpClientHandler#onSecurityEstablished}).
     *
     * @param targetHost the UDP target's host (hostname or literal address)
     * @param targetPort the UDP target's port
     * @param handler the handler to receive CONNECT-UDP events
     */
    public void connectUdp(String targetHost, int targetPort, ConnectUdpEventHandler handler) {
        if (h3Handler == null) {
            handler.error(new IllegalStateException(
                    "CONNECT-UDP over HTTP/3 requires setH3Enabled(true) and an established connection"));
            return;
        }
        String authority = host;
        if (port != 443) {
            authority = host + ":" + port;
        }
        h3Handler.connectUdp(authority, targetHost, targetPort, handler);
    }

    /**
     * Initiates a CONNECT-IP tunnel over HTTP/3 Extended CONNECT (RFC
     * 9484 section 4.4). Requires {@link #setH3Enabled(boolean)} and a
     * completed connection (called after {@link HttpClientHandler#onSecurityEstablished}).
     *
     * @param target the target scope hint ({@link
     *               org.bluezoo.gumdrop.http.ConnectIpTarget#WILDCARD}
     *               for "unspecified", the common case, or a hostname/IP prefix)
     * @param ipProto the IP protocol scope hint ({@link
     *                org.bluezoo.gumdrop.http.ConnectIpTarget#WILDCARD}
     *                for "unspecified", or a decimal Internet Protocol Number)
     * @param handler the handler to receive CONNECT-IP events
     */
    public void connectIp(String target, String ipProto, ConnectIpEventHandler handler) {
        if (h3Handler == null) {
            handler.error(new IllegalStateException(
                    "CONNECT-IP over HTTP/3 requires setH3Enabled(true) and an established connection"));
            return;
        }
        String authority = host;
        if (port != 443) {
            authority = host + ":" + port;
        }
        h3Handler.connectIp(authority, target, ipProto, handler);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alt-Svc discovery
    // ═══════════════════════════════════════════════════════════════════

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

        String altHost = null;
        if (parsed.hostLength > 0) {
            altHost = AltSvcListener.extractAltSvcHost(value, parsed.hostLength);
        }
        int altPort = parsed.port;

        // Cache for future connections/instances to this origin, regardless
        // of whether this instance itself upgrades below.
        AltSvcCache.put(host, port, altHost, altPort, parsed.maxAgeSeconds);

        // Reactive same-instance upgrade only applies once an h1/h2
        // connection is already established (endpointHandler set by
        // connectTcp/connect path). A standalone altSvcReceived() call
        // with no live connection should populate the cache only.
        if (endpointHandler == null) {
            return;
        }

        if (h3Handler != null || h3UpgradeInProgress || !permitsH3()) {
            return;
        }

        events().info("info.altsvc_h3_discovered")
                .attr("host", altHost != null ? altHost : host)
                .attr("port", altPort).emit();

        h3UpgradeInProgress = true;
        // RFC 7838's Alt-Svc is purely advisory for *future* requests to
        // this origin -- it says nothing about the request whose response
        // this header arrived on, which may still be streaming in (and,
        // for a non-idempotent method, may not yet be confirmed to have
        // taken effect). New requests already route to h3Handler as soon
        // as it's set (see request()), in parallel with this h1/h2
        // connection still draining -- that part is safe and unconditional
        // per the design. What must NOT happen is tearing down this
        // connection while it still has streams open: closeWhenIdle()
        // (called once h3 is ready, via the wrapper below) defers that
        // until every stream this connection already accepted has
        // genuinely finished, rather than aborting it out from under a
        // still-in-flight request.
        HttpClientHandler upgradeHandler = new AltSvcUpgradeHandler(connectHandler);
        if (altHost != null) {
            resolveAndConnectH3(altHost, altPort, upgradeHandler, QUIC_UPGRADE);
        } else if (hostAddress != null) {
            connectH3(hostAddress, altPort, host, upgradeHandler, QUIC_UPGRADE);
        } else {
            resolveAndConnectH3(host, altPort, upgradeHandler, QUIC_UPGRADE);
        }
    }

    // Wraps the application's own connectHandler for the Alt-Svc-triggered
    // same-instance h3 upgrade specifically (not the normal connect()
    // path, where there is no earlier h1/h2 connection to worry about):
    // once h3 is genuinely ready, tells the old connection it may close
    // once idle, then delegates to the real handler unchanged.
    private final class AltSvcUpgradeHandler implements HttpClientHandler {

        private final HttpClientHandler delegate;

        AltSvcUpgradeHandler(HttpClientHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            if (endpointHandler != null) {
                endpointHandler.closeWhenIdle();
            }
            delegate.onConnected(endpoint);
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            delegate.onSecurityEstablished(info);
        }

        @Override
        public void onError(Exception cause) {
            delegate.onError(cause);
        }

        @Override
        public void onDisconnected() {
            delegate.onDisconnected();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLI entry point
    // ═══════════════════════════════════════════════════════════════════

    private static void printUsage(PrintStream err) {
        err.println(
                "Usage: HttpClient [options] <URL>\n"
                + "\n"
                + "Options:\n"
                + "  -X <method>       HTTP method (default: GET)\n"
                + "  -H <name:value>   Add request header (repeatable)\n"
                + "  -d <file>         Request body from file (- for stdin)\n"
                + "  -o <file>         Write response body to file"
                        + " (default: stdout)\n"
                + "  --http1.1         Force HTTP/1.1 only\n"
                + "  --http2           Force HTTP/2"
                        + " (prior knowledge / ALPN)\n"
                + "  --http3           Force HTTP/3 (QUIC)\n"
                + "  -E <cert>:<key>   PEM client certificate and key\n"
                + "  -k                Skip TLS peer certificate verification"
                        + " (INSECURE; debugging only)\n"
                + "  -v                Verbose (print response headers)\n"
                + "  -I                HEAD request (headers only)\n"
                + "\n"
                + "URL format: [http|https]://host[:port][/path]\n"
                + "Default port: 80 for http, 443 for https\n");
    }

    /**
     * The parsed command line of {@link #main}.
     */
    static final class CliOptions {
        String method = "GET";
        final List<String> requestHeaders = new ArrayList<String>();
        String bodyFile;
        String outputFile;
        String forceVersion;
        String pemCert;
        String pemKey;
        boolean skipVerify;
        boolean verbose;
        boolean headersOnly;
        String scheme;
        String host;
        int port;
        String path;
    }

    /**
     * Parses the command line, writing a diagnostic to {@code err} and
     * returning {@code null} if it is not valid.
     *
     * @param args command-line arguments
     * @param err where diagnostics are written
     * @return the parsed options, or null if the command line is invalid
     */
    static CliOptions parseArguments(String[] args, PrintStream err) {
        CliOptions options = new CliOptions();
        String url = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-X".equals(arg)) {
                if (++i >= args.length) {
                    err.println("Missing argument for -X");
                    return null;
                }
                options.method = args[i];
            } else if ("-H".equals(arg)) {
                if (++i >= args.length) {
                    err.println("Missing argument for -H");
                    return null;
                }
                options.requestHeaders.add(args[i]);
            } else if ("-d".equals(arg)) {
                if (++i >= args.length) {
                    err.println("Missing argument for -d");
                    return null;
                }
                options.bodyFile = args[i];
            } else if ("-o".equals(arg)) {
                if (++i >= args.length) {
                    err.println("Missing argument for -o");
                    return null;
                }
                options.outputFile = args[i];
            } else if ("--http1.1".equals(arg)) {
                options.forceVersion = "1.1";
            } else if ("--http2".equals(arg)) {
                options.forceVersion = "2";
            } else if ("--http3".equals(arg)) {
                options.forceVersion = "3";
            } else if ("-E".equals(arg)) {
                if (++i >= args.length) {
                    err.println("Missing argument for -E");
                    return null;
                }
                String certKeyArg = args[i];
                int colonPos = certKeyArg.indexOf(':');
                if (colonPos < 0) {
                    err.println("Invalid -E format, expected cert:key");
                    return null;
                }
                options.pemCert = certKeyArg.substring(0, colonPos);
                options.pemKey = certKeyArg.substring(colonPos + 1);
            } else if ("-k".equals(arg)) {
                options.skipVerify = true;
            } else if ("-v".equals(arg)) {
                options.verbose = true;
            } else if ("-I".equals(arg)) {
                options.headersOnly = true;
                options.method = "HEAD";
            } else if (arg.startsWith("-")) {
                err.println("Unknown option: " + arg);
                printUsage(err);
                return null;
            } else {
                url = arg;
            }
        }

        if (url == null) {
            printUsage(err);
            return null;
        }

        String hostPort;
        if (url.startsWith("https://")) {
            options.scheme = "https";
            hostPort = url.substring(8);
        } else if (url.startsWith("http://")) {
            options.scheme = "http";
            hostPort = url.substring(7);
        } else {
            err.println("URL must start with http:// or https://");
            return null;
        }

        int slashPos = hostPort.indexOf('/');
        if (slashPos >= 0) {
            options.path = hostPort.substring(slashPos);
            hostPort = hostPort.substring(0, slashPos);
        } else {
            options.path = "/";
        }

        int colonPos = hostPort.lastIndexOf(':');
        if (colonPos >= 0) {
            options.host = hostPort.substring(0, colonPos);
            try {
                options.port = Integer.parseInt(
                        hostPort.substring(colonPos + 1));
            } catch (NumberFormatException e) {
                err.println("Invalid port number");
                return null;
            }
        } else {
            options.host = hostPort;
            options.port = "https".equals(options.scheme) ? 443 : 80;
        }
        return options;
    }

    /**
     * CLI entry point for making HTTP requests.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        CliOptions options = parseArguments(args, System.err);
        if (options == null) {
            System.exit(1);
            return;
        }
        String method = options.method;
        List<String> requestHeaders = options.requestHeaders;
        String bodyFile = options.bodyFile;
        String outputFile = options.outputFile;
        String forceVersion = options.forceVersion;
        String pemCert = options.pemCert;
        String pemKey = options.pemKey;
        boolean skipVerify = options.skipVerify;
        boolean verbose = options.verbose;
        boolean headersOnly = options.headersOnly;
        String scheme = options.scheme;
        String path = options.path;
        String targetHost = options.host;
        int targetPort = options.port;

        Gumdrop gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
        SelectorLoop loop = gumdrop.nextWorkerLoop();

        try {
            runRequest(gumdrop, loop, targetHost, targetPort, scheme, path, method,
                    requestHeaders, bodyFile, outputFile, forceVersion,
                    pemCert, pemKey, skipVerify, verbose, headersOnly);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            gumdrop.shutdown();
            try {
                gumdrop.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            System.exit(1);
        }

        gumdrop.shutdown();
        try {
            gumdrop.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    // Public (not private) so integration tests can drive the CLI's
    // actual request/response file handling without going through
    // main()'s System.exit() calls.
    public static void runRequest(
            final Gumdrop gumdrop,
            final SelectorLoop loop,
            final String targetHost, final int targetPort,
            final String scheme, final String path,
            final String method, final List<String> requestHeaders,
            final String bodyFile, final String outputFile,
            final String forceVersion,
            final String pemCert, final String pemKey,
            final boolean skipVerify,
            final boolean verbose, final boolean headersOnly)
            throws Exception {

        final HttpClient client =
                new HttpClient(loop, targetHost, targetPort);

        boolean isSecure = "https".equals(scheme);
        TlsConfig clientTls = new TlsConfig().verifyPeer(!skipVerify);
        if (pemCert != null) {
            clientTls.certFile(Path.of(pemCert));
        }
        if (pemKey != null) {
            clientTls.keyFile(Path.of(pemKey));
        }
        client.secure(isSecure).tls(clientTls);

        if ("3".equals(forceVersion)) {
            client.versions(HttpVersion.HTTP_3);
        } else if ("2".equals(forceVersion)) {
            // over cleartext, HTTP/2 alone means prior knowledge
            client.versions(HttpVersion.HTTP_2_0);
        } else if ("1.1".equals(forceVersion)) {
            client.versions(HttpVersion.HTTP_1_1);
        }

        client.altSvcEnabled(false);

        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<Exception> connectError = new AtomicReference<Exception>();

        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connectLatch.countDown();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                connectLatch.countDown();
            }

            @Override
            public void onError(Exception cause) {
                connectError.set(cause);
                connectLatch.countDown();
                doneLatch.countDown();
            }

            @Override
            public void onDisconnected() {
                doneLatch.countDown();
            }
        });

        connectLatch.await();

        Exception connErr = connectError.get();
        if (connErr != null) {
            throw connErr;
        }

        if (verbose) {
            HttpVersion version = client.getVersion();
            if (version != null) {
                System.err.println("* Connected via " + version);
            }
        }

        final boolean outputToStdout = outputFile == null || "-".equals(outputFile);
        final WritableByteChannel out;
        if (!outputToStdout) {
            out = FileChannel.open(Path.of(outputFile),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            out = Channels.newChannel(System.out);
        }

        final AtomicReference<Exception> responseError = new AtomicReference<Exception>();
        final CountDownLatch responseLatch = new CountDownLatch(1);

        HttpRequest req = client.request(HttpMethod.of(method), path, createResponseHandler(
                out, outputToStdout, verbose, headersOnly, responseLatch,
                responseError, client));

        for (int i = 0; i < requestHeaders.size(); i++) {
            String hdr = requestHeaders.get(i);
            int cp = hdr.indexOf(':');
            if (cp > 0) {
                String name = hdr.substring(0, cp).trim();
                String value = hdr.substring(cp + 1).trim();
                req.header(name, value);
            }
        }

        if (bodyFile != null) {
            boolean bodyFromStdin = "-".equals(bodyFile);
            ReadableByteChannel bodyIn = bodyFromStdin
                    ? Channels.newChannel(System.in)
                    : FileChannel.open(Path.of(bodyFile), StandardOpenOption.READ);
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n;
            while ((n = bodyIn.read(buf)) >= 0) {
                if (n > 0) {
                    buf.flip();
                    req.bodyContent(buf);
                    buf.clear();
                }
            }
            if (!bodyFromStdin) {
                bodyIn.close();
            }
        }
        req.endMessage();

        responseLatch.await();

        if (!outputToStdout) {
            out.close();
        }

        client.close();

        Exception respErr = responseError.get();
        if (respErr != null) {
            throw respErr;
        }
    }

    static HttpResponseHandler createResponseHandler(
            final WritableByteChannel out,
            final boolean outputToStdout,
            final boolean verbose,
            final boolean headersOnly,
            final CountDownLatch doneLatch,
            final AtomicReference<Exception> errorRef,
            final HttpClient client) {
        return new DefaultHttpResponseHandler() {

            @Override
            public void status(int code) {
                if (verbose || headersOnly) {
                    HttpVersion version = client.getVersion();
                    String versionStr = version != null
                            ? version.toString() : "HTTP/?";
                    System.err.println(versionStr + " " + code + " "
                            + HttpStatus.fromCode(code));
                }
            }

            @Override
            public void contentType(ContentType contentType) {
                if (verbose || headersOnly) {
                    System.err.println("content-type: " + contentType.toHeaderValue());
                }
            }

            @Override
            public void contentDisposition(ContentDisposition contentDisposition) {
                if (verbose || headersOnly) {
                    System.err.println("content-disposition: " + contentDisposition.toHeaderValue());
                }
            }

            @Override
            public void longHeader(String name, long value) {
                if (verbose || headersOnly) {
                    System.err.println(name + ": " + value);
                }
            }

            @Override
            public void dateHeader(String name, java.time.Instant value) {
                if (verbose || headersOnly) {
                    System.err.println(name + ": " + new HttpDateFormat().format(value.toEpochMilli()));
                }
            }

            @Override
            public void header(String name, ByteBuffer value) {
                if (verbose || headersOnly) {
                    byte[] octets = new byte[value.remaining()];
                    value.duplicate().get(octets);
                    System.err.println(name + ": "
                            + new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }

            @Override
            public void endHeaders() {
                if (verbose || headersOnly) {
                    System.err.println();
                }
            }

            @Override
            public void bodyContent(ByteBuffer data) {
                if (headersOnly) {
                    return;
                }
                try {
                    while (data.hasRemaining()) {
                        out.write(data);
                    }
                    if (outputToStdout) {
                        System.out.flush();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            L10N.getString("warn.response_body_write_error"), e);
                }
            }

            @Override
            public void endMessage() {
                doneLatch.countDown();
            }

            @Override
            public void failed(Exception ex) {
                errorRef.set(ex);
                doneLatch.countDown();
            }
        };
    }
}
