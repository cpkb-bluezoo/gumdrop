/*
 * MailSessionProviderTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.imap.server.ImapServer;
import org.bluezoo.gumdrop.imap.server.ImapServerSessionProviders;
import org.bluezoo.gumdrop.imap.server.MailboxStoreImapSessionProvider;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.pop3.Pop3Listener;
import org.bluezoo.gumdrop.pop3.server.MailboxStorePop3SessionProvider;
import org.bluezoo.gumdrop.pop3.server.Pop3Server;
import org.bluezoo.gumdrop.pop3.server.Pop3ServerSessionProviders;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * IMAP and POP3 session-provider composition (parity with SMTP).
 */
public class MailSessionProviderTest {

    @Test
    public void testMailboxStoreImapSessionProvider() {
        ClientConnected handler = ImapServerSessionProviders.mailbox()
                .openSession(new ImapListener());
        assertNotNull(handler);
    }

    @Test
    public void testImapListenerSessionProviderWiring() {
        MailboxStoreImapSessionProvider provider =
                ImapServerSessionProviders.mailbox();
        ImapListener listener = new ImapListener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    @Test
    public void testEmptyComposedImapServer() {
        ImapServer server = ImapServer.compose()
                .listener(new ImapListener())
                .server();
        assertNotNull(server);
        assertNull(server.openSession(new ImapListener()));
    }

    @Test
    public void testMailboxFactoryOnImapSessionProvider() {
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

    @Test
    public void testDefaultPop3SessionProvider() {
        org.bluezoo.gumdrop.pop3.handler.ClientConnected handler =
                Pop3ServerSessionProviders.mailbox()
                        .greeting("test ready")
                        .openSession(new Pop3Listener());
        assertNotNull(handler);
    }

    @Test
    public void testPop3ListenerSessionProviderWiring() {
        MailboxStorePop3SessionProvider provider =
                Pop3ServerSessionProviders.mailbox();
        Pop3Listener listener = new Pop3Listener().sessionProvider(provider);
        assertSame(provider, listener.getSessionProvider());
    }

    @Test
    public void testEmptyComposedPop3Server() {
        Pop3Server server = Pop3Server.compose()
                .listener(new Pop3Listener())
                .server();
        assertNotNull(server);
        assertNull(server.openSession(new Pop3Listener()));
    }

}
