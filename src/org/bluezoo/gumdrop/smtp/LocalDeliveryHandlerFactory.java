/*
 * LocalDeliveryHandlerFactory.java
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

import java.util.ResourceBundle;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;
import org.bluezoo.gumdrop.smtp.handler.ClientConnectedFactory;

/**
 * Factory for creating {@link LocalDeliveryHandler} instances.
 *
 * <p>Configure this factory with a mailbox factory and local domain,
 * then set it on the SMTP server to enable local mail delivery.
 *
 * <h4>Configuration Example (XML)</h4>
 *
 * <pre>{@code
 * <smtp-server port="25">
 *   <local-delivery-handler>
 *     <local-domain>example.com</local-domain>
 *     <mailbox-factory class="org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory">
 *       <base-path>/var/mail</base-path>
 *     </mailbox-factory>
 *   </local-delivery-handler>
 * </smtp-server>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LocalDeliveryHandler
 */
public class LocalDeliveryHandlerFactory implements ClientConnectedFactory {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private MailboxFactory mailboxFactory;
    private String localDomain;
    private String hostname = "localhost";

    /**
     * Creates a new factory with default settings.
     * The mailbox factory and local domain must be set before use.
     */
    public LocalDeliveryHandlerFactory() {
    }

    /**
     * Creates a new factory with the specified settings.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     * @param localDomain the domain this server accepts mail for
     */
    public LocalDeliveryHandlerFactory(MailboxFactory mailboxFactory, String localDomain) {
        this.mailboxFactory = mailboxFactory;
        this.localDomain = localDomain;
    }

    /**
     * Sets the mailbox factory.
     *
     * @param mailboxFactory the factory for creating mailbox stores
     */
    public void setMailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
    }

    /**
     * Returns the mailbox factory.
     *
     * @return the mailbox factory
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the local domain.
     *
     * @param localDomain the domain this server accepts mail for
     */
    public void setLocalDomain(String localDomain) {
        this.localDomain = localDomain;
    }

    /**
     * Returns the local domain.
     *
     * @return the local domain
     */
    public String getLocalDomain() {
        return localDomain;
    }

    /**
     * Sets the hostname for the SMTP greeting.
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
    public ClientConnected createHandler() {
        if (mailboxFactory == null) {
            throw new IllegalStateException(L10N.getString("err.mailbox_factory_not_configured"));
        }
        if (localDomain == null || localDomain.isEmpty()) {
            throw new IllegalStateException(L10N.getString("err.local_domain_not_configured"));
        }
        return new LocalDeliveryHandler(mailboxFactory, localDomain, hostname);
    }

}

