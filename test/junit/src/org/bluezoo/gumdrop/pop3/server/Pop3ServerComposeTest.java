/*
 * Pop3ServerComposeTest.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.pop3.Pop3Listener;

import static org.junit.Assert.*;

/**
 * Tests for {@link Pop3Server} composition, configuration accessors and
 * session-provider wiring.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Pop3ServerComposeTest {

    private static MailboxFactory factory() {
        return new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return null;
            }
        };
    }

    /** Provider counting lifecycle callbacks. */
    private static final class Counting implements Pop3ServerSessionProvider {
        int started;
        int stopped;

        @Override
        public ClientConnected openSession(TcpListener listener) {
            return new DefaultPOP3Handler("hi");
        }

        @Override
        public void start() {
            started++;
        }

        @Override
        public void stop() {
            stopped++;
        }
    }

    @Test
    public void defaultConfiguration() {
        Pop3Server s = new Pop3Server();
        assertNull(s.getRealm());
        assertNull(s.getMailboxFactory());
        assertEquals(0L, s.getLoginDelayMs());
        assertEquals(600000L, s.getTransactionTimeoutMs());
        assertTrue(s.isEnableUTF8());
        assertFalse(s.isEnablePipelining());
        assertEquals("POP3 server ready", s.getGreeting());
        assertTrue(s.getListeners().isEmpty());
        assertNull(s.openSession(new Pop3Listener()));
    }

    @Test
    public void settersRoundTrip() {
        Pop3Server s = new Pop3Server();
        s.loginDelayMs(5L);
        s.transactionTimeoutMs(7L);
        s.enableUTF8(false);
        s.enablePipelining(true);
        s.realm(null);
        assertEquals(5L, s.getLoginDelayMs());
        assertEquals(7L, s.getTransactionTimeoutMs());
        assertFalse(s.isEnableUTF8());
        assertTrue(s.isEnablePipelining());
    }

    @Test
    public void greetingCreatesMailboxProvider() {
        Pop3Server s = new Pop3Server();
        s.greeting("welcome");
        assertEquals("welcome", s.getGreeting());
        assertNotNull(s.openSession(new Pop3Listener()));
    }

    @Test
    public void mailboxFactoryCreatesAndUpdatesProvider() {
        Pop3Server s = new Pop3Server();
        MailboxFactory f1 = factory();
        s.mailboxFactory(f1);
        assertSame(f1, s.getMailboxFactory());
        MailboxFactory f2 = factory();
        s.mailboxFactory(f2);
        assertSame(f2, s.getMailboxFactory());
        s.mailboxFactory(null);
        assertNull(s.getMailboxFactory());
    }

    @Test
    public void greetingAfterFactoryReusesProvider() {
        Pop3Server s = new Pop3Server();
        s.mailboxFactory(factory());
        s.greeting("hello");
        assertEquals("hello", s.getGreeting());
    }

    @Test
    public void greetingRejectedForCustomProvider() {
        Pop3Server composed = Pop3Server.compose()
                .listener(new Pop3Listener())
                .sessionProvider(new Counting())
                .server();
        try {
            composed.greeting("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void deprecatedCreateHandlerDelegates() {
        Pop3Server s = new Pop3Server();
        s.greeting("g");
        assertNotNull(s.createHandler(new Pop3Listener()));
    }

    @Test
    public void composeBuildsServer() {
        Counting provider = new Counting();
        Pop3Server s = Pop3Server.compose()
                .listener(new Pop3Listener())
                .sessionProvider(provider)
                .realm(null)
                .loginDelayMs(10L)
                .transactionTimeoutMs(20L)
                .enableUTF8(false)
                .enablePipelining(true)
                .server();
        assertEquals(1, s.getListeners().size());
        assertEquals(10L, s.getLoginDelayMs());
        assertEquals(20L, s.getTransactionTimeoutMs());
        assertFalse(s.isEnableUTF8());
        assertTrue(s.isEnablePipelining());
        assertNotNull(s.openSession(new Pop3Listener()));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void deprecatedBuilderAndBuild() {
        Pop3Server s = Pop3Server.builder()
                .listener(new Pop3Listener())
                .mailboxFactory(factory())
                .build();
        assertNotNull(s);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void composerMailboxFactoryOnExistingMailboxProvider() {
        Pop3Server s = Pop3Server.compose()
                .listener(new Pop3Listener())
                .sessionProvider(Pop3ServerSessionProviders.mailbox())
                .mailboxFactory(factory())
                .server();
        assertNotNull(s);
    }

    @SuppressWarnings("deprecation")
    @Test(expected = IllegalStateException.class)
    public void composerMailboxFactoryRejectedForCustomProvider() {
        Pop3Server.compose()
                .sessionProvider(new Counting())
                .mailboxFactory(factory());
    }

    @SuppressWarnings("deprecation")
    @Test(expected = NullPointerException.class)
    public void composerMailboxFactoryNull() {
        Pop3Server.compose().mailboxFactory(null);
    }

    @Test(expected = IllegalStateException.class)
    public void composeRequiresListener() {
        Pop3Server.compose().server();
    }

    @Test(expected = NullPointerException.class)
    public void composeRejectsNullListener() {
        Pop3Server.compose().listener(null);
    }

    @Test(expected = NullPointerException.class)
    public void composeRejectsNullProvider() {
        Pop3Server.compose().sessionProvider(null);
    }

    @Test
    public void sessionPerConnectionSupplier() {
        Pop3Server s = Pop3Server.compose()
                .listener(new Pop3Listener())
                .sessionPerConnection(new Supplier<ClientConnected>() {
                    @Override
                    public ClientConnected get() {
                        return new DefaultPOP3Handler("per");
                    }
                })
                .server();
        assertNotNull(s.openSession(new Pop3Listener()));
    }

    @Test
    public void singleListenerProviderIsInherited() {
        Pop3Listener l = new Pop3Listener();
        l.sessionProvider(new Counting());
        Pop3Server s = Pop3Server.compose().listener(l).server();
        assertNotNull(s.openSession(l));
    }

    @Test
    public void startAndStopDriveLifecycle() {
        Counting provider = new Counting();
        Pop3Listener l = new Pop3Listener();
        Pop3Server s = Pop3Server.compose()
                .listener(l)
                .sessionProvider(provider)
                .server();
        s.mailboxFactory(null);
        s.start(null);
        assertEquals(1, provider.started);
        s.stop();
        assertEquals(1, provider.stopped);
    }

    @Test
    public void startWithoutProvider() {
        Pop3Server s = new Pop3Server();
        s.addListener(new Pop3Listener());
        s.mailboxFactory(factory());
        s.start(null);
        s.stop();
    }
}
