/*
 * HttpServer.java
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

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.DefaultHttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HstsPolicy;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.HttpTlsConfig;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * Abstract base for HTTP protocol servers.
 *
 * <p>An {@code HttpServer} defines the application logic for handling
 * HTTP requests: an {@link HttpStreamHandler} that binds an
 * {@link HttpRequestHandler} per stream, and an optional
 * {@link HttpAuthenticationProvider} for authenticating requests.
 *
 * <p>New applications should use {@link #compose()} rather than
 * subclassing {@code HttpServer} or configuring XML services.
 *
 * <p>A server owns one or more transport listeners:
 * <ul>
 *   <li>{@link Http2Listener} for HTTP/2 over TCP (HTTP/1.1 fallback)</li>
 *   <li>{@link Http3Listener} for HTTP/3 over QUIC</li>
 * </ul>
 *
 * <p>During {@link #start()}, the server:
 * <ol>
 *   <li>Calls {@link #initService()} for subclass-specific initialisation
 *       (e.g., starting a servlet container or building a handler factory).</li>
 *   <li>Wires each listener by pushing the handler factory and
 *       authentication provider into it.</li>
 *   <li>If both TCP and QUIC listeners are present, injects an
 *       {@code Alt-Svc} header on the TCP endpoints to advertise HTTP/3.</li>
 *   <li>Calls {@link Listener#start()} on each listener.</li>
 * </ol>
 *
 * <p>During {@link #stop()}, the server stops listeners first, then
 * calls {@link #destroyService()} for subclass-specific teardown.
 *
 * <h2>Composition Example</h2>
 * <pre>{@code
 * HttpServer server = HttpServer.compose()
 *         .secureEndpoint(443, TlsConfig.pem(Path.of("cert.pem"), Path.of("key.pem")))
 *         .streamHandler(new MyStreamHandler())
 *         .server();
 * gumdrop.addServer(server);
 * }</pre>
 *
 * <p>{@link Composer#secureEndpoint(int, TlsConfig)} wires HTTPS (TCP:
 * HTTP/2 + HTTP/1.1) and HTTP/3 (QUIC) on the same port, with {@code Alt-Svc}
 * on TCP responses. Plaintext HTTP/1.1 is a legacy fallback — add
 * {@link Composer#plaintextListener(int)} only when you need it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Server
 * @see Http2Listener
 * @see Http3Listener
 * @see HttpStreamHandler
 */
public abstract class HttpServer implements Server {

    /**
     * Starts fluent composition of a concrete {@link HttpServer}.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * @deprecated use {@link #compose()}.
     */
    @Deprecated
    public static Composer builder() {
        return compose();
    }

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    private static final Logger LOGGER =
            Logger.getLogger(HttpServer.class.getName());

    private final List<Listener> listeners = new ArrayList<Listener>();
    private Realm realm;
    private boolean addSecurityHeaders = true;
    private boolean hstsEnabled;
    private long hstsMaxAge = HstsPolicy.DEFAULT_MAX_AGE_SECONDS;
    private boolean hstsIncludeSubDomains;
    private boolean hstsPreload;

    // ── Realm ──

    /**
     * Returns the authentication realm for this server.
     *
     * @return the realm, or null if no realm is configured
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for this server.
     *
     * <p>When a realm is configured and no explicit
     * {@link HttpAuthenticationProvider} is returned by
     * {@link #getAuthenticationProvider()}, a
     * {@link DefaultHttpAuthenticationProvider} will be created
     * automatically to bridge the realm to HTTP authentication.
     *
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Sets whether to add default security headers (X-Frame-Options,
     * X-Content-Type-Options) to responses. Default: true.
     *
     * @param addSecurityHeaders true to add headers (unless app sets them)
     */
    public void setAddSecurityHeaders(boolean addSecurityHeaders) {
        this.addSecurityHeaders = addSecurityHeaders;
    }

    /**
     * Returns whether default security headers are added to responses.
     */
    public boolean isAddSecurityHeaders() {
        return addSecurityHeaders;
    }

    /**
     * Sets the RFC 6797 HSTS policy applied to secure TCP listeners and
     * HTTP/3. XML property: {@code hsts-enabled} and related names on
     * the service.
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

    public HstsPolicy getHstsPolicy() {
        return buildHstsPolicy();
    }

    /** XML property: {@code hsts-enabled} */
    public void setHstsEnabled(boolean enabled) {
        hstsEnabled = enabled;
    }

    /** XML property: {@code hsts-max-age} */
    public void setHstsMaxAge(long maxAgeSeconds) {
        if (maxAgeSeconds < 0) {
            throw new IllegalArgumentException("max-age must be non-negative");
        }
        hstsMaxAge = maxAgeSeconds;
    }

    /** XML property: {@code hsts-include-subdomains} */
    public void setHstsIncludeSubDomains(boolean includeSubDomains) {
        hstsIncludeSubDomains = includeSubDomains;
    }

    /** XML property: {@code hsts-preload} */
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

    // ── Listener management ──

    /**
     * Adds a TCP (HTTP/2 + HTTP/1.1 fallback) listener to this server.
     *
     * @param endpoint the TCP listener
     */
    public void addListener(Http2Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Adds a QUIC (HTTP/3) listener to this server.
     *
     * @param endpoint the QUIC listener
     */
    public void addListener(Http3Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all current listeners, both TCP and QUIC.
     */
    /**
     * Sets the listeners from a configuration list. Each item must be
     * an {@link Http2Listener} or {@link Http3Listener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Http3Listener) {
                addListener((Http3Listener) item);
            } else if (item instanceof Http2Listener) {
                addListener((Http2Listener) item);
            }
        }
    }

    @Override
    public List<? extends Listener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Application logic hooks ──

    /**
     * Returns the stream handler that binds {@link HttpRequestHandler}
     * instances for each new stream, or {@code null} for the handler-less
     * default (404, built-in method validation).
     */
    protected HttpStreamHandler getStreamHandler() {
        return null;
    }

    /**
     * Returns the authentication provider for this server, or null
     * if authentication is not required.
     *
     * <p>The default implementation returns null (no authentication).
     * Subclasses may override to provide an authentication provider
     * that applies to all listeners.
     *
     * @return the authentication provider, or null
     */
    protected HttpAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

    /**
     * Initialises server-specific application resources.
     *
     * <p>Called at the beginning of {@link #start(Gumdrop)}, before
     * listeners are wired and started. Subclasses should initialise
     * containers, thread pools, caches, or any other application-level
     * resources here.
     *
     * <p>The default implementation does nothing.
     *
     * @param gumdrop the runtime this server is starting under
     */
    protected void initService(Gumdrop gumdrop) {
        // Default: no-op
    }

    /**
     * Tears down server-specific application resources.
     *
     * <p>Called at the end of {@link #stop()}, after all listeners have
     * been stopped. Subclasses should shut down containers, thread
     * pools, and other application-level resources here.
     *
     * <p>The default implementation does nothing.
     */
    protected void destroyService() {
        // Default: no-op
    }

    // ── Lifecycle ──

    /**
     * Starts this server: initialises application logic, wires
     * listeners, computes Alt-Svc, and starts each listener.
     *
     * @param gumdrop the runtime this server is starting under
     */
    @Override
    public void start(Gumdrop gumdrop) {
        initService(gumdrop);

        HttpStreamHandler streamHandler = getStreamHandler();
        HttpAuthenticationProvider authProvider =
                getAuthenticationProvider();
        if (authProvider == null && realm != null) {
            authProvider = new DefaultHttpAuthenticationProvider(realm);
        }
        String altSvc = computeAltSvc();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            wireListener(listener, streamHandler, authProvider, altSvc);
            startListener(gumdrop, listener);
        }
    }

    /**
     * Stops this server: stops all listeners first, then tears down
     * application logic.
     */
    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        destroyService();
    }

    // ── Internal wiring ──

    /**
     * Wires a single listener with the server's handler factory,
     * authentication provider, and Alt-Svc header.
     */
    private void wireListener(Object listener,
                              HttpStreamHandler streamHandler,
                              HttpAuthenticationProvider authProvider,
                              String altSvc) {
        if (listener instanceof Http2Listener) {
            Http2Listener tcp = (Http2Listener) listener;
            tcp.setStreamHandler(streamHandler);
            tcp.setAuthenticationProvider(authProvider);
            tcp.setAddSecurityHeaders(addSecurityHeaders);
            if (tcp.isSecure() && hstsEnabled) {
                tcp.setHstsPolicy(buildHstsPolicy());
            }
            if (altSvc != null) {
                tcp.setAltSvc(altSvc);
            }
        } else if (listener instanceof Http3Listener) {
            Http3Listener quic = (Http3Listener) listener;
            quic.setStreamHandler(streamHandler);
            quic.setAuthenticationProvider(authProvider);
            quic.setAddSecurityHeaders(addSecurityHeaders);
            if (hstsEnabled) {
                quic.setHstsPolicy(buildHstsPolicy());
            }
        }
    }

    /**
     * Starts a single listener.
     */
    private void startListener(Gumdrop gumdrop, Object listener) {
        if (listener instanceof Http3Listener) {
            Http3Listener h3 = (Http3Listener) listener;
            if (h3.getSelectorLoop() == null) {
                h3.setSelectorLoop(gumdrop.nextWorkerLoop());
            }
        }
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start(gumdrop);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, MessageFormat.format(
                        L10N.getString("log.http_listener_start_failed"), listener), e);
            }
        }
    }

    /**
     * Stops a single listener.
     */
    private void stopListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("warn.http_listener_stop_error"), listener), e);
            }
        }
    }

    /**
     * Computes the {@code Alt-Svc} header value when this server has
     * both TCP and QUIC listeners.
     *
     * <p>If at least one {@link Http3Listener} is present, this
     * method builds an Alt-Svc value like {@code h3=":443"} so that
     * HTTP/1.1 and HTTP/2 responses advertise the availability of HTTP/3.
     *
     * @return the Alt-Svc header value, or null if there are no QUIC
     *         listeners
     */
    private String computeAltSvc() {
        boolean hasTcp = false;
        List<Integer> h3Ports = new ArrayList<Integer>();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof Http2Listener) {
                hasTcp = true;
            } else if (listener instanceof Http3Listener) {
                Http3Listener h3 = (Http3Listener) listener;
                int port = h3.getPort();
                if (port > 0) {
                    h3Ports.add(Integer.valueOf(port));
                }
            }
        }

        if (!hasTcp || h3Ports.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h3Ports.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("h3=\":");
            sb.append(h3Ports.get(i));
            sb.append("\"; ma=86400");
        }
        return sb.toString();
    }

    /**
     * Fluent composition of listeners and a shared stream handler.
     */
    public static final class Composer {

        private final List<Http2Listener> tcpListeners = new ArrayList<Http2Listener>();
        private final List<Http3Listener> quicListeners = new ArrayList<Http3Listener>();
        private HttpStreamHandler streamHandler;
        private Realm realm;
        private boolean addSecurityHeaders = true;
        private HstsPolicy hstsPolicy = HstsPolicy.disabled();

        private Composer() {
        }

        /**
         * Adds a TCP (HTTP/2 + HTTP/1.1 fallback) listener.
         */
        public Composer listener(Http2Listener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            tcpListeners.add(listener);
            return this;
        }

        /**
         * Adds a QUIC (HTTP/3) listener.
         */
        public Composer listener(Http3Listener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            quicListeners.add(listener);
            return this;
        }

        /**
         * Wires the default secure HTTP endpoint: HTTPS on TCP (HTTP/2 +
         * HTTP/1.1) and HTTP/3 on QUIC, same port, shared TLS material.
         */
        public Composer secureEndpoint(int port, TlsConfig tls) {
            if (tls == null) {
                throw new NullPointerException("tls");
            }
            listener(new Http2Listener()
                    .port(port)
                    .secure(true)
                    .tls(tls));
            listener(new Http3Listener()
                    .port(port)
                    .tls(tls));
            return this;
        }

        /**
         * @deprecated use {@link #secureEndpoint(int, TlsConfig)}.
         */
        @Deprecated
        public Composer secureEndpoint(int port, HttpTlsConfig tls) {
            if (tls == null) {
                throw new NullPointerException("tls");
            }
            return secureEndpoint(port, tls.unwrap());
        }

        /**
         * Adds a cleartext HTTP/1.1 listener. Legacy fallback only.
         */
        public Composer plaintextListener(int port) {
            return listener(new Http2Listener().port(port));
        }

        /**
         * Binds an {@link HttpStreamHandler} for each new stream.
         */
        public Composer streamHandler(HttpStreamHandler streamHandler) {
            if (streamHandler == null) {
                throw new NullPointerException("streamHandler");
            }
            this.streamHandler = streamHandler;
            return this;
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Composer addSecurityHeaders(boolean addSecurityHeaders) {
            this.addSecurityHeaders = addSecurityHeaders;
            return this;
        }

        public Composer hsts(HstsPolicy hstsPolicy) {
            this.hstsPolicy = hstsPolicy != null ? hstsPolicy : HstsPolicy.disabled();
            return this;
        }

        /**
         * Creates the composed server. At least one listener must be configured.
         */
        public HttpServer server() {
            if (tcpListeners.isEmpty() && quicListeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            ComposedHttpServer server = new ComposedHttpServer(streamHandler);
            for (int i = 0; i < tcpListeners.size(); i++) {
                server.addListener(tcpListeners.get(i));
            }
            for (int i = 0; i < quicListeners.size(); i++) {
                server.addListener(quicListeners.get(i));
            }
            if (realm != null) {
                server.setRealm(realm);
            }
            server.setAddSecurityHeaders(addSecurityHeaders);
            server.setHstsPolicy(hstsPolicy);
            return server;
        }

        /**
         * @deprecated use {@link #server()}.
         */
        @Deprecated
        public HttpServer build() {
            return server();
        }
    }

}
