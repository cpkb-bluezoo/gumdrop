/*
 * ImapServerLifecycleTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.ImapListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Lifecycle tests for {@link ImapServer}: start and stop wiring of the
 * service configuration into listeners, provider start and stop hooks,
 * and listener failures during start and stop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapServerLifecycleTest {

    private Gumdrop gumdrop;

    @Before
    public void setUp() throws Exception {
        gumdrop = TestGumdrop.create();
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    private static MailboxFactory factory() {
        return new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
    }

    /** Listener whose start and stop are recorded and may fail. */
    private static final class RecordingListener extends ImapListener {
        final List<String> events;
        final boolean failStart;
        final boolean failStop;

        RecordingListener(List<String> events, boolean failStart,
                boolean failStop) {
            this.events = events;
            this.failStart = failStart;
            this.failStop = failStop;
        }

        @Override
        public void start() {
            events.add("listener-start");
            if (failStart) {
                throw new IllegalStateException("start refused");
            }
        }

        @Override
        public void stop() {
            events.add("listener-stop");
            if (failStop) {
                throw new IllegalStateException("stop refused");
            }
        }
    }

    private static final class RecordingProvider
            implements ImapServerSessionProvider {
        final List<String> events;

        RecordingProvider(List<String> events) {
            this.events = events;
        }

        @Override
        public void start() {
            events.add("provider-start");
        }

        @Override
        public void stop() {
            events.add("provider-stop");
        }

        @Override
        public ClientConnected openSession(TcpListener listener) {
            return null;
        }
    }

    @Test
    public void testStartPushesConfigurationIntoListener() {
        List<String> events = new ArrayList<String>();
        RecordingListener l = new RecordingListener(events, false, false);
        RecordingProvider provider = new RecordingProvider(events);
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        MailboxFactory mf = factory();
        ImapServer s = ImapServer.compose()
                .listener(l)
                .sessionProvider(provider)
                .quotaManager(qm)
                .loginTimeoutMs(1234L)
                .commandTimeoutMs(5678L)
                .enableIDLE(false)
                .enableNAMESPACE(false)
                .enableQUOTA(false)
                .enableMOVE(false)
                .enableCOMPRESS(false)
                .enableUTF8ACCEPT(false)
                .enableSORT(false)
                .maxLineLength(321)
                .maxLiteralSize(654)
                .allowPlaintextLogin(true)
                .server();
        s.setRealm(null);
        s.setMailboxFactory(mf);
        s.start(gumdrop);
        assertSame(s, l.getServer());
        assertEquals(1234L, l.getLoginTimeoutMs());
        assertEquals(5678L, l.getCommandTimeoutMs());
        assertFalse(l.isEnableIDLE());
        assertFalse(l.isEnableNAMESPACE());
        assertFalse(l.isEnableQUOTA());
        assertFalse(l.isEnableMOVE());
        assertFalse(l.isEnableCOMPRESS());
        assertFalse(l.isEnableUTF8ACCEPT());
        assertFalse(l.isEnableSORT());
        assertEquals(321, l.getMaxLineLength());
        assertEquals(654, l.getMaxLiteralSize());
        assertTrue(l.isAllowPlaintextLogin());
        assertSame(qm, l.getQuotaManager());
        assertSame(provider, l.getSessionProvider());
        assertEquals("provider-start", events.get(0));
        assertEquals("listener-start", events.get(1));
        s.stop();
        assertEquals("listener-stop", events.get(2));
        assertEquals("provider-stop", events.get(3));
        assertEquals(4, events.size());
    }

    @Test
    public void testStartWithoutProviderOrQuotaManager() {
        List<String> events = new ArrayList<String>();
        RecordingListener l = new RecordingListener(events, false, false);
        ImapServer s = new ImapServer();
        s.addListener(l);
        s.start(gumdrop);
        assertSame(s, l.getServer());
        assertNull(l.getSessionProvider());
        assertNull(l.getQuotaManager());
        s.stop();
        assertEquals(2, events.size());
        assertEquals("listener-start", events.get(0));
        assertEquals("listener-stop", events.get(1));
    }

    @Test
    public void testListenerStartAndStopFailuresDoNotAbort() {
        List<String> events = new ArrayList<String>();
        RecordingListener bad = new RecordingListener(events, true, true);
        RecordingListener good = new RecordingListener(events, false, false);
        RecordingProvider provider = new RecordingProvider(events);
        ImapServer s = ImapServer.compose()
                .listener(bad)
                .listener(good)
                .sessionProvider(provider)
                .server();
        s.start(gumdrop);
        s.stop();
        List<String> expected = new ArrayList<String>();
        expected.add("provider-start");
        expected.add("listener-start");
        expected.add("listener-start");
        expected.add("listener-stop");
        expected.add("listener-stop");
        expected.add("provider-stop");
        assertEquals(expected, events);
        assertSame(s, good.getServer());
    }

    @Test
    public void testSessionProviderDefaultHooksAreNoOps() {
        ImapServerSessionProvider p = new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return null;
            }
        };
        p.start();
        p.stop();
        assertNull(p.openSession(new ImapListener()));
    }

    @Test
    public void testMailboxProviderStartWithAndWithoutFactory() {
        MailboxStoreImapSessionProvider p =
                ImapServerSessionProviders.mailbox();
        assertNull(p.getMailboxFactory());
        p.start();
        MailboxFactory f = factory();
        p.mailboxFactory(f);
        assertSame(f, p.getMailboxFactory());
        p.start();
        ImapListener l = new ImapListener();
        ClientConnected c = p.openSession(l);
        assertTrue(c instanceof DefaultIMAPHandler);
        assertSame(f, l.getMailboxFactory());
        p.mailboxFactory(null);
        ImapListener l2 = new ImapListener();
        assertTrue(p.openSession(l2) instanceof DefaultIMAPHandler);
        assertNull(l2.getMailboxFactory());
        TcpListener plain = new TcpListener() {
            @Override
            public String getDescription() {
                return "plain";
            }

            @Override
            protected ProtocolHandler createHandler() {
                return null;
            }
        };
        assertTrue(p.openSession(plain) instanceof DefaultIMAPHandler);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testComposerDeprecatedMailboxFactoryNull() {
        try {
            ImapServer.compose().mailboxFactory(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("mailboxFactory", expected.getMessage());
        }
    }

    @Test
    public void testComposerRealmFlowsToServer() {
        ImapServer s = ImapServer.compose()
                .listener(new ImapListener())
                .realm(null)
                .server();
        assertNull(s.getRealm());
    }
}
