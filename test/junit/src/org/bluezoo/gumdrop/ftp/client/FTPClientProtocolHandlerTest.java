/*
 * FTPClientProtocolHandlerTest.java
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.ftp.client.handler.*;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FTPClientProtocolHandler} state transitions:
 * greeting, USER/PASS/ACCT login sequences, AUTH TLS, and the
 * authenticated-state commands (CWD/CDUP/PWD/TYPE/STRU/MODE/DELE/RMD/MKD/
 * QUIT). Mirrors the structure of {@code SMTPClientProtocolHandlerTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPClientProtocolHandlerTest {

    private FTPClientProtocolHandler handler;
    private StubEndpoint endpoint;
    private final List<String> sentCommands = new ArrayList<>();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicReference<String> serviceUnavailable = new AtomicReference<>();

    @Before
    public void setUp() {
        sentCommands.clear();
        disconnected.set(false);
        serviceUnavailable.set(null);
        endpoint = new StubEndpoint(sentCommands);
        handler = new FTPClientProtocolHandler(new ServerGreeting() {
            @Override
            public void handleGreeting(ClientLoginState login, String message) {
            }
            @Override
            public void handleServiceUnavailable(String message) {
                serviceUnavailable.set(message);
            }
            @Override
            public void onConnected(Endpoint ep) {
            }
            @Override
            public void onDisconnected() {
                disconnected.set(true);
            }
            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }
            @Override
            public void onError(Exception e) {
            }
        });
        handler.connected(endpoint);
    }

    private void simulateResponse(String response) {
        handler.receive(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
    }

    private String getLastSentCommand() {
        return sentCommands.isEmpty() ? "" : sentCommands.get(sentCommands.size() - 1);
    }

    private void login() {
        simulateResponse("220 mail.example.com FTP ready\r\n");
        AtomicReference<ClientAuthenticatedState> auth = new AtomicReference<>();
        handler.user("alice", new ServerUserReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleUserAccepted(ClientAuthenticatedState a) { auth.set(a); }
            @Override public void handlePasswordRequired(ClientPasswordState pass) {
                pass.pass("secret", new ServerPassReplyHandler() {
                    @Override public void handleServiceClosing(String message) { }
                    @Override public void handleAuthenticated(ClientAuthenticatedState a) {
                        auth.set(a);
                    }
                    @Override public void handleAccountRequired(ClientAccountState acct) { }
                    @Override public void handleAuthFailed(ClientLoginState login, String message) { }
                });
            }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleRejected(ClientLoginState login, String message) { }
        });
        simulateResponse("331 Password required for alice\r\n");
        simulateResponse("230 User alice logged in\r\n");
        assertNotNull("login() helper must reach AUTHENTICATED state", auth.get());
    }

    // ── Greeting ──

    @Test
    public void testGreetingOk() {
        AtomicReference<String> greeting = new AtomicReference<>();
        FTPClientProtocolHandler h = new FTPClientProtocolHandler(new ServerGreeting() {
            @Override public void handleGreeting(ClientLoginState login, String message) {
                greeting.set(message);
            }
            @Override public void handleServiceUnavailable(String message) { }
            @Override public void onConnected(Endpoint ep) { }
            @Override public void onDisconnected() { }
            @Override public void onSecurityEstablished(SecurityInfo info) { }
            @Override public void onError(Exception e) { }
        });
        h.connected(new StubEndpoint(new ArrayList<>()));
        h.receive(ByteBuffer.wrap("220 mail.example.com FTP ready\r\n"
                .getBytes(StandardCharsets.US_ASCII)));
        assertEquals("mail.example.com FTP ready", greeting.get());
    }

    @Test
    public void testGreetingServiceUnavailable() {
        simulateResponse("421 Too many connections\r\n");
        assertEquals("421 Too many connections", serviceUnavailable.get());
    }

    // ── USER / PASS / ACCT ──

    @Test
    public void testUserAcceptedWithoutPassword() {
        simulateResponse("220 ready\r\n");
        AtomicReference<ClientAuthenticatedState> auth = new AtomicReference<>();
        handler.user("anonymous", new ServerUserReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleUserAccepted(ClientAuthenticatedState a) { auth.set(a); }
            @Override public void handlePasswordRequired(ClientPasswordState pass) { }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleRejected(ClientLoginState login, String message) { }
        });
        assertEquals("USER anonymous", getLastSentCommand());
        simulateResponse("230 Logged in\r\n");
        assertNotNull(auth.get());
    }

    @Test
    public void testUserRejected() {
        simulateResponse("220 ready\r\n");
        AtomicReference<String> rejected = new AtomicReference<>();
        handler.user("baduser", new ServerUserReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleUserAccepted(ClientAuthenticatedState a) { }
            @Override public void handlePasswordRequired(ClientPasswordState pass) { }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleRejected(ClientLoginState login, String message) {
                rejected.set(message);
            }
        });
        simulateResponse("530 Not logged in\r\n");
        assertEquals("Not logged in", rejected.get());
    }

    @Test
    public void testPassAuthFailed() {
        simulateResponse("220 ready\r\n");
        AtomicReference<ClientPasswordState> passState = new AtomicReference<>();
        handler.user("alice", new ServerUserReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleUserAccepted(ClientAuthenticatedState a) { }
            @Override public void handlePasswordRequired(ClientPasswordState pass) {
                passState.set(pass);
            }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleRejected(ClientLoginState login, String message) { }
        });
        simulateResponse("331 Password required\r\n");
        AtomicReference<String> failed = new AtomicReference<>();
        passState.get().pass("wrong", new ServerPassReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleAuthenticated(ClientAuthenticatedState a) { }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleAuthFailed(ClientLoginState login, String message) {
                failed.set(message);
            }
        });
        assertEquals("PASS wrong", getLastSentCommand());
        simulateResponse("530 Login incorrect\r\n");
        assertEquals("Login incorrect", failed.get());
    }

    @Test
    public void testAccountRequiredFlow() {
        simulateResponse("220 ready\r\n");
        AtomicReference<ClientAccountState> acctState = new AtomicReference<>();
        handler.user("alice", new ServerUserReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleUserAccepted(ClientAuthenticatedState a) { }
            @Override public void handlePasswordRequired(ClientPasswordState pass) {
                pass.pass("secret", new ServerPassReplyHandler() {
                    @Override public void handleServiceClosing(String message) { }
                    @Override public void handleAuthenticated(ClientAuthenticatedState a) { }
                    @Override public void handleAccountRequired(ClientAccountState acct) {
                        acctState.set(acct);
                    }
                    @Override public void handleAuthFailed(ClientLoginState login, String message) { }
                });
            }
            @Override public void handleAccountRequired(ClientAccountState acct) { }
            @Override public void handleRejected(ClientLoginState login, String message) { }
        });
        simulateResponse("331 Password required\r\n");
        simulateResponse("332 Account required\r\n");
        assertNotNull(acctState.get());

        AtomicReference<ClientAuthenticatedState> auth = new AtomicReference<>();
        acctState.get().acct("finance", new ServerAcctReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleAuthenticated(ClientAuthenticatedState a) { auth.set(a); }
            @Override public void handleAuthFailed(ClientLoginState login, String message) { }
        });
        assertEquals("ACCT finance", getLastSentCommand());
        simulateResponse("230 Logged in\r\n");
        assertNotNull(auth.get());
    }

    // ── AUTH TLS (RFC 4217) ──

    @Test
    public void testAuthTlsEstablished() {
        simulateResponse("220 ready\r\n");
        AtomicBoolean established = new AtomicBoolean();
        handler.authTls(new ServerAuthTlsReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleTlsEstablished(ClientLoginState login) {
                established.set(true);
            }
            @Override public void handleTlsUnavailable(ClientLoginState login) { }
        });
        assertEquals("AUTH TLS", getLastSentCommand());
        assertFalse(endpoint.tlsStarted);
        simulateResponse("234 AUTH TLS successful\r\n");
        assertTrue(endpoint.tlsStarted);
        handler.securityEstablished(new SecurityInfo() {
            @Override public String getProtocol() { return "TLSv1.3"; }
            @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
            @Override public int getKeySize() { return 128; }
            @Override public java.security.cert.Certificate[] getPeerCertificates() { return null; }
            @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
            @Override public String getApplicationProtocol() { return null; }
            @Override public long getHandshakeDurationMs() { return 0; }
            @Override public boolean isSessionResumed() { return false; }
        });
        assertTrue(established.get());
    }

    @Test
    public void testAuthTlsUnavailable() {
        simulateResponse("220 ready\r\n");
        AtomicBoolean unavailable = new AtomicBoolean();
        handler.authTls(new ServerAuthTlsReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleTlsEstablished(ClientLoginState login) { }
            @Override public void handleTlsUnavailable(ClientLoginState login) {
                unavailable.set(true);
            }
        });
        simulateResponse("502 Command not implemented\r\n");
        assertTrue(unavailable.get());
    }

    // ── Authenticated-state commands ──

    @Test
    public void testCwdOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.cwd("/pub", new ServerCwdReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { ok.set(true); }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        assertEquals("CWD /pub", getLastSentCommand());
        simulateResponse("250 Directory changed\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testCwdError() {
        login();
        AtomicReference<Integer> errCode = new AtomicReference<>();
        handler.cwd("/nope", new ServerCwdReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) {
                errCode.set(code);
            }
        });
        simulateResponse("550 No such directory\r\n");
        assertEquals(Integer.valueOf(550), errCode.get());
    }

    @Test
    public void testPwdParsesQuotedPathname() {
        login();
        AtomicReference<String> path = new AtomicReference<>();
        handler.pwd(new ServerPwdReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePathname(String pathname, ClientAuthenticatedState a) {
                path.set(pathname);
            }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        assertEquals("PWD", getLastSentCommand());
        simulateResponse("257 \"/home/alice\" is current directory\r\n");
        assertEquals("/home/alice", path.get());
    }

    @Test
    public void testPwdParsesDoubledQuoteEscape() {
        login();
        AtomicReference<String> path = new AtomicReference<>();
        handler.pwd(new ServerPwdReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePathname(String pathname, ClientAuthenticatedState a) {
                path.set(pathname);
            }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        simulateResponse("257 \"/home/\"\"weird\"\"\" is current directory\r\n");
        assertEquals("/home/\"weird\"", path.get());
    }

    @Test
    public void testMkdParsesQuotedPathname() {
        login();
        AtomicReference<String> path = new AtomicReference<>();
        handler.mkd("newdir", new ServerMkdReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePathname(String pathname, ClientAuthenticatedState a) {
                path.set(pathname);
            }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        assertEquals("MKD newdir", getLastSentCommand());
        simulateResponse("257 \"/home/alice/newdir\" created\r\n");
        assertEquals("/home/alice/newdir", path.get());
    }

    @Test
    public void testTypeOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.type("I", simpleOk(ok));
        assertEquals("TYPE I", getLastSentCommand());
        simulateResponse("200 Type set to I\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testStruOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.stru("F", simpleOk(ok));
        assertEquals("STRU F", getLastSentCommand());
        simulateResponse("200 Structure set to F\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testModeOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.mode("S", simpleOk(ok));
        assertEquals("MODE S", getLastSentCommand());
        simulateResponse("200 Mode set to S\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testCdupOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.cdup(simpleOk(ok));
        assertEquals("CDUP", getLastSentCommand());
        simulateResponse("200 Directory changed to parent\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testDeleOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.dele("file.txt", simpleOk(ok));
        assertEquals("DELE file.txt", getLastSentCommand());
        simulateResponse("250 File deleted\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testRmdOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.rmd("olddir", simpleOk(ok));
        assertEquals("RMD olddir", getLastSentCommand());
        simulateResponse("250 Directory removed\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testSimpleReplyError() {
        login();
        AtomicReference<Integer> errCode = new AtomicReference<>();
        handler.dele("missing.txt", new ServerSimpleReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) {
                errCode.set(code);
            }
        });
        simulateResponse("550 File not found\r\n");
        assertEquals(Integer.valueOf(550), errCode.get());
    }

    @Test
    public void testQuitClosesConnection() {
        login();
        handler.quit();
        assertEquals("QUIT", getLastSentCommand());
        simulateResponse("221 Goodbye\r\n");
        assertFalse(handler.isOpen());
    }

    // ── PASV / EPSV ──

    @Test
    public void testPasvParsesAddress() {
        login();
        AtomicReference<InetSocketAddress> addr = new AtomicReference<>();
        handler.pasv(new ServerPasvReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePassive(InetSocketAddress a, ClientAuthenticatedState s) {
                addr.set(a);
            }
            @Override public void handleError(ClientAuthenticatedState s, int code, String message) { }
        });
        assertEquals("PASV", getLastSentCommand());
        simulateResponse("227 Entering Passive Mode (127,0,0,1,200,50)\r\n");
        assertEquals("/127.0.0.1", addr.get().getAddress().toString());
        assertEquals(200 * 256 + 50, addr.get().getPort());
    }

    @Test
    public void testPasvError() {
        login();
        AtomicReference<Integer> errCode = new AtomicReference<>();
        handler.pasv(new ServerPasvReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePassive(InetSocketAddress a, ClientAuthenticatedState s) { }
            @Override public void handleError(ClientAuthenticatedState s, int code, String message) {
                errCode.set(code);
            }
        });
        simulateResponse("502 Command not implemented\r\n");
        assertEquals(Integer.valueOf(502), errCode.get());
    }

    @Test
    public void testEpsvParsesPortUsingControlHost() {
        login();
        AtomicReference<InetSocketAddress> addr = new AtomicReference<>();
        handler.epsv(new ServerEpsvReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePassive(InetSocketAddress a, ClientAuthenticatedState s) {
                addr.set(a);
            }
            @Override public void handleError(ClientAuthenticatedState s, int code, String message) { }
        });
        assertEquals("EPSV", getLastSentCommand());
        simulateResponse("229 Entering Extended Passive Mode (|||6446|)\r\n");
        assertEquals(6446, addr.get().getPort());
        assertEquals("/127.0.0.1", addr.get().getAddress().toString());
    }

    @Test
    public void testEpsvError() {
        login();
        AtomicBoolean unavailable = new AtomicBoolean();
        handler.epsv(new ServerEpsvReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handlePassive(InetSocketAddress a, ClientAuthenticatedState s) { }
            @Override public void handleError(ClientAuthenticatedState s, int code, String message) {
                unavailable.set(true);
            }
        });
        simulateResponse("500 Unknown command\r\n");
        assertTrue(unavailable.get());
    }

    // ── PORT / EPRT ──

    @Test
    public void testPortSendsLoopbackAddressAndEphemeralPort() {
        login();
        handler.port(new ServerPortReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        String sent = getLastSentCommand();
        assertTrue(sent, sent.startsWith("PORT 127,0,0,1,"));
    }

    @Test
    public void testPortOkCallback() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.port(new ServerPortReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { ok.set(true); }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        simulateResponse("200 PORT command successful\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testPortError() {
        login();
        AtomicReference<Integer> errCode = new AtomicReference<>();
        handler.port(new ServerPortReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) {
                errCode.set(code);
            }
        });
        simulateResponse("500 PORT rejected\r\n");
        assertEquals(Integer.valueOf(500), errCode.get());
    }

    @Test
    public void testEprtSendsIpv4AddressFamilyOne() {
        login();
        handler.eprt(new ServerPortReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        String sent = getLastSentCommand();
        assertTrue(sent, sent.startsWith("EPRT |1|127.0.0.1|"));
    }

    @Test
    public void testEprtOkCallback() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.eprt(new ServerPortReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { ok.set(true); }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        simulateResponse("200 EPRT command successful\r\n");
        assertTrue(ok.get());
    }

    // ── PBSZ / PROT (RFC 4217) ──

    @Test
    public void testPbszOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.pbsz(0, simpleOk(ok));
        assertEquals("PBSZ 0", getLastSentCommand());
        simulateResponse("200 PBSZ=0\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testProtPOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.prot("P", simpleOk(ok));
        assertEquals("PROT P", getLastSentCommand());
        simulateResponse("200 Protection level set to P\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testProtCOk() {
        login();
        AtomicBoolean ok = new AtomicBoolean();
        handler.prot("C", simpleOk(ok));
        assertEquals("PROT C", getLastSentCommand());
        simulateResponse("200 Protection level set to C\r\n");
        assertTrue(ok.get());
    }

    @Test
    public void testProtError() {
        login();
        AtomicReference<Integer> errCode = new AtomicReference<>();
        handler.prot("P", new ServerSimpleReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) {
                errCode.set(code);
            }
        });
        simulateResponse("503 PBSZ required first\r\n");
        assertEquals(Integer.valueOf(503), errCode.get());
    }

    // ── 421 mid-session ──

    @Test
    public void test421ClosesConnectionMidSession() {
        login();
        handler.pwd(new ServerPwdReplyHandler() {
            @Override public void handleServiceClosing(String message) {
                serviceUnavailable.set(message);
            }
            @Override public void handlePathname(String pathname, ClientAuthenticatedState a) { }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        });
        simulateResponse("421 Idle timeout\r\n");
        assertEquals("Idle timeout", serviceUnavailable.get());
        assertFalse(handler.isOpen());
    }

    private static ServerSimpleReplyHandler simpleOk(AtomicBoolean ok) {
        return new ServerSimpleReplyHandler() {
            @Override public void handleServiceClosing(String message) { }
            @Override public void handleOk(ClientAuthenticatedState a) { ok.set(true); }
            @Override public void handleError(ClientAuthenticatedState a, int code, String message) { }
        };
    }

    static class StubEndpoint implements Endpoint {
        private final List<String> sentCommands;
        boolean tlsStarted;

        StubEndpoint(List<String> sentCommands) {
            this.sentCommands = sentCommands;
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String cmd = new String(bytes, StandardCharsets.US_ASCII);
            String trimmed = cmd.replace("\r\n", "");
            if (!trimmed.isEmpty()) {
                sentCommands.add(trimmed);
            }
        }

        @Override
        public boolean isOpen() { return true; }

        @Override
        public boolean isClosing() { return false; }

        @Override
        public void close() { }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return new InetSocketAddress(
                        java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 21);
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 50000);
        }

        @Override
        public boolean isSecure() { return false; }

        @Override
        public SecurityInfo getSecurityInfo() { return null; }

        @Override
        public void startTLS() throws IOException {
            tlsStarted = true;
        }

        @Override
        public void pauseRead() { }

        @Override
        public void resumeRead() { }

        @Override
        public void onWriteReady(Runnable callback) { }

        @Override
        public org.bluezoo.gumdrop.SelectorLoop getSelectorLoop() { return null; }

        @Override
        public void execute(Runnable task) { task.run(); }

        @Override
        public void setTrace(org.bluezoo.gumdrop.telemetry.Trace trace) { }

        @Override
        public org.bluezoo.gumdrop.telemetry.Trace getTrace() { return null; }

        @Override
        public boolean isTelemetryEnabled() { return false; }

        @Override
        public org.bluezoo.gumdrop.telemetry.TelemetryConfig getTelemetryConfig() { return null; }

        @Override
        public org.bluezoo.gumdrop.TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return null;
        }
    }
}
