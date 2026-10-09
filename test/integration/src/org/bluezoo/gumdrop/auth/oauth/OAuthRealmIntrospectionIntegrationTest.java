/*
 * OAuthRealmIntrospectionIntegrationTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.auth.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCalls;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Token introspection (RFC 7662) end to end against a scripted
 * authorization server on a loopback socket owned by the test. The
 * server answers each connection with the next canned reply (or closes
 * the connection without replying), so every outcome of the streaming
 * introspection response handler is reached without timing.
 *
 * <p>Integration test: the realm's HTTP client builds its own transport, so the
 * scripted authorization server must be a real loopback socket served by a
 * thread, with a booted runtime providing the loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuthRealmIntrospectionIntegrationTest {

    private static final String CLOSE = "<close>";

    private Gumdrop gumdrop;
    private ClientEndpoint keeper;
    private ServerSocket server;
    private Thread acceptor;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<String>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void setUp() throws Exception {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1).drainTimeoutMs(0));
        // A registered client keeps the runtime (and its loop) alive between requests
        keeper = new ClientEndpoint(new TcpTransportFactory(), gumdrop.nextWorkerLoop(), "localhost", 1);
        gumdrop.addClient(keeper);
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "oauth-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        acceptor.join();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private void serve() {
        while (true) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;
            }
            try {
                handle(s);
            } catch (IOException e) {
                // client went away; next connection
            } catch (InterruptedException e) {
                return;
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing quietly
                }
            }
        }
    }

    private void handle(Socket s) throws IOException, InterruptedException {
        s.setSoTimeout(10000);
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            head.write(b);
            if ((b == '\r' && (state == 0 || state == 2))
                    || (b == '\n' && (state == 1 || state == 3))) {
                state++;
            } else if (b == '\r') {
                state = 1;
            } else {
                state = 0;
            }
        }
        String headers = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        int length = 0;
        String[] lines = headers.split("\r\n");
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(lines[i].substring(15).trim());
            }
        }
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) {
                return;
            }
            off += n;
        }
        if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
            body = readChunked(in);
        }
        requests.add(headers + new String(body, StandardCharsets.UTF_8));
        String reply = replies.take();
        if (CLOSE.equals(reply)) {
            return;
        }
        OutputStream out = s.getOutputStream();
        out.write(reply.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0 || b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) {
                readLine(in);
                break;
            }
            for (int i = 0; i < size; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("short chunk");
                }
                body.write(b);
            }
            readLine(in);
        }
        return body.toByteArray();
    }

    private static String http(int status, String reason, String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n" + json;
    }

    private OAuthRealm realm(boolean cache) {
        Properties config = new Properties();
        config.setProperty("oauth.authorization.server.url",
                "http://127.0.0.1:" + server.getLocalPort());
        config.setProperty("oauth.client.id", "cid");
        config.setProperty("oauth.client.secret", "sec");
        config.setProperty("oauth.http.timeout", "20000");
        config.setProperty("oauth.cache.enabled", Boolean.toString(cache));
        config.setProperty("oauth.scope.mapping.reader", "read");
        OAuthRealm unbound = new OAuthRealm(config);
        Realm bound = unbound.forSelectorLoop(gumdrop.nextWorkerLoop());
        return (OAuthRealm) bound;
    }

    @Test
    public void activeTokenYieldsUserScopesAndExpiry() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK",
                "{\"active\":true,\"username\":\"alice\",\"scope\":\"  read   write \","
                + "\"exp\":4102444800,\"client_id\":\"x\",\"nested\":{\"exp\":1}}"));
        Realm.TokenValidationResult r = RealmCalls.validateOAuthToken(realm, "tok+en");
        assertNotNull(r);
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertEquals(2, r.scopes.length);
        assertEquals("read", r.scopes[0]);
        assertEquals("write", r.scopes[1]);
        assertEquals(4102444800L, r.expirationTime);
        assertTrue(RealmCalls.isUserInRole(realm, "alice", "reader"));
        assertFalse(RealmCalls.isUserInRole(realm, "alice", "unmapped"));
        assertEquals(1, requests.size());
        String req = requests.get(0);
        assertTrue(req, req.startsWith("POST /oauth/introspect"));
        assertTrue(req, req.contains("token=tok%2Ben"));
        assertTrue(req, req.contains("Authorization: Basic "));
    }

    @Test
    public void subjectIsUsedWhenUsernameMissing() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK", "{\"active\":true,\"sub\":\"subject-1\",\"scope\":\"\"}"));
        Realm.TokenValidationResult r = RealmCalls.validateOAuthToken(realm, "t");
        assertTrue(r.valid);
        assertEquals("subject-1", r.username);
        assertEquals(0, r.scopes.length);
    }

    @Test
    public void activeTokenWithoutAnyIdentityIsRejected() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK", "{\"active\":true,\"username\":\"\"}"));
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
    }

    @Test
    public void nestedMembersCannotOverrideTopLevelFields() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK",
                "{\"active\":false,\"ext\":{\"active\":true,\"username\":\"mallory\"},"
                + "\"list\":[{\"active\":true}]}"));
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
        replies.add(http(200, "OK",
                "{\"active\":true,\"username\":\"alice\",\"ext\":{\"username\":\"mallory\","
                + "\"scope\":\"admin\"},\"scope\":\"read\"}"));
        Realm.TokenValidationResult r = RealmCalls.validateOAuthToken(realm, "t2");
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertEquals(1, r.scopes.length);
        assertEquals("read", r.scopes[0]);
    }

    @Test
    public void inactiveTokenIsRejected() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK", "{\"active\":false}"));
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
    }

    @Test
    public void errorStatusIsRejected() {
        OAuthRealm realm = realm(false);
        replies.add(http(401, "Unauthorized", "{\"active\":true,\"username\":\"mallory\"}"));
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
    }

    @Test
    public void malformedJsonIsRejected() {
        OAuthRealm realm = realm(false);
        replies.add(http(200, "OK", "{\"active\": tru"));
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
    }

    @Test
    public void connectionClosedWithoutReplyIsRejected() {
        OAuthRealm realm = realm(false);
        replies.add(CLOSE);
        assertFalse(RealmCalls.validateOAuthToken(realm, "t").valid);
    }

    @Test
    public void cachedResultAvoidsSecondRequest() {
        OAuthRealm realm = realm(true);
        replies.add(http(200, "OK", "{\"active\":true,\"username\":\"bob\",\"scope\":\"read\"}"));
        Realm.TokenValidationResult first = RealmCalls.validateOAuthToken(realm, "same");
        Realm.TokenValidationResult second = RealmCalls.validateOAuthToken(realm, "same");
        assertTrue(first.valid);
        assertTrue(second.valid);
        assertEquals(1, requests.size());
    }

    @Test
    public void blankTokenNeverContactsServer() {
        OAuthRealm realm = realm(false);
        assertFalse(RealmCalls.validateOAuthToken(realm, null).valid);
        assertFalse(RealmCalls.validateOAuthToken(realm, "   ").valid);
        assertFalse(RealmCalls.validateBearerToken(realm, "").valid);
        assertTrue(requests.isEmpty());
    }

    @Test
    public void unboundRealmCannotIntrospect() {
        Properties config = new Properties();
        config.setProperty("oauth.authorization.server.url", "http://127.0.0.1:1");
        config.setProperty("oauth.client.id", "cid");
        config.setProperty("oauth.client.secret", "sec");
        OAuthRealm unbound = new OAuthRealm(config);
        assertFalse(RealmCalls.validateOAuthToken(unbound, "t").valid);
    }
}
