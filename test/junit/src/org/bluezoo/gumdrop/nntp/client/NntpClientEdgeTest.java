/*
 * NntpClientEdgeTest.java
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.nntp.client.NntpClient.StartTls;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.StubTelemetry;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.bluezoo.gumdrop.tls.TlsConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Corners of the NNTP client: configuration, failures of the connection and
 * of the application's own callbacks, odd input from the server, and
 * timeouts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpClientEdgeTest {

    /** An endpoint that records what is sent, with timers the test fires. */
    private static final class Wire implements Endpoint {
        final List<String> lines = new ArrayList<String>();
        final List<Runnable> timers = new ArrayList<Runnable>();
        final List<Boolean> cancelled = new ArrayList<Boolean>();
        boolean open = true;
        boolean secure;
        boolean tlsFails;

        @Override public void send(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            for (String line : new String(b, StandardCharsets.UTF_8).split("\r\n")) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 119); }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() throws IOException {
            if (tlsFails) {
                throw new IOException("no handshake");
            }
        }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            timers.add(cb);
            final int index = cancelled.size();
            cancelled.add(Boolean.FALSE);
            return new TimerHandle() {
                @Override public void cancel() { cancelled.set(index, Boolean.TRUE); }
                @Override public boolean isCancelled() { return cancelled.get(index).booleanValue(); }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public TelemetryConfig getTelemetryConfig() { return StubTelemetry.CONFIG; }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }

        String last() {
            return lines.isEmpty() ? null : lines.get(lines.size() - 1);
        }
    }

    private static final class Events implements NntpClientHandler {
        NntpSession session;
        NntpGreeting greeting;
        Exception error;
        boolean disconnected;
        boolean throwOnConnected;
        boolean throwOnError;

        @Override
        public void connected(NntpSession s, NntpGreeting g) {
            session = s;
            greeting = g;
            if (throwOnConnected) {
                throw new IllegalStateException("application bug");
            }
        }

        @Override
        public void error(Exception cause) {
            error = cause;
            if (throwOnError) {
                throw new IllegalStateException("application bug");
            }
        }

        @Override
        public void disconnected() {
            disconnected = true;
        }
    }

    private static final class Outcome<T> implements NntpCallback<T> {
        T result;
        NntpException error;
        boolean done;
        boolean throwing;

        @Override
        public void completed(T r) {
            result = r;
            done = true;
            if (throwing) {
                throw new IllegalStateException("application bug");
            }
        }

        @Override
        public void failed(NntpException e) {
            error = e;
            done = true;
            if (throwing) {
                throw new IllegalStateException("application bug");
            }
        }
    }

    private Wire wire;
    private Events events;
    private NntpClientProtocolHandler handler;

    @Before
    public void setUp() {
        start(null, null, StartTls.NEVER, null, 0);
    }

    private void start(String user, String password, StartTls tls, String sasl, long timeoutMs) {
        wire = new Wire();
        events = new Events();
        handler = new NntpClientProtocolHandler(events, user, password, tls, timeoutMs, sasl, "news.example.com");
        handler.connected(wire);
    }

    private void server(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void ready() {
        server("200 hello\r\n101 caps\r\nREADER\r\n.\r\n");
        assertNotNull(events.session);
        wire.lines.clear();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ── the facade ──

    @Test
    public void testFacadeSettersAndConstructors() {
        Gumdrop gumdrop = TestGumdrop.create();
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        NntpClient c = new NntpClient();
        assertSame(c, c.secure(true));
        assertSame(c, c.tls(new TlsConfig()));
        assertSame(c, c.startTls(StartTls.REQUIRED));
        assertSame(c, c.credentials("u", "p"));
        assertSame(c, c.saslMechanism("PLAIN"));
        assertSame(c, c.commandTimeoutMs(5000));
        assertSame(c, c.host("news.example.com"));
        assertSame(c, c.port(563));
        assertSame(c, c.selectorLoop(loop));
        assertSame(c, c.dnsResolver(null));
        assertFalse(c.isOpen());
        c.close();
        assertFalse(new NntpClient("news.example.com", 119).isOpen());
        assertFalse(new NntpClient(InetAddress.getLoopbackAddress(), 119).isOpen());
    }

    @Test
    public void testConnectNeedsATargetAndAPasswordForTheUser() {
        Gumdrop gumdrop = TestGumdrop.create();
        try {
            new NntpClient().connect(gumdrop, new Events());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // good
        }
        try {
            new NntpClient("news.example.com", 119).credentials("alice", null).connect(gumdrop, new Events());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("password"));
        }
    }

    @Test(expected = NullPointerException.class)
    public void testHandlerIsRequired() {
        new NntpClientProtocolHandler(null, null, null, StartTls.NEVER, 0, null, "h");
    }

    // ── the connection ──

    @Test
    public void testTransportErrorFailsTheConnection() {
        ready();
        Outcome<GroupResult> out = new Outcome<GroupResult>();
        events.session.group("a.b", out);
        handler.error(new IOException("reset"));
        assertNotNull(events.error);
        assertNotNull(out.error);
        assertFalse(wire.open);
        assertFalse(events.session.isAlive());
        assertFalse(handler.isOpen());
    }

    @Test
    public void testCloseAndOpenState() {
        assertTrue(handler.isOpen());
        handler.close();
        assertFalse(wire.open);
        assertFalse(handler.isOpen());
    }

    @Test
    public void testUnusedCallbacksAreHarmless() {
        handler.rawBytes(ByteBuffer.wrap(new byte[] {1}));
        handler.tokenTooLong();
    }

    @Test
    public void testResponseCodeMustBeThreeDigits() {
        server("2x0 what\r\n");
        assertNotNull(events.error);
        assertFalse(wire.open);
    }

    @Test
    public void testBareStatusCodeAndStrayReturns() {
        server("200\r\n");
        assertEquals("CAPABILITIES", wire.last());
        server("101 a\rb\r\nREADER\r\n.\r\n");
        assertNotNull(events.session);
    }

    @Test
    public void testReplyWithNothingWaitingIsIgnored() {
        ready();
        server("200 I have nothing to say\r\n");
        assertTrue(wire.open);
        Outcome<GroupResult> out = new Outcome<GroupResult>();
        events.session.group("a.b", out);
        server("211 1 1 1 a.b\r\n");
        assertEquals("a.b", out.result.getName());
    }

    // ── STARTTLS and login ──

    @Test
    public void testStartTlsThatCannotBeStarted() {
        start(null, null, StartTls.REQUIRED, null, 0);
        wire.tlsFails = true;
        server("200 hi\r\n101 caps\r\nSTARTTLS\r\n.\r\n382 go\r\n");
        assertNotNull(events.error);
        assertNull(events.session);
    }

    @Test
    public void testStartTlsRefusedCarriesOnWhenOnlyIfOffered() {
        start(null, null, StartTls.IF_OFFERED, null, 0);
        server("200 hi\r\n101 caps\r\nSTARTTLS\r\n.\r\n580 no\r\n");
        assertNotNull(events.session);
        assertNull(events.error);
    }

    @Test
    public void testLoginAcceptedAtOnceByUser() {
        start("alice", "secret", StartTls.NEVER, null, 0);
        server("200 hi\r\n101 caps\r\n.\r\n281 welcome\r\n");
        assertTrue(events.greeting.isAuthenticated());
    }

    @Test
    public void testUserRefused() {
        start("alice", "secret", StartTls.NEVER, null, 0);
        server("200 hi\r\n101 caps\r\n.\r\n481 no\r\n");
        assertNull(events.session);
        assertEquals(481, ((NntpException) events.error).getCode());
    }

    @Test
    public void testSaslSuccessWithFinalData() {
        start("alice", "secret", StartTls.NEVER, "PLAIN", 0);
        server("200 hi\r\n101 caps\r\nSASL PLAIN\r\n.\r\n283 " + b64("extra") + "\r\n");
        assertTrue(events.greeting.isAuthenticated());
    }

    @Test
    public void testSaslDataThatIsNotBase64() {
        start("alice", "token", StartTls.NEVER, "OAUTHBEARER", 0);
        server("200 hi\r\n101 caps\r\nSASL OAUTHBEARER\r\n.\r\n383 !!!\r\n");
        assertNotNull(events.error);
        assertTrue(events.error.getMessage(), events.error.getMessage().contains("malformed"));
    }

    @Test
    public void testSaslFailureOfTheMechanism() {
        start("alice", "secret", StartTls.NEVER, "SCRAM-SHA-256", 0);
        server("200 hi\r\n101 caps\r\nSASL SCRAM-SHA-256\r\n.\r\n");
        // a server-first message that does not extend our nonce
        server("383 " + b64("r=other,s=" + b64("salt") + ",i=4096") + "\r\n");
        assertNotNull(events.error);
        assertNull(events.session);
    }

    @Test
    public void testSaslScramThroughTheClient() throws Exception {
        start("alice", "secret", StartTls.NEVER, "SCRAM-SHA-256", 0);
        server("200 hi\r\n");
        wire.lines.clear();
        server("101 caps\r\nSASL SCRAM-SHA-256\r\n.\r\n");
        String line = wire.last();
        assertTrue(line, line.startsWith("AUTHINFO SASL SCRAM-SHA-256 "));
        String clientFirst = new String(Base64.getDecoder().decode(line.substring(line.lastIndexOf(' ') + 1)),
                StandardCharsets.UTF_8);
        String bare = clientFirst.substring(3);
        String clientNonce = bare.substring(bare.indexOf("r=") + 2);
        Realm.ScramCredentials creds = Realm.ScramCredentials.derive("secret",
                "0123456789abcdef".getBytes(StandardCharsets.UTF_8), 4096, "SHA-256");
        String serverNonce = clientNonce + "serverpart";
        String serverFirst = SaslUtils.generateScramServerFirst(serverNonce, creds.salt, creds.iterations);
        server("383 " + b64(serverFirst) + "\r\n");
        String finalLine = wire.last();
        String clientFinal = new String(Base64.getDecoder().decode(finalLine), StandardCharsets.UTF_8);
        byte[] signature = SaslUtils.verifyScramClientFinal(creds, bare + "," + serverFirst, clientFinal, serverNonce);
        assertNotNull("the server rejects the client's proof", signature);
        server("283 " + b64("v=" + Base64.getEncoder().encodeToString(signature)) + "\r\n");
        assertTrue(events.greeting.isAuthenticated());
    }

    // ── the application's own callbacks fail ──

    @Test
    public void testApplicationThatThrowsOnConnectedIsSurvived() {
        events.throwOnConnected = true;
        server("200 hi\r\n101 caps\r\n.\r\n");
        assertTrue(wire.open);
        Outcome<ArticleRef> out = new Outcome<ArticleRef>();
        events.session.stat(1, out);
        server("223 1 <a@b>\r\n");
        assertEquals(1, out.result.getNumber());
    }

    @Test
    public void testApplicationThatThrowsInCompletionIsSurvived() {
        ready();
        Outcome<GroupResult> first = new Outcome<GroupResult>();
        first.throwing = true;
        Outcome<ArticleRef> second = new Outcome<ArticleRef>();
        events.session.group("a.b", first);
        events.session.stat(1, second);
        server("211 1 1 1 a.b\r\n223 1 <a@b>\r\n");
        assertTrue(first.done);
        assertTrue(second.done);
    }

    @Test
    public void testApplicationThatThrowsInFailureIsSurvived() {
        ready();
        Outcome<GroupResult> out = new Outcome<GroupResult>();
        out.throwing = true;
        events.session.group("a.b", out);
        handler.disconnected();
        assertNotNull(out.error);
        Outcome<GroupResult> late = new Outcome<GroupResult>();
        late.throwing = true;
        events.session.group("a.b", late);
        assertNotNull(late.error);
    }

    @Test
    public void testApplicationThatThrowsInAConsumerIsSurvived() {
        ready();
        Outcome<Void> done = new Outcome<Void>();
        events.session.article(1, new NntpConsumer<ByteBuffer>() {
            @Override
            public void accept(ByteBuffer b) {
                throw new IllegalStateException("application bug");
            }
        }, done);
        server("220 1 <a@b> article\r\nline\r\n.\r\n");
        assertTrue(done.done);
        Outcome<Void> list = new Outcome<Void>();
        events.session.listActive(null, new NntpConsumer<NewsgroupEntry>() {
            @Override
            public void accept(NewsgroupEntry e) {
                throw new IllegalStateException("application bug");
            }
        }, list);
        server("215 list\r\na.b 2 1 y\r\n.\r\n");
        assertTrue(list.done);
    }

    @Test
    public void testApplicationThatThrowsInQuitIsSurvived() {
        ready();
        events.session.quit();
        server("205 bye\r\n");
        assertFalse(wire.open);
    }

    // ── what is read ──

    @Test
    public void testLongAndOverlongListLines() {
        ready();
        final List<NewsgroupEntry> got = new ArrayList<NewsgroupEntry>();
        Outcome<Void> out = new Outcome<Void>();
        events.session.listActive("*", new NntpConsumer<NewsgroupEntry>() {
            @Override
            public void accept(NewsgroupEntry e) {
                got.add(e);
            }
        }, out);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            name.append('g');
        }
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 70000; i++) {
            huge.append('h');
        }
        server("215 list\r\n" + name + " 2 1 y\r\n" + huge + " 2 1 y\r\nlast.group 3 1 n\r\n.\r\n");
        assertTrue(out.done);
        assertEquals(2, got.size());
        assertEquals(300, got.get(0).getName().length());
        assertEquals("last.group", got.get(1).getName());
    }

    @Test
    public void testMalformedEntriesAreSkipped() {
        ready();
        final List<OverviewEntry> got = new ArrayList<OverviewEntry>();
        Outcome<Void> out = new Outcome<Void>();
        events.session.over(1, 3, new NntpConsumer<OverviewEntry>() {
            @Override
            public void accept(OverviewEntry e) {
                got.add(e);
            }
        }, out);
        server("224 overview\r\nnonsense\r\nx\tno number\r\n3\ts\tf\td\t<3@x>\t\tbad\tbad\r\n.\r\n");
        assertTrue(out.done);
        assertEquals(1, got.size());
        assertEquals(0, got.get(0).getBytes());
        assertEquals("d", got.get(0).getDate());
    }

    @Test
    public void testMalformedGroupAndStatReplies() {
        ready();
        Outcome<GroupResult> g = new Outcome<GroupResult>();
        events.session.group("a.b", g);
        server("211 garbage\r\n");
        assertTrue(g.error.getMessage(), g.error.getMessage().contains("malformed"));
        Outcome<GroupResult> g2 = new Outcome<GroupResult>();
        events.session.group("a.b", g2);
        server("211 x y z a.b\r\n");
        assertNotNull(g2.error);
        Outcome<ArticleRef> s = new Outcome<ArticleRef>();
        events.session.stat(1, s);
        server("223 notanumber <a@b>\r\n");
        assertNotNull(s.error);
        Outcome<ArticleRef> s2 = new Outcome<ArticleRef>();
        events.session.stat(1, s2);
        server("223 1\r\n");
        assertNotNull(s2.error);
    }

    @Test
    public void testStatAndArticleByMessageId() {
        ready();
        Outcome<ArticleRef> s = new Outcome<ArticleRef>();
        events.session.stat("<a@b>", s);
        assertEquals("STAT <a@b>", wire.last());
        server("223 0 <a@b>\r\n");
        assertEquals(0, s.result.getNumber());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Outcome<Void> a = new Outcome<Void>();
        events.session.article("<a@b>", new NntpConsumer<ByteBuffer>() {
            @Override
            public void accept(ByteBuffer b) {
                out.write(b.get());
            }
        }, a);
        assertEquals("ARTICLE <a@b>", wire.last());
        server("220 0 <a@b> article\r\nx\r\n.\r\n");
        assertTrue(a.done);
    }

    @Test
    public void testSessionExposesWhatTheServerSaid() {
        ready();
        assertTrue(events.session.isPostingAllowed());
        assertEquals(java.util.Arrays.asList("READER"), events.session.getCapabilities());
        assertTrue(events.session.isAlive());
        assertFalse(events.greeting.isSecure());
        NntpStatus status = new NntpStatus(200, "ok");
        assertTrue(status.isSuccess());
        assertFalse(new NntpStatus(411, "no").isSuccess());
        assertEquals("200 ok", status.toString());
    }

    @Test
    public void testEntryParsers() {
        assertNull(NewsgroupEntry.parse("a.b 1 2"));
        assertNull(NewsgroupEntry.parse("a.b x 2 y"));
        assertNull(GroupResult.parse("1 2 3"));
        assertNull(ArticleRef.parse("1"));
        assertNull(ArticleRef.parse("x <a@b>"));
        assertNull(OverviewEntry.parse("1"));
        assertNull(OverviewEntry.parse("x\ty"));
        OverviewEntry e = OverviewEntry.parse("1\ts\tf\td\t<m@x>\t<r@x>\t12\t3\textra");
        assertEquals("<r@x>", e.getReferences());
        assertEquals("f", e.getFrom());
        assertEquals(3, e.getLines());
        assertEquals("", OverviewEntry.parse("1\ts").getFrom());
    }

    // ── timeouts ──

    @Test
    public void testNoReplyInTimeFailsTheConnection() {
        start(null, null, StartTls.NEVER, null, 1000);
        server("200 hi\r\n");
        assertEquals(1, wire.timers.size());
        wire.timers.get(0).run();
        assertNotNull(events.error);
        assertTrue(events.error.getMessage(), events.error.getMessage().contains("no reply"));
        assertFalse(wire.open);
    }

    @Test
    public void testReplyCancelsTheTimer() {
        start(null, null, StartTls.NEVER, null, 1000);
        server("200 hi\r\n");
        server("101 caps\r\n.\r\n");
        assertTrue(wire.cancelled.get(0).booleanValue());
        assertNotNull(events.session);
    }

    @Test
    public void testDisconnectAfterFailureIsQuiet() {
        server("502 go away\r\n");
        assertNotNull(events.error);
        events.error = null;
        handler.disconnected();
        assertNull(events.error);
        assertFalse(events.disconnected);
    }

    @Test
    public void testApplicationThatThrowsOnErrorIsSurvived() {
        events.throwOnError = true;
        server("502 go away\r\n");
        assertNotNull(events.error);
        assertFalse(wire.open);
    }

    @Test
    public void testQuitThatIsNeverAnswered() {
        ready();
        events.session.quit();
        handler.disconnected();
        assertTrue(events.disconnected);
        assertFalse(events.session.isAlive());
    }
}
