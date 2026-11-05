/*
 * NntpClientTest.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.nntp.client.NntpClient.StartTls;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The NNTP client, driven by scripted server output through a stub endpoint.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpClientTest {

    /** Records what the handler is told. */
    private static final class Events implements NntpClientHandler {
        NntpSession session;
        NntpGreeting greeting;
        Exception error;
        boolean disconnected;

        @Override
        public void connected(NntpSession s, NntpGreeting g) {
            session = s;
            greeting = g;
        }

        @Override
        public void error(Exception cause) {
            error = cause;
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }
    }

    /** Keeps the outcome of a command. */
    private static final class Outcome<T> implements NntpCallback<T> {
        T result;
        NntpException error;
        boolean done;

        @Override
        public void completed(T r) {
            result = r;
            done = true;
        }

        @Override
        public void failed(NntpException e) {
            error = e;
            done = true;
        }
    }

    private static final class Items<T> implements NntpConsumer<T> {
        final List<T> items = new ArrayList<T>();

        @Override
        public void accept(T item) {
            items.add(item);
        }
    }

    private static final class Text implements NntpConsumer<ByteBuffer> {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void accept(ByteBuffer b) {
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            out.write(bytes, 0, bytes.length);
        }

        String text() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private RecordingStubEndpoint endpoint;
    private Events events;
    private NntpClientProtocolHandler handler;

    @Before
    public void setUp() {
        start(null, null, StartTls.NEVER);
    }

    private void start(String user, String password, StartTls tls) {
        start(user, password, tls, null);
    }

    private void start(String user, String password, StartTls tls, String sasl) {
        endpoint = new RecordingStubEndpoint();
        events = new Events();
        handler = new NntpClientProtocolHandler(events, user, password, tls, 0, sasl, "news.example.com");
        handler.connected(endpoint);
    }

    private void server(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void serverByByte(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(b.length + 1);
        for (int i = 0; i < b.length; i++) {
            buf.put(b[i]);
            buf.flip();
            handler.receive(buf);
            buf.compact();
        }
    }

    private List<String> sent() {
        List<String> r = endpoint.getResponses();
        endpoint.clearResponses();
        return r;
    }

    private void ready() {
        server("200 hello\r\n");
        sent();
        server("101 caps\r\nVERSION 2\r\nreader\r\nOVER\r\n.\r\n");
        assertNotNull(events.session);
    }

    // ── connecting ──

    @Test
    public void testGreetingAndCapabilities() {
        server("200 Welcome\r\n");
        assertEquals(java.util.Arrays.asList("CAPABILITIES"), sent());
        assertNull(events.session);
        server("101 Capability list:\r\nVERSION 2\r\nreader\r\nOVER\r\n.\r\n");
        assertNotNull(events.session);
        assertTrue(events.greeting.isPostingAllowed());
        assertEquals(java.util.Arrays.asList("VERSION 2", "READER", "OVER"),
                events.greeting.getCapabilities());
        assertFalse(events.greeting.isAuthenticated());
        assertEquals("Welcome", events.greeting.getStatus().getText());
    }

    @Test
    public void testNoPostingWhenGreetedWith201() {
        server("201 read only\r\n");
        server("101 caps\r\n.\r\n");
        assertFalse(events.greeting.isPostingAllowed());
        assertFalse(events.session.isPostingAllowed());
    }

    @Test
    public void testServerWithoutCapabilitiesStillConnects() {
        server("200 hi\r\n");
        server("500 what?\r\n");
        assertNotNull(events.session);
        assertTrue(events.greeting.getCapabilities().isEmpty());
    }

    @Test
    public void testRefusedGreeting() {
        server("502 go away\r\n");
        assertNotNull(events.error);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testHandshakeOneByteAtATime() {
        serverByByte("200 hi\r\n101 caps\r\nVERSION 2\r\n.\r\n");
        assertNotNull(events.session);
    }

    @Test
    public void testLogin() {
        start("alice", "secret", StartTls.NEVER);
        server("200 hi\r\n");
        sent();
        server("101 caps\r\nAUTHINFO USER\r\n.\r\n");
        assertEquals(java.util.Arrays.asList("AUTHINFO USER alice"), sent());
        server("381 password\r\n");
        assertEquals(java.util.Arrays.asList("AUTHINFO PASS secret"), sent());
        assertNull(events.session);
        server("281 ok\r\n");
        assertNotNull(events.session);
        assertTrue(events.greeting.isAuthenticated());
    }

    @Test
    public void testRejectedLogin() {
        start("alice", "wrong", StartTls.NEVER);
        server("200 hi\r\n101 caps\r\n.\r\n381 pw\r\n481 no\r\n");
        assertNull(events.session);
        assertNotNull(events.error);
        assertEquals(481, ((NntpException) events.error).getCode());
    }

    @Test
    public void testStartTlsRequired() {
        start(null, null, StartTls.REQUIRED);
        server("200 hi\r\n");
        sent();
        server("101 caps\r\nSTARTTLS\r\n.\r\n");
        assertEquals(java.util.Arrays.asList("STARTTLS"), sent());
        server("382 go\r\n");
        assertNull(events.session);
        handler.securityEstablished(null);
        assertEquals(java.util.Arrays.asList("CAPABILITIES"), sent());
        server("101 caps\r\nREADER\r\n.\r\n");
        assertNotNull(events.session);
        assertEquals(java.util.Arrays.asList("READER"), events.greeting.getCapabilities());
    }

    @Test
    public void testStartTlsRequiredButNotOffered() {
        start(null, null, StartTls.REQUIRED);
        server("200 hi\r\n101 caps\r\nREADER\r\n.\r\n");
        assertNull(events.session);
        assertNotNull(events.error);
    }

    @Test
    public void testStartTlsIfOfferedCarriesOnInTheClear() {
        start(null, null, StartTls.IF_OFFERED);
        server("200 hi\r\n101 caps\r\nREADER\r\n.\r\n");
        assertNotNull(events.session);
    }

    @Test
    public void testStartTlsRefusedWhenRequired() {
        start(null, null, StartTls.REQUIRED);
        server("200 hi\r\n101 caps\r\nSTARTTLS\r\n.\r\n580 no\r\n");
        assertNotNull(events.error);
    }

    // ── AUTHINFO SASL ──

    @Test
    public void testSaslPlain() {
        start("alice", "secret", StartTls.NEVER, "PLAIN");
        server("200 hi\r\n");
        sent();
        server("101 caps\r\nAUTHINFO USER SASL\r\nSASL PLAIN SCRAM-SHA-256\r\n.\r\n");
        String expected = "AUTHINFO SASL PLAIN " + java.util.Base64.getEncoder()
                .encodeToString("\0alice\0secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.asList(expected), sent());
        server("281 ok\r\n");
        assertNotNull(events.session);
        assertTrue(events.greeting.isAuthenticated());
    }

    @Test
    public void testSaslMechanismNotOffered() {
        start("alice", "secret", StartTls.NEVER, "PLAIN");
        server("200 hi\r\n101 caps\r\nSASL SCRAM-SHA-256\r\n.\r\n");
        assertNull(events.session);
        assertNotNull(events.error);
    }

    @Test
    public void testSaslUnknownMechanism() {
        start("alice", "secret", StartTls.NEVER, "FROBNICATE");
        server("200 hi\r\n101 caps\r\n.\r\n");
        assertNotNull(events.error);
    }

    @Test
    public void testSaslRejected() {
        start("alice", "wrong", StartTls.NEVER, "PLAIN");
        server("200 hi\r\n101 caps\r\nSASL PLAIN\r\n.\r\n481 no\r\n");
        assertNull(events.session);
        assertEquals(481, ((NntpException) events.error).getCode());
    }

    @Test
    public void testSaslChallengeIsAnswered() {
        // OAUTHBEARER answers a failure challenge with a single 0x01 (RFC 7628)
        start("alice", "token", StartTls.NEVER, "OAUTHBEARER");
        server("200 hi\r\n");
        sent();
        server("101 caps\r\nSASL OAUTHBEARER\r\n.\r\n");
        assertTrue(sent().get(0).startsWith("AUTHINFO SASL OAUTHBEARER "));
        server("383 " + java.util.Base64.getEncoder().encodeToString("{\"status\":\"invalid_token\"}"
                .getBytes(StandardCharsets.UTF_8)) + "\r\n");
        assertEquals(java.util.Arrays.asList("AQ=="), sent());
        server("481 no\r\n");
        assertNotNull(events.error);
    }

    // ── commands ──

    @Test
    public void testGroup() {
        ready();
        sent();
        Outcome<GroupResult> out = new Outcome<GroupResult>();
        events.session.group("misc.test", out);
        assertEquals(java.util.Arrays.asList("GROUP misc.test"), sent());
        server("211 3 1 5 misc.test\r\n");
        assertEquals(3, out.result.getCount());
        assertEquals(1, out.result.getFirst());
        assertEquals(5, out.result.getLast());
        assertEquals("misc.test", out.result.getName());
    }

    @Test
    public void testGroupRefused() {
        ready();
        Outcome<GroupResult> out = new Outcome<GroupResult>();
        events.session.group("no.such", out);
        server("411 no such group\r\n");
        assertNull(out.result);
        assertEquals(411, out.error.getCode());
    }

    @Test
    public void testStat() {
        ready();
        Outcome<ArticleRef> out = new Outcome<ArticleRef>();
        events.session.stat(7, out);
        server("223 7 <a@b> exists\r\n");
        assertEquals(7, out.result.getNumber());
        assertEquals("<a@b>", out.result.getMessageId());
    }

    @Test
    public void testListActiveDeliversEntriesAsTheyArrive() {
        ready();
        sent();
        Items<NewsgroupEntry> items = new Items<NewsgroupEntry>();
        Outcome<Void> out = new Outcome<Void>();
        events.session.listActive("comp.*", items, out);
        assertEquals(java.util.Arrays.asList("LIST ACTIVE comp.*"), sent());
        serverByByte("215 list follows\r\ncomp.a 10 1 y\r\n");
        // the unstuffer holds a line end until it has seen the next byte
        // (a dot there would be a stuffed dot or the terminator), so an
        // entry is delivered once the following line has begun
        assertEquals(0, items.items.size());
        serverByByte("c");
        assertEquals(1, items.items.size());
        assertFalse(out.done);
        serverByByte("omp.b 5 2 m\r\n.\r\n");
        assertTrue(out.done);
        assertEquals(2, items.items.size());
        assertEquals("comp.b", items.items.get(1).getName());
        assertEquals(5, items.items.get(1).getHigh());
        assertEquals(2, items.items.get(1).getLow());
        assertEquals('m', items.items.get(1).getStatus());
    }

    @Test
    public void testOver() {
        ready();
        Items<OverviewEntry> items = new Items<OverviewEntry>();
        Outcome<Void> out = new Outcome<Void>();
        events.session.over(1, 2, items, out);
        server("224 overview\r\n1\tHi\ta@x\tMon, 1 Jan 2024\t<1@x>\t<0@x>\t120\t4\r\n"
                + "2\tRe: Hi\tb@x\tTue\t<2@x>\t\t90\t\r\n.\r\n");
        assertTrue(out.done);
        assertEquals(2, items.items.size());
        OverviewEntry e = items.items.get(0);
        assertEquals(1, e.getNumber());
        assertEquals("Hi", e.getSubject());
        assertEquals("a@x", e.getFrom());
        assertEquals("<1@x>", e.getMessageId());
        assertEquals("<0@x>", e.getReferences());
        assertEquals(120, e.getBytes());
        assertEquals(4, e.getLines());
        assertEquals("", items.items.get(1).getReferences());
        assertEquals(0, items.items.get(1).getLines());
    }

    @Test
    public void testArticleIsDotUnstuffedAndStreamed() {
        ready();
        sent();
        Text text = new Text();
        Outcome<Void> out = new Outcome<Void>();
        events.session.article(1, text, out);
        assertEquals(java.util.Arrays.asList("ARTICLE 1"), sent());
        serverByByte("220 1 <a@b> article\r\nSubject: x\r\n\r\n..dot\r\nbody\r\n.\r\n");
        assertTrue(out.done);
        assertNull(out.error);
        assertEquals("Subject: x\r\n\r\n.dot\r\nbody\r\n", text.text());
    }

    @Test
    public void testHeadAndBodyCodes() {
        ready();
        Text head = new Text();
        Outcome<Void> h = new Outcome<Void>();
        events.session.head(1, head, h);
        server("221 1 <a@b> head\r\nSubject: x\r\n.\r\n");
        assertEquals("Subject: x\r\n", head.text());
        assertTrue(h.done && h.error == null);
        Text body = new Text();
        Outcome<Void> b = new Outcome<Void>();
        events.session.body(1, body, b);
        server("222 1 <a@b> body\r\nhello\r\n.\r\n");
        assertEquals("hello\r\n", body.text());
        assertTrue(b.done && b.error == null);
    }

    @Test
    public void testArticleNotFound() {
        ready();
        Text text = new Text();
        Outcome<Void> out = new Outcome<Void>();
        events.session.article(99, text, out);
        server("423 no such article\r\n");
        assertEquals(423, out.error.getCode());
        assertEquals("", text.text());
    }

    @Test
    public void testCommandsRunOneAtATimeInOrder() {
        ready();
        sent();
        Outcome<GroupResult> g = new Outcome<GroupResult>();
        Outcome<ArticleRef> s = new Outcome<ArticleRef>();
        events.session.group("a.b", g);
        events.session.stat(1, s);
        assertEquals(java.util.Arrays.asList("GROUP a.b"), sent());
        server("211 1 1 1 a.b\r\n");
        assertEquals(java.util.Arrays.asList("STAT 1"), sent());
        server("223 1 <x@y>\r\n");
        assertTrue(g.done && s.done);
    }

    @Test
    public void testBlockAndNextReplyInOneRead() {
        ready();
        Text text = new Text();
        Outcome<Void> a = new Outcome<Void>();
        Outcome<ArticleRef> s = new Outcome<ArticleRef>();
        events.session.article(1, text, a);
        events.session.stat(2, s);
        server("220 1 <a@b> article\r\nline\r\n.\r\n");
        server("223 2 <c@d>\r\n");
        assertTrue(a.done);
        assertEquals(2, s.result.getNumber());
    }

    @Test
    public void testRawCommand() {
        ready();
        sent();
        Text text = new Text();
        Outcome<NntpStatus> out = new Outcome<NntpStatus>();
        events.session.command("LIST NEWSGROUPS", true, text, out);
        server("215 info\r\na.b A group\r\n.\r\n");
        assertEquals(215, out.result.getCode());
        assertEquals("a.b A group\r\n", text.text());
        Outcome<NntpStatus> date = new Outcome<NntpStatus>();
        events.session.command("DATE", false, null, date);
        server("111 20240101000000\r\n");
        assertEquals("20240101000000", date.result.getText());
        Outcome<NntpStatus> bad = new Outcome<NntpStatus>();
        events.session.command("FROB", false, null, bad);
        server("500 what\r\n");
        assertEquals(500, bad.result.getCode());
        assertNull(bad.error);
    }

    @Test
    public void testPost() {
        ready();
        sent();
        Outcome<Void> out = new Outcome<Void>();
        events.session.post("From: a@x\nSubject: s\n\n.dot\nend".getBytes(StandardCharsets.UTF_8), out);
        assertEquals(java.util.Arrays.asList("POST"), sent());
        server("340 send it\r\n");
        assertEquals(java.util.Arrays.asList("From: a@x", "Subject: s", "..dot", "end", "."), sent());
        assertFalse(out.done);
        server("240 received\r\n");
        assertTrue(out.done);
        assertNull(out.error);
    }

    @Test
    public void testPostRefused() {
        ready();
        Outcome<Void> out = new Outcome<Void>();
        events.session.post("x".getBytes(StandardCharsets.UTF_8), out);
        server("440 no posting\r\n");
        assertEquals(440, out.error.getCode());
    }

    @Test
    public void testDotStuff() {
        assertEquals("a\r\n..b\r\n.\r\n", new String(
                NntpSession.dotStuff("a\n.b".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        assertEquals("a\r\n\r\nb\r\n.\r\n", new String(
                NntpSession.dotStuff("a\r\rb\r\n".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        assertEquals(".\r\n", new String(NntpSession.dotStuff(new byte[0]), StandardCharsets.UTF_8));
    }

    @Test
    public void testQuit() {
        ready();
        sent();
        events.session.quit();
        assertEquals(java.util.Arrays.asList("QUIT"), sent());
        assertFalse(events.session.isAlive());
        Outcome<GroupResult> late = new Outcome<GroupResult>();
        events.session.group("a.b", late);
        assertNotNull(late.error);
        server("205 bye\r\n");
        assertFalse(endpoint.isOpen());
        assertFalse(events.disconnected);
        handler.disconnected();
        assertTrue(events.disconnected);
        assertNull(events.error);
    }

    @Test
    public void testDisconnectFailsCommandsInFlightAndQueued() {
        ready();
        Outcome<GroupResult> a = new Outcome<GroupResult>();
        Outcome<ArticleRef> b = new Outcome<ArticleRef>();
        events.session.group("a.b", a);
        events.session.stat(1, b);
        handler.disconnected();
        assertEquals(0, a.error.getCode());
        assertNotNull(b.error);
        assertTrue(events.disconnected);
    }

    @Test
    public void testDisconnectDuringSetupIsAnError() {
        server("200 hi\r\n");
        handler.disconnected();
        assertNotNull(events.error);
    }

    @Test
    public void testMalformedStatusLineFailsTheConnection() {
        ready();
        Outcome<GroupResult> a = new Outcome<GroupResult>();
        events.session.group("a.b", a);
        server("what is this\r\n");
        assertNotNull(a.error);
        assertNotNull(events.error);
    }

    @Test
    public void testLineBreakInCommandIsRefused() {
        ready();
        try {
            events.session.group("a\r\nQUIT", new Outcome<GroupResult>());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }
}
