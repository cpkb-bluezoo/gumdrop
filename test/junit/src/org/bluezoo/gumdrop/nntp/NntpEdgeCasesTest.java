/*
 * NntpEdgeCasesTest.java
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
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubFactory;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubMailbox;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubStore;
import org.bluezoo.gumdrop.nntp.server.ArticleRef;
import org.bluezoo.gumdrop.nntp.server.ClientConnected;
import org.bluezoo.gumdrop.nntp.server.ConnectedState;
import org.bluezoo.gumdrop.nntp.server.DefaultNntpHandler;
import org.bluezoo.gumdrop.nntp.server.GroupState;
import org.bluezoo.gumdrop.nntp.server.NntpServerSessionProvider;
import org.bluezoo.gumdrop.nntp.server.ReaderHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge cases of the NNTP protocol handler and the stock handler: what
 * happens when the application or the store misbehaves, and the odd corners
 * of the command syntax.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpEdgeCasesTest {

    private static final String ART1 =
            "Subject: First\r\nFrom: a@example.com\r\nMessage-ID: <1@example.com>\r\n\r\nHello\r\n";

    private StubFactory factory;
    private NntpListener listener;
    private StubEndpoint endpoint;
    private NntpProtocolHandler handler;

    @Before
    public void setUp() {
        factory = new StubFactory();
        factory.add("test.group", ART1);
        factory.add("empty.group");
        listener = new NntpListener();
        listener.mailboxFactory(factory);
    }

    private void connect() {
        endpoint = new StubEndpoint();
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        take();
    }

    private void provide(final ClientConnected session) {
        listener.sessionProvider(new NntpServerSessionProvider() {
            @Override
            public ClientConnected openSession(org.bluezoo.gumdrop.TcpListener l) {
                return session;
            }
        });
    }

    private void feed(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** As a connection does: what the lexer leaves unread is offered again with the next bytes. */
    private void feedAll(String text) {
        ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < 5 && buf.hasRemaining(); i++) {
            handler.receive(buf);
        }
    }

    private String take() {
        String s = endpoint.text();
        endpoint.sent.clear();
        return s;
    }

    private String exchange(String command) {
        take();
        feed(command + "\r\n");
        return take();
    }

    // ── the connection ──

    @Test
    public void testTransportErrorClosesTheConnection() {
        connect();
        handler.error(new IOException("broken"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testRawBytesOnTheCommandChannelAreIgnored() {
        connect();
        handler.rawBytes(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals("205 Closing connection\r\n", exchange("QUIT"));
    }

    @Test
    public void testInvalidEncodingInTheCommandOrItsArguments() {
        connect();
        take();
        handler.receive(ByteBuffer.wrap(new byte[] {(byte) 0xC3, (byte) 0x28, '\r', '\n'}));
        assertEquals("501 Invalid command encoding\r\n", take());
        handler.receive(ByteBuffer.wrap(new byte[] {'G', 'R', 'O', 'U', 'P', ' ', (byte) 0xC3, (byte) 0x28, '\r', '\n'}));
        assertEquals("501 Invalid command encoding\r\n", take());
        assertEquals("205 Closing connection\r\n", exchange("QUIT"));
    }

    @Test
    public void testOverlongCommandWordIsDiscarded() {
        connect();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            sb.append('X');
        }
        take();
        feedAll(sb.toString() + "\r\n");
        String r = take();
        assertTrue(r, r.startsWith("501 "));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testSessionRejectedWithoutText() {
        provide(new ClientConnected() {
            @Override
            public void connected(ConnectedState state, org.bluezoo.gumdrop.Endpoint e) {
                state.rejectConnection();
            }

            @Override
            public void disconnected() {
            }
        });
        endpoint = new StubEndpoint();
        new NntpProtocolHandler(listener).connected(endpoint);
        assertEquals("502 Service permanently unavailable\r\n", take());
        assertFalse(endpoint.open);
    }

    @Test
    public void testSessionRejectedWithText() {
        provide(new ClientConnected() {
            @Override
            public void connected(ConnectedState state, org.bluezoo.gumdrop.Endpoint e) {
                state.rejectConnection("No news for you");
            }

            @Override
            public void disconnected() {
            }
        });
        endpoint = new StubEndpoint();
        new NntpProtocolHandler(listener).connected(endpoint);
        assertEquals("502 No news for you\r\n", take());
        assertFalse(endpoint.open);
    }

    @Test
    public void testServerShuttingDown() {
        provide(new ClientConnected() {
            @Override
            public void connected(ConnectedState state, org.bluezoo.gumdrop.Endpoint e) {
                state.serverShuttingDown();
            }

            @Override
            public void disconnected() {
            }
        });
        endpoint = new StubEndpoint();
        new NntpProtocolHandler(listener).connected(endpoint);
        assertEquals("400 Service temporarily unavailable\r\n", take());
        assertFalse(endpoint.open);
    }

    @Test
    public void testCommandsBeforeTheSessionIsAccepted() {
        provide(new ClientConnected() {
            @Override
            public void connected(ConnectedState state, org.bluezoo.gumdrop.Endpoint e) {
                // decides later
            }

            @Override
            public void disconnected() {
            }
        });
        connect();
        assertEquals("400 Service temporarily unavailable\r\n", exchange("DATE"));
    }

    // ── an application that takes its time ──

    /** Holds the state of the GROUP it was asked for until the test answers it. */
    private static final class Slow extends DefaultNntpHandler {
        GroupState pending;
        int asked;

        Slow() {
            super("slow", false);
        }

        @Override
        public void selectGroup(GroupState state, MailboxStore store, String group) {
            asked++;
            pending = state;
        }
    }

    @Test
    public void testCommandsSentWhileOneRunsWaitForIt() {
        final Slow slow = new Slow();
        provide(slow);
        connect();
        feed("GROUP a\r\nGROUP b\r\nDATE\r\n");
        assertEquals(1, slow.asked);
        assertEquals("", take());
        GroupState first = slow.pending;
        first.noSuchGroup(slow);
        // the answer to the first, then the second is run and waits in its turn
        assertEquals("411 No such newsgroup\r\n", take());
        assertEquals(2, slow.asked);
        slow.pending.noSuchGroup(slow);
        String r = take();
        assertTrue(r, r.startsWith("411 No such newsgroup\r\n111 "));
    }

    @Test
    public void testApplicationErrorIsReported() {
        final Slow slow = new Slow();
        provide(slow);
        connect();
        feed("GROUP a\r\n");
        slow.pending.error("it broke", slow);
        assertEquals("403 it broke\r\n", take());
        feed("GROUP a\r\n");
        slow.pending.error(null, slow);
        assertEquals("403 Internal fault\r\n", take());
    }

    @Test
    public void testApplicationThatThrowsGets403() {
        provide(new DefaultNntpHandler("hi", false) {
            @Override
            public void selectGroup(GroupState state, MailboxStore store, String group) {
                throw new IllegalStateException("bug");
            }
        });
        connect();
        assertEquals("403 Internal fault\r\n", exchange("GROUP test.group"));
        // the connection carries on
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testPostErrorIs441() {
        provide(new DefaultNntpHandler("hi", true) {
            @Override
            public void post(org.bluezoo.gumdrop.nntp.server.PostState state, MailboxStore store) {
                state.notPosted(null, this);
            }
        });
        listener.postingAllowed(true);
        connect();
        assertEquals("441 Posting failed\r\n", exchange("POST"));
    }

    // ── the store ──

    private void useStore(final StubStore store) {
        listener.mailboxFactory(new MailboxFactory() {
            @Override
            public MailboxStore createStore() {
                return store;
            }
        });
    }

    @Test
    public void testStoreThatCannotBeOpened() {
        useStore(new StubStore(factory) {
            @Override
            public void open(String username) throws IOException {
                throw new IOException("no such user");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("and it will not close");
            }
        });
        connect();
        assertEquals("403 Newsgroup store unavailable\r\n", exchange("GROUP test.group"));
    }

    @Test
    public void testNoStoreConfigured() {
        listener.mailboxFactory(null);
        connect();
        assertEquals("403 Newsgroup store not configured\r\n", exchange("GROUP test.group"));
    }

    // ── command syntax ──

    @Test
    public void testCurrentArticleNeeded() {
        connect();
        exchange("GROUP empty.group");
        assertEquals("420 Current article number is invalid\r\n", exchange("ARTICLE"));
        assertEquals("420 Current article number is invalid\r\n", exchange("OVER"));
        assertEquals("420 Current article number is invalid\r\n", exchange("HDR subject"));
    }

    @Test
    public void testBadArguments() {
        connect();
        exchange("GROUP test.group");
        assertTrue(exchange("ARTICLE <bad").startsWith("501 "));
        assertTrue(exchange("ARTICLE 12x").startsWith("501 "));
        assertTrue(exchange("OVER <bad").startsWith("501 "));
        assertTrue(exchange("OVER 1-x").startsWith("501 "));
        assertTrue(exchange("LISTGROUP test.group 1-x").startsWith("501 "));
        assertTrue(exchange("HDR").startsWith("501 "));
        assertTrue(exchange("LIST FROB").startsWith("501 "));
        assertTrue(exchange("NEWGROUPS 20240101").startsWith("501 "));
        assertTrue(exchange("NEWGROUPS 20240101 12").startsWith("501 "));
        assertTrue(exchange("NEWGROUPS 2024010x 120000").startsWith("501 "));
    }

    @Test
    public void testNewGroupsAcceptsTwoAndFourDigitYears() {
        connect();
        assertEquals("231 List of new newsgroups follows\r\n.\r\n", exchange("NEWGROUPS 20240101 000000"));
        assertEquals("231 List of new newsgroups follows\r\n.\r\n", exchange("NEWGROUPS 240101 000000"));
        assertEquals("231 List of new newsgroups follows\r\n.\r\n", exchange("NEWGROUPS 240101 000000 GMT"));
    }

    @Test
    public void testParsingHelpers() {
        assertTrue(NntpProtocolHandler.isMessageId("<a@b>"));
        assertFalse(NntpProtocolHandler.isMessageId("<>"));
        assertFalse(NntpProtocolHandler.isMessageId("a@b"));
        assertFalse(NntpProtocolHandler.isMessageId("<a b>"));
        assertFalse(NntpProtocolHandler.isMessageId("<a>b>"));
        assertFalse(NntpProtocolHandler.isMessageId("<aé>"));
        StringBuilder big = new StringBuilder("<");
        for (int i = 0; i < 250; i++) {
            big.append('x');
        }
        assertFalse(NntpProtocolHandler.isMessageId(big.append('>').toString()));

        assertEquals(0, NntpProtocolHandler.parseNumber("0"));
        assertEquals(2147483647L, NntpProtocolHandler.parseNumber("2147483647"));
        assertEquals(-1, NntpProtocolHandler.parseNumber("2147483648"));
        assertEquals(-1, NntpProtocolHandler.parseNumber("12345678901"));
        assertEquals(-1, NntpProtocolHandler.parseNumber(""));
        assertEquals(-1, NntpProtocolHandler.parseNumber("1a"));

        ArticleRef r = NntpProtocolHandler.parseRange("5-");
        assertEquals(5, r.getNumber());
        assertEquals(Long.MAX_VALUE, r.getLast());
        assertEquals(3, NntpProtocolHandler.parseRange("3-7").getNumber());
        assertEquals(7, NntpProtocolHandler.parseRange("3-7").getLast());
        assertEquals(4, NntpProtocolHandler.parseRange("4").getLast());
        assertNull(NntpProtocolHandler.parseRange("x"));
        assertNull(NntpProtocolHandler.parseRange("x-3"));
        assertNull(NntpProtocolHandler.parseRange("3-x"));
    }

    @Test
    public void testParseDateTime() {
        assertEquals(Instant.parse("2024-02-03T04:05:06Z"), NntpProtocolHandler.parseDateTime("20240203", "040506"));
        assertEquals(Instant.parse("2024-02-03T04:05:06Z"), NntpProtocolHandler.parseDateTime("240203", "040506"));
        // a two digit year that would be in the future is the century before
        Instant late = NntpProtocolHandler.parseDateTime("990101", "000000");
        assertTrue(late.isBefore(Instant.now()));
        assertNull(NntpProtocolHandler.parseDateTime("20241301", "000000"));
        assertNull(NntpProtocolHandler.parseDateTime("2024", "000000"));
        assertNull(NntpProtocolHandler.parseDateTime("20240101", "0000"));
        assertNull(NntpProtocolHandler.parseDateTime("20240101", "00000x"));
        assertNull(NntpProtocolHandler.parseDateTime("2024010x", "000000"));
        assertNull(NntpProtocolHandler.parseDateTime("20240101", "250000"));
    }

    // ── what is sent ──

    @Test
    public void testLongListsAreSentInBlocksAndDotsAreStuffed() {
        for (int i = 0; i < 300; i++) {
            factory.add("a.very.long.newsgroup.name.number." + i, new String[0]);
        }
        factory.add(".dotted", new String[0]);
        connect();
        String r = exchange("LIST NEWSGROUPS");
        assertTrue(r.length() > 8192);
        assertTrue(r, r.contains("\r\n..dotted\t\r\n"));
        assertTrue(r.endsWith("\r\n.\r\n"));
    }

    @Test
    public void testUnusualLineEndingsInAnArticle() {
        factory.add("odd.group", "Subject: x\r\nMessage-ID: <o@x>\r\n\r\nlone\rcarriage\rreturn\r");
        connect();
        exchange("GROUP odd.group");
        assertEquals("222 1 <o@x> body follows\r\nlone\rcarriage\rreturn\r\r\n.\r\n", exchange("BODY 1"));
    }

    @Test
    public void testOverviewValuesAreMadeSafe() {
        factory.add("tabs.group", "Subject: a\tb\r\nFrom: x@y\r\nMessage-ID: <t@x>\r\n\r\nbody\r\n");
        connect();
        exchange("GROUP tabs.group");
        String r = exchange("OVER 1");
        assertTrue(r, r.contains("\r\n1\ta b\tx@y\t"));
    }

    // ── a mailbox that cannot be read ──

    /** A store whose mailboxes fail where told to. */
    private static final class Faulty extends StubStore {
        boolean failList;
        boolean failOpen;
        boolean failCount;
        boolean failTop;
        boolean failContent;
        boolean failAppend;
        boolean failRead;
        boolean failClose;
        boolean failAsyncOpen;

        Faulty(StubFactory factory) {
            super(factory);
        }

        @Override
        public List<String> listMailboxes(String reference, String pattern) throws IOException {
            if (failList) {
                throw new IOException("list");
            }
            return super.listMailboxes(reference, pattern);
        }

        @Override
        public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
            if (failOpen) {
                throw new IOException("open");
            }
            final StubMailbox base = (StubMailbox) super.openMailbox(name, readOnly);
            return new StubMailbox(name, base.articlesList(), base.deletedList()) {
                @Override
                public int getMessageCount() throws IOException {
                    if (failCount) {
                        throw new IOException("count");
                    }
                    return super.getMessageCount();
                }

                @Override
                public java.nio.channels.ReadableByteChannel getMessageTop(int n, int lines) throws IOException {
                    if (failTop) {
                        throw new IOException("top");
                    }
                    return super.getMessageTop(n, lines);
                }

                @Override
                public java.nio.channels.ReadableByteChannel getMessageContent(int n) throws IOException {
                    if (failContent) {
                        throw new IOException("content");
                    }
                    return super.getMessageContent(n);
                }

                @Override
                public void startAppendMessage(java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> flags,
                        java.time.OffsetDateTime date) throws IOException {
                    if (failAppend) {
                        throw new IOException("append");
                    }
                    super.startAppendMessage(flags, date);
                }

                @Override
                public AsyncMessageContent openAsyncContent(int n) throws IOException {
                    if (failAsyncOpen) {
                        throw new IOException("async");
                    }
                    if (!failRead) {
                        return null;
                    }
                    return new AsyncMessageContent() {
                        @Override
                        public long size() {
                            return 10;
                        }

                        @Override
                        public long bodyOffset() {
                            return 0;
                        }

                        @Override
                        public void read(ByteBuffer dst, long position,
                                CompletionHandler<Integer, ByteBuffer> h) {
                            h.failed(new IOException("read"), dst);
                        }

                        @Override
                        public void close() throws IOException {
                            throw new IOException("close");
                        }
                    };
                }

                @Override
                public void close(boolean expunge) throws IOException {
                    if (failClose) {
                        throw new IOException("close");
                    }
                    super.close(expunge);
                }
            };
        }
    }

    private Faulty faulty() {
        Faulty f = new Faulty(factory);
        useStore(f);
        return f;
    }

    @Test
    public void testGroupSelectionFailures() {
        Faulty f = faulty();
        connect();
        assertEquals("411 No such newsgroup\r\n", exchange("GROUP comp.*"));
        f.failList = true;
        assertEquals("403 Unable to select newsgroup\r\n", exchange("GROUP test.group"));
    }

    @Test
    public void testListFailures() {
        Faulty f = faulty();
        connect();
        f.failOpen = true;
        // a group that cannot be counted is listed as empty
        assertTrue(exchange("LIST").contains("test.group 0 0 n\r\n"));
        f.failList = true;
        assertEquals("403 Unable to list newsgroups\r\n", exchange("LIST"));
        f.failList = false;
        f.failOpen = false;
        exchange("GROUP test.group");
        f.failCount = true;
        assertEquals("403 Unable to list articles\r\n", exchange("LISTGROUP"));
        assertEquals("403 Unable to list articles\r\n", exchange("OVER 1"));
        assertEquals("403 Unable to list articles\r\n", exchange("HDR subject 1"));
        assertEquals("403 Unable to retrieve article\r\n", exchange("ARTICLE 1"));
    }

    @Test
    public void testUnreadableArticles() {
        Faulty f = faulty();
        connect();
        exchange("GROUP test.group");
        f.failContent = true;
        assertEquals("403 Unable to retrieve article\r\n", exchange("ARTICLE 1"));
        f.failContent = false;
        f.failTop = true;
        assertEquals("403 Unable to list articles\r\n", exchange("OVER 1"));
    }

    @Test
    public void testAsyncReadFailureClosesTheConnection() {
        Faulty f = faulty();
        f.failRead = true;
        connect();
        exchange("GROUP test.group");
        take();
        feed("ARTICLE 1\r\n");
        assertTrue(take().startsWith("220 1 "));
        assertFalse(endpoint.open);
    }

    @Test
    public void testHeadersByByteCountAndForAbsentArticles() {
        connect();
        exchange("GROUP test.group");
        assertTrue(exchange("HDR :bytes 1").contains("\r\n1 "));
        assertEquals("225 Headers follow\r\n1 \r\n.\r\n".replace("1 \r\n", "1 \r\n"),
                exchange("HDR :lines 1"));
        assertTrue(exchange("HDR Message-ID 1").contains("<1@example.com>"));
        assertTrue(exchange("HDR subject 5-9").startsWith("423 "));
        assertTrue(exchange("OVER 5-9").startsWith("423 "));
        assertTrue(exchange("OVER <9@x>").startsWith("430 "));
    }

    @Test
    public void testEmptyArticle() {
        factory.add("blank.group", "");
        connect();
        exchange("GROUP blank.group");
        assertEquals("220 1 <1@gumdrop.invalid> article follows\r\n.\r\n", exchange("ARTICLE 1"));
    }

    @Test
    public void testNextAndLastWithNoCurrentArticle() {
        connect();
        exchange("GROUP empty.group");
        assertEquals("420 Current article number is invalid\r\n", exchange("NEXT"));
        assertEquals("420 Current article number is invalid\r\n", exchange("LAST"));
    }

    @Test
    public void testArticleWithoutAMessageIdGetsOne() {
        factory.add("anon.group", "Subject: no id\r\n\r\nbody\r\n");
        connect();
        exchange("GROUP anon.group");
        assertTrue(exchange("STAT 1").startsWith("223 1 <1@gumdrop.invalid>"));
        assertTrue(exchange("STAT <1@gumdrop.invalid>").startsWith("223 0 <1@gumdrop.invalid>"));
        assertTrue(exchange("STAT <nope@x>").startsWith("430 "));
    }

    // ── posting ──

    @Test
    public void testPostRefusedByTheApplication() {
        provide(new DefaultNntpHandler("hi", false));
        listener.postingAllowed(true);
        connect();
        assertEquals("440 Posting not permitted\r\n", exchange("POST"));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testPostedArticleThatIsTooLarge() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            line.append('x');
        }
        line.append("\r\n");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n\r\n");
        for (int i = 0; i < 1100; i++) {
            feed(line.toString());
        }
        feed(".\r\n");
        assertTrue(take().endsWith("441 Article too large\r\n"));
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testPostedArticleToOddNewsgroups() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: ,comp.*, test.group\r\n\r\nx\r\n.\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(2, factory.groups.get("test.group").size());
    }

    @Test
    public void testPostThatCannotBeStored() {
        Faulty f = faulty();
        f.failAppend = true;
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: test.group\r\n\r\nx\r\n.\r\n");
        assertEquals("441 Posting failed\r\n", take());
    }

    @Test
    public void testConnectionLostDuringPost() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\n");
        handler.disconnected();
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testDisconnectClosesTheMailboxAndStore() {
        connect();
        exchange("GROUP test.group");
        StubMailbox mailbox = factory.lastMailbox;
        handler.disconnected();
        assertTrue(mailbox.closed);
        assertTrue(factory.lastStore.closed);
    }

    @Test
    public void testFailuresClosingAreSurvived() {
        Faulty f = faulty();
        f.failClose = true;
        connect();
        exchange("GROUP test.group");
        exchange("GROUP empty.group");
        handler.disconnected();
        assertNotNull(factory);
    }

    @Test
    public void testArticleLoadedWhenAsyncReadsCannotBeOpened() {
        Faulty f = faulty();
        f.failAsyncOpen = true;
        connect();
        exchange("GROUP test.group");
        String r = exchange("BODY 1");
        assertTrue(r, r.startsWith("222 1 <1@example.com> body follows\r\nHello\r\n"));
    }

    @Test
    public void testCurrentArticleThatHasGoneAway() {
        connect();
        exchange("GROUP test.group");
        exchange("STAT 1");
        factory.delete("test.group", 1);
        assertEquals("420 Current article number is invalid\r\n", exchange("ARTICLE"));
    }

    @Test
    public void testPostToAGroupThatDoesNotExistAmongOthers() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: no.such.group,test.group\r\n\r\nx\r\n.\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(2, factory.groups.get("test.group").size());
    }

    /** Holds back the write-ready callback until the test lets it go, as a full socket would. */
    private static final class Congested extends StubEndpoint {
        Runnable pending;

        @Override
        public void onWriteReady(Runnable callback) {
            pending = callback;
        }

        /** Lets the writer carry on until it has nothing more to wait for. */
        void drain() {
            for (int i = 0; i < 1000 && pending != null; i++) {
                Runnable r = pending;
                pending = null;
                r.run();
            }
        }
    }

    @Test
    public void testArticleIsPacedByTheSocket() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            body.append("a line of the article, number ").append(i).append("\r\n");
        }
        factory.add("big.group", "Subject: big\r\nMessage-ID: <big@x>\r\n\r\n" + body);
        Congested slow = new Congested();
        endpoint = slow;
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        take();
        exchange("GROUP big.group");
        take();
        feed("BODY 1\r\n");
        assertTrue(slow.pending != null);
        slow.drain();
        String r = take();
        assertTrue(r.length() > 100000);
        assertTrue(r.endsWith("number 4999\r\n.\r\n"));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    // ── the stock handler called directly ──

    private static final class Noted implements org.bluezoo.gumdrop.nntp.server.ArticleState,
            org.bluezoo.gumdrop.nntp.server.OverState, org.bluezoo.gumdrop.nntp.server.PostState {
        String last;

        public void found(long number, String messageId, AsyncMessageContent content, ReaderHandler handler) {
            last = "found";
        }

        public void notFound(ReaderHandler handler) {
            last = "notFound";
        }

        public void error(String message, ReaderHandler handler) {
            last = "error";
        }

        public OverviewWriter beginOverview() {
            last = "overview";
            return null;
        }

        public HeaderWriter beginHeaders() {
            last = "headers";
            return null;
        }

        public void accept(org.bluezoo.gumdrop.nntp.server.PostSink sink) {
            last = "accept";
        }

        public void refuse(ReaderHandler handler) {
            last = "refuse";
        }

        public void posted(ReaderHandler handler) {
            last = "posted";
        }

        public void notPosted(String message, ReaderHandler handler) {
            last = "notPosted";
        }
    }

    @Test
    public void testStockHandlerRefusesToPostWhenItWasToldNotTo() {
        Noted n = new Noted();
        new DefaultNntpHandler("hi", false).post(n, null);
        assertEquals("refuse", n.last);
    }

    @Test
    public void testStockHandlerDoesNotResolveARangeAsOneArticle() throws IOException {
        Noted n = new Noted();
        Mailbox mb = new StubStore(factory).openMailbox("test.group", true);
        new DefaultNntpHandler("hi", false).retrieve(n, mb, ArticleRef.range(1, 2),
                org.bluezoo.gumdrop.nntp.server.ArticlePart.ARTICLE);
        assertEquals("notFound", n.last);
    }

    @Test
    public void testStockHandlerWithNoMailbox() {
        Noted n = new Noted();
        DefaultNntpHandler h = new DefaultNntpHandler("hi", false);
        h.overview(n, null, ArticleRef.number(1));
        assertEquals("notFound", n.last);
        h.headers(n, null, ArticleRef.number(1), "subject");
        assertEquals("notFound", n.last);
    }

    @Test
    public void testArticleRefsDescribeThemselves() {
        assertEquals("<a@b>", ArticleRef.messageId("<a@b>").toString());
        assertEquals("3-", ArticleRef.range(3, Long.MAX_VALUE).toString());
        assertEquals("3-7", ArticleRef.range(3, 7).toString());
        assertTrue(ArticleRef.current(4).toString().contains("4"));
        assertTrue(ArticleRef.next(4).toString().contains("4"));
    }
}
