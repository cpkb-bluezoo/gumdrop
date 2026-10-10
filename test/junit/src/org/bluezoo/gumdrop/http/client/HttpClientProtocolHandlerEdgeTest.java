/*
 * HttpClientProtocolHandlerEdgeTest.java
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


package org.bluezoo.gumdrop.http.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.testsupport.CollectingResponseHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.RecordingSelectorLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge and error branches of {@link HttpClientProtocolHandler} that the
 * behaviour tests do not reach: handler-less connections and requests,
 * authentication challenges (Basic, Digest variants, proxy, unanswerable),
 * chunked and trailer handling while discarding a challenged body, protocol
 * switch hooks, h2c upgrade, request body content coding, shutdown ordering
 * and the diagnostic tracing switches. Everything runs over an in-memory
 * endpoint with mocks for the response and connection handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerEdgeTest {

    private static final class Recorder extends CollectingResponseHandler {
        HttpStatus status;
        int okCalls;
        int errorCalls;
        int closeCalls;
        boolean endBody;
        final List<Exception> failures = new ArrayList<Exception>();
        final List<String> headers = new ArrayList<String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void ok(HttpStatus response) {
            okCalls++;
            status = response;
        }

        @Override
        public void error(HttpStatus response) {
            errorCalls++;
            status = response;
        }

        @Override
        public void header(String name, String value) {
            headers.add(name + ": " + value);
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            body.write(b, 0, b.length);
        }

        @Override
        public void endResponseBody() {
            endBody = true;
        }

        @Override
        public void close() {
            closeCalls++;
        }

        @Override
        public void failed(Exception ex) {
            failures.add(ex);
        }
    }

    private static final class Conn implements HttpClientHandler {
        int disconnected;
        final List<Exception> errors = new ArrayList<Exception>();

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
            errors.add(cause);
        }

        @Override
        public void onDisconnected() {
            disconnected++;
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }
    }

    /** Protocol-switch aware subclass standing in for the WebSocket handler. */
    private static final class Switching extends HttpClientProtocolHandler {
        boolean accept;
        boolean switched;
        int switchCalls;
        boolean wantEvents;
        final List<String> order = new ArrayList<String>();
        final ByteArrayOutputStream tail = new ByteArrayOutputStream();

        Switching(HttpClientHandler handler) {
            super(handler, "example.com", 80, false);
        }

        @Override
        protected HttpMessageHandler protocolSwitchEvents() {
            if (!wantEvents) {
                return null;
            }
            return new DefaultHttpResponseHandler() {
                @Override
                public void status(int code) {
                    order.add("status:" + code);
                }

                @Override
                public void header(String name, ByteBuffer value) {
                    order.add("header:" + name.toLowerCase() + "="
                            + StandardCharsets.ISO_8859_1.decode(value.duplicate()));
                }

                @Override
                public void endHeaders() {
                    order.add("endHeaders");
                }

                @Override
                public void bodyContent(ByteBuffer data) {
                    order.add("body");
                }

                @Override
                public void endMessage() {
                    order.add("endMessage");
                }
            };
        }

        @Override
        protected boolean handleProtocolSwitch(HttpStatus status) {
            order.add("switch:" + status.code);
            switchCalls++;
            if (accept) {
                switched = true;
            }
            return accept;
        }

        @Override
        protected boolean isExternallyHandled() {
            return switched;
        }

        @Override
        public void receive(ByteBuffer data) {
            if (switched) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                tail.write(b, 0, b.length);
                return;
            }
            super.receive(data);
        }
    }

    private HttpClientProtocolHandler handler;
    private BinaryRecordingEndpoint endpoint;
    private Conn conn;

    @Before
    public void setUp() {
        conn = new Conn();
        newHandler(false);
    }

    @After
    public void clearProperties() {
        System.clearProperty("gumdrop.http.debug");
        System.clearProperty("gumdrop.integration.log.level");
    }

    private void newHandler(boolean withLoop) {
        handler = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        handler.setH2cUpgradeEnabled(false);
        handler.setSendAcceptEncodingHeader(false);
        endpoint = new BinaryRecordingEndpoint();
        if (withLoop) {
            endpoint.setSelectorLoop(new InlineSelectorLoop());
        }
        handler.connected(endpoint);
    }

    private String sent() {
        return new String(endpoint.getAllBytes(), StandardCharsets.UTF_8);
    }

    private void feed(String raw) {
        handler.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private Recorder get() {
        Recorder r = new Recorder();
        handler.get("/p", r).endMessage();
        return r;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    private static SecurityInfo info(final String protocol, final String cipher, final String alpn) {
        return new SecurityInfo() {
            @Override
            public String getProtocol() {
                return protocol;
            }

            @Override
            public String getCipherSuite() {
                return cipher;
            }

            @Override
            public int getKeySize() {
                return 128;
            }

            @Override
            public java.security.cert.Certificate[] getPeerCertificates() {
                return null;
            }

            @Override
            public java.security.cert.Certificate[] getLocalCertificates() {
                return null;
            }

            @Override
            public String getApplicationProtocol() {
                return alpn;
            }

            @Override
            public long getHandshakeDurationMs() {
                return 0;
            }

            @Override
            public boolean isSessionResumed() {
                return false;
            }
        };
    }

    // ── connections without a connection handler ──

    @Test
    public void connectionWithoutHandlerToleratesEveryLifecycleEvent() {
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(null, "example.com", 443, true);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "http/1.1"));
        assertEquals(HttpVersion.HTTP_1_1, h.getVersion());
        assertTrue(h.isOpen());
        h.error(new IOException("boom"));
        assertFalse(h.isOpen());
        h.disconnected();
        assertFalse(h.isOpen());
    }

    @Test
    public void h2AlpnWithoutHandlerSendsThePrefaceOnTheLoop() {
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(null, "example.com", 443, true);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        RecordingSelectorLoop loop = new RecordingSelectorLoop();
        ep.setSelectorLoop(loop);
        h.connected(ep);
        h.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "h2"));
        assertEquals(0, ep.getAllBytes().length);
        loop.runRecorded();
        String wire = new String(ep.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.startsWith("PRI * HTTP/2.0"));
        assertEquals(HttpVersion.HTTP_2_0, h.getVersion());
    }

    @Test
    public void closingBeforeTheDeferredPrefaceSuppressesIt() {
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(conn, "example.com", 443, true);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        RecordingSelectorLoop loop = new RecordingSelectorLoop();
        ep.setSelectorLoop(loop);
        h.connected(ep);
        h.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", "h2"));
        h.close();
        loop.runRecorded();
        String wire = new String(ep.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertFalse(wire, wire.startsWith("PRI"));
        assertEquals(1, ep.getCloseCount());
    }

    @Test
    public void isOpenRequiresAnOpenEndpoint() {
        HttpClientProtocolHandler fresh = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        assertFalse(fresh.isOpen());
        assertTrue(handler.isOpen());
        endpoint.close();
        assertFalse(handler.isOpen());
    }

    @Test
    public void requestShutdownBeforeConnectingJustMarksTheHandlerClosed() {
        HttpClientProtocolHandler fresh = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        fresh.requestShutdown();
        assertFalse(fresh.isOpen());
    }

    @Test
    public void requestShutdownIsRunOnTheConnectionLoop() {
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        RecordingSelectorLoop loop = new RecordingSelectorLoop();
        ep.setSelectorLoop(loop);
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        h.setH2cUpgradeEnabled(false);
        h.connected(ep);
        h.requestShutdown();
        assertEquals(1, loop.recordedCount());
        assertEquals(0, ep.getCloseCount());
        loop.runRecorded();
        loop.runRecorded();
        assertEquals(1, ep.getCloseCount());
    }

    // ── request validation ──

    @Test
    public void requestLineValidationRejectsEachMalformedPart() {
        String[] methods = {null, "", "GE T", "GéT", "G:T", "G/T", "G\u007fT"};
        for (int i = 0; i < methods.length; i++) {
            try {
                handler.request(HttpMethod.of(methods[i]), "/p", null);
                org.junit.Assert.fail("method accepted: " + methods[i]);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        String[] paths = {null, "", "/a b", "/a\r\nX: y", "/a\u007fb", "/a\u0000"};
        for (int i = 0; i < paths.length; i++) {
            try {
                handler.get(paths[i], null);
                org.junit.Assert.fail("path accepted: " + paths[i]);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        HttpRequest ok = handler.request(HttpMethod.of("M-SEARCH"), "/*", null);
        assertNotNull(ok);
    }

    // ── responses delivered to a request without a handler ──

    @Test
    public void handlerlessRequestsConsumeEveryResponseShape() {
        handler.get("/a", null).endMessage();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc");
        handler.get("/b", null).endMessage();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\nX-T: v\r\n\r\n");
        handler.head("/c", null).endMessage();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n");
        handler.get("/f", null).endMessage();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        Recorder last = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        assertEquals(1, last.okCalls);
        assertEquals("ok", new String(last.body.toByteArray(), StandardCharsets.US_ASCII));
        assertEquals(5, count(sent(), "Host: example.com"));
    }

    @Test
    public void handlerlessChunkedBodyIsConsumed() {
        handler.get("/b", null).endMessage();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\n\r\n");
        Recorder r = get();
        feed("HTTP/1.1 204 No Content\r\n\r\n");
        assertEquals(1, r.okCalls);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void hiddenContentEncodingTrailerIsNotDelivered() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\n"
                + "Content-Encoding: gzip\r\nX-Kept: yes\r\n\r\n");
        assertEquals(1, r.closeCalls);
        assertTrue(r.headers.toString(), r.headers.contains("x-kept: yes"));
        for (int i = 0; i < r.headers.size(); i++) {
            assertFalse(r.headers.get(i), r.headers.get(i).toLowerCase().startsWith("content-encoding"));
        }
    }

    @Test
    public void aTrailerLineWithoutAColonMakesTheResponseMalformed() {
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\nnocolon\r\n\r\n");
        assertEquals(1, r.failures.size());
    }

    @Test
    public void foldedHeaderBeforeAnyHeaderIsMalformed() {
        // RFC 9112 section 5.2: a fold with no field line to continue
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\n folded-first\r\nX-A: 1\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.failures.size());
        assertEquals(0, r.okCalls);
    }

    // ── authentication ──

    private void respondChallenge(String header, String value) {
        feed("HTTP/1.1 " + header + "\r\n" + value + "\r\nContent-Length: 0\r\n\r\n");
    }

    @Test
    public void proxyAuthenticationIsAnsweredWithProxyAuthorization() {
        handler.credentials("user", "pass");
        Recorder r = get();
        respondChallenge("407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"p\"");
        assertEquals(0, r.errorCalls);
        String wire = sent();
        assertTrue(wire, wire.contains("Proxy-Authorization: Basic dXNlcjpwYXNz"));
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.okCalls);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void proxyAuthenticationWithoutChallengeOrCredentialsIsDelivered() {
        Recorder noCredentials = get();
        respondChallenge("407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"p\"");
        assertEquals(1, noCredentials.errorCalls);
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, noCredentials.status);
        handler.credentials("user", "pass");
        Recorder noHeader = get();
        respondChallenge("407 Proxy Authentication Required", "X-Other: 1");
        assertEquals(1, noHeader.errorCalls);
        handler.credentials("user", null);
        Recorder noPassword = get();
        respondChallenge("407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"p\"");
        assertEquals(1, noPassword.errorCalls);
        assertFalse(sent(), sent().contains("Proxy-Authorization"));
    }

    @Test
    public void unauthorizedWithoutChallengeHeaderOrPasswordIsDelivered() {
        handler.credentials("user", "pass");
        Recorder noHeader = get();
        respondChallenge("401 Unauthorized", "X-Other: 1");
        assertEquals(1, noHeader.errorCalls);
        handler.credentials("user", null);
        Recorder noPassword = get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Basic realm=\"r\"");
        assertEquals(1, noPassword.errorCalls);
        handler.credentials(null, "pass");
        Recorder noUser = get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Basic realm=\"r\"");
        assertEquals(1, noUser.errorCalls);
        assertFalse(sent(), sent().contains("Authorization:"));
    }

    @Test
    public void secondChallengeAfterRetryIsDelivered() {
        handler.credentials("user", "pass");
        Recorder r = get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Basic realm=\"r\"");
        assertEquals(1, count(sent(), "Authorization: Basic"));
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Basic realm=\"r\"");
        assertEquals(1, r.errorCalls);
        assertEquals(HttpStatus.UNAUTHORIZED, r.status);
        assertEquals(1, r.closeCalls);
    }

    @Test
    public void bareBasicSchemeIsAnswered() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Basic");
        assertTrue(sent(), sent().contains("Authorization: Basic dXNlcjpwYXNz"));
    }

    @Test
    public void digestWithSessionAlgorithmUserhashAndOpaque() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", "
                + "algorithm=SHA-256-sess, qop=\"auth\", opaque=\"op\", userhash=true");
        String wire = sent();
        int at = wire.indexOf("Authorization: Digest ");
        assertTrue(wire, at > 0);
        String auth = wire.substring(at, wire.indexOf("\r\n", at));
        assertTrue(auth, auth.contains("qop=auth, nc=00000001"));
        assertTrue(auth, auth.contains("opaque=\"op\""));
        assertTrue(auth, auth.contains("algorithm=SHA-256-sess"));
        assertTrue(auth, auth.contains("userhash=true"));
        assertFalse(auth, auth.contains("username=\"user\""));
    }

    @Test
    public void sha256ChallengeWinsOverEarlierMd5ChallengeHeader() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized",
                "WWW-Authenticate: Digest realm=\"r\", nonce=\"nmd5\", qop=\"auth\", algorithm=MD5\r\n"
                + "WWW-Authenticate: Digest realm=\"r\", nonce=\"nsha\", qop=\"auth\", algorithm=SHA-256");
        String wire = sent();
        int at = wire.indexOf("Authorization: Digest ");
        assertTrue(wire, at > 0);
        String auth = wire.substring(at, wire.indexOf("\r\n", at));
        assertTrue(auth, auth.contains("nonce=\"nsha\""));
        assertTrue(auth, auth.contains("algorithm=SHA-256"));
    }

    @Test
    public void digestChallengeIsPreferredOverBasicWhenAccepted() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized",
                "WWW-Authenticate: Basic realm=\"r\"\r\n"
                + "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=SHA-256");
        assertTrue(sent(), sent().contains("Authorization: Digest "));
        assertFalse(sent(), sent().contains("Authorization: Basic"));
    }

    @Test
    public void basicIsUsedWhenOnlyMd5DigestIsOffered() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized",
                "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=MD5\r\n"
                + "WWW-Authenticate: Basic realm=\"r\"");
        assertTrue(sent(), sent().contains("Authorization: Basic "));
    }

    @Test
    public void digestWithoutQopOmitsClientNonceFields() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Digest realm=r nonce=n algorithm=SHA-256");
        String wire = sent();
        int at = wire.indexOf("Authorization: Digest ");
        assertTrue(wire, at > 0);
        String auth = wire.substring(at, wire.indexOf("\r\n", at));
        assertTrue(auth, auth.contains("realm=\"r\""));
        assertTrue(auth, auth.contains("nonce=\"n\""));
        assertFalse(auth, auth.contains("qop="));
        assertFalse(auth, auth.contains("opaque="));
        assertTrue(auth, auth.contains("algorithm=SHA-256"));
        assertFalse(auth, auth.contains("userhash"));
    }

    @Test
    public void digestWithAuthIntOnlyQopStillUsesAuth() {
        handler.credentials("user", "pass");
        get();
        respondChallenge("401 Unauthorized", "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\", algorithm=SHA-256");
        assertTrue(sent(), sent().contains("qop=auth, nc=00000001"));
    }

    @Test
    public void unanswerableChallengesAreDeliveredToTheHandler() {
        handler.credentials("user", "pass");
        String[] challenges = {
            "Bearer realm=\"x\"",
            "Digest realm=\"r\", nonce=\"n\", algorithm=NO-SUCH-DIGEST",
            "Digest realm=\"r\", nonce=\"n\", algorithm=MD5",
            "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=MD5-sess",
            "Digest realm=\"r\", nonce=\"n\", qop=\"auth\"",
            "Digest nonce=\"n\"",
            "Digest nonce=\"n\", realm=\"unterminated",
            "Digest nonce=n, realm=",
            "Digest",
        };
        for (int i = 0; i < challenges.length; i++) {
            Recorder r = get();
            respondChallenge("401 Unauthorized", "WWW-Authenticate: " + challenges[i]);
            assertEquals(challenges[i], 1, r.errorCalls);
            assertEquals(challenges[i], HttpStatus.UNAUTHORIZED, r.status);
            assertEquals(challenges[i], 1, r.closeCalls);
        }
        assertFalse(sent(), sent().contains("Authorization:"));
    }

    @Test
    public void challengedChunkedBodyWithTrailersIsDiscardedBeforeTheRetry() {
        handler.credentials("user", "pass");
        Recorder r = get();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"r\"\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n4;ext=1\r\nfull\r\n0\r\nX-Trailer: t\r\n\r\n");
        assertEquals(0, r.errorCalls);
        assertEquals(1, count(sent(), "Authorization: Basic"));
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.okCalls);
        assertEquals("only the final response's headers", 1, r.headers.size());
        assertEquals("content-length: 0", r.headers.get(0));
    }

    @Test
    public void challengedBodyIsDiscardedWithAndWithoutAHandler() {
        handler.credentials("user", "pass");
        handler.get("/a", null).endMessage();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"r\"\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n4\r\nfull\r\n0\r\nX-Trailer: t\r\n\r\n");
        assertEquals(1, count(sent(), "Authorization: Basic"));
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        Recorder r = get();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"r\"\r\nContent-Length: 5\r\n\r\nabcde");
        assertEquals(2, count(sent(), "Authorization: Basic"));
        assertEquals(0, r.errorCalls);
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.okCalls);
    }

    @Test
    public void challengeWithAnEmptyBodyIsRetriedAtOnce() {
        handler.credentials("user", "pass");
        get();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"r\"\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, count(sent(), "Authorization: Basic"));
    }

    // ── protocol switch and h2c upgrade ──

    @Test
    public void acceptedProtocolSwitchHandsTheRemainingBytesToTheSubclass() {
        Switching sw = new Switching(conn);
        sw.accept = true;
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(false);
        Recorder r = new Recorder();
        sw.get("/ws", r).endMessage();
        String raw = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\nFRAME";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, sw.switchCalls);
        assertEquals("FRAME", new String(sw.tail.toByteArray(), StandardCharsets.US_ASCII));
        assertEquals(0, r.okCalls);
    }

    @Test
    public void protocolSwitchEventsReceiveTheResponseEventsBeforeTheSwitchHook() {
        Switching sw = new Switching(conn);
        sw.accept = true;
        sw.wantEvents = true;
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(false);
        Recorder r = new Recorder();
        sw.get("/ws", r).endMessage();
        String raw = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\n\r\nFRAME";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        List<String> expected = new ArrayList<String>();
        expected.add("status:101");
        expected.add("header:upgrade=websocket");
        expected.add("header:connection=Upgrade");
        expected.add("endHeaders");
        expected.add("switch:101");
        assertEquals(expected, sw.order);
        assertEquals("FRAME", new String(sw.tail.toByteArray(), StandardCharsets.US_ASCII));
        assertEquals(0, r.okCalls);
    }

    @Test
    public void protocolSwitchEventsAreNotGivenAnH2cUpgrade() {
        Switching sw = new Switching(conn);
        sw.wantEvents = true;
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        ep.setSelectorLoop(new InlineSelectorLoop());
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(true);
        Recorder r = new Recorder();
        sw.get("/p", r).endMessage();
        String raw = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, sw.switchCalls);
        assertTrue(sw.order.toString(), sw.order.isEmpty());
    }

    @Test
    public void unacceptedProtocolSwitchFailsTheResponse() {
        Switching sw = new Switching(conn);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(false);
        Recorder r = new Recorder();
        sw.get("/ws", r).endMessage();
        String raw = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, sw.switchCalls);
        assertEquals(0, r.okCalls);
        assertEquals(1, r.failures.size());
    }

    @Test
    public void foreignUpgradeDuringH2cAttemptIsOfferedToTheSubclass() {
        Switching sw = new Switching(conn);
        sw.accept = true;
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(true);
        Recorder r = new Recorder();
        sw.get("/p", r).endMessage();
        assertTrue(new String(ep.getAllBytes(), StandardCharsets.US_ASCII).contains("Upgrade: h2c"));
        String raw = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, sw.switchCalls);
        assertEquals(HttpVersion.HTTP_1_1, sw.getVersion());
    }

    @Test
    public void foreignUpgradeDuringH2cAttemptIsWarnedAboutWhenUnhandled() {
        Switching sw = new Switching(conn);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        sw.connected(ep);
        sw.setH2cUpgradeEnabled(true);
        Recorder r = new Recorder();
        sw.get("/p", r).endMessage();
        String raw = "HTTP/1.1 101 Switching Protocols\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        sw.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, sw.switchCalls);
        assertEquals(0, r.okCalls);
        assertEquals(1, r.failures.size());
    }

    @Test
    public void declinedH2cUpgradeFallsBackToHttp1() {
        handler.setH2cUpgradeEnabled(true);
        Recorder r = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, r.okCalls);
        get();
        assertEquals(1, count(sent(), "Upgrade: h2c"));
        assertEquals(HttpVersion.HTTP_1_1, handler.getVersion());
    }

    @Test
    public void secureConnectionsNeverOfferH2cUpgrade() {
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(conn, "example.com", 80, true);
        h.setH2cUpgradeEnabled(true);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        h.connected(ep);
        h.securityEstablished(info("TLSv1.3", "TLS_AES_128_GCM_SHA256", null));
        h.get("/p", new Recorder()).endMessage();
        String wire = new String(ep.getAllBytes(), StandardCharsets.US_ASCII);
        assertFalse(wire, wire.contains("Upgrade: h2c"));
        assertTrue(wire, wire.contains("Connection: keep-alive"));
    }

    @Test
    public void acceptedH2cUpgradeSwitchesToH2AndParsesTrailingFrames() {
        newHandler(true);
        handler.setH2cUpgradeEnabled(true);
        Recorder r = get();
        byte[] head = ("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] settings = new byte[] {0, 0, 0, 4, 0, 0, 0, 0, 0};
        byte[] all = new byte[head.length + settings.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(settings, 0, all, head.length, settings.length);
        handler.receive(ByteBuffer.wrap(all));
        assertEquals(HttpVersion.HTTP_2_0, handler.getVersion());
        byte[] wire = endpoint.getAllBytes();
        String text = new String(wire, StandardCharsets.ISO_8859_1);
        assertTrue(text, text.contains("PRI * HTTP/2.0"));
        assertEquals(0, r.failures.size());
    }

    // ── request bodies ──

    @Test
    public void nullRequestBodyChunkIsIgnored() {
        newHandler(true);
        Recorder r = new Recorder();
        HttpStream req = (HttpStream) handler.post("/up", r);
        req.endHeaders();
        int before = endpoint.getAllBytes().length;
        assertEquals(0, req.bodyContent(null));
        assertEquals(0, handler.sendRequestBody(req, null));
        assertEquals(before, endpoint.getAllBytes().length);
    }

    private static byte[] gunzip(byte[] in) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(in));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = gz.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Reassembles the chunked request body that follows the header block. */
    private static byte[] dechunk(String wire) {
        int pos = wire.indexOf("\r\n\r\n") + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (pos < wire.length()) {
            int eol = wire.indexOf("\r\n", pos);
            int size = Integer.parseInt(wire.substring(pos, eol), 16);
            if (size == 0) {
                break;
            }
            byte[] chunk = wire.substring(eol + 2, eol + 2 + size).getBytes(StandardCharsets.ISO_8859_1);
            out.write(chunk, 0, chunk.length);
            pos = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    @Test
    public void gzipRequestBodyIsEncodedAndChunked() throws Exception {
        handler.setEncodeRequestBodyContentCoding(true);
        Recorder r = new Recorder();
        HttpRequest req = handler.post("/up", r);
        req.header("Content-Encoding", "gzip");
        byte[] plain = "hello hello hello hello".getBytes(StandardCharsets.US_ASCII);
        int n = req.bodyContent(ByteBuffer.wrap(plain));
        assertEquals(plain.length, n);
        req.endMessage();
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(wire, wire.endsWith("0\r\n\r\n"));
        byte[] decoded = gunzip(dechunk(wire));
        assertEquals("hello hello hello hello", new String(decoded, StandardCharsets.US_ASCII));
    }

    @Test
    public void unsupportedRequestContentCodingFailsTheHandler() {
        handler.setEncodeRequestBodyContentCoding(true);
        Recorder r = new Recorder();
        HttpStream stream = new HttpStream(handler, "POST", "/up", r);
        stream.header("Content-Encoding", "rot13");
        stream.endHeaders();
        int n = handler.sendRequestBodyEncoded(stream, ByteBuffer.wrap(new byte[] {1, 2, 3}), false);
        assertEquals(0, n);
        assertEquals(1, r.failures.size());
    }

    @Test
    public void nullEncodedChunkIsTreatedAsEmptyAndEndFinishesTheStream() throws Exception {
        handler.setEncodeRequestBodyContentCoding(true);
        Recorder r = new Recorder();
        HttpStream stream = new HttpStream(handler, "POST", "/up", r);
        stream.header("Content-Encoding", "gzip");
        stream.endHeaders();
        int n = handler.sendRequestBodyEncoded(stream, null, false);
        assertEquals(0, n);
        n = handler.sendRequestBodyEncoded(stream, null, true);
        assertEquals(0, n);
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        byte[] decoded = gunzip(dechunk(wire));
        assertEquals(0, decoded.length);
    }

    @Test
    public void encodedBodyWithoutACodingIsNotWritten() {
        HttpStream stream = new HttpStream(handler, "POST", "/up");
        int n = handler.sendRequestBodyEncoded(stream, ByteBuffer.wrap(new byte[] {1}), false);
        assertEquals(0, n);
    }

    // ── diagnostics ──

    @Test
    public void debugSwitchTracesButDoesNotChangeBehaviour() {
        System.setProperty("gumdrop.http.debug", "true");
        newHandler(false);
        Recorder fixed = get();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc");
        assertEquals("abc", new String(fixed.body.toByteArray(), StandardCharsets.US_ASCII));
        Recorder chunked = get();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nxyz\r\n0\r\n\r\n");
        assertEquals("xyz", new String(chunked.body.toByteArray(), StandardCharsets.US_ASCII));
        Recorder none = new Recorder();
        handler.head("/h", none).endMessage();
        feed("HTTP/1.1 200 OK\r\n\r\n");
        assertEquals(1, none.closeCalls);
        handler.credentials("user", "pass");
        Recorder auth = get();
        feed("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"r\"\r\nContent-Length: 2\r\n\r\nno");
        feed("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        assertEquals(1, auth.okCalls);
    }

    @Test
    public void debugSwitchTracesHttp2PriorKnowledgeConnect() {
        System.setProperty("gumdrop.http.debug", "true");
        HttpClientProtocolHandler h = new HttpClientProtocolHandler(conn, "example.com", 80, false);
        h.setH2WithPriorKnowledge(true);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        ep.setSelectorLoop(new InlineSelectorLoop());
        h.connected(ep);
        assertEquals(HttpVersion.HTTP_2_0, h.getVersion());
    }

    @Test
    public void integrationTraceAndFineLoggingDoNotAffectShutdown() {
        Logger logger = Logger.getLogger(HttpClientProtocolHandler.class.getName());
        Level saved = logger.getLevel();
        logger.setLevel(Level.FINE);
        System.setProperty("gumdrop.integration.log.level", "FINE");
        try {
            newHandler(false);
            handler.setIdleTimeoutMs(5000);
            handler.connected(endpoint);
            endpoint.fireTimers();
            assertEquals(1, endpoint.getCloseCount());
            handler.error(new IOException("trace me"));
            handler.disconnected();
            assertEquals(1, conn.disconnected);
            assertEquals(1, conn.errors.size());
        } finally {
            logger.setLevel(saved);
        }
    }

    @Test
    public void integrationTraceWithAnInvalidLevelIsDisabled() {
        System.setProperty("gumdrop.integration.log.level", "not-a-level");
        handler.close();
        assertEquals(1, endpoint.getCloseCount());
    }
}
