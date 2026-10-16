/*
 * FipsProfileTest.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;
import org.bluezoo.gumdrop.testsupport.CompositeTestProvider;
import org.bluezoo.gumdrop.testsupport.RecordingKeyLog;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The approved profile a FIPS runtime applies to every handshake
 * configuration (issue #570): AES-GCM with SHA-256 or SHA-384, ECDHE on
 * the NIST curves, no ChaCha20-Poly1305, X25519, Ed25519, hybrid ML-KEM
 * or Encrypted Client Hello, and no key logging. A configuration under
 * such a runtime can narrow the profile but never widen it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FipsProfileTest {

    private static CryptoServices fips() {
        CompositeTestProvider.install();
        return CryptoServices.pinned(CompositeTestProvider.NAME, null);
    }

    @After
    public void clearDefaultKeyLog() {
        KeyLog.setDefault(null);
    }

    // -- the profile itself --

    @Test
    public void onlyAesGcmSuitesArePermitted() {
        assertTrue(FipsProfile.permits(CipherSuite.TLS_AES_128_GCM_SHA256));
        assertTrue(FipsProfile.permits(CipherSuite.TLS_AES_256_GCM_SHA384));
        assertFalse(FipsProfile.permits(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        assertTrue(FipsProfile.permits(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));
        assertTrue(FipsProfile.permits(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384));
        assertFalse(FipsProfile.permits(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));
        assertFalse(FipsProfile.permits(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256));
    }

    @Test
    public void onlyNistCurvesArePermitted() {
        assertTrue(FipsProfile.permits(NamedGroup.SECP256R1));
        assertTrue(FipsProfile.permits(NamedGroup.SECP384R1));
        assertFalse(FipsProfile.permits(NamedGroup.X25519));
        assertFalse(FipsProfile.permits(NamedGroup.X25519_MLKEM768));
        assertFalse(FipsProfile.permits(NamedGroup.SECP256R1_MLKEM768));
        assertFalse(FipsProfile.permits(NamedGroup.SECP384R1_MLKEM1024));
    }

    @Test
    public void ed25519AndMlDsaSignaturesAreNotPermitted() {
        assertTrue(FipsProfile.permits(SignatureScheme.ECDSA_SECP256R1_SHA256));
        assertTrue(FipsProfile.permits(SignatureScheme.ECDSA_SECP384R1_SHA384));
        assertTrue(FipsProfile.permits(SignatureScheme.RSA_PSS_RSAE_SHA256));
        assertTrue(FipsProfile.permits(SignatureScheme.RSA_PKCS1_SHA256));
        assertFalse(FipsProfile.permits(SignatureScheme.ED25519));
        assertFalse(FipsProfile.permits(SignatureScheme.MLDSA44));
        assertFalse(FipsProfile.permits(SignatureScheme.MLDSA65));
        assertFalse(FipsProfile.permits(SignatureScheme.MLDSA87));
    }

    // -- TLS 1.3 configuration --

    @Test
    public void defaultsAreFilteredUnderAFipsRuntime() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, fips());
        assertEquals(Arrays.asList(CipherSuite.TLS_AES_256_GCM_SHA384, CipherSuite.TLS_AES_128_GCM_SHA256),
                config.getCipherSuites());
        assertEquals(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.SECP384R1), config.getNamedGroups());
    }

    @Test
    public void defaultsAreUntouchedOutsideFipsMode() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, new CryptoServices());
        assertTrue(config.getCipherSuites().contains(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        assertTrue(config.getNamedGroups().contains(NamedGroup.X25519_MLKEM768));
    }

    @Test
    public void aConfigurationCanNarrowButNotWidenTheProfile() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER, fips());
        config.setCipherSuites(Arrays.asList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_AES_128_GCM_SHA256));
        assertEquals(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256), config.getCipherSuites());
        config.setNamedGroups(Arrays.asList(NamedGroup.X25519_MLKEM768, NamedGroup.X25519, NamedGroup.SECP384R1));
        assertEquals(Collections.singletonList(NamedGroup.SECP384R1), config.getNamedGroups());
    }

    @Test
    public void aConfigurationOfferingNothingApprovedIsRejected() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, fips());
        try {
            config.setCipherSuites(Collections.singletonList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
            fail("ChaCha20-Poly1305 alone must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            config.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
            fail("X25519 alone must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void encryptedClientHelloIsRejected() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, fips());
        try {
            config.setEchEnabled(true);
            fail("ECH must be rejected");
        } catch (IllegalStateException expected) {
        }
        try {
            config.setEchGreaseEnabled(true);
            fail("ECH GREASE must be rejected");
        } catch (IllegalStateException expected) {
        }
        HandshakeConfig server = new HandshakeConfig(HandshakeRole.SERVER, fips());
        try {
            server.addEchServerKey(EchConfig.createV13(1, new byte[32], "public.example", 0), new byte[32]);
            fail("an ECH server key must be rejected");
        } catch (IllegalStateException expected) {
        }
        // Turning it off is always fine
        config.setEchEnabled(false);
        config.setEchGreaseEnabled(false);
    }

    @Test
    public void keyLoggingIsDisabled() {
        RecordingKeyLog log = new RecordingKeyLog();
        KeyLog.setDefault(log);
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, fips());
        config.setKeyLog(log);
        assertNull(config.resolvedKeyLog());
        Tls12HandshakeConfig config12 = new Tls12HandshakeConfig(HandshakeRole.CLIENT, fips());
        config12.setKeyLog(log);
        assertNull(config12.resolvedKeyLog());
        HandshakeConfig plain = new HandshakeConfig(HandshakeRole.CLIENT, new CryptoServices());
        assertNotNull(plain.resolvedKeyLog());
    }

    // -- TLS 1.2 configuration --

    @Test
    public void tls12DefaultsAreFilteredUnderAFipsRuntime() {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT, fips());
        for (Tls12CipherSuite suite : config.getCipherSuites()) {
            assertTrue(suite.name(), FipsProfile.permits(suite));
        }
        assertEquals(4, config.getCipherSuites().size());
        assertEquals(Collections.singletonList(NamedGroup.SECP256R1), config.getNamedGroups());
        try {
            config.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
            fail("X25519 alone must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            config.setCipherSuites(Collections.singletonList(
                    Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));
            fail("ChaCha20-Poly1305 alone must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    // -- handshakes --

    private static final class Sink13 implements TlsEventSink {
        final List<byte[]> out = new ArrayList<byte[]>();
        TlsProtocolError error;

        @Override
        public void handshakeDataReady(byte[] data) {
            out.add(data);
        }

        @Override
        public void handshakeSecretsReady() {
        }

        @Override
        public void applicationSecretsReady() {
        }

        @Override
        public void protocolError(TlsProtocolError e) {
            error = e;
        }

        List<byte[]> drain() {
            List<byte[]> copy = new ArrayList<byte[]>(out);
            out.clear();
            return copy;
        }
    }

    private static void run13(HandshakeEngine client, Sink13 clientSink, HandshakeEngine server, Sink13 serverSink) {
        client.start(clientSink);
        List<byte[]> toServer = clientSink.drain();
        int rounds = 0;
        while (!(client.isComplete() && server.isComplete()) && !client.isFailed() && !server.isFailed()
                && rounds < 20) {
            rounds++;
            for (byte[] message : toServer) {
                server.processMessage(message, serverSink);
                if (serverSink.error != null) {
                    return;
                }
            }
            List<byte[]> toClient = serverSink.drain();
            toServer.clear();
            for (byte[] message : toClient) {
                client.processMessage(message, clientSink);
                if (clientSink.error != null) {
                    return;
                }
            }
            toServer.addAll(clientSink.drain());
        }
    }

    private static HandshakeConfig server13(CryptoServices crypto) throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER, crypto);
        config.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        config.setLocalTransportParameters(new byte[] { 1 });
        return config;
    }

    private static HandshakeConfig client13(CryptoServices crypto) throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT, crypto);
        config.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        config.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        config.setLocalTransportParameters(new byte[] { 2 });
        return config;
    }

    @Test
    public void tls13HandshakeCompletesUnderTheApprovedProfile() throws Exception {
        CryptoServices crypto = fips();
        HandshakeEngine client = new HandshakeEngine(client13(crypto));
        HandshakeEngine server = new HandshakeEngine(server13(crypto));
        Sink13 clientSink = new Sink13();
        Sink13 serverSink = new Sink13();
        run13(client, clientSink, server, serverSink);
        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertTrue(FipsProfile.permits(server.getNegotiatedCipherSuite()));
    }

    @Test
    public void aClientOfferingOnlyX25519AndChaChaIsRefusedByAFipsServer() throws Exception {
        HandshakeConfig clientConfig = client13(new CryptoServices());
        clientConfig.setCipherSuites(Collections.singletonList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        clientConfig.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
        HandshakeEngine client = new HandshakeEngine(clientConfig);
        HandshakeEngine server = new HandshakeEngine(server13(fips()));
        Sink13 clientSink = new Sink13();
        Sink13 serverSink = new Sink13();
        run13(client, clientSink, server, serverSink);
        assertNotNull("the server must refuse", serverSink.error);
        assertFalse(server.isComplete());
    }

    @Test
    public void aClientOfferingOnlyX25519IsRefusedByAFipsServer() throws Exception {
        HandshakeConfig clientConfig = client13(new CryptoServices());
        clientConfig.setNamedGroups(Collections.singletonList(NamedGroup.X25519));
        HandshakeEngine client = new HandshakeEngine(clientConfig);
        HandshakeEngine server = new HandshakeEngine(server13(fips()));
        Sink13 clientSink = new Sink13();
        Sink13 serverSink = new Sink13();
        run13(client, clientSink, server, serverSink);
        assertNotNull("the server must refuse", serverSink.error);
        assertFalse(server.isComplete());
    }

    private static final class Sink12 implements Tls12EventSink {
        final List<byte[]> out = new ArrayList<byte[]>();
        boolean complete;
        TlsProtocolError error;

        @Override
        public void handshakeDataReady(byte[] data) {
            out.add(data);
        }

        @Override
        public void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client,
                DirectionalKeyMaterial server) {
        }

        @Override
        public void sendChangeCipherSpec() {
        }

        @Override
        public void handshakeComplete() {
            complete = true;
        }

        @Override
        public void protocolError(TlsProtocolError e) {
            error = e;
        }
    }

    @Test
    public void tls12HandshakeCompletesUnderTheApprovedProfile() throws Exception {
        CryptoServices crypto = fips();
        Tls12HandshakeConfig serverConfig = new Tls12HandshakeConfig(HandshakeRole.SERVER, crypto);
        serverConfig.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        Tls12HandshakeConfig clientConfig = new Tls12HandshakeConfig(HandshakeRole.CLIENT, crypto);
        clientConfig.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        clientConfig.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(clientConfig);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverConfig);
        Sink12 clientSink = new Sink12();
        Sink12 serverSink = new Sink12();
        client.start(clientSink);
        for (int round = 0; round < 40; round++) {
            boolean moved = false;
            List<byte[]> toServer = new ArrayList<byte[]>(clientSink.out);
            clientSink.out.clear();
            for (byte[] message : toServer) {
                moved = true;
                server.processMessage(message, serverSink);
            }
            List<byte[]> toClient = new ArrayList<byte[]>(serverSink.out);
            serverSink.out.clear();
            for (byte[] message : toClient) {
                moved = true;
                client.processMessage(message, clientSink);
            }
            if (!moved) {
                break;
            }
        }
        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(clientSink.complete);
        assertTrue(serverSink.complete);
        assertTrue(FipsProfile.permits(server.getNegotiatedCipherSuite()));
    }

}
