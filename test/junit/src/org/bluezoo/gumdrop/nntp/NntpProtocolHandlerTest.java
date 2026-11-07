/*
 * NntpProtocolHandlerTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link NntpProtocolHandler} with the stock handler over an
 * in-memory mailbox store. A stub endpoint stands in for the connection,
 * and there is no storage executor, so handler calls run inline.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpProtocolHandlerTest {

    private static final String ART1 =
            "Subject: First\r\nFrom: a@example.com\r\nDate: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
            + "Message-ID: <1@example.com>\r\nReferences: <0@example.com>\r\n"
            + "\r\nHello\r\n.dot line\r\nlast\r\n";
    // LF-only line endings, no final line ending
    private static final String ART2 =
            "Subject: Second\nFrom: b@example.com\nMessage-ID: <2@example.com>\n\nbody2";
    private static final String ART3 =
            "Subject: Deleted\r\nMessage-ID: <3@example.com>\r\n\r\ngone\r\n";
    private static final String ART4 =
            "Subject: Fourth\r\nFrom: d@example.com\r\nMessage-ID: <4@example.com>\r\n\r\nfour\r\n";

    private NntpProtocolHandler handler;
    private StubEndpoint endpoint;
    private TestListener listener;
    private StubFactory factory;

    @Before
    public void setUp() {
        factory = new StubFactory();
        factory.add("test.group", ART1, ART2, ART3, ART4);
        factory.delete("test.group", 3);
        factory.add("empty.group");
        listener = new TestListener();
        listener.mailboxFactory(factory);
        handler = new NntpProtocolHandler(listener);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
    }

    private void send(String command) {
        handler.receive(ByteBuffer.wrap((command + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** Everything sent so far, and forgets it. */
    private String take() {
        String s = endpoint.text();
        endpoint.sent.clear();
        return s;
    }

    private String exchange(String command) {
        take();
        send(command);
        return take();
    }

    @Test
    public void testGreeting() {
        assertEquals("201 NNTP server ready\r\n", take());
    }

    @Test
    public void testGreetingWhenPostingAllowed() {
        TestListener l = new TestListener();
        l.postingAllowed(true);
        StubEndpoint e = new StubEndpoint();
        new NntpProtocolHandler(l).connected(e);
        assertEquals("200 NNTP server ready\r\n", e.text());
    }

    @Test
    public void testCapabilities() {
        String r = exchange("CAPABILITIES");
        assertTrue(r, r.startsWith("101 Capability list:\r\nVERSION 2\r\n"));
        assertTrue(r, r.contains("\r\nREADER\r\n"));
        assertTrue(r, r.contains("\r\nOVER\r\n"));
        assertTrue(r, r.contains("\r\nHDR\r\n"));
        assertTrue(r, r.endsWith("\r\n.\r\n"));
        assertFalse(r, r.contains("\r\nPOST\r\n"));
    }

    @Test
    public void testModeReader() {
        assertEquals("201 Reader mode\r\n", exchange("MODE READER"));
        assertTrue(exchange("MODE STREAM").startsWith("501 "));
    }

    @Test
    public void testDate() {
        String r = exchange("DATE");
        assertTrue(r, r.matches("111 \\d{14}\r\n"));
    }

    @Test
    public void testHelp() {
        String r = exchange("HELP");
        assertTrue(r, r.startsWith("100 "));
        assertTrue(r, r.endsWith("\r\n.\r\n"));
    }

    @Test
    public void testUnknownCommandAndCaseInsensitivity() {
        assertEquals("500 Unknown command\r\n", exchange("FROBNICATE"));
        assertEquals("205 Closing connection\r\n", exchange("quit"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testLineTooLong() {
        StringBuilder sb = new StringBuilder("GROUP ");
        for (int i = 0; i < 600; i++) {
            sb.append('x');
        }
        String r = exchange(sb.toString());
        assertTrue(r, r.startsWith("501 "));
        // and the connection carries on
        assertEquals("205 Closing connection\r\n", exchange("QUIT"));
    }

    @Test
    public void testGroup() {
        assertEquals("211 4 1 4 test.group\r\n", exchange("GROUP test.group"));
        assertEquals("411 No such newsgroup\r\n", exchange("GROUP no.such"));
        assertEquals("211 0 0 0 empty.group\r\n", exchange("GROUP empty.group"));
        assertTrue(exchange("GROUP").startsWith("501 "));
    }

    @Test
    public void testSelectingAGroupClosesTheLastOne() {
        exchange("GROUP test.group");
        StubMailbox first = factory.lastMailbox;
        exchange("GROUP empty.group");
        assertTrue(first.closed);
    }

    @Test
    public void testListGroup() {
        assertEquals("412 No newsgroup selected\r\n", exchange("LISTGROUP"));
        assertEquals("211 4 1 4 test.group list follows\r\n1\r\n2\r\n4\r\n.\r\n",
                exchange("LISTGROUP test.group"));
        assertEquals("211 4 1 4 test.group list follows\r\n2\r\n4\r\n.\r\n",
                exchange("LISTGROUP test.group 2-"));
        assertEquals("211 4 1 4 test.group list follows\r\n1\r\n.\r\n",
                exchange("LISTGROUP test.group 1-1"));
        assertEquals("211 4 1 4 test.group list follows\r\n1\r\n2\r\n4\r\n.\r\n",
                exchange("LISTGROUP"));
        assertEquals("411 No such newsgroup\r\n", exchange("LISTGROUP no.such"));
    }

    @Test
    public void testArticleNeedsAGroupExceptByMessageId() {
        assertEquals("412 No newsgroup selected\r\n", exchange("ARTICLE"));
        assertEquals("412 No newsgroup selected\r\n", exchange("ARTICLE 1"));
        assertEquals("412 No newsgroup selected\r\n", exchange("LAST"));
        assertEquals("430 No article with that message-id\r\n", exchange("ARTICLE <1@example.com>"));
    }

    @Test
    public void testArticleByNumber() {
        exchange("GROUP test.group");
        assertEquals("220 1 <1@example.com> article follows\r\n"
                + "Subject: First\r\nFrom: a@example.com\r\nDate: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "Message-ID: <1@example.com>\r\nReferences: <0@example.com>\r\n"
                + "\r\nHello\r\n..dot line\r\nlast\r\n.\r\n", exchange("ARTICLE 1"));
    }

    @Test
    public void testHeadAndBody() {
        exchange("GROUP test.group");
        assertEquals("221 1 <1@example.com> head follows\r\n"
                + "Subject: First\r\nFrom: a@example.com\r\nDate: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "Message-ID: <1@example.com>\r\nReferences: <0@example.com>\r\n.\r\n",
                exchange("HEAD 1"));
        assertEquals("222 1 <1@example.com> body follows\r\n"
                + "Hello\r\n..dot line\r\nlast\r\n.\r\n", exchange("BODY 1"));
    }

    @Test
    public void testLineEndingsAreNormalisedAndLastLineTerminated() {
        exchange("GROUP test.group");
        assertEquals("220 2 <2@example.com> article follows\r\n"
                + "Subject: Second\r\nFrom: b@example.com\r\nMessage-ID: <2@example.com>\r\n"
                + "\r\nbody2\r\n.\r\n", exchange("ARTICLE 2"));
    }

    @Test
    public void testStatAndCurrentArticle() {
        exchange("GROUP test.group");
        assertEquals("223 1 <1@example.com>\r\n", exchange("STAT"));
        assertEquals("223 2 <2@example.com>\r\n", exchange("STAT 2"));
        // STAT by number moves the current article
        assertEquals("223 2 <2@example.com>\r\n", exchange("STAT"));
        // by message-id it does not, and the number is 0
        assertEquals("223 0 <4@example.com>\r\n", exchange("STAT <4@example.com>"));
        assertEquals("223 2 <2@example.com>\r\n", exchange("STAT"));
    }

    @Test
    public void testNextAndLastSkipDeletedArticles() {
        exchange("GROUP test.group");
        assertEquals("421 No next article in this group\r\n".replace("421", "223 2 <2@example.com>")
                .substring(0, 0) + "223 2 <2@example.com>\r\n", exchange("NEXT"));
        assertEquals("223 4 <4@example.com>\r\n", exchange("NEXT"));
        assertEquals("421 No next article in this group\r\n", exchange("NEXT"));
        assertEquals("223 2 <2@example.com>\r\n", exchange("LAST"));
        assertEquals("223 1 <1@example.com>\r\n", exchange("LAST"));
        assertEquals("422 No previous article in this group\r\n", exchange("LAST"));
    }

    @Test
    public void testNoSuchArticle() {
        exchange("GROUP test.group");
        assertEquals("423 No article with that number\r\n", exchange("ARTICLE 3"));
        assertEquals("423 No article with that number\r\n", exchange("STAT 99"));
        assertEquals("430 No article with that message-id\r\n", exchange("BODY <nope@example.com>"));
        assertTrue(exchange("ARTICLE xyz").startsWith("501 "));
        assertTrue(exchange("ARTICLE <bad id>").startsWith("501 "));
    }

    @Test
    public void testNoCurrentArticleInEmptyGroup() {
        exchange("GROUP empty.group");
        assertEquals("420 Current article number is invalid\r\n", exchange("ARTICLE"));
        assertEquals("420 Current article number is invalid\r\n", exchange("NEXT"));
    }

    @Test
    public void testListActive() {
        String expected = "215 Information follows\r\n"
                + "empty.group 0 0 n\r\ntest.group 4 1 n\r\n.\r\n";
        assertEquals(expected, exchange("LIST"));
        assertEquals(expected, exchange("LIST ACTIVE"));
        assertEquals("215 Information follows\r\ntest.group 4 1 n\r\n.\r\n",
                exchange("LIST ACTIVE test.*"));
        assertEquals("215 Information follows\r\nempty.group 0 0 n\r\n.\r\n",
                exchange("LIST ACTIVE *,!test.*"));
    }

    @Test
    public void testListNewsgroups() {
        assertEquals("215 Descriptions follow\r\nempty.group\t\r\ntest.group\t\r\n.\r\n",
                exchange("LIST NEWSGROUPS"));
    }

    @Test
    public void testListOverviewFmt() {
        String r = exchange("LIST OVERVIEW.FMT");
        assertTrue(r, r.startsWith("215 "));
        assertTrue(r, r.contains("\r\nSubject:\r\nFrom:\r\nDate:\r\nMessage-ID:\r\nReferences:\r\n:bytes\r\n:lines\r\n"));
        assertTrue(r, r.endsWith("\r\n.\r\n"));
    }

    @Test
    public void testListUnknownKeyword() {
        assertTrue(exchange("LIST BOGUS").startsWith("501 "));
    }

    @Test
    public void testNewGroupsFindsNothingNew() {
        assertEquals("231 List of new newsgroups follows\r\n.\r\n", exchange("NEWGROUPS 20240101 000000 GMT"));
        assertEquals("231 List of new newsgroups follows\r\n.\r\n", exchange("NEWGROUPS 240101 000000"));
        assertTrue(exchange("NEWGROUPS yesterday now").startsWith("501 "));
        assertTrue(exchange("NEWGROUPS 20240101").startsWith("501 "));
    }

    @Test
    public void testOver() {
        exchange("GROUP test.group");
        String r = exchange("OVER 1-2");
        assertTrue(r, r.startsWith("224 Overview information follows\r\n"));
        String first = "1\tFirst\ta@example.com\tMon, 1 Jan 2024 00:00:00 +0000\t<1@example.com>\t<0@example.com>\t"
                + ART1.length() + "\t\r\n";
        assertTrue(r, r.contains(first));
        assertTrue(r, r.contains("\r\n2\tSecond\tb@example.com\t\t<2@example.com>\t\t"));
        assertTrue(r, r.endsWith("\r\n.\r\n"));
        assertFalse(r, r.contains("\r\n3\t"));
    }

    @Test
    public void testOverVariants() {
        exchange("GROUP test.group");
        String current = exchange("OVER");
        assertTrue(current, current.contains("\r\n1\tFirst\t"));
        assertFalse(current, current.contains("\r\n2\t"));
        String open = exchange("XOVER 2-");
        assertTrue(open, open.contains("\r\n2\tSecond\t"));
        assertTrue(open, open.contains("\r\n4\tFourth\t"));
        String byId = exchange("OVER <4@example.com>");
        assertTrue(byId, byId.contains("\r\n0\tFourth\t"));
        assertEquals("423 No articles in that range\r\n", exchange("OVER 10-20"));
        assertEquals("430 No article with that message-id\r\n", exchange("OVER <no@example.com>"));
        assertTrue(exchange("OVER 5-1x").startsWith("501 "));
    }

    @Test
    public void testOverNeedsAGroup() {
        assertEquals("412 No newsgroup selected\r\n", exchange("OVER 1-2"));
        assertEquals("412 No newsgroup selected\r\n", exchange("OVER"));
    }

    @Test
    public void testHdr() {
        exchange("GROUP test.group");
        assertEquals("225 Headers follow\r\n1 First\r\n2 Second\r\n4 Fourth\r\n.\r\n",
                exchange("HDR Subject 1-"));
        assertEquals("225 Headers follow\r\n1 a@example.com\r\n2 b@example.com\r\n.\r\n",
                exchange("XHDR from 1-2"));
        assertEquals("225 Headers follow\r\n0 Fourth\r\n.\r\n", exchange("HDR Subject <4@example.com>"));
        assertTrue(exchange("HDR").startsWith("501 "));
    }

    @Test
    public void testHdrBytes() {
        exchange("GROUP test.group");
        assertEquals("225 Headers follow\r\n4 " + ART4.length() + "\r\n.\r\n", exchange("HDR :bytes 4"));
    }

    @Test
    public void testOneByteAtATime() {
        take();
        byte[] all = "GROUP test.group\r\nSTAT 2\r\nBODY\r\n".getBytes(StandardCharsets.ISO_8859_1);
        // the lexer leaves what it cannot yet use at the buffer's position,
        // and the caller compacts, as the transport does
        ByteBuffer buf = ByteBuffer.allocate(16);
        for (int i = 0; i < all.length; i++) {
            buf.put(all[i]);
            buf.flip();
            handler.receive(buf);
            buf.compact();
        }
        assertEquals("211 4 1 4 test.group\r\n223 2 <2@example.com>\r\n"
                + "222 2 <2@example.com> body follows\r\nbody2\r\n.\r\n", take());
    }

    @Test
    public void testPipelinedCommandsAnswerInOrder() {
        take();
        send("GROUP test.group\r\nSTAT 1\r\nSTAT 2\r\nLAST\r\nQUIT");
        assertEquals("211 4 1 4 test.group\r\n223 1 <1@example.com>\r\n223 2 <2@example.com>\r\n"
                + "223 1 <1@example.com>\r\n205 Closing connection\r\n", take());
    }

    @Test
    public void testStoreIsOpenedOnceForTheAnonymousUser() {
        exchange("GROUP test.group");
        exchange("LIST");
        assertEquals(1, factory.storesCreated);
        assertEquals("news", factory.lastStore.user);
    }

    @Test
    public void testDisconnectClosesStoreAndMailbox() {
        exchange("GROUP test.group");
        StubMailbox mb = factory.lastMailbox;
        handler.disconnected();
        assertTrue(mb.closed);
        assertTrue(factory.lastStore.closed);
    }

    @Test
    public void testNoFactory() {
        TestListener l = new TestListener();
        StubEndpoint e = new StubEndpoint();
        NntpProtocolHandler h = new NntpProtocolHandler(l);
        h.connected(e);
        e.sent.clear();
        h.receive(ByteBuffer.wrap("GROUP x\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(e.text(), e.text().startsWith("403 "));
    }

    // ── stubs ──

    static class TestListener extends NntpListener {
    }

    static class StubEndpoint implements Endpoint {
        final List<byte[]> sent = Collections.synchronizedList(new ArrayList<byte[]>());
        boolean open = true;
        boolean secure;

        synchronized String text() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < sent.size(); i++) {
                out.write(sent.get(i), 0, sent.get(i).length);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        @Override public synchronized void send(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            sent.add(b);
        }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 119); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 54321); }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() throws IOException { }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public TelemetryConfig getTelemetryConfig() {
            return org.bluezoo.gumdrop.testsupport.StubTelemetry.CONFIG;
        }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
    }

    static class StubFactory implements MailboxFactory {
        final Map<String, List<String>> groups = new TreeMap<String, List<String>>();
        final Map<String, List<Integer>> deleted = new TreeMap<String, List<Integer>>();
        int storesCreated;
        StubStore lastStore;
        StubMailbox lastMailbox;
        final List<StubMailbox> opened = new ArrayList<StubMailbox>();

        void add(String group, String... articles) {
            List<String> list = new ArrayList<String>();
            Collections.addAll(list, articles);
            groups.put(group, list);
            deleted.put(group, new ArrayList<Integer>());
        }

        void delete(String group, int number) {
            deleted.get(group).add(Integer.valueOf(number));
        }

        @Override
        public MailboxStore createStore() {
            storesCreated++;
            lastStore = new StubStore(this);
            return lastStore;
        }
    }

    static class StubStore implements MailboxStore {
        final StubFactory factory;
        String user;
        boolean closed;

        StubStore(StubFactory factory) {
            this.factory = factory;
        }

        @Override public void open(String username) throws IOException { user = username; }
        @Override public void close() throws IOException { closed = true; }
        @Override public char getHierarchyDelimiter() { return '.'; }
        @Override public List<String> listMailboxes(String reference, String pattern) throws IOException {
            List<String> r = new ArrayList<String>();
            for (String name : factory.groups.keySet()) {
                if (pattern.equals("*") || pattern.equals(name)) {
                    r.add(name);
                }
            }
            return r;
        }
        @Override public List<String> listSubscribed(String reference, String pattern) {
            return Collections.emptyList();
        }
        @Override public void subscribe(String mailboxName) { }
        @Override public void unsubscribe(String mailboxName) { }
        @Override public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
            List<String> articles = factory.groups.get(name);
            if (articles == null) {
                throw new IOException("Mailbox does not exist: " + name);
            }
            StubMailbox m = new StubMailbox(name, articles, factory.deleted.get(name));
            factory.lastMailbox = m;
            factory.opened.add(m);
            return m;
        }
        @Override public void createMailbox(String mailboxName) { }
        @Override public void deleteMailbox(String mailboxName) { }
        @Override public void renameMailbox(String oldName, String newName) { }
        @Override public java.util.Set<org.bluezoo.gumdrop.mailbox.MailboxAttribute>
                getMailboxAttributes(String mailboxName) {
            return Collections.emptySet();
        }
    }

    static class StubMailbox implements Mailbox {
        final String name;
        final List<String> articles;
        final List<Integer> deleted;
        boolean closed;

        StubMailbox(String name, List<String> articles, List<Integer> deleted) {
            this.name = name;
            this.articles = articles;
            this.deleted = deleted;
        }

        List<String> articlesList() {
            return articles;
        }

        List<Integer> deletedList() {
            return deleted;
        }

        @Override public void close(boolean expunge) throws IOException { closed = true; }
        @Override public String getName() { return name; }
        @Override public int getMessageCount() throws IOException { return articles.size(); }
        @Override public long getMailboxSize() { return 0; }
        @Override public Iterator<MessageDescriptor> getMessageList() { return Collections.<MessageDescriptor>emptyList().iterator(); }
        @Override public MessageDescriptor getMessage(final int n) throws IOException {
            return new MessageDescriptor() {
                @Override public int getMessageNumber() { return n; }
                @Override public long getSize() { return articles.get(n - 1).length(); }
                @Override public String getUniqueId() { return Integer.toString(n); }
            };
        }
        @Override public ReadableByteChannel getMessageContent(int n) throws IOException {
            return Channels.newChannel(new java.io.ByteArrayInputStream(
                    articles.get(n - 1).getBytes(StandardCharsets.ISO_8859_1)));
        }
        @Override public ReadableByteChannel getMessageTop(int n, int lines) throws IOException {
            return getMessageContent(n);
        }
        @Override public void deleteMessage(int n) { deleted.add(Integer.valueOf(n)); }
        @Override public boolean isDeleted(int n) throws IOException { return deleted.contains(Integer.valueOf(n)); }
        @Override public void undeleteAll() { deleted.clear(); }
        @Override public String getUniqueId(int n) throws IOException { return Integer.toString(n); }

        /** Whether openAsyncAppend works; false makes it return null, as mbox does. */
        boolean asyncAppend = true;
        /** Holds each write's completion until completeWrites() is called. */
        boolean deferWrites;
        boolean failWrites;
        boolean failFinish;
        final List<StubWriter> writers = new ArrayList<StubWriter>();

        void completeWrites() {
            for (StubWriter w : writers) {
                w.completePending();
            }
        }

        @Override public org.bluezoo.gumdrop.mailbox.AsyncMessageWriter openAsyncAppend(
                java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> flags, java.time.OffsetDateTime date)
                throws IOException {
            if (!asyncAppend) {
                return null;
            }
            StubWriter w = new StubWriter();
            w.failWrites = failWrites;
            w.failFinish = failFinish;
            writers.add(w);
            return w;
        }

        /** An asynchronous writer that keeps what it is given. */
        final class StubWriter implements org.bluezoo.gumdrop.mailbox.AsyncMessageWriter {
            final ByteArrayOutputStream data = new ByteArrayOutputStream();
            final List<Integer> chunkSizes = new ArrayList<Integer>();
            final List<Runnable> pending = new ArrayList<Runnable>();
            boolean finished;
            boolean aborted;
            boolean failWrites;
            boolean failFinish;

            @Override public void write(final ByteBuffer src,
                    final java.nio.channels.CompletionHandler<Integer, ByteBuffer> handler) {
                final int n = src.remaining();
                byte[] b = new byte[n];
                src.get(b);
                Runnable done = new Runnable() {
                    @Override public void run() {
                        if (failWrites) {
                            handler.failed(new IOException("disk full"), src);
                        } else {
                            data.write(b, 0, n);
                            chunkSizes.add(Integer.valueOf(n));
                            handler.completed(Integer.valueOf(n), src);
                        }
                    }
                };
                if (deferWrites) {
                    pending.add(done);
                } else {
                    done.run();
                }
            }

            void completePending() {
                List<Runnable> now = new ArrayList<Runnable>(pending);
                pending.clear();
                for (Runnable r : now) {
                    r.run();
                }
            }

            @Override public boolean wantsPause() { return false; }

            @Override public void finish(java.nio.channels.CompletionHandler<Long, Void> handler) {
                if (failFinish) {
                    handler.failed(new IOException("cannot move into place"), null);
                    return;
                }
                finished = true;
                articles.add(new String(data.toByteArray(), StandardCharsets.ISO_8859_1));
                handler.completed(Long.valueOf(articles.size()), null);
            }

            @Override public void abort() { aborted = true; }

            @Override public void close() { }
        }

        private ByteArrayOutputStream appending;
        @Override public void startAppendMessage(java.util.Set<org.bluezoo.gumdrop.mailbox.Flag> flags,
                java.time.OffsetDateTime date) throws IOException {
            appending = new ByteArrayOutputStream();
        }
        @Override public void appendMessageContent(ByteBuffer data) throws IOException {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            appending.write(b, 0, b.length);
        }
        @Override public long endAppendMessage() throws IOException {
            articles.add(new String(appending.toByteArray(), StandardCharsets.ISO_8859_1));
            appending = null;
            return articles.size();
        }
    }

}
