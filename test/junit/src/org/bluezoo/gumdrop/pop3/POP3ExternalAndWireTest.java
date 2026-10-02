/*
 * POP3ExternalAndWireTest
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers SASL EXTERNAL with a client certificate (RFC 4422 Appendix A) and
 * the wire-level rejection of non-ASCII command, argument and SASL
 * continuation bytes by {@link Pop3ProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ExternalAndWireTest {

    /** Realm that maps one certificate to a user, or refuses it. */
    private static final class CertRealm extends POP3ProtocolHandlerTest.StubRealm {
        boolean accept = true;
        X509Certificate seen;

        @Override
        public Realm.CertificateAuthenticationResult authenticateCertificate(
                X509Certificate certificate) {
            seen = certificate;
            if (accept) {
                return Realm.CertificateAuthenticationResult.success("testuser");
            }
            return Realm.CertificateAuthenticationResult.failure();
        }
    }

    /** Endpoint presenting a peer certificate. */
    private static final class CertEndpoint extends POP3ProtocolHandlerTest.StubEndpoint {
        Certificate[] peer;

        @Override
        public SecurityInfo getSecurityInfo() {
            return new POP3ProtocolHandlerTest.StubSecurityInfo() {
                @Override
                public Certificate[] getPeerCertificates() {
                    return peer;
                }
            };
        }
    }

    private CertRealm realm;
    private CertEndpoint endpoint;
    private POP3ProtocolHandlerTest.TestPOP3Listener listener;
    private Pop3ProtocolHandler handler;

    @Before
    public void setUp() throws Exception {
        realm = new CertRealm();
        realm.supportedMechanisms.add(SaslMechanism.EXTERNAL);
        realm.supportedMechanisms.add(SaslMechanism.PLAIN);
        listener = new POP3ProtocolHandlerTest.TestPOP3Listener();
        listener.setRealm(realm);
        listener.setMailboxFactory(new POP3ProtocolHandlerTest.StubMailboxFactory());
        listener.setEnableAPOP(false);
        endpoint = new CertEndpoint();
        endpoint.secure = true;
        TestCertificates.Identity id = TestCertificates.ec256();
        endpoint.peer = new Certificate[] {id.getCertificate()};
        handler = new Pop3ProtocolHandler(listener);
        handler.connected(endpoint);
        handler.securityEstablished(new POP3ProtocolHandlerTest.StubSecurityInfo());
        endpoint.sentData.clear();
    }

    private void send(String command) {
        handler.receive(ByteBuffer.wrap((command + "\r\n").getBytes(StandardCharsets.US_ASCII)));
    }

    private void sendRaw(byte[] bytes) {
        handler.receive(ByteBuffer.wrap(bytes));
    }

    private String last() {
        List<String> all = endpoint.getResponses();
        assertFalse(all.isEmpty());
        return all.get(all.size() - 1);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    // ---- EXTERNAL ----

    @Test
    public void externalAuthenticatesFromClientCertificate() {
        send("AUTH EXTERNAL");
        assertTrue(last(), last().startsWith("+OK"));
        assertTrue(realm.seen != null);
        endpoint.sentData.clear();
        send("STAT");
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void externalWithMatchingAuthorizationIdentity() {
        send("AUTH EXTERNAL " + b64("testuser"));
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void externalWithDifferentAuthorizationIdentityIsRefused() {
        send("AUTH EXTERNAL " + b64("someone-else"));
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void externalWithEmptyAuthorizationIdentityMeansNone() {
        // RFC 5034 section 4: "=" is a zero-length initial response
        send("AUTH EXTERNAL =");
        assertTrue(last(), last().startsWith("+OK"));
    }

    @Test
    public void externalRefusedWhenRealmRejectsCertificate() {
        realm.accept = false;
        send("AUTH EXTERNAL");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void externalRefusedWithEmptyChain() {
        endpoint.peer = new Certificate[0];
        send("AUTH EXTERNAL");
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void authListAdvertisesExternalOnSecureConnection() {
        send("AUTH");
        String all = "";
        for (String line : endpoint.getResponses()) {
            all = all + line + "\n";
        }
        assertTrue(all, all.contains("EXTERNAL"));
        assertTrue(all, all.contains("PLAIN"));
    }

    // ---- non-ASCII bytes ----

    @Test
    public void nonAsciiKeywordIsRejected() {
        sendRaw(new byte[] {'S', 'T', (byte) 0xE9, 'T', '\r', '\n'});
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void nonAsciiArgumentIsRejected() {
        sendRaw(new byte[] {'U', 'S', 'E', 'R', ' ', (byte) 0xE9, '\r', '\n'});
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void nonAsciiSaslContinuationIsRejected() {
        send("AUTH PLAIN");
        assertTrue(last(), last().startsWith("+"));
        sendRaw(new byte[] {(byte) 0xE9, '\r', '\n'});
        assertTrue(last(), last().startsWith("-ERR"));
    }

    @Test
    public void connectionRecoversAfterRejectedLine() {
        sendRaw(new byte[] {'S', 'T', (byte) 0xE9, 'T', '\r', '\n'});
        endpoint.sentData.clear();
        send("NOOP");
        assertTrue(last(), last().startsWith("+OK"));
    }

    // ---- lifecycle ----

    @Test
    public void transportErrorClosesEndpoint() {
        handler.error(new java.io.IOException("reset"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void rawBytesAreIgnored() {
        handler.rawBytes(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(0, endpoint.getResponses().size());
    }

    @Test
    public void securityEstablishedAfterStlsDoesNotRepeatGreeting() {
        listener.starttlsAvailable = true;
        CertEndpoint plain = new CertEndpoint();
        Pop3ProtocolHandler h = new Pop3ProtocolHandler(listener);
        h.connected(plain);
        h.receive(ByteBuffer.wrap("STLS\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(plain.startTLSCalled);
        plain.sentData.clear();
        h.securityEstablished(new POP3ProtocolHandlerTest.StubSecurityInfo());
        assertEquals(0, plain.getResponses().size());
    }
}
