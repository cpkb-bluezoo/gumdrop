/*
 * LocalDeliveryServer.java
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
 * SMTP service for local mailbox delivery.
 *
 * <p>Legacy XML entry point. New applications should compose
 * {@link LocalDeliverySessionProvider} via {@link SmtpServer#compose()} instead
 * of subclassing this type.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.smtp.LocalDeliveryServer">
 *   <property name="local-domain">example.com</property>
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mbox"/>
 *   <listener class="org.bluezoo.gumdrop.smtp.SmtpListener"
 *           name="mx" port="25"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SmtpServer
 * @see LocalDeliverySessionProvider
 * @see org.bluezoo.gumdrop.smtp.LocalDeliveryHandler
 */
public class LocalDeliveryServer extends SmtpServer {

    private final LocalDeliverySessionProvider sessionProvider =
            new LocalDeliverySessionProvider();

    /**
     * Sets the local domain that this service accepts mail for.
     *
     * @param localDomain the local domain name
     */
    public void setLocalDomain(String localDomain) {
        sessionProvider.localDomain(localDomain);
    }

    /**
     * Returns the local domain.
     *
     * @return the local domain name
     */
    public String getLocalDomain() {
        return sessionProvider.getLocalDomain();
    }

    /**
     * Sets the hostname used in the SMTP greeting.
     *
     * @param hostname the server hostname
     */
    public void setHostname(String hostname) {
        sessionProvider.hostname(hostname);
    }

    /**
     * Returns the hostname.
     *
     * @return the server hostname
     */
    public String getHostname() {
        return sessionProvider.getHostname();
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
