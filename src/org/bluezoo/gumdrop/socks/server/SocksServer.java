/*
 * SocksServer.java
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

package org.bluezoo.gumdrop.socks.server;

import org.bluezoo.gumdrop.socks.SocksListener;
import org.bluezoo.gumdrop.socks.SocksProtocolHandler;

import java.net.InetAddress;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.function.Supplier;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.util.CidrNetwork;

/**
 * SOCKS proxy server — listeners, configuration, and session composition.
 *
 * <p>A {@code SocksServer} manages one or more {@link SocksListener}
 * instances, an optional {@link Realm} for authentication, outbound
 * destination filtering, and relay lifecycle. Do not subclass for
 * application logic — use {@link #compose()} with a {@link
 * SocksServerSessionProvider}, or a bare {@code compose()} with no session
 * provider for an open proxy (see security warning below).
 *
 * <p>Supports SOCKS4, SOCKS4a, and SOCKS5 (RFC 1928) protocols. The
 * protocol version is auto-detected from the first byte of each
 * client connection.
 *
 * <h2>Composition Example</h2>
 * <pre>{@code
 * SocksServer server = SocksServer.compose()
 *         .listener(new SocksListener().port(1080).bindWildcard())
 *         .blockedDestinations("127.0.0.0/8,10.0.0.0/8,::1/128")
 *         .maxRelays(1000)
 *         .server();
 * gumdrop.addServer(server);
 * }</pre>
 *
 * <p><strong>Security warning:</strong> {@code SocksServer.compose()} with
 * no session provider is an <em>open proxy</em> — without a configured
 * {@code realm} and destination filtering it must not be exposed to
 * untrusted networks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SocksListener
 * @see SocksServerSessionProvider
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1961">RFC 1961</a>
 */
public class SocksServer implements Server, SocksServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(SocksServer.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    private final List<SocksListener> listeners = new ArrayList<>();

    private SocksServerSessionProvider sessionProvider;
    private Realm realm;
    private List<CidrNetwork> allowedDestinations;
    private List<CidrNetwork> blockedDestinations;
    private int maxRelays = 0;
    private long relayIdleTimeoutMs = 5 * 60 * 1000;

    private final AtomicInteger activeRelayCount = new AtomicInteger(0);

    // ── Listener management ──

    /**
     * Adds a SOCKS listener to this service.
     *
     * @param listener the listener
     */
    public void addListener(SocksListener listener) {
        listeners.add(listener);
    }

    // Rawtypes: the configuration parser reflectively invokes this bean
    // setter with a raw List of parsed config objects, filtered below by
    // instanceof - the parameter can't be generically typed since the
    // parser has no compile-time knowledge of the target element type.
    @SuppressWarnings("rawtypes")
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof SocksListener) {
                addListener((SocksListener) item);
            }
        }
    }

    @Override
    public List<SocksListener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Configuration ──

    /**
     * Returns the authentication realm for this service.
     *
     * @return the realm, or null if no authentication is required
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm. When set, SOCKS5 clients are
     * required to authenticate. RFC 1928 §3 method negotiation;
     * RFC 1929 username/password; RFC 1961 GSS-API. SOCKS4 clients
     * are not affected (the userid is passed through in the request).
     *
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Sets the CIDR networks that clients are allowed to connect to.
     * If set, only destinations matching one of these networks will
     * be permitted.
     *
     * @param allowed the permitted destination networks, or null
     */
    public void setAllowedDestinations(List<CidrNetwork> allowed) {
        this.allowedDestinations = allowed == null || allowed.isEmpty()
                ? null : new ArrayList<CidrNetwork>(allowed);
    }

    /**
     * Sets the CIDR networks that clients are blocked from connecting
     * to. Destinations matching any of these networks will be denied.
     * Block rules are evaluated before allow rules.
     *
     * @param blocked the denied destination networks, or null
     */
    public void setBlockedDestinations(List<CidrNetwork> blocked) {
        this.blockedDestinations = blocked == null || blocked.isEmpty()
                ? null : new ArrayList<CidrNetwork>(blocked);
    }

    /**
     * Sets the maximum number of concurrent relay connections. A
     * value of 0 means unlimited.
     *
     * @param maxRelays the maximum relay count
     */
    public void setMaxRelays(int maxRelays) {
        this.maxRelays = maxRelays;
    }

    /**
     * Returns the maximum number of concurrent relay connections.
     *
     * @return the maximum relay count, or 0 for unlimited
     */
    public int getMaxRelays() {
        return maxRelays;
    }

    /**
     * Sets the idle timeout for relay connections. Relays with no
     * data transfer in either direction for this duration will be
     * closed.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public void setRelayIdleTimeoutMs(long timeoutMs) {
        this.relayIdleTimeoutMs = timeoutMs;
    }

    /**
     * Returns the relay idle timeout in milliseconds.
     *
     * @return the timeout
     */
    public long getRelayIdleTimeoutMs() {
        return relayIdleTimeoutMs;
    }

    // ── Destination filtering ──

    /**
     * Checks whether a connection to the given destination address is
     * permitted by the service's destination filtering rules.
     *
     * @param address the destination address
     * @return true if the destination is allowed
     */
    public boolean isDestinationAllowed(InetAddress address) {
        if (blockedDestinations != null) {
            for (Iterator<CidrNetwork> it = blockedDestinations.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(address)) {
                    return false;
                }
            }
        }

        if (allowedDestinations != null) {
            for (Iterator<CidrNetwork> it = allowedDestinations.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(address)) {
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    // ── Relay tracking ──

    /**
     * Attempts to acquire a relay slot. Returns {@code false} if the
     * maximum number of concurrent relays has been reached.
     *
     * @return true if a relay slot was acquired
     */
    public boolean acquireRelay() {
        if (maxRelays <= 0) {
            activeRelayCount.incrementAndGet();
            return true;
        }
        while (true) {
            int current = activeRelayCount.get();
            if (current >= maxRelays) {
                return false;
            }
            if (activeRelayCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases a relay slot.
     */
    public void releaseRelay() {
        activeRelayCount.decrementAndGet();
    }

    /**
     * Returns the number of currently active relays.
     *
     * @return the active relay count
     */
    public int getActiveRelayCount() {
        return activeRelayCount.get();
    }

    // ── Session pipeline ──

    /**
     * Opens the {@link SocksSessionHandler} for an incoming connection.
     *
     * <p>Delegates to the composed {@link SocksServerSessionProvider}.
     * Returns {@code null} when no provider is configured, which accepts
     * every CONNECT/BIND request that passes destination filtering and
     * relay limits.
     */
    @Override
    public SocksSessionHandler openSession(TcpListener listener) {
        if (sessionProvider != null) {
            return sessionProvider.openSession(listener);
        }
        return null;
    }

    protected SocksServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setComposedSessionProvider(SocksServerSessionProvider provider) {
        this.sessionProvider = provider;
    }

    /**
     * Creates the protocol handler for a new SOCKS connection.
     * Called by {@link SocksListener#createHandler()}.
     */
    public SocksProtocolHandler createProtocolHandler(SocksListener listener) {
        SocksProtocolHandler handler =
                new SocksProtocolHandler(listener, this);
        SocksSessionHandler session = openSession(listener);
        if (session != null) {
            handler.setConnectHandler(session);
            handler.setBindHandler(session);
        }
        return handler;
    }

    // ── Lifecycle ──

    /**
     * Initialises service resources before listeners are started.
     */
    protected void initService() {
        SocksServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.start();
        }
    }

    /**
     * Tears down service resources after listeners are stopped.
     */
    protected void destroyService() {
        SocksServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    @Override
    public void start(Gumdrop gumdrop) {
        initService();

        for (SocksListener ep : listeners) {
            wireListener(ep);
            ep.setServer(this);
            try {
                ep.start(gumdrop);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, MessageFormat.format(
                        L10N.getString("log.listener_start_failed"), ep), e);
            }
        }
    }

    @Override
    public void stop() {
        for (SocksListener ep : listeners) {
            try {
                ep.stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("log.listener_stop_error"), ep), e);
            }
        }
        destroyService();
    }

    private void wireListener(SocksListener ep) {
        if (realm != null && ep.getRealm() == null) {
            ep.setRealm(realm);
        }
    }

    /**
     * Starts fluent composition of a concrete {@link SocksServer}.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * Fluent composition of listeners and a {@link SocksServerSessionProvider}.
     */
    public static final class Composer {

        private final List<SocksListener> listeners = new ArrayList<SocksListener>();
        private SocksServerSessionProvider sessionProvider;
        private Realm realm;
        private List<CidrNetwork> allowedDestinations;
        private List<CidrNetwork> blockedDestinations;
        private int maxRelays;
        private long relayIdleTimeoutMs = 5 * 60 * 1000;

        private Composer() {
        }

        public Composer listener(SocksListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(SocksServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        /**
         * Creates a fresh {@link SocksSessionHandler} for each accepted
         * connection.
         */
        public Composer sessionPerConnection(Supplier<SocksSessionHandler> supplier) {
            return sessionProvider(SocksServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Composer allowedDestinations(List<CidrNetwork> allowedDestinations) {
            this.allowedDestinations = allowedDestinations;
            return this;
        }

        public Composer blockedDestinations(List<CidrNetwork> blockedDestinations) {
            this.blockedDestinations = blockedDestinations;
            return this;
        }

        public Composer maxRelays(int maxRelays) {
            this.maxRelays = maxRelays;
            return this;
        }

        public Composer relayIdleTimeoutMs(long relayIdleTimeoutMs) {
            this.relayIdleTimeoutMs = relayIdleTimeoutMs;
            return this;
        }

        /**
         * Creates the composed server. At least one listener is required;
         * a session provider is optional (its absence yields an open
         * proxy — see the security warning on {@link SocksServer#compose()}).
         */
        public SocksServer server() {
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            SocksServer server = new SocksServer();
            server.setComposedSessionProvider(sessionProvider);
            if (realm != null) {
                server.setRealm(realm);
            }
            if (allowedDestinations != null) {
                server.setAllowedDestinations(allowedDestinations);
            }
            if (blockedDestinations != null) {
                server.setBlockedDestinations(blockedDestinations);
            }
            server.setMaxRelays(maxRelays);
            server.setRelayIdleTimeoutMs(relayIdleTimeoutMs);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }
    }

}
