/*
 * MailboxStoreImapSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.imap.handler.DefaultIMAPHandler;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;

import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * Stock {@link ImapServerSessionProvider} that wires a {@link MailboxFactory}
 * into each accepting {@link ImapListener} and returns {@link DefaultIMAPHandler}.
 *
 * <p>The factory is a property of this provider, not of {@link ImapServer}.
 *
 * <pre>{@code
 * ImapServer server = ImapServer.compose()
 *         .listener(new ImapListener().port(993).bindWildcard().secure(true).tls(tls))
 *         .realm(realm)
 *         .sessionProvider(ImapServerSessionProviders.mailbox(maildirFactory))
 *         .server();
 * }</pre>
 */
public class MailboxStoreImapSessionProvider implements ImapServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(MailboxStoreImapSessionProvider.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    private MailboxFactory mailboxFactory;

    /**
     * Sets the mailbox factory used for authenticated sessions.
     *
     * @param mailboxFactory the factory, or {@code null} for handler-only
     * @return this provider
     */
    public MailboxStoreImapSessionProvider mailboxFactory(
            MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
        return this;
    }

    /**
     * Returns the configured mailbox factory, or {@code null}.
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    @Override
    public void start() {
        if (mailboxFactory == null) {
            LOGGER.warning(L10N.getString("warn.no_mailbox_factory"));
        }
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (listener instanceof ImapListener) {
            ImapListener imapListener = (ImapListener) listener;
            if (mailboxFactory != null) {
                imapListener.setMailboxFactory(mailboxFactory);
            }
        }
        return new DefaultIMAPHandler();
    }

}
