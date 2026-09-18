/*
 * Http2Listener.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.http.server;


import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.TlsConfigSupport;
import org.bluezoo.gumdrop.tls.TlsConfig;

import java.net.InetAddress;

/**
 * TCP transport listener for HTTP/2 over TLS, with HTTP/1.1 fallback.
 *
 * <p>RFC 9113 section 3.2: HTTP/2 over TLS uses ALPN {@code h2}; {@code
 * http/1.1} is negotiated when the client does not offer {@code h2}. Pair
 * with {@link org.bluezoo.gumdrop.http.h3.Http3Listener} for the
 * recommended production stack (HTTPS + HTTP/3).
 *
 * <p>Default ports: 80 (HTTP), 443 (HTTPS) per RFC 9110 section 4.2.
 *
 * <p>This is the transport endpoint; application logic is provided by
 * an {@link org.bluezoo.gumdrop.http.HttpServer} which sets the request
 * router and authentication provider during wiring.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.h3.Http3Listener
 */
public class Http2Listener extends TcpListener {

    protected static final int HTTP_DEFAULT_PORT = 80;
    protected static final int HTTPS_DEFAULT_PORT = 443;

    protected int port = -1;

    /**
     * HTTP/2 frame padding amount for server-originated frames.
     * Padding can be used to obscure the actual size of DATA, HEADERS,
     * and PUSH_PROMISE frames for security purposes (RFC 9113 section 6.1).
     * Value must be between 0-255 bytes.
     */
    protected int framePadding = 0;

    /**
     * RFC 9113 section 5.1.2: maximum number of concurrent HTTP/2 streams
     * the server will accept per connection.  Advertised to the client via
     * SETTINGS_MAX_CONCURRENT_STREAMS (section 6.5.2).
     */
    private int maxConcurrentStreams = 100;

    public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 8192;

    /**
     * Default maximum request body size: 64 MB. Bounds memory and handler
     * exposure to oversized uploads. Deployments can raise this or set
     * {@code 0} (unlimited) via {@link #setMaxRequestBodySize(long)}.
     */
    public static final long DEFAULT_MAX_REQUEST_BODY_SIZE = 64L * 1024 * 1024;

    /**
     * RFC 9113 section 6.5.2: maximum header list size advertised to clients.
     */
    private int maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE;

    /**
     * RFC 9110 section 15.5.14: maximum request body size in bytes.
     * {@code 0} means unlimited.
     */
    private long maxRequestBodySize = DEFAULT_MAX_REQUEST_BODY_SIZE;

    /**
     * Authentication provider for HTTP connections created by this endpoint.
     */
    private HttpAuthenticationProvider authenticationProvider;

    /**
     * Stream handler for binding {@link HttpRequestHandler} instances.
     * If null, the default 404 behaviour is used.
     */
    private HttpStreamHandler streamHandler;

    /**
     * Alt-Svc header value to inject into responses, or null.
     * Set by the owning HttpServer when an HTTP/3 listener is also
     * configured.
     */
    private String altSvc;

    /**
     * RFC 9112 section 9.8: idle connection timeout in milliseconds.
     * Connections that receive no data for this duration are closed.
     * 0 means no timeout (default).
     */
    private long idleTimeoutMs = 0;

    /**
     * RFC 9112 section 9.6: maximum requests per persistent connection.
     * After this many requests, the server sends Connection: close.
     * 0 means unlimited (default).
     */
    private int maxRequestsPerConnection = 0;

    /**
     * RFC 9110 section 9.3.8: whether TRACE method is enabled.
     * Disabled by default for security reasons.
     */
    private boolean traceMethodEnabled = false;

    /**
     * RFC 9113 section 6.7: PING keep-alive interval in milliseconds.
     * The server sends periodic PING frames on idle HTTP/2 connections
     * to detect dead connections. 0 means disabled (default).
     */
    private long pingIntervalMs = 0;

    /**
     * Metrics for this endpoint (null if telemetry is not enabled).
     */
    private HttpServerMetrics metrics;

    /**
     * Whether to add default security headers to responses. Default: true.
     */
    private boolean addSecurityHeaders = true;

    private boolean hstsEnabled;
    private long hstsMaxAge = HstsPolicy.DEFAULT_MAX_AGE_SECONDS;
    private boolean hstsIncludeSubDomains;
    private boolean hstsPreload;

    /**
     * Whether to compress response bodies when the client advertises
     * {@code Accept-Encoding} (Brotli preferred, then gzip, then deflate).
     * Uses {@code Content-Encoding} on the response. Default: true.
     */
    private boolean compressResponses = true;

    public String getDescription() {
        return secure ? "https" : "http";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Sets the TCP port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public Http2Listener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public Http2Listener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public Http2Listener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public Http2Listener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public Http2Listener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? HTTPS_DEFAULT_PORT : HTTP_DEFAULT_PORT;
        }
        if (isMetricsEnabled()) {
            metrics = new HttpServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the HTTP server metrics
     */
    public HttpServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * No-op: server channel cleanup is handled centrally by
     * {@link org.bluezoo.gumdrop.TcpListener#closeServerChannels} during
     * unregister/shutdown, so individual listeners do not need to
     * close their own channels.
     */
    public void stop() {
        // Gumdrop.closeServerChannels() handles cleanup centrally
    }

    /**
     * Gets the HTTP/2 frame padding amount for server-originated frames.
     *
     * @return padding amount in bytes (0-255)
     */
    public int getFramePadding() {
        return framePadding;
    }

    /**
     * Sets the HTTP/2 frame padding amount for server-originated frames.
     *
     * @param framePadding padding amount in bytes (0-255)
     * @throws IllegalArgumentException if padding is outside valid range
     */
    /**
     * Returns the maximum number of concurrent HTTP/2 streams per connection.
     *
     * @return the maximum concurrent streams
     */
    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /**
     * Sets the maximum number of concurrent HTTP/2 streams per connection.
     * Advertised via SETTINGS_MAX_CONCURRENT_STREAMS.
     * XML property name: {@code max-concurrent-streams}
     *
     * @param maxConcurrentStreams the limit (must be positive)
     */
    public void setMaxConcurrentStreams(int maxConcurrentStreams) {
        if (maxConcurrentStreams < 1) {
            throw new IllegalArgumentException(
                    "maxConcurrentStreams must be positive, got: "
                            + maxConcurrentStreams);
        }
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    /**
     * Returns the maximum HTTP/2 header list size per connection.
     */
    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Sets the maximum HTTP/2 header list size advertised via
     * SETTINGS_MAX_HEADER_LIST_SIZE.
     * XML property name: {@code max-header-list-size}
     */
    public void setMaxHeaderListSize(int maxHeaderListSize) {
        if (maxHeaderListSize < 1) {
            throw new IllegalArgumentException(
                    "maxHeaderListSize must be positive, got: "
                            + maxHeaderListSize);
        }
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Returns the maximum request body size in bytes ({@code 0} = unlimited).
     */
    public long getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    /**
     * Sets the maximum request body size enforced for HTTP/1 and HTTP/2
     * requests. Requests exceeding this limit receive status 413.
     * XML property name: {@code max-request-body-size}
     *
     * @param maxRequestBodySize the limit in bytes, or {@code 0} for unlimited
     */
    public void setMaxRequestBodySize(long maxRequestBodySize) {
        if (maxRequestBodySize < 0) {
            throw new IllegalArgumentException(
                    "maxRequestBodySize must not be negative, got: "
                            + maxRequestBodySize);
        }
        this.maxRequestBodySize = maxRequestBodySize;
    }

    public void setFramePadding(int framePadding) {
        if (framePadding < 0 || framePadding > 255) {
            throw new IllegalArgumentException(
                    "Frame padding must be between 0-255 bytes, got: "
                            + framePadding);
        }
        this.framePadding = framePadding;
    }

    /**
     * Sets the authentication provider for this endpoint.
     *
     * @param provider the authentication provider, or null to disable
     */
    public void setAuthenticationProvider(
            HttpAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Returns the authentication provider for this endpoint.
     *
     * @return the authentication provider, or null if not configured
     */
    public HttpAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    public void setStreamHandler(HttpStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    public HttpStreamHandler getStreamHandler() {
        return streamHandler;
    }

    /**
     * Sets whether to add default security headers to responses.
     * XML property: {@code add-security-headers}
     */
    public void setAddSecurityHeaders(boolean addSecurityHeaders) {
        this.addSecurityHeaders = addSecurityHeaders;
    }

    /**
     * Returns whether default security headers are added to responses.
     */
    public boolean getAddSecurityHeaders() {
        return addSecurityHeaders;
    }

    /**
     * Sets the RFC 6797 HSTS policy for this listener. Ignored on
     * plaintext (non-TLS) listeners.
     *
     * @param hstsPolicy the policy, or {@code null} for disabled
     */
    public void setHstsPolicy(HstsPolicy hstsPolicy) {
        if (hstsPolicy == null || !hstsPolicy.isEnabled()) {
            hstsEnabled = false;
            return;
        }
        hstsEnabled = true;
        hstsMaxAge = hstsPolicy.getMaxAgeSeconds();
        hstsIncludeSubDomains = hstsPolicy.isIncludeSubDomains();
        hstsPreload = hstsPolicy.isPreload();
    }

    /**
     * Returns the configured HSTS policy.
     */
    public HstsPolicy getHstsPolicy() {
        return buildHstsPolicy();
    }

    /**
     * Enables or disables HSTS. XML property: {@code hsts-enabled}
     */
    public void setHstsEnabled(boolean enabled) {
        hstsEnabled = enabled;
    }

    /**
     * @return whether HSTS is enabled on this listener
     */
    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    /**
     * Sets {@code max-age} for HSTS. XML property: {@code hsts-max-age}
     */
    public void setHstsMaxAge(long maxAgeSeconds) {
        if (maxAgeSeconds < 0) {
            throw new IllegalArgumentException("max-age must be non-negative");
        }
        hstsMaxAge = maxAgeSeconds;
    }

    /**
     * XML property: {@code hsts-include-subdomains}
     */
    public void setHstsIncludeSubDomains(boolean includeSubDomains) {
        hstsIncludeSubDomains = includeSubDomains;
    }

    /**
     * XML property: {@code hsts-preload}
     */
    public void setHstsPreload(boolean preload) {
        hstsPreload = preload;
    }

    private HstsPolicy buildHstsPolicy() {
        if (!hstsEnabled) {
            return HstsPolicy.disabled();
        }
        return HstsPolicy.enabled(hstsMaxAge)
                .includeSubDomains(hstsIncludeSubDomains)
                .preload(hstsPreload);
    }

    /**
     * Returns the {@code Strict-Transport-Security} header value for
     * responses on this listener, or {@code null} if HSTS must not be sent.
     */
    public String getStrictTransportSecurityHeaderValue() {
        if (!isSecure()) {
            return null;
        }
        return buildHstsPolicy().headerValue();
    }

    /**
     * Sets whether response bodies may be compressed via {@code Content-Encoding}.
     * XML property: {@code compress-responses}
     */
    public void setCompressResponses(boolean compressResponses) {
        this.compressResponses = compressResponses;
    }

    /**
     * Returns whether response body compression is enabled for this endpoint.
     */
    public boolean getCompressResponses() {
        return compressResponses;
    }

    /**
     * Sets the Alt-Svc header value to inject into HTTP responses.
     * Typically set by the owning service to advertise HTTP/3.
     *
     * @param altSvc the Alt-Svc header value, or null to disable
     */
    public void setAltSvc(String altSvc) {
        this.altSvc = altSvc;
    }

    /**
     * Returns the Alt-Svc header value, or null if not set.
     *
     * @return the Alt-Svc header value
     */
    public String getAltSvc() {
        return altSvc;
    }

    /**
     * RFC 9112 section 9.8: idle connection timeout in milliseconds.
     * @return the idle timeout, or 0 for no timeout
     */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    /**
     * Sets the idle connection timeout.
     * XML property name: {@code idle-timeout-ms}
     * @param idleTimeoutMs timeout in milliseconds, 0 to disable
     */
    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * RFC 9112 section 9.6: maximum requests per persistent connection.
     * @return the limit, or 0 for unlimited
     */
    public int getMaxRequestsPerConnection() {
        return maxRequestsPerConnection;
    }

    /**
     * Sets the maximum requests per persistent connection.
     * XML property name: {@code max-requests-per-connection}
     * @param maxRequestsPerConnection the limit, 0 for unlimited
     */
    public void setMaxRequestsPerConnection(int maxRequestsPerConnection) {
        this.maxRequestsPerConnection = maxRequestsPerConnection;
    }

    /**
     * RFC 9110 section 9.3.8: whether the TRACE method is enabled.
     * @return true if TRACE is enabled
     */
    public boolean isTraceMethodEnabled() {
        return traceMethodEnabled;
    }

    /**
     * Enables or disables the TRACE method.
     * XML property name: {@code trace-method-enabled}
     * @param traceMethodEnabled true to enable
     */
    public void setTraceMethodEnabled(boolean traceMethodEnabled) {
        this.traceMethodEnabled = traceMethodEnabled;
    }

    /**
     * RFC 9113 section 6.7: PING keep-alive interval for HTTP/2.
     * @return the interval in milliseconds, or 0 if disabled
     */
    public long getPingIntervalMs() {
        return pingIntervalMs;
    }

    /**
     * Sets the PING keep-alive interval for HTTP/2 connections.
     * XML property name: {@code ping-interval-ms}
     * @param pingIntervalMs interval in milliseconds, 0 to disable
     */
    public void setPingIntervalMs(long pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new HttpProtocolHandler(this, framePadding, maxConcurrentStreams,
                maxHeaderListSize);
    }

    // RFC 9113 section 3.2: HTTP/2 over TLS uses ALPN (RFC 7301)
    // with "h2" as the protocol identifier. "http/1.1" is the fallback.
    @Override
    protected void configureTransportFactory(TransportFactory factory) {
        super.configureTransportFactory(factory);
        if (secure && factory instanceof TcpTransportFactory) {
            TcpTransportFactory tcpFactory = (TcpTransportFactory) factory;
            tcpFactory.setApplicationProtocols("h2", "http/1.1");
        }
    }

    /**
     * @deprecated use {@code new Http2Listener().port(...)} fluent configuration.
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated use fluent methods on {@link Http2Listener} instead.
     */
    @Deprecated
    public static final class Builder {

        private int port = -1;
        private String addresses;
        private boolean secure;
        private TlsConfig tls;

        private Builder() {
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder addresses(String addresses) {
            this.addresses = addresses;
            return this;
        }

        /**
         * Marks this listener as HTTPS (TLS on TCP). Requires
         * {@link #tls(TlsConfig)} or legacy keystore setters before
         * {@link Listener#start()}.
         */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /**
         * Sets the server TLS identity (PEM, keystore, or credentials).
         *
         * <p>When {@link #secure(boolean)} is {@code true}, this supplies
         * the certificate chain and private key for HTTPS (HTTP/2 with
         * HTTP/1.1 fallback over TLS).
         */
        public Builder tls(TlsConfig tls) {
            if (tls == null) {
                throw new NullPointerException("tls");
            }
            this.tls = tls;
            return this;
        }

        /**
         * @deprecated use {@link #tls(TlsConfig)}.
         */
        @Deprecated
        public Builder tls(HttpTlsConfig tls) {
            if (tls == null) {
                throw new NullPointerException("tls");
            }
            return tls(tls.unwrap());
        }

        public Http2Listener build() {
            Http2Listener listener = new Http2Listener();
            listener.setPort(port);
            if (addresses != null) {
                listener.setAddresses(addresses);
            }
            listener.setSecure(secure);
            if (tls != null) {
                TlsConfigSupport.apply(tls, listener);
            }
            return listener;
        }
    }

}
