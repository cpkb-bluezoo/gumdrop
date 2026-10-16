/*
 * FipsProvenanceTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.testsupport.CompositeTestProvider;
import org.bluezoo.gumdrop.testsupport.RecordingKeyLog;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Where a FIPS runtime's cryptography comes from (issue #570): a full TLS
 * 1.3 and TLS 1.2 connection over the record engines, under services
 * pinned to the {@link CompositeTestProvider}, draws every cipher, MAC,
 * digest, signature, key derivation, key exchange and random value from
 * that provider, and logs no keys.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FipsProvenanceTest {

    private static final String[] SERVICE_TYPES = {
        "Cipher", "Mac", "MessageDigest", "Signature", "KDF", "KeyPairGenerator", "KeyAgreement",
        "KeyFactory", "CertificateFactory", "SecureRandom",
    };

    @After
    public void clearDefaultKeyLog() {
        KeyLog.setDefault(null);
    }

    private static CryptoServices fips() {
        CompositeTestProvider.install();
        return CryptoServices.pinned(CompositeTestProvider.NAME, null);
    }

    private static Map<String, Integer> snapshot(CompositeTestProvider provider) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int i = 0; i < SERVICE_TYPES.length; i++) {
            counts.put(SERVICE_TYPES[i], Integer.valueOf(provider.handedOut(SERVICE_TYPES[i])));
        }
        return counts;
    }

    private static int since(CompositeTestProvider provider, Map<String, Integer> before, String type) {
        return provider.handedOut(type) - before.get(type).intValue();
    }

    private static void assertDrewOn(CompositeTestProvider provider, Map<String, Integer> before, String... types) {
        for (int i = 0; i < types.length; i++) {
            assertTrue(types[i] + " should have come from the provider; handed out: "
                    + provider.handedOutTypes(), since(provider, before, types[i]) > 0);
        }
    }

    private static TlsRecordEngine[] tls13Loopback(CryptoServices crypto) throws Exception {
        HandshakeConfig serverConfig = new HandshakeConfig(HandshakeRole.SERVER, crypto);
        serverConfig.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        serverConfig.setTicketKeys(new TicketKeys(new byte[16]));
        HandshakeConfig clientConfig = new HandshakeConfig(HandshakeRole.CLIENT, crypto);
        clientConfig.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        clientConfig.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        TlsRecordEngine client = new TlsRecordEngine(clientConfig);
        TlsRecordEngine server = new TlsRecordEngine(serverConfig);
        TlsBLoopbackSupport.Sink clientSink = new TlsBLoopbackSupport.Sink();
        TlsBLoopbackSupport.Sink serverSink = new TlsBLoopbackSupport.Sink();
        client.start(clientSink);
        TlsBLoopbackSupport.pump(clientSink, TlsBLoopbackSupport.peer(client), serverSink,
                TlsBLoopbackSupport.peer(server), 0);
        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(clientSink.complete);
        assertTrue(serverSink.complete);
        byte[] message = new byte[] { 1, 2, 3 };
        client.sendApplicationData(message, clientSink);
        TlsBLoopbackSupport.pump(clientSink, TlsBLoopbackSupport.peer(client), serverSink,
                TlsBLoopbackSupport.peer(server), 0);
        assertEquals(3, serverSink.appBytes().length);
        return new TlsRecordEngine[] { client, server };
    }

    private static Tls12RecordEngine[] tls12Loopback(CryptoServices crypto) throws Exception {
        Tls12HandshakeConfig serverConfig = new Tls12HandshakeConfig(HandshakeRole.SERVER, crypto);
        serverConfig.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        serverConfig.setTicketKeys(new TicketKeys(new byte[16]));
        Tls12HandshakeConfig clientConfig = new Tls12HandshakeConfig(HandshakeRole.CLIENT, crypto);
        clientConfig.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        clientConfig.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        clientConfig.setClientTicketStore(new Tls12ClientTicketStore());
        Tls12RecordEngine client = new Tls12RecordEngine(clientConfig);
        Tls12RecordEngine server = new Tls12RecordEngine(serverConfig);
        TlsBLoopbackSupport.Sink clientSink = new TlsBLoopbackSupport.Sink();
        TlsBLoopbackSupport.Sink serverSink = new TlsBLoopbackSupport.Sink();
        client.start(clientSink);
        TlsBLoopbackSupport.pump(clientSink, TlsBLoopbackSupport.peer(client), serverSink,
                TlsBLoopbackSupport.peer(server), 0);
        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(clientSink.complete);
        assertTrue(serverSink.complete);
        byte[] message = new byte[] { 1, 2, 3 };
        client.sendApplicationData(message, clientSink);
        TlsBLoopbackSupport.pump(clientSink, TlsBLoopbackSupport.peer(client), serverSink,
                TlsBLoopbackSupport.peer(server), 0);
        assertEquals(3, serverSink.appBytes().length);
        return new Tls12RecordEngine[] { client, server };
    }

    @Test
    public void tls13ConnectionDrawsEveryServiceFromTheProvider() throws Exception {
        CompositeTestProvider provider = CompositeTestProvider.install();
        CryptoServices crypto = fips();
        Map<String, Integer> before = snapshot(provider);
        tls13Loopback(crypto);
        // Record protection and the session ticket: Cipher. Transcript:
        // MessageDigest. Key schedule: KDF. Finished: Mac. CertificateVerify:
        // Signature. Key share: KeyPairGenerator, KeyAgreement, KeyFactory.
        // The peer's chain: CertificateFactory. Randoms and nonces: SecureRandom.
        assertDrewOn(provider, before, "Cipher", "MessageDigest", "KDF", "Mac", "Signature", "KeyPairGenerator",
                "KeyAgreement", "KeyFactory", "CertificateFactory", "SecureRandom");
    }

    @Test
    public void tls12ConnectionDrawsItsPrfHmacsFromTheProvider() throws Exception {
        CompositeTestProvider provider = CompositeTestProvider.install();
        CryptoServices crypto = fips();
        Map<String, Integer> before = snapshot(provider);
        tls12Loopback(crypto);
        assertDrewOn(provider, before, "Cipher", "MessageDigest", "Mac", "Signature", "KeyPairGenerator",
                "KeyAgreement", "KeyFactory", "CertificateFactory", "SecureRandom");
        // TLS 1.2 has no HKDF: the PRF is HMAC iteration
        assertEquals(0, since(provider, before, "KDF"));
    }

    @Test
    public void keyLoggingProducesNothingUnderAFipsRuntime() throws Exception {
        RecordingKeyLog log = new RecordingKeyLog();
        KeyLog.setDefault(log);
        tls13Loopback(fips());
        tls12Loopback(fips());
        assertTrue("nothing may be logged in FIPS mode: " + log.entries.size(), log.entries.isEmpty());
        tls13Loopback(new CryptoServices());
        assertTrue("the same connection logs outside FIPS mode", !log.entries.isEmpty());
    }

}
