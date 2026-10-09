/*
 * ImapServerTest.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Configuration, composition and session-provider tests for
 * {@link ImapServer} and its {@link ImapServer.Composer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapServerTest {

    private static MailboxFactory factory() {
        return new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
    }

    @Test
    public void testDefaultsAndSetters() {
        ImapServer s = new ImapServer();
        assertEquals(60000L, s.getLoginTimeoutMs());
        assertEquals(300000L, s.getCommandTimeoutMs());
        assertTrue(s.isEnableIDLE());
        assertTrue(s.isEnableNAMESPACE());
        assertTrue(s.isEnableQUOTA());
        assertTrue(s.isEnableMOVE());
        assertTrue(s.isEnableCOMPRESS());
        assertTrue(s.isEnableUTF8ACCEPT());
        assertTrue(s.isEnableSORT());
        assertFalse(s.isAllowPlaintextLogin());
        assertEquals(8192, s.getMaxLineLength());
        assertEquals(25 * 1024 * 1024, s.getMaxLiteralSize());
        assertNull(s.getRealm());
        assertNull(s.getMailboxFactory());
        assertNull(s.getQuotaManager());
        assertTrue(s.getListeners().isEmpty());

        s.loginTimeoutMs(1L);
        s.commandTimeoutMs(2L);
        s.enableIDLE(false);
        s.enableNAMESPACE(false);
        s.enableQUOTA(false);
        s.enableMOVE(false);
        s.enableCOMPRESS(false);
        s.enableUTF8ACCEPT(false);
        s.enableSORT(false);
        s.allowPlaintextLogin(true);
        s.maxLineLength(100);
        s.maxLiteralSize(200);
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        s.quotaManager(qm);
        assertEquals(1L, s.getLoginTimeoutMs());
        assertEquals(2L, s.getCommandTimeoutMs());
        assertFalse(s.isEnableIDLE());
        assertFalse(s.isEnableNAMESPACE());
        assertFalse(s.isEnableQUOTA());
        assertFalse(s.isEnableMOVE());
        assertFalse(s.isEnableCOMPRESS());
        assertFalse(s.isEnableUTF8ACCEPT());
        assertFalse(s.isEnableSORT());
        assertTrue(s.isAllowPlaintextLogin());
        assertEquals(100, s.getMaxLineLength());
        assertEquals(200, s.getMaxLiteralSize());
        assertSame(qm, s.getQuotaManager());
    }

    @Test
    public void testOpenSessionWithoutProviderIsNull() {
        ImapServer s = new ImapServer();
        TcpListener l = new ImapListener();
        assertNull(s.openSession(l));
        assertNull(s.createHandler(l));
    }

    @Test
    public void testSetMailboxFactoryCreatesProvider() {
        ImapServer s = new ImapServer();
        s.mailboxFactory(null);
        assertNull(s.getMailboxFactory());
        assertNull(s.openSession(new ImapListener()));
        MailboxFactory f = factory();
        s.mailboxFactory(f);
        assertSame(f, s.getMailboxFactory());
        assertNotNull(s.openSession(new ImapListener()));
        MailboxFactory g = factory();
        s.mailboxFactory(g);
        assertSame(g, s.getMailboxFactory());
    }

    @Test
    public void testSetMailboxFactoryWithCustomProviderKeepsProvider() {
        final ClientConnected custom = new DefaultIMAPHandler();
        ImapServer s = ImapServer.compose()
                .listener(new ImapListener())
                .sessionProvider(new ImapServerSessionProvider() {
                    @Override
                    public ClientConnected openSession(TcpListener listener) {
                        return custom;
                    }
                }).server();
        s.mailboxFactory(factory());
        assertSame(custom, s.openSession(new ImapListener()));
    }

    @Test
    public void testComposerBuildsConfiguredServer() {
        ImapListener l = new ImapListener();
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        ImapServer s = ImapServer.compose()
                .listener(l)
                .sessionPerConnection(new Supplier<ClientConnected>() {
                    @Override
                    public ClientConnected get() {
                        return new DefaultIMAPHandler();
                    }
                })
                .quotaManager(qm)
                .loginTimeoutMs(11L)
                .commandTimeoutMs(22L)
                .enableIDLE(false)
                .enableNAMESPACE(false)
                .enableQUOTA(false)
                .enableMOVE(false)
                .enableCOMPRESS(false)
                .enableUTF8ACCEPT(false)
                .enableSORT(false)
                .maxLineLength(77)
                .maxLiteralSize(88)
                .allowPlaintextLogin(true)
                .realm(null)
                .server();
        assertEquals(1, s.getListeners().size());
        assertSame(qm, s.getQuotaManager());
        assertEquals(11L, s.getLoginTimeoutMs());
        assertEquals(22L, s.getCommandTimeoutMs());
        assertFalse(s.isEnableIDLE());
        assertEquals(77, s.getMaxLineLength());
        assertEquals(88, s.getMaxLiteralSize());
        assertTrue(s.isAllowPlaintextLogin());
        ClientConnected c = s.openSession(l);
        assertTrue(c instanceof DefaultIMAPHandler);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testComposerDeprecatedBuilderAndMailboxFactory() {
        ImapServer s = ImapServer.builder()
                .listener(new ImapListener())
                .mailboxFactory(factory())
                .build();
        assertNotNull(s.openSession(new ImapListener()));
        ImapServer t = ImapServer.builder()
                .listener(new ImapListener())
                .sessionProvider(ImapServerSessionProviders.mailbox())
                .mailboxFactory(factory())
                .server();
        assertNotNull(t.openSession(new ImapListener()));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testComposerMailboxFactoryWithCustomProviderRejected() {
        ImapServer.Composer c = ImapServer.compose();
        c.sessionProvider(new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return null;
            }
        });
        try {
            c.mailboxFactory(factory());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testComposerValidation() {
        ImapServer.Composer c = ImapServer.compose();
        try {
            c.server();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            c.listener(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("listener", expected.getMessage());
        }
        try {
            c.sessionProvider(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("provider", expected.getMessage());
        }
    }

    @Test
    public void testSessionProvidersValidation() {
        try {
            ImapServerSessionProviders.mailbox(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("factory", expected.getMessage());
        }
        try {
            ImapServerSessionProviders.perSession(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("supplier", expected.getMessage());
        }
    }

    @Test
    public void testServerFallsBackToSingleListenerProvider() {
        MailboxStoreImapSessionProvider p = ImapServerSessionProviders.mailbox();
        ImapListener l = new ImapListener().sessionProvider(p);
        ImapServer s = ImapServer.compose().listener(l).server();
        assertNotNull(s.openSession(l));
    }
}
