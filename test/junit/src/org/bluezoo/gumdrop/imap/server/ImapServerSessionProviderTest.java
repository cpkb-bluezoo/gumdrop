/*
 * ImapServerSessionProviderTest.java
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

import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Composition tests for {@link ImapServerSessionProviders} under {@code imap.server}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapServerSessionProviderTest {

    @Test
    public void testMailboxProviderReturnsHandler() {
        ClientConnected handler = ImapServerSessionProviders.mailbox()
                .openSession(new ImapListener());
        assertNotNull(handler);
    }

    @Test
    public void testListenerSessionProviderWiring() {
        MailboxStoreImapSessionProvider provider = ImapServerSessionProviders.mailbox();
        ImapListener listener = new ImapListener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    @Test
    public void testMailboxFactoryOnOpenSession() {
        MailboxFactory factory = new MailboxFactory() {
            @Override
            public org.bluezoo.gumdrop.mailbox.MailboxStore createStore() {
                return null;
            }
        };
        ImapListener listener = new ImapListener();
        ImapServerSessionProviders.mailbox(factory).openSession(listener);
        assertSame(factory, listener.getMailboxFactory());
    }
}
