/*
 * LocalDeliveryService.java
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

import java.util.ResourceBundle;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * SMTP service for local mailbox delivery.
 *
 * <p>This service creates {@link LocalDeliveryHandler} instances for
 * each incoming connection. It accepts mail for a configured local
 * domain and delivers messages to local mailboxes via the
 * {@link org.bluezoo.gumdrop.mailbox.MailboxFactory} configured on
 * the service.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.smtp.LocalDeliveryService">
 *   <property name="local-domain">example.com</property>
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mbox"/>
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener"
 *           name="mx" port="25"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SMTPService
 * @see LocalDeliveryHandler
 */
public class LocalDeliveryService extends SMTPService {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private String localDomain;
    private String hostname = "localhost";

    /**
     * Sets the local domain that this service accepts mail for.
     *
     * @param localDomain the local domain name
     */
    public void setLocalDomain(String localDomain) {
        this.localDomain = localDomain;
    }

    /**
     * Returns the local domain.
     *
     * @return the local domain name
     */
    public String getLocalDomain() {
        return localDomain;
    }

    /**
     * Sets the hostname used in the SMTP greeting.
     *
     * @param hostname the server hostname
     */
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /**
     * Returns the hostname.
     *
     * @return the server hostname
     */
    public String getHostname() {
        return hostname;
    }

    @Override
    protected ClientConnected createHandler(TCPListener endpoint) {
        if (getMailboxFactory() == null) {
            throw new IllegalStateException(
                    L10N.getString("err.mailbox_factory_not_configured"));
        }
        if (localDomain == null || localDomain.isEmpty()) {
            throw new IllegalStateException(
                    L10N.getString("err.local_domain_not_configured"));
        }
        return new LocalDeliveryHandler(getMailboxFactory(), localDomain,
                hostname);
    }

}
