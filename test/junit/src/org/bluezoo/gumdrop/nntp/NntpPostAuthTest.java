/*
 * NntpPostAuthTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubEndpoint;
import org.bluezoo.gumdrop.nntp.NntpProtocolHandlerTest.StubFactory;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * POST, STARTTLS and AUTHINFO (RFC 3977 section 6.3, RFC 4642, RFC 4643).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpPostAuthTest {

    private static final String ARTICLE =
            "From: a@example.com\r\nSubject: Hi\r\nNewsgroups: test.group,no.such\r\n"
            + "\r\nHello\r\n.dot\r\nlast\r\n";

    private static final byte[] SALT = "saltsaltsalt".getBytes(StandardCharsets.UTF_8);
    private static final Realm.ScramCredentials CREDENTIALS =
            Realm.ScramCredentials.derive("secret", SALT, 4096, "SHA-256");

    private static class TestRealm implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.LOGIN, SaslMechanism.SCRAM_SHA_256,
                    SaslMechanism.EXTERNAL);
        }

        @Override
        public void getScramCredentials(String user, RealmCallback<Realm.ScramCredentials> callback) {
            callback.completed(user.equals("alice") ? CREDENTIALS : null);
        }

        @Override
        public void authenticateCertificate(X509Certificate certificate,
                RealmCallback<Realm.CertificateAuthenticationResult> callback) {
            callback.completed(Realm.CertificateAuthenticationResult.success("alice"));
        }

        @Override
        public void passwordMatch(String user, String password, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.valueOf(user.equals("alice") && password.equals("secret")));
        }

        @Override
        public void getDigestHA1(String username, String realmName, RealmCallback<String> callback) {
            callback.completed(null);
        }

        @Override
        public void isUserInRole(String username, String role, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }
    }

    private static final class Listener extends NntpListener {
        boolean tls;

        @Override
        protected boolean isSTARTTLSAvailable() {
            return tls;
        }
    }

    private StubFactory factory;
    private Listener listener;
    private StubEndpoint endpoint;
    private NntpProtocolHandler handler;

    @Before
    public void setUp() {
        factory = new StubFactory();
        factory.add("test.group", "Subject: One\r\nFrom: x@example.com\r\nMessage-ID: <1@x>\r\n\r\nbody\r\n");
        listener = new Listener();
        listener.mailboxFactory(factory);
    }

    private void connect() {
        handler = new NntpProtocolHandler(listener);
        endpoint = new StubEndpoint();
        handler.connected(endpoint);
        take();
    }

    private void feed(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
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

    // ── POST ──

    @Test
    public void testPostRefusedWhenNotAllowed() {
        connect();
        assertEquals("440 Posting not permitted\r\n", exchange("POST"));
    }

    @Test
    public void testPostStoresTheArticleInEachExistingGroup() {
        listener.postingAllowed(true);
        connect();
        assertEquals("340 Send article to be posted\r\n", exchange("POST"));
        feed(ARTICLE.replace("\r\n.dot", "\r\n..dot") + ".\r\n");
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(2, factory.groups.get("test.group").size());
        assertEquals(ARTICLE, factory.groups.get("test.group").get(1));
        // and the connection carries on
        assertEquals("211 2 1 2 test.group\r\n", exchange("GROUP test.group"));
    }

    @Test
    public void testPostOneByteAtATime() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        String wire = ARTICLE.replace("\r\n.dot", "\r\n..dot") + ".\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("240 Article received OK\r\n", take());
        assertEquals(ARTICLE, factory.groups.get("test.group").get(1));
    }

    @Test
    public void testCommandPipelinedAfterAnArticleIsRun() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed(ARTICLE + ".\r\nDATE\r\n");
        String r = take();
        assertTrue(r, r.startsWith("240 Article received OK\r\n111 "));
    }

    @Test
    public void testPostWithoutRequiredHeadersIsNotPosted() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("Subject: no from\r\nNewsgroups: test.group\r\n\r\nx\r\n.\r\n");
        assertTrue(take().startsWith("441 "));
        assertEquals(1, factory.groups.get("test.group").size());
    }

    @Test
    public void testPostToNoExistingGroupIsNotPosted() {
        listener.postingAllowed(true);
        connect();
        exchange("POST");
        feed("From: a@x\r\nSubject: s\r\nNewsgroups: no.such\r\n\r\nx\r\n.\r\n");
        assertEquals("441 None of the newsgroups exist\r\n", take());
    }

    @Test
    public void testCapabilitiesAdvertisePost() {
        listener.postingAllowed(true);
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nPOST\r\n"));
    }

    // ── STARTTLS ──

    @Test
    public void testStartTlsIsOnlyAdvertisedWhenAvailable() {
        connect();
        assertFalse(exchange("CAPABILITIES").contains("STARTTLS"));
        assertEquals("580 TLS not available\r\n", exchange("STARTTLS"));
        listener.tls = true;
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nSTARTTLS\r\n"));
    }

    @Test
    public void testStartTlsUpgradesAndForgetsTheGroup() {
        listener.tls = true;
        connect();
        exchange("GROUP test.group");
        assertEquals("382 Continue with TLS negotiation\r\n", exchange("STARTTLS"));
        assertEquals("412 No newsgroup selected\r\n", exchange("STAT"));
    }

    @Test
    public void testStartTlsWhenAlreadySecure() {
        listener.tls = true;
        connect();
        endpoint.secure = true;
        assertEquals("502 Already using TLS\r\n", exchange("STARTTLS"));
        assertFalse(exchange("CAPABILITIES").contains("STARTTLS"));
    }

    // ── AUTHINFO ──

    @Test
    public void testAuthinfoNeedsARealm() {
        connect();
        assertEquals("500 Unknown command\r\n", exchange("AUTHINFO USER alice"));
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO"));
    }

    @Test
    public void testAuthinfoUserPass() {
        listener.realm(new TestRealm());
        connect();
        assertTrue(exchange("CAPABILITIES").contains("\r\nAUTHINFO USER SASL\r\n"));
        assertEquals("482 AUTHINFO USER required first\r\n", exchange("AUTHINFO PASS secret"));
        assertEquals("381 Password required\r\n", exchange("AUTHINFO USER alice"));
        assertEquals("281 Authentication accepted\r\n", exchange("AUTHINFO PASS secret"));
        assertEquals("502 Already authenticated\r\n", exchange("AUTHINFO USER alice"));
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO"));
    }

    @Test
    public void testAuthinfoWrongPassword() {
        listener.realm(new TestRealm());
        connect();
        exchange("AUTHINFO USER alice");
        assertEquals("481 Authentication failed\r\n", exchange("AUTHINFO PASS wrong"));
        // the user must be given again
        assertEquals("482 AUTHINFO USER required first\r\n", exchange("AUTHINFO PASS secret"));
    }

    @Test
    public void testStoreIsOpenedForTheAuthenticatedUser() {
        listener.realm(new TestRealm());
        connect();
        exchange("GROUP test.group");
        assertEquals("news", factory.lastStore.user);
        exchange("AUTHINFO USER alice");
        exchange("AUTHINFO PASS secret");
        assertTrue(factory.lastStore.closed);
        assertEquals("412 No newsgroup selected\r\n", exchange("STAT"));
        exchange("GROUP test.group");
        assertEquals("alice", factory.lastStore.user);
    }

    @Test
    public void testPasswordsRefusedInTheClearWhereTlsCanBeHad() {
        listener.realm(new TestRealm());
        listener.tls = true;
        connect();
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO USER"));
        assertEquals("483 Encryption required\r\n", exchange("AUTHINFO USER alice"));
        endpoint.secure = true;
        assertEquals("381 Password required\r\n", exchange("AUTHINFO USER alice"));
    }

    @Test
    public void testAuthenticationRequired() {
        listener.realm(new TestRealm());
        listener.authenticationRequired(true);
        connect();
        assertEquals("480 Authentication required\r\n", exchange("GROUP test.group"));
        assertEquals("480 Authentication required\r\n", exchange("LIST"));
        assertTrue(exchange("CAPABILITIES").startsWith("101 "));
        exchange("AUTHINFO USER alice");
        exchange("AUTHINFO PASS secret");
        assertEquals("211 1 1 1 test.group\r\n", exchange("GROUP test.group"));
    }

    // ── AUTHINFO SASL ──

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(String user, String password) {
        return b64("\0" + user + "\0" + password);
    }

    @Test
    public void testSaslMechanismsAreAdvertisedAccordingToTls() {
        listener.realm(new TestRealm());
        connect();
        String clear = exchange("CAPABILITIES");
        assertTrue(clear, clear.contains("\r\nAUTHINFO USER SASL\r\n"));
        assertTrue(clear, clear.contains("\r\nSASL SCRAM-SHA-256\r\n"));
        assertFalse(clear, clear.contains("EXTERNAL"));
        endpoint.secure = true;
        assertTrue(exchange("CAPABILITIES").contains("\r\nSASL PLAIN LOGIN SCRAM-SHA-256 EXTERNAL\r\n"));
    }

    @Test
    public void testSaslPlainWithInitialResponse() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("281 Authentication accepted\r\n",
                exchange("AUTHINFO SASL PLAIN " + plain("alice", "secret")));
        assertEquals("502 Already authenticated\r\n", exchange("AUTHINFO SASL PLAIN " + plain("alice", "secret")));
    }

    @Test
    public void testSaslPlainWithChallenge() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("383 =\r\n", exchange("AUTHINFO SASL PLAIN"));
        assertEquals("281 Authentication accepted\r\n", exchange(plain("alice", "secret")));
    }

    @Test
    public void testSaslPlainWrongPassword() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("481 Authentication failed\r\n",
                exchange("AUTHINFO SASL PLAIN " + plain("alice", "wrong")));
    }

    @Test
    public void testSaslPlainRefusedInTheClear() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("483 Encryption required\r\n", exchange("AUTHINFO SASL PLAIN " + plain("alice", "secret")));
    }

    @Test
    public void testSaslUnknownMechanism() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("504 Mechanism not supported\r\n", exchange("AUTHINFO SASL FROBNICATE"));
        assertEquals("501 SASL mechanism required\r\n", exchange("AUTHINFO SASL"));
    }

    @Test
    public void testSaslCancelAndDataIsNotACommand() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        exchange("AUTHINFO SASL PLAIN");
        assertEquals("481 Authentication exchange cancelled\r\n", exchange("*"));
        // commands are commands again
        assertTrue(exchange("DATE").startsWith("111 "));
        // while an exchange is open a line that looks like a command is data
        exchange("AUTHINFO SASL PLAIN");
        String r = exchange("QUIT");
        assertTrue(r, r.startsWith("481 "));
        assertTrue(endpoint.open);
    }

    @Test
    public void testSaslScram() throws Exception {
        listener.realm(new TestRealm());
        connect();
        String clientNonce = "rOprNGfwEbeRWgbNEkqO";
        String bare = "n=alice,r=" + clientNonce;
        String r = exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,," + bare));
        assertTrue(r, r.startsWith("383 "));
        String serverFirst = new String(Base64.getDecoder().decode(r.substring(4).trim()), StandardCharsets.UTF_8);
        String nonce = null;
        int iterations = 0;
        String[] attrs = serverFirst.split(",");
        for (int i = 0; i < attrs.length; i++) {
            if (attrs[i].startsWith("r=")) {
                nonce = attrs[i].substring(2);
            } else if (attrs[i].startsWith("i=")) {
                iterations = Integer.parseInt(attrs[i].substring(2));
            }
        }
        assertTrue(nonce.startsWith(clientNonce));
        byte[] salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec("secret".toCharArray(), SALT, iterations, 256)).getEncoded();
        byte[] clientKey = hmac(salted, "Client Key");
        byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
        String finalNoProof = "c=biws,r=" + nonce;
        String authMessage = bare + "," + serverFirst + "," + finalNoProof;
        byte[] signature = hmac(storedKey, authMessage);
        byte[] proof = new byte[signature.length];
        for (int i = 0; i < proof.length; i++) {
            proof[i] = (byte) (clientKey[i] ^ signature[i]);
        }
        String clientFinal = finalNoProof + ",p=" + Base64.getEncoder().encodeToString(proof);
        r = exchange(b64(clientFinal));
        assertTrue(r, r.startsWith("283 "));
        byte[] serverSignature = hmac(hmac(salted, "Server Key"), authMessage);
        assertEquals("v=" + Base64.getEncoder().encodeToString(serverSignature),
                new String(Base64.getDecoder().decode(r.substring(4).trim()), StandardCharsets.UTF_8));
        assertFalse(exchange("CAPABILITIES").contains("AUTHINFO"));
    }

    @Test
    public void testSaslScramUnknownUserAndBadProof() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("481 Authentication failed\r\n",
                exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=nobody,r=abc")));
        exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=alice,r=abc"));
        assertEquals("481 Authentication failed\r\n", exchange(b64("c=biws,r=abcX,p=AAAA")));
        assertEquals("501 Invalid SCRAM message\r\n",
                exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("y,,n=alice,r=abc")));
    }

    @Test
    public void testSaslFailureThenSuccess() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("481 Authentication failed\r\n",
                exchange("AUTHINFO SASL PLAIN " + plain("alice", "wrong")));
        assertEquals("281 Authentication accepted\r\n",
                exchange("AUTHINFO SASL PLAIN " + plain("alice", "secret")));
    }

    @Test
    public void testSaslLoginAsksForUserThenPassword() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("383 " + b64("Username:") + "\r\n", exchange("AUTHINFO SASL LOGIN"));
        assertEquals("383 " + b64("Password:") + "\r\n", exchange(b64("alice")));
        assertEquals("281 Authentication accepted\r\n", exchange(b64("secret")));
    }

    @Test
    public void testSaslLoginWithInitialResponseAndWrongPassword() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("383 " + b64("Password:") + "\r\n", exchange("AUTHINFO SASL LOGIN " + b64("alice")));
        assertEquals("481 Authentication failed\r\n", exchange(b64("wrong")));
    }

    @Test
    public void testSaslLoginRefusedInTheClear() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("483 Encryption required\r\n", exchange("AUTHINFO SASL LOGIN"));
    }

    /** A secure endpoint that presents a client certificate. */
    private void secureWithCertificate() throws Exception {
        final Certificate[] certs = {TestCertificates.ec256().getCertificate()};
        final SecurityInfo info = (SecurityInfo) Proxy.newProxyInstance(
                SecurityInfo.class.getClassLoader(), new Class<?>[] {SecurityInfo.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return method.getName().equals("getPeerCertificates") ? certs : null;
                    }
                });
        endpoint = new StubEndpoint() {
            @Override
            public SecurityInfo getSecurityInfo() {
                return info;
            }
        };
        endpoint.secure = true;
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        handler.securityEstablished(info); // implicit TLS: the greeting follows the handshake
        take();
    }

    @Test
    public void testSaslExternalUsesTheClientCertificate() throws Exception {
        listener.realm(new TestRealm());
        secureWithCertificate();
        assertEquals("383 =\r\n", exchange("AUTHINFO SASL EXTERNAL"));
        assertEquals("281 Authentication accepted\r\n", exchange("="));
        assertEquals("211 1 1 1 test.group\r\n", exchange("GROUP test.group"));
        assertEquals("alice", factory.lastStore.user);
    }

    @Test
    public void testSaslExternalWithInitialResponse() throws Exception {
        listener.realm(new TestRealm());
        secureWithCertificate();
        assertEquals("281 Authentication accepted\r\n", exchange("AUTHINFO SASL EXTERNAL ="));
    }

    @Test
    public void testSaslExternalWithoutACertificateFails() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true; // secure, but the stub presents no certificate
        assertEquals("481 Authentication failed\r\n", exchange("AUTHINFO SASL EXTERNAL ="));
    }

    @Test
    public void testSaslExternalNeedsTls() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("483 Encryption required\r\n", exchange("AUTHINFO SASL EXTERNAL"));
    }

    // ── edges of AUTHINFO ──

    @Test
    public void testAuthinfoSyntax() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("501 User name required\r\n", exchange("AUTHINFO USER"));
        exchange("AUTHINFO USER alice");
        assertEquals("501 Password required\r\n", exchange("AUTHINFO PASS"));
        assertEquals("501 Unknown AUTHINFO keyword\r\n", exchange("AUTHINFO FROB"));
        assertEquals("501 Unknown AUTHINFO keyword\r\n", exchange("AUTHINFO"));
    }

    @Test
    public void testLockoutAfterTooManyFailures() {
        listener.realm(new TestRealm());
        listener.maxAuthFailures(1);
        connect();
        exchange("AUTHINFO USER alice");
        assertEquals("481 Authentication failed\r\n", exchange("AUTHINFO PASS wrong"));
        exchange("AUTHINFO USER alice");
        assertEquals("481 Too many failed attempts; try again later\r\n", exchange("AUTHINFO PASS secret"));
        endpoint.secure = true;
        assertEquals("481 Too many failed attempts; try again later\r\n",
                exchange("AUTHINFO SASL PLAIN " + plain("alice", "secret")));
    }

    /** A realm whose lookups fail. */
    private static final class BrokenRealm implements Realm {
        final Throwable failure;

        BrokenRealm(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return EnumSet.of(SaslMechanism.PLAIN, SaslMechanism.SCRAM_SHA_256, SaslMechanism.EXTERNAL);
        }

        @Override
        public void passwordMatch(String user, String password, RealmCallback<Boolean> callback) {
            callback.failed(failure);
        }

        @Override
        public void getScramCredentials(String user, RealmCallback<Realm.ScramCredentials> callback) {
            callback.failed(failure);
        }

        @Override
        public void authenticateCertificate(X509Certificate certificate,
                RealmCallback<Realm.CertificateAuthenticationResult> callback) {
            callback.failed(failure);
        }

        @Override
        public void getDigestHA1(String username, String realmName, RealmCallback<String> callback) {
            callback.completed(null);
        }

        @Override
        public void isUserInRole(String username, String role, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }
    }

    @Test
    public void testRealmThatFailsOnPassword() {
        listener.realm(new BrokenRealm(new RuntimeException("directory down")));
        connect();
        exchange("AUTHINFO USER alice");
        assertEquals("403 Internal fault\r\n", exchange("AUTHINFO PASS secret"));
        // the connection carries on
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testRealmThatFailsOnScram() {
        listener.realm(new BrokenRealm(new RuntimeException("directory down")));
        connect();
        assertEquals("403 Internal fault\r\n",
                exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=alice,r=abc")));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testRealmWithoutScram() {
        listener.realm(new BrokenRealm(new UnsupportedOperationException("no SCRAM")));
        connect();
        assertEquals("504 Mechanism not supported\r\n",
                exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=alice,r=abc")));
    }

    @Test
    public void testRealmThatFailsOnTheFinalScramStep() {
        final int[] calls = {0};
        listener.realm(new TestRealm() {
            @Override
            public void getScramCredentials(String user, RealmCallback<Realm.ScramCredentials> callback) {
                if (++calls[0] == 1) {
                    super.getScramCredentials(user, callback);
                } else {
                    callback.failed(new RuntimeException("went away"));
                }
            }
        });
        connect();
        assertTrue(exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=alice,r=abc")).startsWith("383 "));
        assertEquals("403 Internal fault\r\n", exchange(b64("c=biws,r=abc,p=AAAA")));
    }

    @Test
    public void testRealmThatFailsOnTheCertificate() throws Exception {
        listener.realm(new BrokenRealm(new RuntimeException("directory down")));
        secureWithCertificate();
        assertEquals("403 Internal fault\r\n", exchange("AUTHINFO SASL EXTERNAL ="));
    }

    @Test
    public void testSaslDataThatIsNotBase64() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("501 Invalid base64 data\r\n", exchange("AUTHINFO SASL PLAIN !!!"));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testScramFirstMessageWithoutANonce() {
        listener.realm(new TestRealm());
        connect();
        assertEquals("501 Invalid SCRAM message\r\n",
                exchange("AUTHINFO SASL SCRAM-SHA-256 " + b64("n,,n=alice")));
    }

    @Test
    public void testOverlongSaslLineEndsTheExchange() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        exchange("AUTHINFO SASL PLAIN");
        StringBuilder sb = new StringBuilder("x ");
        for (int i = 0; i < 600; i++) {
            sb.append('y');
        }
        assertTrue(exchange(sb.toString()).startsWith("501 "));
        assertTrue(exchange("DATE").startsWith("111 "));
    }

    @Test
    public void testPlainWithoutThreeParts() {
        listener.realm(new TestRealm());
        connect();
        endpoint.secure = true;
        assertEquals("481 Authentication failed\r\n", exchange("AUTHINFO SASL PLAIN " + b64("no separators")));
    }

    @Test
    public void testStartTlsThatCannotBeStarted() {
        listener.tls = true;
        endpoint = new StubEndpoint() {
            @Override
            public void startTLS() throws java.io.IOException {
                throw new java.io.IOException("no handshake");
            }
        };
        handler = new NntpProtocolHandler(listener);
        handler.connected(endpoint);
        take();
        assertEquals("382 Continue with TLS negotiation\r\n", exchange("STARTTLS"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testPostRefusedByTheApplicationAfterAccepting() {
        listener.postingAllowed(true);
        listener.sessionProvider(new org.bluezoo.gumdrop.nntp.server.NntpServerSessionProvider() {
            @Override
            public org.bluezoo.gumdrop.nntp.server.ClientConnected openSession(org.bluezoo.gumdrop.TcpListener l) {
                return new org.bluezoo.gumdrop.nntp.server.DefaultNntpHandler("hi", true) {
                    @Override
                    public void post(org.bluezoo.gumdrop.nntp.server.PostState state,
                            org.bluezoo.gumdrop.mailbox.MailboxStore store) {
                        state.refuse(this);
                    }
                };
            }
        });
        connect();
        assertEquals("440 Posting not permitted\r\n", exchange("POST"));
    }

    private static byte[] hmac(byte[] key, String data) throws RuntimeException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }
}
