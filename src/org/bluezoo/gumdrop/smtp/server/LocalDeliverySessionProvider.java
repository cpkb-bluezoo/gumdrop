/*
 * LocalDeliverySessionProvider.java
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

import java.util.ResourceBundle;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.SmtpListener;

/**
 * Stock {@link SmtpServerSessionProvider} for local mailbox delivery.
 *
 * <p>Accepts mail only for the configured local domain and stores messages via
 * {@link MailboxFactory}. The factory may be set on this provider or pushed
 * onto the accepting {@link SmtpListener} by {@link SmtpServer#start()}.
 *
 * <pre>{@code
 * SmtpServer server = SmtpServer.compose()
 *         .listener(new SmtpListener().port(25).bindWildcard())
 *         .mailboxFactory(maildirFactory)
 *         .sessionProvider(new LocalDeliverySessionProvider()
 *                 .localDomain("example.com")
 *                 .hostname("mail.example.com"))
 *         .server();
 * }</pre>
 *
 * @see LocalDeliveryHandler
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LocalDeliverySessionProvider implements SmtpServerSessionProvider {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private String localDomain;
    private String hostname = "localhost";
    private MailboxFactory mailboxFactory;

    /**
     * Sets the local domain this server accepts mail for.
     *
     * @param localDomain the domain name
     * @return this provider
     */
    public LocalDeliverySessionProvider localDomain(String localDomain) {
        this.localDomain = localDomain;
        return this;
    }

    /**
     * Sets the hostname used in the SMTP greeting.
     *
     * @param hostname the server hostname
     * @return this provider
     */
    public LocalDeliverySessionProvider hostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * Sets the mailbox factory. When omitted, {@link #openSession} reads the
     * factory from the accepting {@link SmtpListener}.
     *
     * @param mailboxFactory the mailbox factory
     * @return this provider
     */
    public LocalDeliverySessionProvider mailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
        return this;
    }

    /**
     * Returns the configured local domain, or {@code null}.
     */
    public String getLocalDomain() {
        return localDomain;
    }

    /**
     * Returns the configured greeting hostname.
     */
    public String getHostname() {
        return hostname;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (!(listener instanceof SmtpListener)) {
            throw new IllegalArgumentException("expected SmtpListener");
        }
        SmtpListener endpoint = (SmtpListener) listener;
        MailboxFactory factory = mailboxFactory != null
                ? mailboxFactory
                : endpoint.getMailboxFactory();
        if (factory == null) {
            throw new IllegalStateException(
                    L10N.getString("err.mailbox_factory_not_configured"));
        }
        if (localDomain == null || localDomain.isEmpty()) {
            throw new IllegalStateException(
                    L10N.getString("err.local_domain_not_configured"));
        }
        return new LocalDeliveryHandler(factory, localDomain, hostname);
    }

}
