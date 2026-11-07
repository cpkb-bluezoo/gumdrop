/*
 * NntpAsyncTest.java
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

package org.bluezoo.gumdrop.nntp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubFactory;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubMailbox;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubStore;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The handler when the storage executor is in play: store work is offered to
 * it and the answers come back when it has run. Its tasks are held by the
 * test, so the order of events is the test's to choose.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpAsyncTest {

    private TestGumdrop.QueuedExecutor queued;
    private StubFactory factory;
    private NntpListener listener;
    private StubEndpoint endpoint;
    private NntpProtocolHandler handler;

    @Before
    public void setUp() {
        queued = new TestGumdrop.QueuedExecutor();
        final Gumdrop gumdrop = TestGumdrop.create(queued);
        final SelectorLoop loop = new SelectorLoop(0) {
            @Override
            public Gumdrop getGumdrop() {
                return gumdrop;
            }
        };
        factory = new StubFactory();
        factory.add("test.group", "Subject: One\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        factory.add("other.group");
        listener = new NntpListener();
        listener.mailboxFactory(factory);
        endpoint = new StubEndpoint() {
            @Override
            public SelectorLoop getSelectorLoop() {
                return loop;
            }
        };
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sent.clear();
    }

    private void feed(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private String take() {
        String s = endpoint.text();
        endpoint.sent.clear();
        return s;
    }

    @Test
    public void testStoreWorkWaitsForTheExecutor() {
        feed("GROUP test.group\r\n");
        assertEquals("", take());
        assertTrue(queued.pendingCount() > 0);
        queued.runAll();
        assertEquals("211 1 1 1 test.group\r\n", take());
    }

    @Test
    public void testDisconnectBeforeTheStoreIsOpenClosesIt() {
        feed("GROUP test.group\r\n");
        handler.disconnected();
        queued.runAll();
        assertTrue(factory.lastStore.closed);
        assertEquals("", take());
    }

    @Test
    public void testSecondCommandWaitsForTheFirst() {
        feed("GROUP test.group\r\nSTAT 1\r\n");
        queued.runAll();
        assertEquals("211 1 1 1 test.group\r\n223 1 <1@x>\r\n", take());
    }

    /** A store whose closing fails with a bug, not an IOException. */
    private final class Unclosable extends StubStore {
        Unclosable(StubFactory f) {
            super(f);
        }

        @Override
        public void close() throws IOException {
            throw new IllegalStateException("bug");
        }

        @Override
        public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
            final StubMailbox base = (StubMailbox) super.openMailbox(name, readOnly);
            return new StubMailbox(name, base.articlesList(), base.deletedList()) {
                @Override
                public void close(boolean expunge) throws IOException {
                    throw new IllegalStateException("bug");
                }
            };
        }
    }

    private void useUnclosable() {
        listener.mailboxFactory(new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return new Unclosable(factory);
            }
        });
    }

    @Test
    public void testFailureToCloseAGroupIsSurvived() {
        useUnclosable();
        feed("GROUP test.group\r\n");
        queued.runAll();
        feed("GROUP other.group\r\n");
        queued.runAll();
        assertEquals("211 1 1 1 test.group\r\n211 0 0 0 other.group\r\n", take());
    }

    @Test
    public void testFailureToCloseOnDisconnectIsSurvived() {
        useUnclosable();
        feed("GROUP test.group\r\n");
        queued.runAll();
        assertEquals("211 1 1 1 test.group\r\n", take());
        handler.disconnected();
        queued.runAll();
        assertEquals("", take());
    }

    @Test
    public void testFailureToCloseAStoreThatArrivedTooLate() {
        useUnclosable();
        feed("GROUP test.group\r\n");
        handler.disconnected();
        queued.runAll();
        assertEquals("", take());
    }
}
