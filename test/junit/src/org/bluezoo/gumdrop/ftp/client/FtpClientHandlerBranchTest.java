/*
 * FtpClientHandlerBranchTest.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.AcceptLoopProbe;
import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.testsupport.RefusingTransportFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch-level unit tests for the control-connection reply handling of
 * {@link FtpClientProtocolHandler}: multi-line replies, malformed replies,
 * PASV/EPSV parsing, error callbacks, 421 handling and transfer failures
 * that need no data connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FtpClientHandlerBranchTest {

    private Gumdrop gumdrop;
    private ClientEndpoint keeper;

    private FtpClientProtocolHandler handler;
    private TestEndpoint endpoint;
    private final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> events = new ArrayList<String>();
    private final List<Exception> errors = new ArrayList<Exception>();

    /** Stub control endpoint with adjustable addresses and a failing startTLS. */
    static final class TestEndpoint extends FTPClientProtocolHandlerTest.StubEndpoint {
        SocketAddress remote = new InetSocketAddress("127.0.0.1", 50000);
        SocketAddress local;
        boolean failTls;
        boolean closed;

        TestEndpoint(List<String> sentCommands) {
            super(sentCommands);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remote;
        }

        @Override
        public SocketAddress getLocalAddress() {
            if (local != null) {
                return local;
            }
            return super.getLocalAddress();
        }

        @Override
        public void startTLS() throws IOException {
            if (failTls) {
                throw new IOException("no tls");
            }
            super.startTLS();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Records every reply-handler callback as a short event string. */
    private final class Recorder implements UserReplyHandler, PassReplyHandler, AcctReplyHandler,
            AuthTlsReplyHandler, CwdReplyHandler, SimpleReplyHandler, PwdReplyHandler, MkdReplyHandler,
            PasvReplyHandler, EpsvReplyHandler, PortReplyHandler {
        InetSocketAddress passive;

        @Override
        public void handleServiceClosing(String message) {
            events.add("closing:" + message);
        }

        @Override
        public void handleUserAccepted(ClientAuthenticatedState a) {
            events.add("userAccepted");
        }

        @Override
        public void handlePasswordRequired(ClientPasswordState pass) {
            events.add("passwordRequired");
        }

        @Override
        public void handleAccountRequired(ClientAccountState acct) {
            events.add("accountRequired");
        }

        @Override
        public void handleRejected(ClientLoginState login, String message) {
            events.add("rejected:" + message);
        }

        @Override
        public void handleAuthenticated(ClientAuthenticatedState a) {
            events.add("authenticated");
        }

        @Override
        public void handleAuthFailed(ClientLoginState login, String message) {
            events.add("authFailed:" + message);
        }

        @Override
        public void handleTlsEstablished(ClientLoginState login) {
            events.add("tlsEstablished");
        }

        @Override
        public void handleTlsUnavailable(ClientLoginState login) {
            events.add("tlsUnavailable");
        }

        @Override
        public void handleOk(ClientAuthenticatedState a) {
            events.add("ok");
        }

        @Override
        public void handleError(ClientAuthenticatedState a, int code, String message) {
            events.add("error:" + code + ":" + message);
        }

        @Override
        public void handlePathname(String pathname, ClientAuthenticatedState a) {
            events.add("path:" + pathname);
        }

        @Override
        public void handlePassive(InetSocketAddress dataAddress, ClientAuthenticatedState a) {
            passive = dataAddress;
            events.add("passive:" + dataAddress.getAddress().getHostAddress() + ":" + dataAddress.getPort());
        }
    }

    private final class Greeting implements RemoteGreeting {
        @Override
        public void handleGreeting(ClientLoginState login, String message) {
            events.add("greeting:" + message);
        }

        @Override
        public void handleServiceUnavailable(String message) {
            events.add("unavailable:" + message);
        }

        @Override
        public void onConnected(Endpoint ep) {
            events.add("connected");
        }

        @Override
        public void onDisconnected() {
            events.add("disconnected");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            events.add("security");
        }

        @Override
        public void onError(Exception e) {
            errors.add(e);
            events.add("onError");
        }
    }

    @Before
    public void setUp() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        // A registered client keeps the runtime from auto-shutting-down when a
        // data connection attempt fails and deregisters itself.
        keeper = new ClientEndpoint(new TcpTransportFactory(), gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        endpoint = new TestEndpoint(sent);
        handler = new FtpClientProtocolHandler(new Greeting());
        handler.setGumdrop(gumdrop);
        handler.connected(endpoint);
    }

    @After
    public void tearDown() throws InterruptedException {
        handler.disconnected();
        handler.close();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private void reply(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private String lastSent() {
        if (sent.isEmpty()) {
            return "";
        }
        return sent.get(sent.size() - 1);
    }

    private Recorder ready() {
        reply("220 ready\r\n");
        events.clear();
        return new Recorder();
    }

    private void assertEvents(String... expected) {
        List<String> want = new ArrayList<String>();
        for (String e : expected) {
            want.add(e);
        }
        assertEquals(want, events);
    }

    // ── construction and state ──

    @Test(expected = NullPointerException.class)
    public void constructorRejectsNullGreeting() {
        new FtpClientProtocolHandler(null);
    }

    @Test
    public void isOpenAndCloseLifecycle() {
        assertTrue(handler.isConnected());
        assertTrue(handler.isOpen());
        handler.close();
        assertTrue(endpoint.closed);
        assertFalse(handler.isConnected());
        assertFalse(handler.isOpen());
        endpoint.closed = false;
        handler.close();
        assertFalse("second close is a no-op", endpoint.closed);
    }

    @Test
    public void closeBeforeConnectedDoesNotFail() {
        FtpClientProtocolHandler fresh = new FtpClientProtocolHandler(new Greeting());
        assertFalse(fresh.isOpen());
        assertTrue(fresh.isConnected() == false);
        fresh.close();
        fresh.disconnected();
        assertTrue(events.contains("disconnected"));
    }

    @Test
    public void setSecureAndCredentialsAreAccepted() {
        handler.setSecure(true);
        handler.setClientCredentials(null);
        assertTrue(handler.isConnected());
    }

    @Test
    public void transportErrorIsReportedAndEntersErrorState() {
        handler.error(new IOException("reset"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof FtpException);
        assertNotNull(errors.get(0).getCause());
        assertFalse(handler.isConnected());
    }

    @Test
    public void disconnectedNotifiesGreetingHandler() {
        handler.disconnected();
        assertTrue(events.contains("disconnected"));
        assertFalse(handler.isConnected());
    }

    @Test
    public void securityEstablishedWithoutPendingAuthTlsIsIgnored() {
        Recorder r = ready();
        handler.securityEstablished(null);
        assertTrue(events.isEmpty());
        assertNotNull(r);
    }

    // ── sending ──

    @Test
    public void commandWhenNotConnectedReportsError() {
        Recorder r = ready();
        handler.close();
        handler.user("a", r);
        assertTrue(sent.isEmpty());
        assertEquals(1, errors.size());
        assertEquals("Not connected", errors.get(0).getMessage());
    }

    @Test
    public void commandWithCrlfIsRejected() {
        Recorder r = ready();
        try {
            handler.cwd("a\r\nDELE b", r);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(sent.isEmpty());
        }
        try {
            handler.cwd("a\nb", r);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(sent.isEmpty());
        }
    }

    // ── greeting and lexing ──

    @Test
    public void nonTwoTwentyGreetingIsServiceUnavailable() {
        reply("554 no service\r\n");
        assertEvents("unavailable:554 no service");
        assertFalse(handler.isConnected());
    }

    @Test
    public void multiLineGreetingUsesFirstLine() {
        reply("220-Welcome\r\n220 Ready\r\n");
        assertEvents("connected", "greeting:Welcome");
    }

    @Test
    public void multiLineReplyWithCodelessBodyLine() {
        reply("220-Welcome to the server\r\n  free text line\r\n220 Ready\r\n");
        assertTrue(events.toString(), events.contains("greeting:Welcome to the server"));
        assertTrue(events.toString(), errors.isEmpty());
        assertTrue(handler.isConnected());
    }

    @Test
    public void multiLineReplyBodyLineWithOtherCodeIsText() {
        reply("220-first\r\n551 not a terminator\r\n220 last\r\n");
        assertTrue(events.toString(), events.contains("greeting:first"));
        assertTrue(events.toString(), errors.isEmpty());
    }

    @Test
    public void multiLineReplyShortBodyLine() {
        reply("220-first\r\nab\r\n220 last\r\n");
        assertTrue(events.toString(), events.contains("greeting:first"));
        assertTrue(events.toString(), errors.isEmpty());
    }

    @Test
    public void bareCrlfIsIgnored() {
        reply("\r\n");
        assertTrue(events.isEmpty());
        reply("220 ok\r\n");
        assertTrue(events.contains("greeting:ok"));
    }

    @Test
    public void nonNumericCodeIsAnError() {
        reply("2x0 bad\r\n");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage(), errors.get(0).getMessage().indexOf("2x0") >= 0);
        assertFalse(handler.isConnected());
    }

    @Test
    public void shortCodeIsAnError() {
        reply("22\r\n");
        assertEquals(1, errors.size());
        assertFalse(handler.isConnected());
    }

    @Test
    public void fourDigitCodeIsReadAsThreeDigitCodeAndSeparator() {
        Recorder r = ready();
        handler.user("a", r);
        reply("2300 text\r\n");
        assertTrue(errors.isEmpty());
        assertEvents("userAccepted");
    }

    @Test
    public void replyWithoutTextIsAccepted() {
        reply("220\r\n");
        assertTrue(errors.isEmpty());
        assertEvents("connected", "greeting:");
    }

    /**
     * Feeds bytes the way the transport does: unconsumed bytes left in the
     * buffer by the lexer are kept and re-presented with the next chunk.
     */
    private static void feedInChunks(FtpClientProtocolHandler h, byte[] bytes, int chunk) {
        ByteBuffer acc = ByteBuffer.allocate(bytes.length + 8);
        int i = 0;
        while (i < bytes.length) {
            int n = Math.min(chunk, bytes.length - i);
            acc.put(bytes, i, n);
            i += n;
            acc.flip();
            h.receive(acc);
            acc.compact();
        }
    }

    @Test
    public void replyFedInSmallChunksMatchesWholeBuffer() {
        String text = "220-Hello\r\n 220 look\r\n220 done\r\n";
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        FtpClientProtocolHandler whole = new FtpClientProtocolHandler(new Greeting());
        whole.setGumdrop(gumdrop);
        whole.connected(new TestEndpoint(new ArrayList<String>()));
        whole.receive(ByteBuffer.wrap(bytes));
        List<String> expected = new ArrayList<String>(events);
        assertFalse(expected.isEmpty());
        whole.close();
        for (int chunk = 1; chunk <= 4; chunk++) {
            events.clear();
            errors.clear();
            FtpClientProtocolHandler h = new FtpClientProtocolHandler(new Greeting());
            h.setGumdrop(gumdrop);
            h.connected(new TestEndpoint(new ArrayList<String>()));
            feedInChunks(h, bytes, chunk);
            assertEquals("chunk size " + chunk, expected, events);
            assertTrue(errors.isEmpty());
            h.close();
        }
    }

    @Test
    public void responseAfterCloseIsIgnored() {
        Recorder r = ready();
        handler.cwd("/x", r);
        handler.close();
        reply("250 ok\r\n");
        assertTrue(events.isEmpty());
    }

    @Test
    public void unexpectedResponseInConnectedStateIsIgnored() {
        ready();
        reply("200 surprise\r\n");
        assertTrue(events.isEmpty());
        assertTrue(errors.isEmpty());
        assertTrue(handler.isConnected());
    }

    @Test
    public void quitClosesRegardlessOfReplyCode() {
        ready();
        handler.quit();
        assertEquals("QUIT", lastSent());
        reply("221 bye\r\n");
        assertTrue(endpoint.closed);
        assertFalse(handler.isConnected());
    }

    @Test
    public void code421WithoutHandlerReportsServiceUnavailable() {
        ready();
        reply("421 shutting down\r\n");
        assertEvents("unavailable:421 shutting down");
        assertTrue(endpoint.closed);
    }

    @Test
    public void code421DuringUserReachesReplyHandler() {
        Recorder r = ready();
        handler.user("a", r);
        reply("421 go away\r\n");
        assertEvents("closing:go away");
    }

    // ── login replies ──

    @Test
    public void userReplyBranches() {
        Recorder r = ready();
        handler.user("a", r);
        assertEquals("USER a", lastSent());
        reply("230 in\r\n");
        handler.user("a", r);
        reply("331 pw\r\n");
        handler.user("a", r);
        reply("332 acct\r\n");
        handler.user("a", r);
        reply("530 no\r\n");
        assertEvents("userAccepted", "passwordRequired", "accountRequired", "rejected:no");
    }

    @Test
    public void passReplyBranches() {
        Recorder r = ready();
        handler.pass("pw", r);
        assertEquals("PASS pw", lastSent());
        reply("230 in\r\n");
        handler.pass("pw", r);
        reply("332 acct\r\n");
        handler.pass("pw", r);
        reply("530 bad\r\n");
        assertEvents("authenticated", "accountRequired", "authFailed:bad");
    }

    @Test
    public void acctReplyBranches() {
        Recorder r = ready();
        handler.acct("proj", r);
        assertEquals("ACCT proj", lastSent());
        reply("230 in\r\n");
        handler.acct("proj", r);
        reply("503 bad\r\n");
        assertEvents("authenticated", "authFailed:bad");
    }

    @Test
    public void authTlsStartFailureReportsUnavailable() {
        Recorder r = ready();
        endpoint.failTls = true;
        handler.authTls(r);
        reply("234 go\r\n");
        assertEvents("tlsUnavailable");
    }

    @Test
    public void authTlsRejected() {
        Recorder r = ready();
        handler.authTls(r);
        reply("500 no\r\n");
        assertEvents("tlsUnavailable");
    }

    // ── authenticated commands ──

    @Test
    public void pwdAndMkdFailureAndUnquotedForms() {
        Recorder r = ready();
        handler.pwd(r);
        reply("550 denied\r\n");
        handler.mkd("d", r);
        reply("550 exists\r\n");
        handler.pwd(r);
        reply("257 /plain/path\r\n");
        handler.mkd("d", r);
        reply("257 \"/a\"\"b\" created\r\n");
        handler.pwd(r);
        reply("257 \"unterminated\r\n");
        assertEvents("error:550:denied", "error:550:exists", "path:/plain/path", "path:/a\"b",
                "path:unterminated");
    }

    @Test
    public void cwdAndSimpleCommandsSendExpectedText() {
        Recorder r = ready();
        handler.cwd("/tmp", r);
        assertEquals("CWD /tmp", lastSent());
        reply("250 ok\r\n");
        handler.cdup(r);
        assertEquals("CDUP", lastSent());
        reply("200 ok\r\n");
        handler.mkd("n", r);
        assertEquals("MKD n", lastSent());
        reply("257 \"n\"\r\n");
        handler.rmd("n", r);
        assertEquals("RMD n", lastSent());
        reply("250 ok\r\n");
        handler.dele("f", r);
        assertEquals("DELE f", lastSent());
        reply("550 no\r\n");
        handler.type("I", r);
        assertEquals("TYPE I", lastSent());
        reply("200 ok\r\n");
        handler.stru("F", r);
        assertEquals("STRU F", lastSent());
        reply("200 ok\r\n");
        handler.mode("S", r);
        assertEquals("MODE S", lastSent());
        reply("200 ok\r\n");
        handler.pbsz(0, r);
        reply("200 ok\r\n");
        assertEvents("ok", "ok", "path:n", "ok", "error:550:no", "ok", "ok", "ok", "ok");
    }

    // ── PASV / EPSV ──

    @Test
    public void pasvParsesTupleAnywhereInText() {
        Recorder r = ready();
        handler.pasv(r);
        assertEquals("PASV", lastSent());
        reply("227 Entering Passive Mode (192,168,1,2,4,1).\r\n");
        assertEvents("passive:192.168.1.2:1025");
    }

    @Test
    public void pasvMalformedRepliesAreReportedAsErrors() {
        String[] bad = {
            "227 no tuple here",
            "227 (1,2,3,4,5)",
            "227 (1,2,3,4,5,6,7)",
            "227 (1,2,3,256,5,6)",
            "227 (1,2,3,-1,5,6)",
            "227 (a,2,3,4,5,6)",
            "227 (1,2,3,4,x,6)",
            "227 (1,2,3,4,5,6",
        };
        for (String b : bad) {
            events.clear();
            Recorder r = ready();
            handler.pasv(r);
            reply(b + "\r\n");
            assertEquals(b, 1, events.size());
            assertTrue(b + ": " + events, events.get(0).startsWith("error:227:Malformed PASV reply"));
            resetHandler();
        }
    }

    private void resetHandler() {
        handler.disconnected();
        handler.close();
        endpoint = new TestEndpoint(sent);
        handler = new FtpClientProtocolHandler(new Greeting());
        handler.setGumdrop(gumdrop);
        handler.connected(endpoint);
    }

    @Test
    public void pasvErrorCode() {
        Recorder r = ready();
        handler.pasv(r);
        reply("425 cannot\r\n");
        assertEvents("error:425:cannot");
    }

    @Test
    public void pasvOnIpv6ControlWithLoopbackTupleUsesControlHost() throws Exception {
        endpoint.remote = new InetSocketAddress(InetAddress.getByName("::1"), 21);
        Recorder r = ready();
        handler.pasv(r);
        reply("227 (127,0,0,1,0,80)\r\n");
        assertNotNull(r.passive);
        assertEquals(InetAddress.getByName("::1"), r.passive.getAddress());
        assertEquals(80, r.passive.getPort());
    }

    @Test
    public void pasvOnIpv6ControlWithRoutableTupleKeepsTuple() throws Exception {
        endpoint.remote = new InetSocketAddress(InetAddress.getByName("::1"), 21);
        Recorder r = ready();
        handler.pasv(r);
        reply("227 (10,1,2,3,0,80)\r\n");
        assertEquals("10.1.2.3", r.passive.getAddress().getHostAddress());
    }

    @Test
    public void pasvWithNonInetControlAddressKeepsTuple() {
        endpoint.remote = new SocketAddress() {
            private static final long serialVersionUID = 1L;
        };
        Recorder r = ready();
        handler.pasv(r);
        reply("227 (127,0,0,1,0,80)\r\n");
        assertEquals("127.0.0.1", r.passive.getAddress().getHostAddress());
    }

    @Test
    public void epsvUsesControlHostAndParsedPort() {
        Recorder r = ready();
        handler.epsv(r);
        assertEquals("EPSV", lastSent());
        reply("229 Entering Extended Passive Mode (|||6446|)\r\n");
        assertEvents("passive:127.0.0.1:6446");
    }

    @Test
    public void epsvToleratesOtherDelimitersAndNetFields() {
        Recorder r = ready();
        handler.epsv(r);
        reply("229 (!!!7000!)\r\n");
        handler.epsv(r);
        reply("229 (|2|::1|7001|)\r\n");
        assertEvents("passive:127.0.0.1:7000", "passive:127.0.0.1:7001");
    }

    @Test
    public void epsvMalformedRepliesAreReportedAsErrors() {
        String[] bad = {
            "229 no parens",
            "229 ()",
            "229 (|||)",
            "229 (|||abc|)",
            "229 (|||70000000000|)",
            "229 (|||12",
        };
        for (String b : bad) {
            events.clear();
            Recorder r = ready();
            handler.epsv(r);
            reply(b + "\r\n");
            assertEquals(b, 1, events.size());
            assertTrue(b + ": " + events, events.get(0).startsWith("error:229:Malformed EPSV reply"));
            resetHandler();
        }
    }

    @Test
    public void epsvErrorCode() {
        Recorder r = ready();
        handler.epsv(r);
        reply("500 no\r\n");
        assertEvents("error:500:no");
    }

    // ── PORT / EPRT ──

    @Test
    public void portOnIpv6LocalAddressIsRejectedLocally() throws Exception {
        endpoint.local = new InetSocketAddress(InetAddress.getByName("::1"), 21);
        Recorder r = ready();
        handler.port(r);
        assertEquals(1, events.size());
        assertTrue(events.toString(), events.get(0).startsWith("error:0:PORT requires an IPv4"));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void eprtOnIpv6LocalAddressUsesFamilyTwo() throws Exception {
        endpoint.local = new InetSocketAddress(InetAddress.getByName("::1"), 21);
        Recorder r = ready();
        handler.eprt(r);
        assertTrue(lastSent(), lastSent().startsWith("EPRT |2|"));
        reply("200 ok\r\n");
        assertEvents("ok");
    }

    @Test
    public void portAndEprtRejectionsReportErrors() {
        Recorder r = ready();
        handler.port(r);
        reply("501 bad\r\n");
        handler.eprt(r);
        reply("522 bad proto\r\n");
        assertEvents("error:501:bad", "error:522:bad proto");
    }

    @Test
    public void portAcceptsAnyTwoXxReply() {
        Recorder r = ready();
        handler.port(r);
        reply("250 fine\r\n");
        assertEvents("ok");
    }

    // ── PROT ──

    @Test
    public void protReplyBranches() {
        Recorder r = ready();
        handler.prot("P", r);
        assertEquals("PROT P", lastSent());
        reply("200 ok\r\n");
        handler.prot("C", r);
        reply("534 no\r\n");
        assertEvents("ok", "error:534:no");
    }

    // ── transfers without a data connection ──

    private static class Down implements RetrReplyHandler {
        final List<String> log;

        Down(List<String> log) {
            this.log = log;
        }

        @Override
        public void handleServiceClosing(String message) {
            log.add("down-closing:" + message);
        }

        @Override
        public void handleContent(ByteBuffer data) {
            log.add("content");
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            log.add("down-complete");
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            log.add("down-failed:" + code + ":" + message);
        }
    }

    private static final class Up implements StorReplyHandler {
        final List<String> log;

        Up(List<String> log) {
            this.log = log;
        }

        @Override
        public void handleServiceClosing(String message) {
            log.add("up-closing:" + message);
        }

        @Override
        public void handleReadyToSend(ClientDataSink sink) {
            log.add("ready");
        }

        @Override
        public void handleTransferComplete(ClientAuthenticatedState a) {
            log.add("up-complete");
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            log.add("up-failed:" + code + ":" + message);
        }
    }

    private static final class Lst implements ListReplyHandler {
        final List<String> log;

        Lst(List<String> log) {
            this.log = log;
        }

        @Override
        public void handleServiceClosing(String message) {
            log.add("list-closing:" + message);
        }

        @Override
        public void handleEntries(List<FtpFileEntry> entries, ClientAuthenticatedState a) {
            log.add("entries:" + entries.size());
        }

        @Override
        public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
            log.add("list-failed:" + code + ":" + message);
        }
    }

    @Test
    public void activeModeCommandsAreSentImmediately() {
        ready();
        List<String> log = new ArrayList<String>();
        handler.retr("a.txt", null, new Down(log));
        assertEquals("RETR a.txt", lastSent());
        handler.stor("b.txt", null, new Up(log));
        assertEquals("STOR b.txt", lastSent());
        handler.appe("c.txt", null, new Up(log));
        assertEquals("APPE c.txt", lastSent());
        handler.list("dir", null, new Lst(log));
        assertEquals("LIST dir", lastSent());
        handler.list(null, null, new Lst(log));
        assertEquals("LIST", lastSent());
        handler.list("", null, new Lst(log));
        assertEquals("LIST", lastSent());
        handler.nlst("dir", null, new Lst(log));
        assertEquals("NLST dir", lastSent());
        handler.mlsd("dir", null, new Lst(log));
        assertEquals("MLSD dir", lastSent());
        handler.mlsd(null, null, new Lst(log));
        assertEquals("MLSD", lastSent());
    }

    @Test
    public void transferFailureRepliesNotifyEachCallbackKind() {
        ready();
        List<String> log = new ArrayList<String>();
        handler.retr("a", null, new Down(log));
        reply("150 opening\r\n");
        reply("125 already\r\n");
        reply("550 no such file\r\n");
        handler.stor("a", null, new Up(log));
        reply("553 denied\r\n");
        handler.list("a", null, new Lst(log));
        reply("450 busy\r\n");
        assertEquals("[down-failed:550:no such file, up-failed:553:denied, list-failed:450:busy]",
                log.toString());
        assertTrue(handler.isConnected());
    }

    @Test
    public void successReplyWithoutDataEofDoesNotCompleteTransfer() {
        ready();
        List<String> log = new ArrayList<String>();
        handler.retr("a", null, new Down(log));
        reply("226 done\r\n");
        assertTrue(log.toString(), log.isEmpty());
    }

    @Test
    public void code421DuringTransferReachesTransferCallback() {
        ready();
        List<String> log = new ArrayList<String>();
        handler.retr("a", null, new Down(log));
        reply("421 closing\r\n");
        assertEquals("[down-closing:closing]", log.toString());
    }

    @Test
    public void activeModeTransferFailureReleasesTheListener() throws Exception {
        Recorder r = ready();
        handler.port(r);
        String portCmd = lastSent();
        assertTrue(portCmd, portCmd.startsWith("PORT 127,0,0,1,"));
        reply("200 ok\r\n");
        java.nio.channels.ServerSocketChannel listener = handler.activeListenerChannel();
        assertNotNull("PORT opens a listener", listener);
        List<String> log = new ArrayList<String>();
        handler.retr("missing", null, new Down(log));
        reply("550 no such file\r\n");
        assertEquals("[down-failed:550:no such file]", log.toString());
        assertNull(handler.activeListenerChannel());
        assertFalse("the active-mode listener must be closed after a failed transfer", listener.isOpen());
        assertFalse("and deregistered from the accept loop (closeRawAcceptor)",
                AcceptLoopProbe.isRegistered(gumdrop, listener));
    }

    @Test
    public void activeModeWithProtectedDataConnectionsFailsTheTransfer() {
        Recorder r = ready();
        handler.prot("P", r);
        reply("200 ok\r\n");
        List<String> log = new ArrayList<String>();
        handler.retr("a", null, new Down(log));
        assertEquals(1, log.size());
        assertTrue(log.toString(), log.get(0).startsWith("down-failed:0:PROT P is not supported"));
    }

    @Test
    public void passiveConnectToRefusedPortFailsTheTransfer() throws Exception {
        int port = 9;
        RefusingTransportFactory refusing = new RefusingTransportFactory();
        TestLoopEndpoint loopEndpoint = new TestLoopEndpoint(sent);
        FtpClientProtocolHandler h = new FtpClientProtocolHandler(new Greeting());
        h.setGumdrop(gumdrop);
        h.dataTransportFactory = refusing;
        h.connected(loopEndpoint);
        h.receive(ByteBuffer.wrap("220 hi\r\n".getBytes(StandardCharsets.US_ASCII)));
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        h.retr("a", new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), new Down(log) {
            @Override
            public void handleTransferFailed(ClientAuthenticatedState a, int code, String message) {
                super.handleTransferFailed(a, code, message);
                done.countDown();
            }
        });
        assertTrue("connect failure must be reported", done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(log.toString(), log.get(0).startsWith("down-failed:0:"));
        assertEquals(1, refusing.attempts());
        h.close();
    }

    /** Control stub bound to a real selector loop so data connections can be made. */
    final class TestLoopEndpoint extends FTPClientProtocolHandlerTest.StubEndpoint {
        private final org.bluezoo.gumdrop.SelectorLoop loop = gumdrop.nextWorkerLoop();

        TestLoopEndpoint(List<String> sentCommands) {
            super(sentCommands);
        }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() {
            return loop;
        }
    }
}
