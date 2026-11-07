/*
 * MailboxStoreNntpSessionProvider.java
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

package org.bluezoo.gumdrop.nntp.server;

import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.nntp.NntpListener;

/**
 * Stock {@link NntpServerSessionProvider} that wires a
 * {@link MailboxFactory} into each accepting {@link NntpListener} and
 * returns a {@link DefaultNntpHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxStoreNntpSessionProvider implements NntpServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(MailboxStoreNntpSessionProvider.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.nntp.L10N");

    private MailboxFactory mailboxFactory;
    private String greeting = L10N.getString("nntp.greeting");

    /**
     * Sets the mailbox factory whose store holds the newsgroups.
     *
     * @param mailboxFactory the factory
     * @return this provider
     */
    public MailboxStoreNntpSessionProvider mailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
        return this;
    }

    /**
     * Returns the mailbox factory, or null.
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the greeting sent to readers.
     *
     * @param greeting the greeting text
     * @return this provider
     */
    public MailboxStoreNntpSessionProvider greeting(String greeting) {
        this.greeting = greeting;
        return this;
    }

    /**
     * Returns the greeting.
     */
    public String getGreeting() {
        return greeting;
    }

    @Override
    public void start() {
        if (mailboxFactory == null) {
            LOGGER.warning(L10N.getString("warn.no_mailbox_factory"));
        }
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        boolean posting = false;
        if (listener instanceof NntpListener) {
            NntpListener nntpListener = (NntpListener) listener;
            if (mailboxFactory != null) {
                nntpListener.mailboxFactory(mailboxFactory);
            }
            posting = nntpListener.isPostingAllowed();
        }
        return new DefaultNntpHandler(greeting, posting);
    }

}
