/*
 * SimpleRelayServer.java
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

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * SMTP service for MX-based mail relay.
 *
 * <p>Legacy XML entry point. New applications should compose
 * {@link SimpleRelaySessionProvider} via {@link SmtpServer#compose()} instead
 * of subclassing this type.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.smtp.SimpleRelayServer">
 *   <property name="hostname">relay.example.com</property>
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="auth-required">true</property>
 *   <listener class="org.bluezoo.gumdrop.smtp.SmtpListener"
 *           name="submission" port="587" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SmtpServer
 * @see SimpleRelaySessionProvider
 * @see org.bluezoo.gumdrop.smtp.SimpleRelayHandler
 */
public class SimpleRelayServer extends SmtpServer {

    private final SimpleRelaySessionProvider sessionProvider =
            new SimpleRelaySessionProvider();

    /**
     * Sets the local hostname used in EHLO.
     *
     * @param hostname the local hostname
     */
    public void setHostname(String hostname) {
        sessionProvider.hostname(hostname);
    }

    /**
     * Returns the local hostname.
     *
     * @return the local hostname
     */
    public String getHostname() {
        return sessionProvider.getHostname();
    }

    /**
     * Sets the DNS server address for MX lookups.
     * If not set, system resolvers are used.
     *
     * @param dnsServer the DNS server address
     */
    public void setDnsServer(String dnsServer) {
        sessionProvider.dnsServer(dnsServer);
    }

    /**
     * Returns the DNS server address.
     *
     * @return the DNS server address, or null for system resolvers
     */
    public String getDnsServer() {
        return sessionProvider.getDnsServer();
    }

    /**
     * Sets the DNS query timeout in milliseconds.
     *
     * @param dnsTimeout the timeout in milliseconds
     */
    public void setDnsTimeout(long dnsTimeout) {
        sessionProvider.timeoutMs(dnsTimeout);
    }

    /**
     * Returns the DNS query timeout in milliseconds.
     *
     * @return the timeout in milliseconds
     */
    public long getDnsTimeout() {
        return sessionProvider.getTimeoutMs();
    }

    @Override
    protected SmtpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    @Override
    public ClientConnected openSession(TcpListener endpoint) {
        return sessionProvider.openSession(endpoint);
    }

}
