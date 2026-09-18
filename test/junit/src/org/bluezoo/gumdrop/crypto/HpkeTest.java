/*
 * HpkeTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

/**
 * HPKE base mode smoke tests (RFC 9180).
 */
public class HpkeTest {

    @Test
    public void wirePublicKeyRoundTripsThroughX509Header() throws GeneralSecurityException {
        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] enc = Hpke.extractRawPublicForTest(ephemeral.getPublic());
        assertArrayEquals(Hpke.dhForTest(ephemeral.getPrivate(), ephemeral.getPublic()),
                Hpke.dhForTest(ephemeral.getPrivate(), Hpke.rawX25519PublicForTest(enc)));
    }

    @Test
    public void kemSharedSecretMatchesOnBothSides() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519Aes128Gcm();
        KeyPair recipient = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] enc = Hpke.extractRawPublicForTest(ephemeral.getPublic());
        byte[] pkRm = Hpke.extractRawPublicForTest(recipient.getPublic());
        assertArrayEquals(
                Hpke.dhForTest(ephemeral.getPrivate(), recipient.getPublic()),
                Hpke.dhForTest(recipient.getPrivate(), Hpke.rawX25519PublicForTest(enc)));
        assertArrayEquals(
                hpke.encapSharedSecretForTest(ephemeral.getPrivate(), recipient.getPublic(), enc, pkRm),
                hpke.decapSharedSecretForTest(recipient.getPrivate(), enc, pkRm));
    }

    @Test
    public void roundTripSealOpenWithJceKeys() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519Aes128Gcm();
        SecureRandom random = new SecureRandom();
        KeyPair keys = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] info = new byte[] { 1, 2, 3 };
        Hpke.SenderContext sender = hpke.setupBaseS(keys.getPublic(), info, random);
        Hpke.RecipientContext recipient = hpke.setupBaseR(sender.getEnc(), keys.getPrivate(), keys.getPublic(), info);
        assertArrayEquals(sender.schedule.key, recipient.schedule.key);
        assertArrayEquals(sender.schedule.baseNonce, recipient.schedule.baseNonce);
        byte[] aad = "aad".getBytes();
        byte[] plain = "hello ech".getBytes();
        assertArrayEquals(plain, recipient.open(aad, sender.seal(aad, plain)));
    }

    @Test
    public void roundTripSealOpenWithRawKeys() throws GeneralSecurityException {
        Hpke hpke = Hpke.x25519Aes128Gcm();
        SecureRandom random = new SecureRandom();
        Hpke.RawKeyPair keys = Hpke.generateX25519KeyPair(random);
        byte[] info = new byte[] { 1, 2, 3 };
        Hpke.SenderContext sender = hpke.setupBaseS(keys.publicKey, info, random);
        Hpke.RecipientContext recipient = hpke.setupBaseR(sender.getEnc(), keys.privateKey, keys.publicKey, info);
        byte[] aad = "aad".getBytes();
        byte[] plain = "hello ech".getBytes();
        byte[] ct = sender.seal(aad, plain);
        assertArrayEquals(plain, recipient.open(aad, ct));
    }
}
