/*
 * HpkeTest.java
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

package org.bluezoo.gumdrop.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * HPKE base mode smoke tests (RFC 9180).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpkeTest {

    @Test
    public void rfc9180AppendixA1BaseSetupAndEncryption() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        byte[] skEm = hex("52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736");
        byte[] pkEm = hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431");
        byte[] skRm = hex("4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8");
        byte[] pkRm = hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
        byte[] info = hex("4f6465206f6e2061204772656369616e2055726e");
        byte[] expectedSharedSecret = hex("fe0e18c9f024ce43799ae393c7e8fe8fce9d218875e8227b0187c04e7d2ea1fc");
        byte[] expectedKey = hex("4531685d41d65f03dc48f6b8302c05b0");
        byte[] expectedBaseNonce = hex("56d890e5accaaf011cff4b7d");

        byte[] encapSecret = hpke.encapSharedSecretForTest(
                Hpke.rawX25519PrivateForTest(skEm),
                Hpke.rawX25519PublicForTest(pkRm),
                pkEm,
                pkRm);
        byte[] decapSecret = hpke.decapSharedSecretForTest(
                Hpke.rawX25519PrivateForTest(skRm), pkEm, pkRm);
        assertArrayEquals(expectedSharedSecret, encapSecret);
        assertArrayEquals(expectedSharedSecret, decapSecret);

        Hpke.SenderContext sender = hpke.setupBaseSForTest(
                Hpke.rawX25519PrivateForTest(skEm),
                Hpke.rawX25519PublicForTest(pkEm),
                Hpke.rawX25519PublicForTest(pkRm),
                info);
        Hpke.RecipientContext recipientCtx = hpke.setupBaseR(pkEm, skRm, pkRm, info);
        assertArrayEquals(expectedKey, Hpke.scheduleKeyForTest(sender));
        assertArrayEquals(expectedKey, Hpke.scheduleKeyForTest(recipientCtx));
        assertArrayEquals(expectedBaseNonce, Hpke.scheduleBaseNonceForTest(sender));

        byte[] pt = hex("4265617574792069732074727574682c20747275746820626561757479");
        byte[] aad = hex("436f756e742d30");
        byte[] expectedCt = hex("f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a9"
                + "6d8770ac83d07bea87e13c512a");
        assertArrayEquals(expectedCt, sender.seal(aad, pt));
        assertArrayEquals(pt, recipientCtx.open(aad, expectedCt));
    }

    @Test
    public void wirePublicKeyRoundTripsThroughX509Header() throws GeneralSecurityException {
        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] enc = Hpke.extractRawPublicForTest(ephemeral.getPublic());
        byte[] expected = Hpke.dhForTest(ephemeral.getPrivate(), ephemeral.getPublic());
        byte[] actual = Hpke.dhForTest(ephemeral.getPrivate(), Hpke.rawX25519PublicForTest(enc));
        assertArrayEquals(expected, actual);
    }

    @Test
    public void kemSharedSecretMatchesOnBothSides() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        KeyPair recipient = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] enc = Hpke.extractRawPublicForTest(ephemeral.getPublic());
        byte[] pkRm = Hpke.extractRawPublicForTest(recipient.getPublic());
        byte[] encapDh = Hpke.dhForTest(ephemeral.getPrivate(), recipient.getPublic());
        byte[] decapDh = Hpke.dhForTest(recipient.getPrivate(), Hpke.rawX25519PublicForTest(enc));
        assertArrayEquals(encapDh, decapDh);
        byte[] encapSecret = hpke.encapSharedSecretForTest(ephemeral.getPrivate(), recipient.getPublic(), enc, pkRm);
        byte[] decapSecret = hpke.decapSharedSecretForTest(recipient.getPrivate(), enc, pkRm);
        assertArrayEquals(encapSecret, decapSecret);
    }

    @Test
    public void roundTripSealOpenWithJceKeys() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        SecureRandom random = new SecureRandom();
        KeyPair keys = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] info = new byte[] { 1, 2, 3 };
        Hpke.SenderContext sender = hpke.setupBaseS(keys.getPublic(), info, random);
        byte[] enc = sender.getEnc();
        Hpke.RecipientContext recipient = hpke.setupBaseR(enc, keys.getPrivate(), keys.getPublic(), info);
        assertArrayEquals(Hpke.scheduleKeyForTest(sender), Hpke.scheduleKeyForTest(recipient));
        assertArrayEquals(Hpke.scheduleBaseNonceForTest(sender), Hpke.scheduleBaseNonceForTest(recipient));
        byte[] aad = "aad".getBytes(StandardCharsets.US_ASCII);
        byte[] plain = "hello ech".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = sender.seal(aad, plain);
        assertArrayEquals(plain, recipient.open(aad, ciphertext));
    }

    @Test
    public void roundTripSealOpenWithRawKeys() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM);
        SecureRandom random = new SecureRandom();
        Hpke.RawKeyPair keys = Hpke.generateX25519KeyPair(random);
        byte[] info = new byte[] { 1, 2, 3 };
        Hpke.SenderContext sender = hpke.setupBaseS(keys.getPublicKey(), info, random);
        byte[] enc = sender.getEnc();
        Hpke.RecipientContext recipient = hpke.setupBaseR(enc, keys.getPrivateKey(), keys.getPublicKey(), info);
        byte[] aad = "aad".getBytes(StandardCharsets.US_ASCII);
        byte[] plain = "hello ech".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = sender.seal(aad, plain);
        assertArrayEquals(plain, recipient.open(aad, ciphertext));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final int[] AEADS = {
        Hpke.AEAD_AES_128_GCM, Hpke.AEAD_AES_256_GCM, Hpke.AEAD_CHACHA20_POLY1305
    };

    @Test
    public void everySupportedAeadRoundTrips() throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        for (int aead : AEADS) {
            Hpke hpke = Hpke.x25519HkdfSha256(aead);
            Hpke.RawKeyPair recipient = Hpke.generateX25519KeyPair(random);
            byte[] info = "tls ech".getBytes(StandardCharsets.US_ASCII);
            Hpke.SenderContext sender = hpke.setupBaseS(recipient.getPublicKey(), info, random);
            Hpke.RecipientContext recipientCtx = hpke.setupBaseR(sender.getEnc(),
                    recipient.getPrivateKey(), recipient.getPublicKey(), info);
            assertEquals(Hpke.KDF_HKDF_SHA256, sender.getKdfId());
            assertEquals(aead, sender.getAeadId());
            for (int i = 0; i < 3; i++) {
                byte[] aad = new byte[] { (byte) i, 2, 3 };
                byte[] plain = ("message " + i + " under aead " + aead).getBytes(StandardCharsets.US_ASCII);
                byte[] ct = sender.seal(aad, plain);
                assertEquals(plain.length + 16, ct.length);
                assertArrayEquals(plain, recipientCtx.open(aad, ct));
            }
        }
    }

    @Test
    public void aeadMismatchBetweenSenderAndRecipientFailsToOpen() throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        Hpke.RawKeyPair recipient = Hpke.generateX25519KeyPair(random);
        byte[] info = new byte[] { 1 };
        Hpke.SenderContext sender = Hpke.x25519HkdfSha256(Hpke.AEAD_CHACHA20_POLY1305)
                .setupBaseS(recipient.getPublicKey(), info, random);
        Hpke.RecipientContext wrong = Hpke.x25519HkdfSha256(Hpke.AEAD_AES_128_GCM)
                .setupBaseR(sender.getEnc(), recipient.getPrivateKey(), recipient.getPublicKey(), info);
        byte[] ct = sender.seal(new byte[0], new byte[] { 9, 9, 9 });
        try {
            wrong.open(new byte[0], ct);
            fail("a recipient using another AEAD must not open the message");
        } catch (GeneralSecurityException expected) {
            // authentication failure
        }
    }

    @Test
    public void supportedSuiteIsX25519Sha256WithThreeAeads() {
        for (int aead : AEADS) {
            assertTrue(Hpke.isSupported(Hpke.KEM_X25519_HKDF_SHA256, Hpke.KDF_HKDF_SHA256, aead));
        }
        assertFalse(Hpke.isSupported(0x0010, Hpke.KDF_HKDF_SHA256, Hpke.AEAD_AES_128_GCM));
        assertFalse(Hpke.isSupported(Hpke.KEM_X25519_HKDF_SHA256, 0x0002, Hpke.AEAD_AES_128_GCM));
        assertFalse(Hpke.isSupported(Hpke.KEM_X25519_HKDF_SHA256, Hpke.KDF_HKDF_SHA256, 0xffff));
    }

    @Test
    public void unsupportedAeadIsRejected() {
        try {
            Hpke.x25519HkdfSha256(0xffff);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // not a supported AEAD
        }
    }

}
