/*
 * NntpServerTest.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.nntp.NntpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the composition of an {@link NntpServer} and its stock session provider.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpServerTest {

    @Test(expected = IllegalStateException.class)
    public void testAListenerIsRequired() {
        NntpServer.compose().server();
    }

    @Test(expected = NullPointerException.class)
    public void testNullListenerRefused() {
        NntpServer.compose().listener(null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullProviderRefused() {
        NntpServer.compose().sessionProvider(null);
    }

    @Test
    public void testServerHoldsItsListeners() {
        NntpListener a = new NntpListener().port(1119);
        NntpListener b = new NntpListener().port(1563).secure(true);
        NntpServer server = NntpServer.compose().listener(a).listener(b).server();
        assertEquals(2, server.getListeners().size());
        assertSame(a, server.getListeners().get(0));
    }

    @Test
    public void testNoProviderNoSession() {
        NntpListener l = new NntpListener();
        NntpServer server = NntpServer.compose().listener(l).server();
        assertNull(server.openSession(l));
    }

    @Test
    public void testServerDelegatesToItsProvider() {
        MailboxFactory factory = new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
        NntpListener l = new NntpListener().postingAllowed(true);
        MailboxStoreNntpSessionProvider provider = new MailboxStoreNntpSessionProvider()
                .mailboxFactory(factory).greeting("hello");
        NntpServer server = NntpServer.compose().listener(l).sessionProvider(provider).server();
        assertNotNull(server.openSession(l));
        // the provider hands the listener its factory
        assertSame(factory, l.getMailboxFactory());
        assertTrue(l.isPostingAllowed());
        assertEquals("hello", provider.getGreeting());
    }

}
