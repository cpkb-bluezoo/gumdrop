/*
 * MailboxStorePop3SessionProvider.java
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

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.pop3.Pop3Listener;

import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * Stock {@link Pop3ServerSessionProvider} that wires a {@link MailboxFactory}
 * into each accepting {@link Pop3Listener} and returns {@link DefaultPOP3Handler}.
 *
 * <p>The factory is a property of this provider, not of {@link Pop3Server}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxStorePop3SessionProvider implements Pop3ServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(MailboxStorePop3SessionProvider.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N");

    private MailboxFactory mailboxFactory;
    private String greeting = "POP3 server ready";

    /**
     * Sets the mailbox factory used after successful authentication.
     *
     * @param mailboxFactory the factory, or {@code null} for handler-only
     * @return this provider
     */
    public MailboxStorePop3SessionProvider mailboxFactory(
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

    /**
     * Sets the greeting sent to clients.
     *
     * @param greeting the greeting text
     * @return this provider
     */
    public MailboxStorePop3SessionProvider greeting(String greeting) {
        this.greeting = greeting;
        return this;
    }

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
        if (listener instanceof Pop3Listener) {
            Pop3Listener pop3Listener = (Pop3Listener) listener;
            if (mailboxFactory != null) {
                pop3Listener.setMailboxFactory(mailboxFactory);
            }
        }
        return new DefaultPOP3Handler(greeting);
    }

}
