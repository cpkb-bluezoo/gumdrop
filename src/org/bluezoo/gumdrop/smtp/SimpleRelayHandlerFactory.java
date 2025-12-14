/*
 * SimpleRelayHandlerFactory.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.smtp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.DNSResolver;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;
import org.bluezoo.gumdrop.smtp.handler.ClientConnectedFactory;

/**
 * Factory that creates {@link SimpleRelayHandler} instances.
 *
 * <p>This factory manages a shared DNS resolver for MX lookups and creates
 * handler instances for each incoming connection. The DNS resolver uses
 * system-configured nameservers by default.
 *
 * <h4>Configuration</h4>
 * <pre>{@code
 * <server id="smtp" class="org.bluezoo.gumdrop.smtp.SMTPServer">
 *     <property name="port">25</property>
 *     <property name="client-connected-factory">org.bluezoo.gumdrop.smtp.SimpleRelayHandlerFactory</property>
 * </server>
 * }</pre>
 *
 * <h4>Optional Properties</h4>
 * <ul>
 *   <li>{@code hostname} - Local hostname for EHLO (default: system hostname)</li>
 *   <li>{@code dns-server} - DNS server address (default: system resolvers)</li>
 *   <li>{@code dns-timeout} - DNS query timeout in milliseconds (default: 5000)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SimpleRelayHandler
 */
public class SimpleRelayHandlerFactory implements ClientConnectedFactory {

    private static final Logger LOGGER = Logger.getLogger(SimpleRelayHandlerFactory.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private DNSResolver dnsResolver;
    private String hostname;
    private String dnsServer;
    private long dnsTimeout = 5000;
    private boolean initialized = false;

    /**
     * Creates a new factory with default configuration.
     */
    public SimpleRelayHandlerFactory() {
        // Will be initialized on first use
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration properties
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets the local hostname used in EHLO.
     *
     * @param hostname the local hostname
     */
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /**
     * Returns the local hostname used in EHLO.
     *
     * @return the local hostname
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Sets the DNS server address for MX lookups.
     * If not set, system resolvers are used.
     *
     * @param dnsServer the DNS server address
     */
    public void setDnsServer(String dnsServer) {
        this.dnsServer = dnsServer;
    }

    /**
     * Returns the DNS server address.
     *
     * @return the DNS server address, or null for system resolvers
     */
    public String getDnsServer() {
        return dnsServer;
    }

    /**
     * Sets the DNS query timeout in milliseconds.
     *
     * @param dnsTimeout the timeout in milliseconds
     */
    public void setDnsTimeout(long dnsTimeout) {
        this.dnsTimeout = dnsTimeout;
    }

    /**
     * Returns the DNS query timeout in milliseconds.
     *
     * @return the timeout in milliseconds
     */
    public long getDnsTimeout() {
        return dnsTimeout;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ClientConnectedFactory
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public ClientConnected createHandler() {
        ensureInitialized();
        return new SimpleRelayHandler(dnsResolver, hostname);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }

        // Initialize hostname
        if (hostname == null) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostname = "localhost";
            }
        }

        // Initialize DNS resolver
        dnsResolver = new DNSResolver();
        dnsResolver.setTimeoutMs(dnsTimeout);

        if (dnsServer != null) {
            try {
                dnsResolver.addServer(dnsServer);
            } catch (UnknownHostException e) {
                LOGGER.log(Level.WARNING, "Invalid DNS server: " + dnsServer, e);
            }
        } else {
            dnsResolver.useSystemResolvers();
        }

        try {
            dnsResolver.open();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, L10N.getString("err.dns_resolver_init_failed"), e);
            throw new RuntimeException(L10N.getString("err.dns_resolver_init_failed"), e);
        }

        initialized = true;

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("SimpleRelayHandlerFactory initialized, hostname=" + hostname);
        }
    }

    /**
     * Closes the factory and releases resources.
     * Call this when shutting down the server.
     */
    public void close() {
        if (dnsResolver != null) {
            dnsResolver.close();
            dnsResolver = null;
        }
        initialized = false;
    }

}

