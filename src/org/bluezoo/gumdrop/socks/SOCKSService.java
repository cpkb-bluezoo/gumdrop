/*
 * SOCKSService.java
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

package org.bluezoo.gumdrop.socks;

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

import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.socks.handler.BindHandler;
import org.bluezoo.gumdrop.socks.handler.ConnectHandler;
import org.bluezoo.gumdrop.util.CIDRNetwork;

/**
 * Abstract base for SOCKS proxy application services.
 *
 * <p>A {@code SOCKSService} manages one or more {@link SOCKSListener}
 * instances, an optional {@link Realm} for authentication, outbound
 * destination filtering, and relay lifecycle. Subclasses may override
 * {@link #createConnectHandler(TCPListener)} to provide custom
 * authorization logic for incoming CONNECT requests.
 *
 * <p>Supports SOCKS4, SOCKS4a, and SOCKS5 (RFC 1928) protocols. The
 * protocol version is auto-detected from the first byte of each
 * client connection.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service id="socks" class="org.bluezoo.gumdrop.socks.DefaultSOCKSService">
 *   <property name="realm" ref="#socksRealm"/>
 *   <property name="blocked-destinations">127.0.0.0/8,10.0.0.0/8,::1/128</property>
 *   <property name="max-relays">1000</property>
 *   <listener class="org.bluezoo.gumdrop.socks.SOCKSListener" port="1080"/>
 *   <listener class="org.bluezoo.gumdrop.socks.SOCKSListener"
 *           port="1081" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see SOCKSListener
 * @see DefaultSOCKSService
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1961">RFC 1961</a>
 */
public abstract class SOCKSService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(SOCKSService.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.socks.L10N");

    private final List<SOCKSListener> listeners = new ArrayList<>();

    private Realm realm;
    private List<CIDRNetwork> allowedDestinations;
    private List<CIDRNetwork> blockedDestinations;
    private int maxRelays = 0;
    private long relayIdleTimeoutMs = 5 * 60 * 1000;

    private final AtomicInteger activeRelayCount = new AtomicInteger(0);

    // ── Listener management ──

    /**
     * Adds a SOCKS listener to this service.
     *
     * @param listener the listener
     */
    public void addListener(SOCKSListener listener) {
        listeners.add(listener);
    }

    @SuppressWarnings("rawtypes")
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof SOCKSListener) {
                addListener((SOCKSListener) item);
            }
        }
    }

    @Override
    public List<SOCKSListener> getListeners() {
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
     * @param allowed comma-separated CIDR notation
     */
    public void setAllowedDestinations(String allowed) {
        if (allowed != null && !allowed.isEmpty()) {
            this.allowedDestinations = CIDRNetwork.parseList(allowed);
        }
    }

    /**
     * Sets the CIDR networks that clients are blocked from connecting
     * to. Destinations matching any of these networks will be denied.
     * Block rules are evaluated before allow rules.
     *
     * @param blocked comma-separated CIDR notation
     */
    public void setBlockedDestinations(String blocked) {
        if (blocked != null && !blocked.isEmpty()) {
            this.blockedDestinations = CIDRNetwork.parseList(blocked);
        }
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
     * Sets the idle timeout using a duration string (e.g. "5m", "300s").
     *
     * @param timeout the duration string
     */
    public void setRelayIdleTimeout(String timeout) {
        this.relayIdleTimeoutMs = parseDuration(timeout);
    }

    private static long parseDuration(String duration) {
        if (duration != null && !duration.isEmpty()) {
            duration = duration.trim().toLowerCase();
            long multiplier = 1;
            String numPart = duration;
            if (duration.endsWith("ms")) {
                numPart = duration.substring(0, duration.length() - 2);
            } else if (duration.endsWith("s")) {
                numPart = duration.substring(0, duration.length() - 1);
                multiplier = 1000;
            } else if (duration.endsWith("m")) {
                numPart = duration.substring(0, duration.length() - 1);
                multiplier = 60 * 1000;
            } else if (duration.endsWith("h")) {
                numPart = duration.substring(0, duration.length() - 1);
                multiplier = 60 * 60 * 1000;
            }
            try {
                return Long.parseLong(numPart.trim()) * multiplier;
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0;
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
            for (Iterator<CIDRNetwork> it = blockedDestinations.iterator();
                 it.hasNext(); ) {
                if (it.next().matches(address)) {
                    return false;
                }
            }
        }

        if (allowedDestinations != null) {
            for (Iterator<CIDRNetwork> it = allowedDestinations.iterator();
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

    // ── Handler creation ──

    /**
     * Creates a connect handler for authorizing incoming SOCKS
     * CONNECT requests.
     *
     * <p>Subclasses may override to provide custom authorization
     * logic. Return {@code null} for default behaviour (accept all
     * CONNECT requests that pass destination filtering).
     *
     * @param listener the listener that accepted the connection
     * @return a connect handler, or null for default behaviour
     */
    protected ConnectHandler createConnectHandler(TCPListener listener) {
        return null;
    }

    /**
     * Creates a bind handler for authorizing incoming SOCKS BIND
     * requests.
     *
     * <p>Subclasses may override to provide custom authorization
     * logic. Return {@code null} for default behaviour (accept all
     * BIND requests that pass relay limits).
     *
     * @param listener the listener that accepted the connection
     * @return a bind handler, or null for default behaviour
     */
    protected BindHandler createBindHandler(TCPListener listener) {
        return null;
    }

    /**
     * Creates the protocol handler for a new SOCKS connection.
     * Called by {@link SOCKSListener#createHandler()}.
     */
    SOCKSProtocolHandler createProtocolHandler(SOCKSListener listener) {
        SOCKSProtocolHandler handler =
                new SOCKSProtocolHandler(listener, this);
        ConnectHandler ch = createConnectHandler(listener);
        if (ch != null) {
            handler.setConnectHandler(ch);
        }
        BindHandler bh = createBindHandler(listener);
        if (bh != null) {
            handler.setBindHandler(bh);
        }
        return handler;
    }

    // ── Lifecycle ──

    /**
     * Initialises service resources before listeners are started.
     * The default implementation does nothing.
     */
    protected void initService() {
    }

    /**
     * Tears down service resources after listeners are stopped.
     * The default implementation does nothing.
     */
    protected void destroyService() {
    }

    @Override
    public void start() {
        initService();

        for (SOCKSListener ep : listeners) {
            wireListener(ep);
            ep.setService(this);
            try {
                ep.start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, MessageFormat.format(
                        L10N.getString("log.listener_start_failed"), ep), e);
            }
        }
    }

    @Override
    public void stop() {
        for (SOCKSListener ep : listeners) {
            try {
                ep.stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("log.listener_stop_error"), ep), e);
            }
        }
        destroyService();
    }

    private void wireListener(SOCKSListener ep) {
        if (realm != null && ep.getRealm() == null) {
            ep.setRealm(realm);
        }
    }

}
