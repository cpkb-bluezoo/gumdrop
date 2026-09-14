/*
 * Http3Listener.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HandlerFactoryStreamHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandlerFactory;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.HttpServerMetrics;
import org.bluezoo.gumdrop.TlsConfigSupport;
import org.bluezoo.gumdrop.http.server.HttpTlsConfig;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicEngine;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * QUIC transport listener for HTTP/3 connections.
 *
 * <p>Pair with {@link Http2Listener} for the recommended production stack
 * (HTTPS + HTTP/3 on the same port). It creates a
 * {@link QuicTransportFactory} with ALPN "h3" (RFC 9114 section 3.1),
 * binds to the configured UDP port, and installs an
 * {@link Http3ServerHandler} on each new QUIC connection to dispatch
 * requests to the gumdrop {@link org.bluezoo.gumdrop.http.HttpRequestHandler}
 * API.
 *
 * <p>Per RFC 9114 section 3, HTTP/3 runs exclusively over QUIC
 * (RFC 9000) with mandatory TLS 1.3 (RFC 9001). Retry-based address
 * validation (RFC 9000 section 8.1.2) is on by default; set
 * {@code require-retry} to {@code false} for a trusted path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Http3ServerHandler
 * @see H3Stream
 */
public class Http3Listener extends TcpListener
        implements QuicEngine.ConnectionAcceptedHandler {

    private static final Logger LOGGER =
            Logger.getLogger(Http3Listener.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    private static final int HTTP3_DEFAULT_PORT = 443;

    private int port = -1;

    private HttpStreamHandler streamHandler;
    private HttpAuthenticationProvider authenticationProvider;
    private HttpServerMetrics metrics;
    private SelectorLoop selectorLoop;
    private boolean addSecurityHeaders = true;

    private Path certFile;
    private Path keyFile;

    // RFC 9000 section 8.1.2: Retry-based address validation. Default
    // on for public-facing listeners; set false for a trusted network
    // that already anti-spoofs (the factory itself still defaults off
    // so programmatic/test engines are unchanged).
    private boolean requireRetry = true;

    // RFC 9000 section 18: configurable QUIC transport parameters
    private long quicMaxIdleTimeout = -1;
    private long quicMaxData = -1;
    private long quicMaxStreamDataBidiLocal = -1;
    private long quicMaxStreamDataBidiRemote = -1;
    private long quicMaxStreamDataUni = -1;
    private long quicMaxStreamsBidi = -1;
    private long quicMaxStreamsUni = -1;

    private final List<QuicEngine> engines =
            new ArrayList<QuicEngine>();

    @Override
    public String getDescription() {
        return "h3";
    }

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port to listen on.
     *
     * @param port the UDP port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Sets the UDP port. Returns {@code this} for fluent configuration.
     */
    public Http3Listener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public Http3Listener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public Http3Listener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public Http3Listener secure(boolean flag) {
        super.secure(flag);
        return this;
    }


    @Override
    public Http3Listener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    /**
     * Sets whether RFC 9000 Retry-based address validation is required.
     * Default {@code true}.
     */
    public Http3Listener requireRetry(boolean requireRetry) {
        setRequireRetry(requireRetry);
        return this;
    }

    /**
     * Sets the PEM certificate chain file for QUIC TLS 1.3.
     *
     * @param path the PEM file path
     */
    public void setCertFile(Path path) {
        this.certFile = path;
    }

    public void setCertFile(String path) {
        this.certFile = Path.of(path);
    }

    /**
     * Sets the PEM private key file for QUIC TLS 1.3.
     *
     * @param path the PEM file path
     */
    public void setKeyFile(Path path) {
        this.keyFile = path;
    }

    public void setKeyFile(String path) {
        this.keyFile = Path.of(path);
    }

    /**
     * Sets the handler factory for this endpoint.
     *
     * @param factory the handler factory, or null
     */
    public void setStreamHandler(HttpStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    public HttpStreamHandler getStreamHandler() {
        return streamHandler;
    }

    /**
     * @deprecated use {@link #setStreamHandler(HttpStreamHandler)}.
     */
    @Deprecated
    public void setHandlerFactory(HttpRequestHandlerFactory factory) {
        this.streamHandler = factory != null
                ? new HandlerFactoryStreamHandler(factory) : null;
    }

    /**
     * @deprecated use {@link #getStreamHandler()}.
     */
    @Deprecated
    public HttpRequestHandlerFactory getHandlerFactory() {
        return null;
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
     * Returns the authentication provider for this endpoint.
     *
     * @return the authentication provider, or null if not configured
     */
    public HttpAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
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
     * Returns the SelectorLoop used for QUIC datagram I/O.
     *
     * @return the selector loop, or null if not yet assigned
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    /**
     * Sets the SelectorLoop used for QUIC datagram I/O.
     *
     * @param loop the selector loop
     */
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    /**
     * Sets whether this listener requires RFC 9000 section 8.1.2 Retry
     * before accepting a new connection. Default {@code true}. Set
     * {@code false} for lab / loopback / already-anti-spoofed paths.
     * XML: {@code require-retry}
     *
     * @param requireRetry whether to send Retry to unvalidated Initials
     */
    public void setRequireRetry(boolean requireRetry) {
        this.requireRetry = requireRetry;
    }

    /**
     * Returns whether this listener requires Retry-based address
     * validation (RFC 9000 section 8.1.2).
     *
     * @return true if Retry is required (the default)
     */
    public boolean isRequireRetry() {
        return requireRetry;
    }

    // ── RFC 9000 section 18: QUIC transport parameter setters ──

    /** XML: {@code quic-max-idle-timeout} (milliseconds) */
    public void setQuicMaxIdleTimeout(long ms) { this.quicMaxIdleTimeout = ms; }
    /** XML: {@code quic-max-data} (bytes) */
    public void setQuicMaxData(long bytes) { this.quicMaxData = bytes; }
    /** XML: {@code quic-max-stream-data-bidi-local} (bytes) */
    public void setQuicMaxStreamDataBidiLocal(long bytes) { this.quicMaxStreamDataBidiLocal = bytes; }
    /** XML: {@code quic-max-stream-data-bidi-remote} (bytes) */
    public void setQuicMaxStreamDataBidiRemote(long bytes) { this.quicMaxStreamDataBidiRemote = bytes; }
    /** XML: {@code quic-max-stream-data-uni} (bytes) */
    public void setQuicMaxStreamDataUni(long bytes) { this.quicMaxStreamDataUni = bytes; }
    /** XML: {@code quic-max-streams-bidi} (count) */
    public void setQuicMaxStreamsBidi(long count) { this.quicMaxStreamsBidi = count; }
    /** XML: {@code quic-max-streams-uni} (count) */
    public void setQuicMaxStreamsUni(long count) { this.quicMaxStreamsUni = count; }

    // ── Lifecycle ──

    @Override
    public boolean requiresTcpAccept() {
        return false;
    }

    // RFC 9114 section 3.1 — ALPN protocol identifier "h3"
    @Override
    protected TransportFactory createTransportFactory() {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setApplicationProtocols("h3");
        if (certFile != null) {
            factory.setCertFile(certFile);
        }
        if (keyFile != null) {
            factory.setKeyFile(keyFile);
        }
        factory.setRequireRetry(requireRetry);
        // RFC 9000 section 18: apply configured transport parameters
        if (quicMaxIdleTimeout >= 0) { factory.setMaxIdleTimeout(quicMaxIdleTimeout); }
        if (quicMaxData >= 0) { factory.setMaxData(quicMaxData); }
        if (quicMaxStreamDataBidiLocal >= 0) { factory.setMaxStreamDataBidiLocal(quicMaxStreamDataBidiLocal); }
        if (quicMaxStreamDataBidiRemote >= 0) { factory.setMaxStreamDataBidiRemote(quicMaxStreamDataBidiRemote); }
        if (quicMaxStreamDataUni >= 0) { factory.setMaxStreamDataUni(quicMaxStreamDataUni); }
        if (quicMaxStreamsBidi >= 0) { factory.setMaxStreamsBidi(quicMaxStreamsBidi); }
        if (quicMaxStreamsUni >= 0) { factory.setMaxStreamsUni(quicMaxStreamsUni); }
        return factory;
    }

    @Override
    public void start() {
        if (port <= 0) {
            port = HTTP3_DEFAULT_PORT;
        }
        super.start();
        if (isMetricsEnabled()) {
            metrics = new HttpServerMetrics(getTelemetryConfig());
        }
        // Unlike TCP-accept listeners (which register with the accept
        // loop lazily and don't actually bind until Gumdrop.start()
        // runs), this listener binds a UDP socket right here — but
        // Gumdrop.addListener() calls start() immediately, even for a
        // standalone listener added before Gumdrop.start(), at which
        // point the worker-loop pool nextWorkerLoop() depends on
        // hasn't been allocated yet. Defer the QUIC bind in that case;
        // Gumdrop.start() calls start() again for exactly this
        // scenario once workerLoops exists (issue #106).
        Gumdrop gumdrop = Gumdrop.getInstance();
        if (gumdrop == null || !gumdrop.isStarted()) {
            return;
        }
        if (selectorLoop == null) {
            selectorLoop = gumdrop.nextWorkerLoop();
        }
        if (selectorLoop == null) {
            throw new IllegalStateException(
                    "SelectorLoop must be set before starting "
                            + "Http3Listener");
        }
        bindEngines();
    }

    /**
     * Creates and binds a QuicEngine for each configured address.
     */
    private void bindEngines() {
        QuicTransportFactory factory =
                (QuicTransportFactory) getTransportFactory();

        Set<InetAddress> addrs = getAddresses();
        for (Iterator<InetAddress> it = addrs.iterator();
             it.hasNext(); ) {
            InetAddress addr = it.next();
            try {
                QuicEngine engine = factory.createServerEngine(
                        addr, port, this, selectorLoop);
                engines.add(engine);
            } catch (IOException e) {
                String message = MessageFormat.format(
                        L10N.getString("warn.bind_failed"),
                        addr.getHostAddress(), Integer.valueOf(port));
                LOGGER.log(Level.WARNING, message, e);
            }
        }

        if (engines.isEmpty()) {
            LOGGER.warning(L10N.getString("warn.no_bind_address"));
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < engines.size(); i++) {
            engines.get(i).close();
        }
        engines.clear();
    }

    // ── ConnectionAcceptedHandler ──

    @Override
    public void connectionAccepted(QuicConnection connection) {
        new Http3ServerHandler(connection, streamHandler,
                authenticationProvider, metrics,
                getTelemetryConfig(), addSecurityHeaders);
    }

    /**
     * Not used for HTTP/3. QUIC connections are handled at the
     * connection level by {@link Http3ServerHandler}.
     *
     * @return null
     */
    @Override
    protected ProtocolHandler createHandler() {
        return null;
    }

    /**
     * @deprecated use {@code new Http3Listener().port(...)} fluent configuration.
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated use fluent methods on {@link Http3Listener} instead.
     */
    @Deprecated
    public static final class Builder {

        private int port = -1;
        private String addresses;
        private TlsConfig tls;
        private boolean requireRetry = true;

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
         * Sets the server TLS identity (PEM, keystore, or credentials).
         *
         * <p>HTTP/3 requires TLS 1.3 over QUIC; this configuration is
         * mandatory for production listeners.
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

        /**
         * Sets whether RFC 9000 Retry-based address validation is required.
         * Default {@code true}.
         */
        public Builder requireRetry(boolean requireRetry) {
            this.requireRetry = requireRetry;
            return this;
        }

        public Http3Listener build() {
            Http3Listener listener = new Http3Listener();
            listener.setPort(port);
            if (addresses != null) {
                listener.setAddresses(addresses);
            }
            listener.setRequireRetry(requireRetry);
            if (tls != null) {
                TlsConfigSupport.apply(tls, listener);
            }
            return listener;
        }
    }

}
