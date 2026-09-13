/*
 * LocalDeliverySessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import java.util.ResourceBundle;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.LocalDeliveryHandler;
import org.bluezoo.gumdrop.smtp.SmtpListener;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

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
 * @see LocalDeliveryServer
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
