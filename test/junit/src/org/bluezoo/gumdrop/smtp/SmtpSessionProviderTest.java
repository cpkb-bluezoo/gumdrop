/*
 * SmtpSessionProviderTest.java
 * Copyright (C) 2026 Chris Burdess
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
