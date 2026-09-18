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

/**
 * HPKE base mode smoke tests (RFC 9180).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpkeTest {

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
        Hpke hpke = Hpke.x25519Aes128Gcm();
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
        Hpke hpke = Hpke.x25519Aes128Gcm();
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
        Hpke hpke = Hpke.x25519Aes128Gcm();
        SecureRandom random = new SecureRandom();
        Hpke.RawKeyPair keys = Hpke.generateX25519KeyPair(random);
        byte[] info = new byte[] { 1, 2, 3 };
        Hpke.SenderContext sender = hpke.setupBaseS(keys.publicKey, info, random);
        byte[] enc = sender.getEnc();
        Hpke.RecipientContext recipient = hpke.setupBaseR(enc, keys.privateKey, keys.publicKey, info);
        byte[] aad = "aad".getBytes(StandardCharsets.US_ASCII);
        byte[] plain = "hello ech".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = sender.seal(aad, plain);
        assertArrayEquals(plain, recipient.open(aad, ciphertext));
    }
}
