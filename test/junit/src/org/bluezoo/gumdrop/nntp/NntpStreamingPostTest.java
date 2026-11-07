/*
 * NntpStreamingPostTest.java
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
import java.util.List;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A posted article is stored as it arrives, in constant memory: only the
 * header block is held, writes are made one chunk at a time, and the
 * connection is told to stop reading when the store falls behind.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpStreamingPostTest {

    private static final String HEAD = "From: a@example.com\r\nSubject: s\r\nNewsgroups: test.group\r\n\r\n";

    /** A connection whose reading the server can pause, as a socket's can. */
    private static class Pausable extends StubEndpoint {
        boolean paused;
        int pauses;

        @Override
        public void pauseRead() {
            paused = true;
            pauses++;
        }

        @Override
        public void resumeRead() {
            paused = false;
        }
    }

    private StubFactory factory;
    private NntpListener listener;
    private Pausable endpoint;
    private NntpProtocolHandler handler;

    @Before
    public void setUp() {
        factory = new StubFactory();
        factory.add("test.group", "Subject: One\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        factory.add("other.group");
        listener = new NntpListener();
        listener.mailboxFactory(factory);
        listener.postingAllowed(true);
        connect();
    }

    private void connect() {
        endpoint = new Pausable();
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

    private String post(String article) {
        feed("POST\r\n");
        assertEquals("340 Send article to be posted\r\n", take());
        feed(article + ".\r\n");
        return take();
    }

    /** Makes every mailbox opened from now on hold its write completions back. */
    private void deferWrites() {
        factory = new StubFactory() {
            @Override
            public MailboxStore createStore() {
                return new StubStore(this) {
                    @Override
                    public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
                        StubMailbox m = (StubMailbox) super.openMailbox(name, readOnly);
                        m.deferWrites = true;
                        return m;
                    }
                };
            }
        };
        factory.add("test.group", "Subject: One\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        listener.mailboxFactory(factory);
        connect();
    }

    // ── the memory bound ──

    @Test
    public void testALargeArticleIsStreamedWithinTheBound() {
        deferWrites();

        feed("POST\r\n");
        take();
        final int read = 64 * 1024;
        final long total = 20L * 1024 * 1024;
        byte[] chunk = new byte[read];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) ('a' + i % 26);
            if (i % 78 == 77) {
                chunk[i] = '\n'; // lines, none beginning with a dot
            }
        }
        // line endings as CRLF would double the size; keep bare LF out of it by using CR LF pairs
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] == '\n' && i > 0) {
                chunk[i - 1] = '\r';
            }
        }
        handler.receive(ByteBuffer.wrap(HEAD.getBytes(StandardCharsets.ISO_8859_1)));
        long fed = 0;
        long peak = 0;
        int guard = 0;
        while (fed < total) {
            assertTrue("no progress: fed=" + fed + " paused=" + endpoint.paused + " pauses=" + endpoint.pauses + " opened=" + factory.opened.size() + " sent=" + endpoint.text(), ++guard < 100000);
            if (endpoint.paused) {
                // the storage catches up, as it does while the connection waits
                for (StubMailbox m : factory.opened) {
                    m.completeWrites();
                }
                continue;
            }
            handler.receive(ByteBuffer.wrap(chunk));
            fed += read;
            long stored = 0;
            for (StubMailbox m : factory.opened) {
                for (StubMailbox.StubWriter w : m.writers) {
                    stored += w.data.size();
                }
            }
            peak = Math.max(peak, fed - stored);
        }
        assertTrue("the server asked the connection to wait", endpoint.pauses > 0);
        // header block + 256 KiB high-water mark + the reads in progress when it is hit
        assertTrue("peak outstanding " + peak, peak < 256 * 1024 + 4L * read);
        // finish: the terminator, then let the storage drain
        feed("\r\n.\r\n"); // the last line, then the terminator at the start of a line
        StringBuilder replies = new StringBuilder(take());
        for (int i = 0; i < 100000 && replies.indexOf("\r\n") < 0; i++) {
            for (StubMailbox m : factory.opened) {
                m.completeWrites();
            }
            replies.append(take());
        }
        assertEquals("240 Article received OK\r\n", replies.toString());
        StubMailbox.StubWriter w = factory.opened.get(0).writers.get(0);
        assertTrue("finished=" + w.finished + " aborted=" + w.aborted + " pending=" + w.pending.size()
                + " stored=" + w.data.size() + " fed=" + fed + " paused=" + endpoint.paused, w.finished);
        assertEquals(HEAD.length() + fed + 2, w.data.size());
        assertFalse(endpoint.paused);
    }

    @Test
    public void testWritesAreOneChunkAtATime() {
        deferWrites();
        feed("POST\r\n");
        take();
        feed(HEAD + "x"); // the blank line is held back until the byte after it
        StubMailbox probe = factory.opened.get(0);
        feed("one\r\n");
        feed("two\r\n");
        feed("three\r\n");
        // the header went first and its write is outstanding: what arrives meanwhile
        // is gathered, not written alongside it
        StubMailbox.StubWriter w = probe.writers.get(0);
        assertEquals(0, w.chunkSizes.size());
        assertEquals(1, w.pending.size());
        probe.completeWrites();
        // the header is stored, and what was gathered goes as one write
        assertEquals(java.util.Arrays.asList(Integer.valueOf(HEAD.length())), w.chunkSizes);
        assertEquals(1, w.pending.size());
        probe.completeWrites();
        assertEquals(2, w.chunkSizes.size());
        // (the last line ending is held by the unstuffer until the byte after it)
        assertEquals(Integer.valueOf("xone\r\ntwo\r\nthree".length()), w.chunkSizes.get(1));
    }

    // ── what is stored ──

    @Test
    public void testArticleIsStoredByteForByte() {
        String article = HEAD + "line one\r\n.starts with a dot\r\nlast\r\n";
        // the dot is stuffed on the wire
        feed("POST\r\n");
        take();
        feed(HEAD + "line one\r\n..starts with a dot\r\nlast\r\n.\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(article, factory.groups.get("test.group").get(1));
        StubMailbox m = factory.opened.get(0);
        assertTrue(m.writers.get(0).finished);
        assertTrue(m.closed);
    }

    @Test
    public void testSplitAnywhereGivesTheSameArticle() {
        String article = HEAD + "a line\r\nand another\r\n";
        feed("POST\r\n");
        take();
        String wire = article + ".\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(article, factory.groups.get("test.group").get(1));
    }

    @Test
    public void testCrossPostedArticleGoesToEveryGroup() {
        feed("POST\r\n");
        take();
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: test.group, other.group,test.group\r\n\r\nbody\r\n.\r\n");
        assertEquals("240 Article received OK\r\n", take());
        String expected = "From: a@x\r\nSubject: s\r\nNewsgroups: test.group, other.group,test.group\r\n\r\nbody\r\n";
        assertEquals(expected, factory.groups.get("test.group").get(1));
        assertEquals(expected, factory.groups.get("other.group").get(0));
        assertEquals(2, factory.opened.size());
    }

    @Test
    public void testHeadersWithNoBlankLine() {
        feed("POST\r\n");
        take();
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n.\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n",
                factory.groups.get("test.group").get(1));
    }

    // ── what is refused ──

    @Test
    public void testStoreThatCannotStreamRefusesRatherThanBuffering() {
        listener.mailboxFactory(new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return new StubStore(factory) {
                    @Override
                    public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
                        StubMailbox m = (StubMailbox) super.openMailbox(name, readOnly);
                        m.asyncAppend = false;
                        return m;
                    }
                };
            }
        });
        connect();
        assertEquals("441 This newsgroup store cannot take posts\r\n", post(HEAD + "body\r\n"));
        assertEquals(1, factory.groups.get("test.group").size());
        assertTrue(factory.opened.get(0).closed);
    }

    @Test
    public void testHeaderBlockThatNeverEnds() {
        feed("POST\r\n");
        take();
        StringBuilder sb = new StringBuilder("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n");
        for (int i = 0; i < 2000; i++) {
            sb.append("X-Filler-").append(i).append(": xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\r\n");
        }
        feed(sb.toString());
        feed("more\r\n.\r\n");
        assertEquals("441 Article headers too large\r\n", take());
        assertTrue(factory.opened.isEmpty());
    }

    @Test
    public void testIncompleteHeadersAreRefusedAndTheRestDropped() {
        assertEquals("441 Article must have From, Subject and Newsgroups headers\r\n",
                post("Subject: no from\r\nNewsgroups: test.group\r\n\r\nbody\r\n"));
        assertTrue(factory.opened.isEmpty());
        // the connection is usable again
        assertTrue(post(HEAD + "ok\r\n").startsWith("240 "));
    }

    @Test
    public void testNoSuchNewsgroup() {
        assertEquals("441 None of the newsgroups exist\r\n",
                post("From: a@x\r\nSubject: s\r\nNewsgroups: no.such\r\n\r\nbody\r\n"));
    }

    // ── failures ──

    private void failing(final boolean writes, final boolean finish) {
        listener.mailboxFactory(new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return new StubStore(factory) {
                    @Override
                    public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
                        StubMailbox m = (StubMailbox) super.openMailbox(name, readOnly);
                        m.failWrites = writes;
                        m.failFinish = finish;
                        return m;
                    }
                };
            }
        });
        connect();
    }

    @Test
    public void testWriteFailureFailsThePostAndAbortsTheWriter() {
        failing(true, false);
        feed("POST\r\n");
        take();
        feed(HEAD + "some body\r\n");
        feed("and more body\r\n.\r\n");
        assertEquals("441 Posting failed\r\n", take());
        StubMailbox m = factory.opened.get(0);
        assertTrue(m.writers.get(0).aborted);
        assertTrue(m.closed);
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testFinishFailureFailsThePost() {
        failing(false, true);
        assertEquals("441 Posting failed\r\n", post(HEAD + "body\r\n"));
        assertTrue(factory.opened.get(0).writers.get(0).aborted);
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testConnectionLostMidArticleAbortsTheWriters() {
        feed("POST\r\n");
        take();
        feed(HEAD + "half an arti");
        handler.disconnected();
        StubMailbox m = factory.opened.get(0);
        assertTrue(m.writers.get(0).aborted);
        assertFalse(m.writers.get(0).finished);
        assertTrue(m.closed);
        assertEquals(1, factory.groups.get("test.group").size());
    }

    // ── the storage executor in play: the mailboxes open off the event loop ──

    private TestGumdrop.QueuedExecutor queued;

    private void withExecutor() {
        queued = new TestGumdrop.QueuedExecutor();
        final Gumdrop gumdrop = TestGumdrop.create(queued);
        final SelectorLoop loop = new SelectorLoop(0) {
            @Override
            public Gumdrop getGumdrop() {
                return gumdrop;
            }
        };
        endpoint = new Pausable() {
            @Override
            public SelectorLoop getSelectorLoop() {
                return loop;
            }
        };
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        endpoint.sent.clear();
    }

    @Test
    public void testBodyArrivingWhileTheMailboxesOpenWaitsInOrder() {
        withExecutor();
        feed("POST\r\n");
        queued.runAll(); // the store opens and the handler decides
        take();
        // the whole article, in one go: the body arrives before the mailboxes are open
        feed(HEAD + "the body\r\nof the article\r\n.\r\n");
        assertTrue(endpoint.paused);
        assertEquals("", take());
        queued.runAll();
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(HEAD + "the body\r\nof the article\r\n", factory.groups.get("test.group").get(1));
        assertFalse(endpoint.paused);
    }

    @Test
    public void testConnectionLostWhileTheMailboxesOpen() {
        withExecutor();
        feed("POST\r\n");
        queued.runAll();
        take();
        feed(HEAD + "partial");
        handler.disconnected();
        queued.runAll();
        StubMailbox m = factory.opened.get(0);
        assertTrue(m.writers.get(0).aborted);
        assertTrue(m.closed);
        assertEquals(1, factory.groups.get("test.group").size());
    }
}
