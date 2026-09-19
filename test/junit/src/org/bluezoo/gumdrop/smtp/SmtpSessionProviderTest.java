/*
 * SmtpSessionProviderTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.server.ClientConnected;
import org.bluezoo.gumdrop.smtp.server.LocalDeliverySessionProvider;
import org.bluezoo.gumdrop.smtp.server.SimpleRelaySessionProvider;
import org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProviders;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Workstream C.3.1 — stock {@link org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProvider}
 * implementations and listener wiring.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpSessionProviderTest {

    @Test
    public void testSimpleRelaySessionProviderLifecycle() {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay()
                .hostname("relay.test");
        provider.start();
        try {
            SmtpListener listener = new SmtpListener();
            ClientConnected handler = provider.openSession(listener);
            assertNotNull(handler);
        } finally {
            provider.stop();
        }
    }

    @Test
    public void testLocalDeliverySessionProviderUsesListenerMailboxFactory() {
        MailboxFactory factory = new StubMailboxFactory();
        SmtpListener listener = new SmtpListener();
        listener.setMailboxFactory(factory);

        LocalDeliverySessionProvider provider = SmtpServerSessionProviders
                .localDelivery()
                .localDomain("example.com")
                .hostname("mail.example.com");

        ClientConnected handler = provider.openSession(listener);
        assertNotNull(handler);
    }

    @Test
    public void testListenerSessionProviderWiring() {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay()
                .hostname("relay.test");
        SmtpListener listener = new SmtpListener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    private static final class StubMailboxFactory implements MailboxFactory {
        @Override
        public org.bluezoo.gumdrop.mailbox.MailboxStore createStore() {
            return null;
        }
    }

}
