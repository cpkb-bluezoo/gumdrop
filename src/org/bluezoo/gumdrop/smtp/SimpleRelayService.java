/*
 * SimpleRelayService.java
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

package org.bluezoo.gumdrop.smtp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * SMTP service for MX-based mail relay.
 *
 * <p>This service creates {@link SimpleRelayHandler} instances that
 * accept mail for any domain and relay it via MX lookups. A shared
 * {@link DNSResolver} is initialised when the service starts and
 * closed when it stops.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.smtp.SimpleRelayService">
 *   <property name="hostname">relay.example.com</property>
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="auth-required">true</property>
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener"
 *           name="submission" port="587" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPService
 * @see SimpleRelayHandler
 */
public class SimpleRelayService extends SMTPService {

    private static final Logger LOGGER =
            Logger.getLogger(SimpleRelayService.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private DNSResolver dnsResolver;
    private String hostname;
    private String dnsServer;
    private long dnsTimeout = 5000;

    /**
     * Sets the local hostname used in EHLO.
     *
     * @param hostname the local hostname
     */
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /**
     * Returns the local hostname.
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

    @Override
    protected void initService() {
        if (hostname == null) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostname = "localhost";
            }
        }

        dnsResolver = new DNSResolver();
        dnsResolver.setTimeoutMs(dnsTimeout);

        if (dnsServer != null) {
            try {
                dnsResolver.addServer(dnsServer);
            } catch (UnknownHostException e) {
                LOGGER.log(Level.WARNING, "Invalid DNS server: " + dnsServer,
                        e);
            }
        } else {
            dnsResolver.useSystemResolvers();
        }

        try {
            dnsResolver.open();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    L10N.getString("err.dns_resolver_init_failed"), e);
            throw new RuntimeException(
                    L10N.getString("err.dns_resolver_init_failed"), e);
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("SimpleRelayService initialized, hostname="
                    + hostname);
        }
    }

    @Override
    protected void destroyService() {
        if (dnsResolver != null) {
            dnsResolver.close();
            dnsResolver = null;
        }
    }

    @Override
    protected ClientConnected createHandler(TCPListener endpoint) {
        return new SimpleRelayHandler(dnsResolver, hostname);
    }

}
