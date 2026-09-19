/*
 * MailboxStoreImapSessionProvider.java
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

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.ImapListener;
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
