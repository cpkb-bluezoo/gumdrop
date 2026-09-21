/*
 * Pop3ServerSessionProviderTest.java
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

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.pop3.Pop3Listener;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Composition tests for {@link Pop3ServerSessionProviders}.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Pop3ServerSessionProviderTest {

    @Test
    public void testMailboxProviderOpenSession() {
        ClientConnected handler = Pop3ServerSessionProviders.mailbox()
                .openSession(new Pop3Listener());
        assertNotNull(handler);
    }

    @Test
    public void testListenerWiring() {
        MailboxStorePop3SessionProvider provider = Pop3ServerSessionProviders.mailbox();
        Pop3Listener listener = new Pop3Listener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    @Test
    public void testFactoryPropagatedToListener() {
        MailboxFactory factory = new MailboxFactory() {
            @Override
            public org.bluezoo.gumdrop.mailbox.MailboxStore createStore() {
                return null;
            }
        };
        Pop3Listener listener = new Pop3Listener();
        Pop3ServerSessionProviders.mailbox(factory).openSession(listener);
        assertSame(factory, listener.getMailboxFactory());
    }
}
