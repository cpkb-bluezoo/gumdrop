/*
 * POP3AuthFlowsTest.java
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


package org.bluezoo.gumdrop.pop3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import static org.junit.Assert.*;

/**
 * Drives the less common POP3 authentication flows of
 * {@link Pop3ProtocolHandler}: APOP, CRAM-MD5, DIGEST-MD5, OAUTHBEARER,
 * SCRAM and EXTERNAL failure paths, login delay and mailbox-open failure.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3AuthFlowsTest {

    private Pop3ProtocolHandler handler;
    private TimerEndpoint endpoint;
    private POP3ProtocolHandlerTest.TestPOP3Listener listener;
    private CapableRealm realm;
    private POP3ProtocolHandlerTest.StubMailboxFactory factory;

    @Before
    public void setUp() {
        realm = new CapableRealm();
        factory = new POP3ProtocolHandlerTest.StubMailboxFactory();
        listener = new POP3ProtocolHandlerTest.TestPOP3Listener();
        listener.realm(realm);
        listener.mailboxFactory(factory);
        listener.enableAPOP(false);
        endpoint = new TimerEndpoint();
    }

    private void connect() {
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
    }

    private void send(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private String last() {
        List<String> all = endpoint.getResponses();
        assertFalse(all.isEmpty());
        return all.get(all.size() - 1);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    private String apopTimestamp() {
        String greeting = endpoint.getResponses().get(0);
        int open = greeting.indexOf('<');
        int close = greeting.indexOf('>');
        return greeting.substring(open, close + 1);
    }

    // ── APOP ──

    @Test
    public void testApopSuccess() {
        listener.enableAPOP(true);
        connect();
        String ts = apopTimestamp();
        String digest = SaslUtils.md5Hex((ts + "testpass").getBytes(StandardCharsets.US_ASCII));
        send("APOP testuser " + digest);
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void testApopWrongDigestAndUnsupportedRealm() {
        listener.enableAPOP(true);
        connect();
        send("APOP testuser 00000000000000000000000000000000");
        assertTrue(last().startsWith("-ERR"));
        send("APOP nobody 00000000000000000000000000000000");
        assertTrue(last().startsWith("-ERR"));

        realm.apop = false;
        send("APOP testuser abc");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testApopWithoutRealmClosesConnection() {
        listener.enableAPOP(true);
        listener.realm(null);
        connect();
        send("APOP testuser abc");
        assertTrue(last().startsWith("-ERR"));
        assertFalse(endpoint.isOpen());
    }

    // ── USER / PASS edge cases ──

    @Test
    public void testPassWithoutMailboxFactory() {
        listener.mailboxFactory(null);
        connect();
        send("USER testuser");
        send("PASS testpass");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testPassWhenMailboxOpenFails() {
        listener.mailboxFactory(new FailingFactory());
        connect();
        send("USER testuser");
        send("PASS testpass");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testLoginDelayDefersSecondAttemptUntilTimerFires() {
        listener.loginDelayMs(Long.MAX_VALUE / 4);
        connect();
        send("USER testuser");
        send("PASS wrong");
        assertTrue(last().startsWith("-ERR"));
        int before = endpoint.getResponses().size();
        send("USER testuser");
        int afterUser = endpoint.getResponses().size();
        send("PASS testpass");
        assertEquals(afterUser, endpoint.getResponses().size());
        assertTrue(afterUser > before);
        assertNotNull(endpoint.timerTask);
        endpoint.timerTask.run();
        assertTrue(last().startsWith("+OK"));
    }

    @Test
    public void testStlsFailureClosesConnection() {
        listener.starttlsAvailable = true;
        endpoint.failStartTls = true;
        connect();
        send("STLS");
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void testAuthListWithoutRealm() {
        listener.realm(null);
        connect();
        send("AUTH");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testAuthListHidesTlsOnlyMechanismsOnPlaintext() {
        realm.supportedMechanisms.add(SaslMechanism.PLAIN);
        realm.supportedMechanisms.add(SaslMechanism.EXTERNAL);
        realm.supportedMechanisms.add(SaslMechanism.CRAM_MD5);
        connect();
        send("AUTH");
        StringBuilder all = new StringBuilder();
        for (String line : endpoint.getResponses()) {
            all.append(line).append('\n');
        }
        String text = all.toString();
        assertTrue(text.contains("CRAM-MD5"));
        assertFalse(text.contains("EXTERNAL"));
    }

    // ── CRAM-MD5 ──

    @Test
    public void testCramMd5Success() {
        connect();
        send("AUTH CRAM-MD5");
        String challengeLine = last();
        assertTrue(challengeLine.startsWith("+ "));
        String challenge = SaslUtils.decodeBase64ToString(challengeLine.substring(2));
        String digest = SaslUtils.computeCramMD5Response("testpass", challenge);
        send(b64("testuser " + digest));
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void testCramMd5FailureModes() {
        connect();
        send("AUTH CRAM-MD5");
        send(b64("testuser deadbeef"));
        assertTrue(last().startsWith("-ERR"));

        send("AUTH CRAM-MD5");
        send(b64("nospacehere"));
        assertTrue(last().startsWith("-ERR"));

        send("AUTH CRAM-MD5");
        send("!!!not-base64");
        assertTrue(last().startsWith("-ERR"));

        realm.cram = false;
        send("AUTH CRAM-MD5");
        send(b64("testuser deadbeef"));
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testCramMd5WithoutRealm() {
        listener.realm(null);
        connect();
        send("AUTH CRAM-MD5");
        assertTrue(last().startsWith("-ERR"));
    }

    // ── DIGEST-MD5 ──

    @Test
    public void testDigestMd5Failures() {
        connect();
        send("AUTH DIGEST-MD5 abc");
        assertTrue(last().startsWith("-ERR"));

        send("AUTH DIGEST-MD5");
        assertTrue(last().startsWith("+ "));
        send("!!!not-base64");
        assertTrue(last().startsWith("-ERR"));

        send("AUTH DIGEST-MD5");
        send(b64("realm=\"x\",nonce=\"y\""));
        assertTrue(last().startsWith("-ERR"));

        send("AUTH DIGEST-MD5");
        send(b64("username=\"testuser\",realm=\"localhost\",nonce=\"zzz\",cnonce=\"c\","
                + "nc=00000001,qop=auth,digest-uri=\"pop/localhost\",response=00,maxbuf=4096"));
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testDigestMd5WithoutRealm() {
        listener.realm(null);
        connect();
        send("AUTH DIGEST-MD5");
        assertTrue(last().startsWith("-ERR"));
    }

    // ── OAUTHBEARER ──

    @Test
    public void testOAuthBearerSuccess() {
        connect();
        send("AUTH OAUTHBEARER");
        assertTrue(last().startsWith("+ "));
        send(b64("n,a=testuser,\u0001auth=Bearer goodtoken\u0001\u0001"));
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void testOAuthBearerFailures() {
        connect();
        send("AUTH OAUTHBEARER " + b64("n,a=testuser,\u0001auth=Bearer badtoken\u0001\u0001"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH OAUTHBEARER " + b64("n,a=other,\u0001auth=Bearer goodtoken\u0001\u0001"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH OAUTHBEARER " + b64("garbage"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH OAUTHBEARER !!!");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testOAuthBearerFallsBackToOAuthValidation() {
        realm.bearerReturnsNull = true;
        connect();
        send("AUTH OAUTHBEARER " + b64("n,a=testuser,\u0001auth=Bearer goodtoken\u0001\u0001"));
        assertTrue(last(), last().startsWith("+OK"));
    }

    // ── SCRAM ──

    @Test
    public void testScramRejectsMalformedClientFirst() {
        connect();
        send("AUTH SCRAM-SHA-256 " + b64("p=tls-unique,,n=u,r=abc"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH SCRAM-SHA-256 " + b64("n,,n=u"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH SCRAM-SHA-256 !!!");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testScramUnknownUserAndUnsupportedRealm() {
        connect();
        send("AUTH SCRAM-SHA-256 " + b64("n,,n=ghost,r=abc"));
        assertTrue(last().startsWith("-ERR"));
        realm.scram = false;
        send("AUTH SCRAM-SHA-256 " + b64("n,,n=testuser,r=abc"));
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testScramContinuationAndBadFinal() {
        connect();
        send("AUTH SCRAM-SHA-256");
        assertTrue(last().startsWith("+"));
        send(b64("n,,n=testuser,r=cnonce"));
        assertTrue(last().startsWith("+ "));
        send(b64("c=biws,r=wrongnonce,p=AAAA"));
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testScramFinalWithBadBase64() {
        connect();
        send("AUTH SCRAM-SHA-256 " + b64("n,,n=testuser,r=cnonce"));
        assertTrue(last().startsWith("+ "));
        send("!!!");
        assertTrue(last().startsWith("-ERR"));
    }

    @Test
    public void testScramWithoutRealm() {
        listener.realm(null);
        connect();
        send("AUTH SCRAM-SHA-256");
        assertTrue(last().startsWith("-ERR"));
    }

    // ── EXTERNAL ──

    @Test
    public void testExternalFailsWithoutClientCertificate() {
        connect();
        send("AUTH EXTERNAL");
        assertTrue(last().startsWith("-ERR"));
        send("AUTH EXTERNAL " + b64("someone"));
        assertTrue(last().startsWith("-ERR"));
        send("AUTH EXTERNAL !!!");
        assertTrue(last().startsWith("-ERR"));
    }

    // ── PLAIN / LOGIN invalid base64 ──

    @Test
    public void testPlainAndLoginInvalidBase64() {
        connect();
        send("AUTH PLAIN !!!");
        assertTrue(last().startsWith("-ERR"));
        send("AUTH LOGIN !!!");
        assertTrue(last().startsWith("-ERR"));
        send("AUTH LOGIN");
        send("!!!");
        assertTrue(last().startsWith("-ERR"));
        send("AUTH LOGIN");
        send(b64("testuser"));
        send("!!!");
        assertTrue(last().startsWith("-ERR"));
    }

    // ── Fixtures ──

    static class TimerEndpoint extends POP3ProtocolHandlerTest.StubEndpoint {
        Runnable timerTask;
        boolean failStartTls;

        @Override
        public void startTLS() throws IOException {
            if (failStartTls) {
                throw new IOException("tls failed");
            }
            super.startTLS();
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            timerTask = cb;
            return super.scheduleTimer(delayMs, cb);
        }
    }

    static class FailingFactory extends POP3ProtocolHandlerTest.StubMailboxFactory {
        @Override
        public MailboxStore createStore() {
            return new POP3ProtocolHandlerTest.StubMailboxStore(this) {
                @Override
                public Mailbox openMailbox(String name, boolean readOnly) throws IOException {
                    throw new IOException("cannot open");
                }
            };
        }
    }

    static class CapableRealm extends POP3ProtocolHandlerTest.StubRealm {
        boolean apop = true;
        boolean cram = true;
        boolean scram = true;
        boolean bearerReturnsNull;

        @Override
        public String getApopResponse(String username, String timestamp) {
            if (!apop) {
                throw new UnsupportedOperationException("no apop");
            }
            if (!"testuser".equals(username)) {
                return null;
            }
            return SaslUtils.md5Hex((timestamp + "testpass").getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public String getCramMD5Response(String username, String challenge) {
            if (!cram) {
                throw new UnsupportedOperationException("no cram");
            }
            if (!"testuser".equals(username)) {
                return null;
            }
            return SaslUtils.computeCramMD5Response("testpass", challenge);
        }

        @Override
        public Realm.ScramCredentials getScramCredentials(String username) {
            if (!scram) {
                throw new UnsupportedOperationException("no scram");
            }
            if (!"testuser".equals(username)) {
                return null;
            }
            byte[] salt = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
            return Realm.ScramCredentials.derive("testpass", salt, 4096, "SHA-256");
        }

        @Override
        public Realm.TokenValidationResult validateBearerToken(String token) {
            if (bearerReturnsNull) {
                return null;
            }
            if ("goodtoken".equals(token)) {
                return Realm.TokenValidationResult.success("testuser", new String[0], "Bearer");
            }
            return Realm.TokenValidationResult.failure();
        }

        @Override
        public Realm.TokenValidationResult validateOAuthToken(String token) {
            if ("goodtoken".equals(token)) {
                return Realm.TokenValidationResult.success("testuser", new String[0], "Bearer");
            }
            return Realm.TokenValidationResult.failure();
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return "00000000000000000000000000000000";
        }
    }
}
