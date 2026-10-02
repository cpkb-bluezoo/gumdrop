/*
 * TlsValueClassesTest.java
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the small value, codec and ticket classes of the TLS
 * package: wire reader/writer, transcript hash, session tickets and their
 * sealed payloads, ticket keys, cipher suite tables and TlsConfig merging.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsValueClassesTest {

    private static byte[] filled(int n, int v) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) v;
        }
        return b;
    }

    // Section: WireReader / WireWriter

    @Test
    public void wireWriterAndReaderRoundTrip() throws Exception {
        WireWriter w = new WireWriter();
        w.u8(0x1ff);
        w.u16(0x1234);
        w.u24(0x123456);
        w.u32(0x89abcdef);
        w.opaque8(new byte[] {1, 2});
        w.opaque16(new byte[] {3});
        w.opaque24(new byte[] {4, 5, 6});
        w.opaque8Ascii("h2");
        w.bytes(new byte[] {9});
        assertEquals(1 + 2 + 3 + 4 + 3 + 3 + 6 + 3 + 1, w.length());
        WireReader r = new WireReader(w.toByteArray());
        assertEquals(0xff, r.u8());
        assertEquals(0x1234, r.u16());
        assertEquals(0x123456, r.u24());
        assertEquals(0x89abcdef, r.u32());
        assertArrayEquals(new byte[] {1, 2}, r.opaque8());
        assertArrayEquals(new byte[] {3}, r.opaque16());
        assertArrayEquals(new byte[] {4, 5, 6}, r.opaque24());
        assertEquals("h2", r.opaque8Ascii());
        assertTrue(r.hasRemaining());
        assertEquals(1, r.remaining());
        assertArrayEquals(new byte[] {9}, r.bytes(1));
        assertFalse(r.hasRemaining());
    }

    @Test
    public void wireReaderEveryTruncationFails() throws Exception {
        WireWriter w = new WireWriter();
        w.u32(1);
        byte[] data = w.toByteArray();
        for (int len = 0; len < 4; len++) {
            WireReader r = new WireReader(data, 0, len);
            try {
                r.u32();
                fail("u32 over " + len);
            } catch (HandshakeFormatException expected) {
                // expected
            }
        }
        try {
            new WireReader(new byte[0]).u8();
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
        try {
            new WireReader(new byte[1]).u16();
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
        try {
            new WireReader(new byte[2]).u24();
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
        try {
            new WireReader(new byte[] {5, 1}).opaque8();
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void wireReaderSliceAndOffset() throws Exception {
        byte[] data = new byte[] {0, 0, 7, 8, 9, 10};
        WireReader r = new WireReader(data, 2, 4);
        WireReader sub = r.slice(2);
        assertEquals(2, sub.remaining());
        assertEquals(0x0708, sub.u16());
        assertEquals(2, r.remaining());
        try {
            r.slice(3);
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
        try {
            sub.u8();
            fail();
        } catch (HandshakeFormatException expected) {
            // expected
        }
    }

    @Test
    public void frameHandshakeMessageLayout() {
        byte[] framed = WireWriter.frameHandshakeMessage(2, new byte[] {7, 8});
        assertArrayEquals(new byte[] {2, 0, 0, 2, 7, 8}, framed);
    }

    // Section: Transcript

    @Test
    public void transcriptHashMatchesDigest() throws Exception {
        Transcript t = Transcript.create(CipherSuite.TLS_AES_128_GCM_SHA256);
        byte[] empty = t.hash();
        assertArrayEquals(Transcript.emptyHash(CipherSuite.TLS_AES_128_GCM_SHA256), empty);
        t.update(new byte[] {1, 2, 3});
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(new byte[] {1, 2, 3});
        assertArrayEquals(md.digest(), t.hash());
        byte[] with = t.hashWith(new byte[] {4});
        md.reset();
        md.update(new byte[] {1, 2, 3, 4});
        assertArrayEquals(md.digest(), with);
        assertEquals(32, t.hash().length);
    }

    @Test
    public void transcriptSha384AndEmptyHashes() {
        Transcript t = Transcript.create("SHA-384");
        assertEquals(48, t.hash().length);
        assertEquals(48, Transcript.emptyHash("SHA-384").length);
        assertEquals(32, Transcript.emptyHash("SHA-256").length);
        assertEquals(48, Transcript.emptyHash(CipherSuite.TLS_AES_256_GCM_SHA384).length);
    }

    @Test
    public void transcriptRetryReplacesHistoryWithMessageHash() throws Exception {
        Transcript t = Transcript.create("SHA-256");
        t.update(new byte[] {9, 9, 9});
        byte[] ch1 = filled(32, 0x55);
        t.retry(ch1);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(new byte[] {(byte) 254, 0, 0, 32});
        md.update(ch1);
        assertArrayEquals(md.digest(), t.hash());
    }

    @Test
    public void transcriptUnknownAlgorithmIsRejected() {
        try {
            Transcript.create("NoSuchHash");
            fail();
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // Section: TlsVersion

    @Test
    public void tlsVersionPolicyMatrix() {
        TlsVersionPick.Picked[] picks = TlsVersionPick.Picked.values();
        for (int i = 0; i < picks.length; i++) {
            assertTrue(TlsVersion.NEGOTIATE.allowsClientPick(picks[i]));
        }
        assertTrue(TlsVersion.TLS_1_3.allowsClientPick(TlsVersionPick.Picked.V13));
        assertFalse(TlsVersion.TLS_1_3.allowsClientPick(TlsVersionPick.Picked.V12));
        assertTrue(TlsVersion.TLS_1_2.allowsClientPick(TlsVersionPick.Picked.V12));
        assertFalse(TlsVersion.TLS_1_2.allowsServerPick(TlsVersionPick.Picked.V13));
        assertEquals(TlsVersion.TLS_1_2, TlsVersion.valueOf("TLS_1_2"));
    }

    // Section: TicketKeys

    @Test
    public void ticketKeysRotation() {
        byte[] first = filled(16, 1);
        TicketKeys keys = new TicketKeys(first);
        assertSame(first, keys.getCurrentKey());
        assertEquals(1, keys.candidateKeys().size());
        byte[] second = filled(16, 2);
        keys.rotate(second);
        assertSame(second, keys.getCurrentKey());
        List<byte[]> c = keys.candidateKeys();
        assertEquals(2, c.size());
        assertSame(second, c.get(0));
        assertSame(first, c.get(1));
        byte[] third = filled(16, 3);
        keys.rotate(third);
        assertSame(second, keys.candidateKeys().get(1));
    }

    @Test
    public void ticketKeysRejectWrongLengths() {
        try {
            new TicketKeys(new byte[15]);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        TicketKeys keys = new TicketKeys(new byte[16]);
        try {
            keys.rotate(new byte[17]);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // Section: SessionTicket

    @Test
    public void sessionTicketAccessorsAndAge() {
        byte[] id = new byte[] {1, 2};
        byte[] psk = new byte[] {3};
        long now = System.currentTimeMillis();
        SessionTicket t = new SessionTicket(id, 3600, 77, now, 1234,
                CipherSuite.TLS_AES_128_GCM_SHA256, psk);
        assertSame(id, t.getIdentity());
        assertSame(psk, t.getPsk());
        assertEquals(3600, t.getLifetimeSeconds());
        assertEquals(77, t.getAgeAdd());
        assertEquals(now, t.getIssuedAtMillis());
        assertEquals(1234, t.getMaxEarlyDataSize());
        assertEquals(CipherSuite.TLS_AES_128_GCM_SHA256, t.getCipherSuite());
        assertFalse(t.isExpired());
        int age = t.obfuscatedTicketAge();
        assertTrue(age >= 77);
    }

    @Test
    public void sessionTicketExpiry() {
        long longAgo = System.currentTimeMillis() - 10000L;
        SessionTicket t = new SessionTicket(new byte[1], 1, 0, longAgo, 0,
                CipherSuite.TLS_AES_128_GCM_SHA256, new byte[1]);
        assertTrue(t.isExpired());
    }

    // Section: TicketPayload (TLS 1.3)

    @Test
    public void ticketPayloadSealOpenRoundTrip() {
        byte[] key = filled(16, 7);
        byte[] psk = filled(32, 9);
        byte[] tp = new byte[] {1, 2, 3};
        TicketPayload p = new TicketPayload(1234567L, 86400, -5, psk, 4096,
                CipherSuite.TLS_AES_128_GCM_SHA256, tp);
        byte[] sealed = p.seal(key);
        TicketPayload q = TicketPayload.open(key, sealed);
        assertNotNull(q);
        assertEquals(1234567L, q.issuedAtMillis);
        assertEquals(86400, q.lifetimeSeconds);
        assertEquals(-5, q.ageAdd);
        assertArrayEquals(psk, q.psk);
        assertEquals(4096, q.maxEarlyDataSize);
        assertEquals(CipherSuite.TLS_AES_128_GCM_SHA256, q.cipherSuite);
        assertArrayEquals(tp, q.rememberedTransportParameters);
    }

    @Test
    public void ticketPayloadWithoutTransportParameters() {
        byte[] key = filled(16, 7);
        TicketPayload p = new TicketPayload(1L, 1, 1, filled(48, 1), 0,
                CipherSuite.TLS_AES_256_GCM_SHA384, null);
        TicketPayload q = TicketPayload.open(key, p.seal(key));
        assertNotNull(q);
        assertNull(q.rememberedTransportParameters);
    }

    @Test
    public void ticketPayloadRejectsBadInput() {
        byte[] key = filled(16, 7);
        TicketPayload p = new TicketPayload(1L, 1, 1, filled(32, 1), 0,
                CipherSuite.TLS_AES_128_GCM_SHA256, null);
        byte[] sealed = p.seal(key);
        assertNull(TicketPayload.open(key, new byte[10]));
        assertNull(TicketPayload.open(filled(16, 8), sealed));
        for (int i = 0; i < sealed.length; i++) {
            byte[] bad = sealed.clone();
            bad[i] ^= 0x40;
            assertNull("flip " + i, TicketPayload.open(key, bad));
        }
        byte[] shortened = new byte[sealed.length - 1];
        System.arraycopy(sealed, 0, shortened, 0, shortened.length);
        assertNull(TicketPayload.open(key, shortened));
    }

    // Section: Tls12TicketPayload / Tls12SessionTicket / Tls12ClientTicketStore

    @Test
    public void tls12TicketPayloadRoundTripAndTamper() {
        byte[] key = filled(16, 3);
        byte[] ms = filled(48, 5);
        Tls12TicketPayload p = new Tls12TicketPayload(ms, 0xC02B, System.currentTimeMillis(), 600);
        assertFalse(p.isExpired());
        byte[] sealed = p.seal(key);
        Tls12TicketPayload q = Tls12TicketPayload.open(key, sealed);
        assertNotNull(q);
        assertArrayEquals(ms, q.masterSecret);
        assertEquals(0xC02B, q.cipherSuiteCode);
        assertEquals(600, q.lifetimeSecs);
        assertNull(Tls12TicketPayload.open(key, new byte[5]));
        assertNull(Tls12TicketPayload.open(filled(16, 4), sealed));
        for (int i = 0; i < sealed.length; i++) {
            byte[] bad = sealed.clone();
            bad[i] ^= 1;
            assertNull("flip " + i, Tls12TicketPayload.open(key, bad));
        }
    }

    @Test
    public void tls12TicketPayloadExpiry() {
        long past = System.currentTimeMillis() - 5000L;
        assertTrue(new Tls12TicketPayload(new byte[48], 1, past, 1).isExpired());
        long future = System.currentTimeMillis() + 600000L;
        assertTrue(new Tls12TicketPayload(new byte[48], 1, future, 100).isExpired());
    }

    @Test
    public void tls12TicketPayloadWrongVersionRejected() throws Exception {
        byte[] key = filled(16, 3);
        byte[] nonce = filled(12, 1);
        byte[] plain = new byte[1 + 48 + 2 + 8 + 4];
        plain[0] = 2;
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(plain);
        byte[] ticket = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, ticket, 0, 12);
        System.arraycopy(ct, 0, ticket, 12, ct.length);
        assertNull(Tls12TicketPayload.open(key, ticket));
        byte[] shortPlain = new byte[10];
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, filled(12, 2)));
        byte[] ct2 = c.doFinal(shortPlain);
        byte[] ticket2 = new byte[12 + ct2.length];
        System.arraycopy(filled(12, 2), 0, ticket2, 0, 12);
        System.arraycopy(ct2, 0, ticket2, 12, ct2.length);
        assertNull(Tls12TicketPayload.open(key, ticket2));
    }

    @Test
    public void tls12ClientTicketStoreLifecycle() {
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        assertNull(store.get("a"));
        long now = System.currentTimeMillis();
        Tls12SessionTicket live = new Tls12SessionTicket(new byte[] {1}, new byte[48],
                Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, now, 600);
        store.put("a", live);
        assertSame(live, store.get("a"));
        assertSame(live.getTicket(), live.getTicket());
        assertEquals(48, live.getMasterSecret().length);
        assertEquals(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, live.getCipherSuite());
        assertFalse(live.isExpired());
        store.remove("a");
        assertNull(store.get("a"));
        Tls12SessionTicket stale = new Tls12SessionTicket(new byte[] {1}, new byte[48],
                Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, now - 5000L, 1);
        assertTrue(stale.isExpired());
        store.put("b", stale);
        assertNull(store.get("b"));
        assertNull(store.get("b"));
        Tls12SessionTicket future = new Tls12SessionTicket(new byte[] {1}, new byte[48],
                Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, now + 600000L, 10);
        assertTrue(future.isExpired());
    }

    // Section: Tls12CipherSuite

    @Test
    public void tls12CipherSuiteTable() {
        Tls12CipherSuite[] all = Tls12CipherSuite.values();
        for (int i = 0; i < all.length; i++) {
            assertSame(all[i], Tls12CipherSuite.fromCode(all[i].getCode()));
            assertNotNull(all[i].getPrfHashAlgorithm());
            assertNotNull(all[i].getKeyType());
            assertNotNull(all[i].getAeadKeyAlgorithm());
            assertNotNull(all[i].getAeadTransformation());
            assertTrue(all[i].getAeadKeyLength() >= 16);
            assertTrue(all[i].getFixedIvLength() == 4 || all[i].getFixedIvLength() == 12);
        }
        assertNull(Tls12CipherSuite.fromCode(0x1301));
        assertEquals("SHA-384", Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384.getPrfHashAlgorithm());
        assertEquals("ChaCha20-Poly1305",
                Tls12CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256.getAeadTransformation());
    }

    // Section: Tls12DirectionalKeys

    private static Tls12DirectionalKeys keys12(Tls12CipherSuite suite) {
        DirectionalKeyMaterial m = new DirectionalKeyMaterial(filled(suite.getAeadKeyLength(), 1),
                filled(suite.getFixedIvLength(), 2));
        return Tls12DirectionalKeys.fromMaterial(suite, m);
    }

    @Test
    public void tls12DirectionalKeysGcmSealOpen() throws Exception {
        Tls12DirectionalKeys k = keys12(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256);
        assertTrue(k.hasExplicitNonce());
        byte[] aad = new byte[13];
        byte[] nonce = k.localNonce().clone();
        byte[] sealed = k.sealAppendTag(nonce, aad, new byte[] {1, 2, 3});
        byte[] explicit = k.seqBytes().clone();
        byte[] open = k.openInPlace(k.nonceFromWire(explicit), aad, sealed);
        assertArrayEquals(new byte[] {1, 2, 3}, open);
        sealed[0] ^= 1;
        assertNull(k.openInPlace(k.nonceFromWire(explicit), aad, sealed));
        byte[] padded = new byte[sealed.length + 8];
        sealed[0] ^= 1;
        System.arraycopy(sealed, 0, padded, 4, sealed.length);
        byte[] opened = k.openInPlace(k.nonceFromWire(explicit, 0), aad, 0, aad.length,
                padded, 4, sealed.length);
        assertArrayEquals(new byte[] {1, 2, 3}, opened);
        assertFalse(k.overConfidentialityLimit());
        k.seq = 23726566L;
        assertTrue(k.overConfidentialityLimit());
        k.advance();
        assertEquals(23726567L, k.seq);
    }

    @Test
    public void tls12DirectionalKeysChaChaNonceXorsSequence() throws Exception {
        Tls12DirectionalKeys k = keys12(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256);
        assertFalse(k.hasExplicitNonce());
        byte[] n0 = k.localNonce().clone();
        k.advance();
        byte[] n1 = k.localNonce().clone();
        assertFalse(java.util.Arrays.equals(n0, n1));
        assertArrayEquals(n1, k.nonceFromWire(new byte[8]).clone());
        byte[] aad = new byte[13];
        byte[] ct = k.sealAppendTag(n1, aad, new byte[] {4, 5}, 0, 2);
        assertArrayEquals(new byte[] {4, 5}, k.openInPlace(n1, aad, ct));
        k.seq = Long.MAX_VALUE;
        assertFalse(k.overConfidentialityLimit());
    }

    // Section: TlsProtocolError / sinks / ServerCredentials

    @Test
    public void protocolErrorCarriesAlertAndMessage() {
        TlsProtocolError e = new TlsProtocolError(AlertDescription.DECODE_ERROR, "bad");
        assertEquals(AlertDescription.DECODE_ERROR, e.getAlert());
        assertEquals("bad", e.getMessage());
        assertNotNull(e.toString());
        assertTrue(e.toString().indexOf("bad") >= 0);
    }

    @Test
    public void tlsEventSinkDefaultsAreNoOps() {
        TlsEventSink s = new TlsEventSink() {
            @Override
            public void handshakeDataReady(byte[] data) {
            }

            @Override
            public void handshakeSecretsReady() {
            }

            @Override
            public void applicationSecretsReady() {
            }

            @Override
            public void protocolError(TlsProtocolError error) {
            }
        };
        s.peerTransportParameters(new byte[0]);
        s.peerClosed();
        s.quicEarlyKeysReady(CipherSuite.TLS_AES_128_GCM_SHA256, new byte[32]);
        s.earlyDataAccepted(true);
        s.sessionTicketReceived(null);
        s.applicationTrafficSecretUpdated(KeyUpdateDirection.READ, new byte[32]);
    }

    @Test
    public void tlsRecordSinkDefaultSliceCopiesOrPassesThrough() {
        final java.util.ArrayList<byte[]> got = new java.util.ArrayList<byte[]>();
        TlsRecordSink s = new TlsRecordSink() {
            @Override
            public void ciphertextReady(byte[] data) {
                got.add(data);
            }

            @Override
            public void applicationDataReady(byte[] plaintext) {
            }

            @Override
            public void handshakeComplete() {
            }

            @Override
            public void protocolError(TlsProtocolError error) {
            }
        };
        byte[] whole = new byte[] {1, 2, 3, 4};
        s.ciphertextReady(whole, 0, 4);
        assertSame(whole, got.get(0));
        s.ciphertextReady(whole, 1, 2);
        assertArrayEquals(new byte[] {2, 3}, got.get(1));
        s.peerClosed();
    }

    @Test
    public void transportParameterCheckerDefaultAcceptsTicket() {
        TransportParameterConsistencyChecker c = new TransportParameterConsistencyChecker() {
            @Override
            public boolean isConsistent(byte[] remembered, byte[] current) {
                return false;
            }
        };
        assertTrue(c.acceptsTicketFrom(new byte[0], new byte[0]));
        assertFalse(c.isConsistent(new byte[0], new byte[0]));
    }

    @Test
    public void serverCredentialsHoldsMaterial() {
        ServerCredentials sc = new ServerCredentials(null, null);
        assertNull(sc.getCertificateChain());
        assertNull(sc.getPrivateKey());
    }

    // Section: TlsConfig

    @Test
    public void tlsConfigEffectiveMergesLocalOverFallback() {
        Path a = Paths.get("a.pem");
        Path b = Paths.get("b.pem");
        TlsConfig local = new TlsConfig().certFile(a).keyFile(a).verifyPeer(false);
        TlsConfig def = new TlsConfig().certFile(b).keyFile(b).keystoreFile(b)
                .keystorePass("p").keystoreFormat(KeystoreFormat.PKCS12);
        TlsConfig out = TlsConfig.effective(local, def);
        assertEquals(a, out.getCertFile());
        assertEquals(a, out.getKeyFile());
        assertEquals(b, out.getKeystoreFile());
        assertEquals("p", out.getKeystorePass());
        assertEquals(KeystoreFormat.PKCS12, out.getKeystoreFormat());
        assertFalse(out.isVerifyPeer());
    }

    @Test
    public void tlsConfigEffectiveVerifyPeerFallbacks() {
        TlsConfig noMaterial = new TlsConfig();
        TlsConfig insecureDefault = new TlsConfig().verifyPeer(false);
        assertFalse(TlsConfig.effective(noMaterial, insecureDefault).isVerifyPeer());
        assertTrue(TlsConfig.effective(null, null).isVerifyPeer());
        assertTrue(TlsConfig.effective(noMaterial, new TlsConfig()).isVerifyPeer());
        TlsConfig localSecure = new TlsConfig().certFile(Paths.get("x")).keyFile(Paths.get("y"));
        assertTrue(TlsConfig.effective(localSecure, insecureDefault).isVerifyPeer());
    }

    @Test
    public void tlsConfigEffectiveEchSettings() {
        Path p = Paths.get("ech");
        TlsConfig local = new TlsConfig().echConfigListFile(p).echPrivateKeyFile(p).echServerRequired(true);
        TlsConfig def = new TlsConfig().clientEchConfigListFile(p).clientEchGreaseEnabled(true)
                .clientEchDnsDiscovery(true).clientEchRequired(true);
        TlsConfig out = TlsConfig.effective(local, def);
        assertEquals(p, out.getEchConfigListFile());
        assertEquals(p, out.getEchPrivateKeyFile());
        assertTrue(out.isEchServerRequired());
        assertEquals(p, out.getClientEchConfigListFile());
        assertTrue(out.isClientEchGreaseEnabled());
        assertTrue(out.isClientEchDnsDiscoveryEnabled());
        assertTrue(out.isClientEchRequired());

        TlsConfig defServer = new TlsConfig().echServerRequired(true);
        TlsConfig out2 = TlsConfig.effective(new TlsConfig(), defServer);
        assertTrue(out2.isEchServerRequired());
        TlsConfig localGrease = new TlsConfig().clientEchGreaseEnabled(true);
        assertTrue(TlsConfig.effective(localGrease, new TlsConfig()).isClientEchGreaseEnabled());
        TlsConfig localNoGrease = new TlsConfig().clientEchConfigListFile(p);
        TlsConfig defGrease = new TlsConfig().clientEchGreaseEnabled(true);
        assertFalse(TlsConfig.effective(localNoGrease, defGrease).isClientEchGreaseEnabled());
    }

    @Test
    public void tlsConfigFactoriesAndCopy() {
        Path p = Paths.get("k.p12");
        TlsConfig ks = TlsConfig.keystore(p, "pw");
        assertEquals(KeystoreFormat.PKCS12, ks.getKeystoreFormat());
        assertEquals(p, ks.getKeystoreFile());
        TlsConfig ks2 = TlsConfig.keystore(p, "pw", KeystoreFormat.JKS);
        assertEquals(KeystoreFormat.JKS, ks2.getKeystoreFormat());
        TlsConfig pem = TlsConfig.pem(p, p);
        assertEquals(p, pem.getCertFile());
        ServerCredentials sc = new ServerCredentials(null, null);
        TlsConfig cr = TlsConfig.credentials(sc);
        assertSame(sc, cr.getServerCredentials());
        try {
            TlsConfig.credentials(null);
            fail();
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            TlsConfig.pem(null, p);
            fail();
        } catch (NullPointerException expected) {
            // expected
        }
        TlsConfig target = new TlsConfig();
        assertSame(target, target.copyFrom(null));
        target.copyFrom(ks).serverCredentials(sc);
        assertEquals("pw", target.getKeystorePass());
        assertSame(sc, target.getServerCredentials());
        assertNull(target.getTrustManager());
        TlsConfig ech = new TlsConfig().echConfigListFile(p).echPrivateKeyFile(p).echServerRequired(true)
                .clientEchConfigListFile(p).clientEchGreaseEnabled(true).clientEchDnsDiscovery(true)
                .clientEchRequired(true).keystorePass("x").keystoreFile(p).keystoreFormat(KeystoreFormat.JKS);
        TlsConfig copy = new TlsConfig().copyFrom(ech);
        assertEquals(p, copy.getEchConfigListFile());
        assertEquals(p, copy.getEchPrivateKeyFile());
        assertTrue(copy.isEchServerRequired());
        assertEquals(p, copy.getClientEchConfigListFile());
        assertTrue(copy.isClientEchGreaseEnabled());
        assertTrue(copy.isClientEchDnsDiscoveryEnabled());
        assertTrue(copy.isClientEchRequired());
        assertEquals(KeystoreFormat.JKS, copy.getKeystoreFormat());
    }
}
