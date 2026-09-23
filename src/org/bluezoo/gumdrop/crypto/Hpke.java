/*
 * Hpke.java
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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HPKE base mode (RFC 9180) for {@code DHKEM(X25519, HKDF-SHA256)} with
 * {@code HKDF-SHA256} and one of {@code AES-128-GCM} (the suite RFC 9849
 * section 9 requires), {@code AES-256-GCM} or {@code ChaCha20Poly1305}, as
 * used by TLS ECH. The KEM and KDF are fixed; only the AEAD varies, chosen
 * per instance by {@link #x25519HkdfSha256}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9180">RFC 9180</a>
 */
public final class Hpke {

    public static final int KEM_X25519_HKDF_SHA256 = 0x0020;
    public static final int KDF_HKDF_SHA256 = 0x0001;
    public static final int AEAD_AES_128_GCM = 0x0001;
    public static final int AEAD_AES_256_GCM = 0x0002;
    public static final int AEAD_CHACHA20_POLY1305 = 0x0003;

    private static final byte[] HPKE_V1 = "HPKE-v1".getBytes(StandardCharsets.US_ASCII);
    /** RFC 9180 section 4.1: {@code concat("KEM", I2OSP(kem_id, 2))}. */
    private static final byte[] KEM_SUITE_ID = concat(
            "KEM".getBytes(StandardCharsets.US_ASCII), u16(KEM_X25519_HKDF_SHA256));
    private static final byte[] MODE_BASE = new byte[] { 0 };
    private static final byte[] EMPTY_PSK = new byte[0];
    private static final byte[] EMPTY_PSK_ID = new byte[0];
    private static final byte[] X25519_HEADER = hex("302a300506032b656e032100");

    private static final int SECRET_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;

    private final Hkdf hkdf;
    private final int aeadId;
    /** RFC 9180 section 5.1: full HPKE ciphersuite identifier. */
    private final byte[] hpkeSuiteId;
    /** RFC 9180 section 7.3 {@code Nk} for the AEAD. */
    private final int keyLength;

    private Hpke(int aeadId) {
        this.hkdf = Hkdf.sha256();
        this.aeadId = aeadId;
        this.keyLength = aeadId == AEAD_AES_128_GCM ? 16 : 32;
        this.hpkeSuiteId = concat(
                "HPKE".getBytes(StandardCharsets.US_ASCII),
                u16(KEM_X25519_HKDF_SHA256),
                u16(KDF_HKDF_SHA256),
                u16(aeadId));
    }

    /**
     * Returns whether this implementation can run the given HPKE suite:
     * {@code DHKEM(X25519, HKDF-SHA256)} with {@code HKDF-SHA256} and
     * AES-128-GCM, AES-256-GCM or ChaCha20Poly1305.
     *
     * @param kemId the HPKE KEM identifier
     * @param kdfId the HPKE KDF identifier
     * @param aeadId the HPKE AEAD identifier
     * @return true if supported
     */
    public static boolean isSupported(int kemId, int kdfId, int aeadId) {
        return kemId == KEM_X25519_HKDF_SHA256 && kdfId == KDF_HKDF_SHA256
                && (aeadId == AEAD_AES_128_GCM || aeadId == AEAD_AES_256_GCM
                        || aeadId == AEAD_CHACHA20_POLY1305);
    }

    /**
     * Creates an HPKE instance for {@code DHKEM(X25519, HKDF-SHA256)} with
     * {@code HKDF-SHA256} and the given AEAD.
     *
     * @param aeadId {@link #AEAD_AES_128_GCM}, {@link #AEAD_AES_256_GCM} or
     *        {@link #AEAD_CHACHA20_POLY1305}
     * @return the instance
     * @throws IllegalArgumentException if the AEAD is not supported
     */
    public static Hpke x25519HkdfSha256(int aeadId) {
        if (!isSupported(KEM_X25519_HKDF_SHA256, KDF_HKDF_SHA256, aeadId)) {
            throw new IllegalArgumentException("Unsupported HPKE AEAD: " + aeadId);
        }
        return new Hpke(aeadId);
    }

    /**
     * Generates an X25519 key pair for HPKE / ECH.
     *
     * @param random entropy source
     * @return raw 32-byte public key and private key
     */
    public static RawKeyPair generateX25519KeyPair(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        kpg.initialize(NamedParameterSpec.X25519, random);
        KeyPair kp = kpg.generateKeyPair();
        byte[] pub = extractRawPublic(kp.getPublic());
        byte[] priv = extractRawPrivate(kp.getPrivate());
        return new RawKeyPair(pub, priv);
    }

    /** The X25519 base point u = 9 (RFC 7748 section 4.1), little-endian. */
    private static final byte[] X25519_BASE_POINT = hex(
            "0900000000000000000000000000000000000000000000000000000000000000");

    /**
     * Derives the X25519 public key belonging to a raw private key.
     *
     * @param privateKey the 32-byte private key
     * @return the 32-byte public key
     */
    public static byte[] deriveX25519PublicKey(byte[] privateKey) throws GeneralSecurityException {
        return dh(rawX25519Private(privateKey), rawX25519Public(X25519_BASE_POINT));
    }

    /**
     * Sender side of HPKE base setup.
     *
     * @param recipientPublicKey 32-byte X25519 public key
     * @param info application info (for ECH: {@code "tls ech" || 0x00 || ECHConfig})
     * @param random entropy for ephemeral key
     * @return encapsulation value and sealing context
     */
    public SenderContext setupBaseS(byte[] recipientPublicKey, byte[] info, SecureRandom random)
            throws GeneralSecurityException {
        return setupBaseS(rawX25519Public(recipientPublicKey), info, random);
    }

    SenderContext setupBaseS(PublicKey recipientPublicKey, byte[] info, SecureRandom random)
            throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        KeyPair ephemeral = kpg.generateKeyPair();
        byte[] enc = extractRawPublic(ephemeral.getPublic());
        byte[] pkRm = extractRawPublic(recipientPublicKey);
        byte[] sharedSecret = encapSharedSecret(ephemeral.getPrivate(), recipientPublicKey, enc, pkRm);
        return new SenderContext(enc, keyScheduleBase(sharedSecret, info), aeadId);
    }

    /**
     * Recipient side of HPKE base setup.
     *
     * @param enc sender encapsulation (32 bytes)
     * @param recipientPrivateKey 32-byte X25519 private key
     * @param recipientPublicKey 32-byte X25519 public key matching the private key
     * @param info same info as sender
     */
    public RecipientContext setupBaseR(byte[] enc, byte[] recipientPrivateKey, byte[] recipientPublicKey,
            byte[] info) throws GeneralSecurityException {
        return setupBaseR(enc, rawX25519Private(recipientPrivateKey), rawX25519Public(recipientPublicKey), info);
    }

    RecipientContext setupBaseR(byte[] enc, PrivateKey recipientPrivateKey, PublicKey recipientPublicKey, byte[] info)
            throws GeneralSecurityException {
        PublicKey peer = rawX25519Public(enc);
        byte[] pkRm = extractRawPublic(recipientPublicKey);
        byte[] dh = dh(recipientPrivateKey, peer);
        byte[] kemContext = concat(enc, pkRm);
        byte[] sharedSecret = extractAndExpand(dh, kemContext);
        return new RecipientContext(keyScheduleBase(sharedSecret, info), aeadId);
    }

    byte[] encapSharedSecretForTest(PrivateKey skE, PublicKey pkR, byte[] enc, byte[] pkRm)
            throws GeneralSecurityException {
        return encapSharedSecret(skE, pkR, enc, pkRm);
    }

    byte[] decapSharedSecretForTest(PrivateKey skR, byte[] enc, byte[] pkRm) throws GeneralSecurityException {
        PublicKey pkE = rawX25519Public(enc);
        byte[] dh = dh(skR, pkE);
        byte[] kemContext = concat(enc, pkRm);
        return extractAndExpand(dh, kemContext);
    }

    private byte[] encapSharedSecret(PrivateKey skE, PublicKey pkR, byte[] enc, byte[] pkRm)
            throws GeneralSecurityException {
        byte[] dh = dh(skE, pkR);
        byte[] kemContext = concat(enc, pkRm);
        return extractAndExpand(dh, kemContext);
    }

    private byte[] extractAndExpand(byte[] dh, byte[] kemContext) {
        byte[] eaePrk = kemLabeledExtract(new byte[0], "eae_prk", dh);
        return kemLabeledExpand(eaePrk, "shared_secret", kemContext, SECRET_LENGTH);
    }

    private KeySchedule keyScheduleBase(byte[] sharedSecret, byte[] info) {
        byte[] pskIdHash = hpkeLabeledExtract(new byte[0], "psk_id_hash", EMPTY_PSK_ID);
        byte[] infoHash = hpkeLabeledExtract(new byte[0], "info_hash", info);
        byte[] keyScheduleContext = concat(MODE_BASE, pskIdHash, infoHash);
        byte[] secret = hpkeLabeledExtract(sharedSecret, "secret", EMPTY_PSK);
        byte[] key = hpkeLabeledExpand(secret, "key", keyScheduleContext, keyLength);
        byte[] baseNonce = hpkeLabeledExpand(secret, "base_nonce", keyScheduleContext, NONCE_LENGTH);
        byte[] exporterSecret = hpkeLabeledExpand(secret, "exp", keyScheduleContext, hkdf.getHashLength());
        return new KeySchedule(secret, key, baseNonce, exporterSecret);
    }

    SenderContext setupBaseSForTest(PrivateKey skE, PublicKey pkE, PublicKey pkR, byte[] info)
            throws GeneralSecurityException {
        byte[] enc = extractRawPublic(pkE);
        byte[] pkRm = extractRawPublic(pkR);
        byte[] sharedSecret = encapSharedSecret(skE, pkR, enc, pkRm);
        return new SenderContext(enc, keyScheduleBase(sharedSecret, info), aeadId);
    }

    byte[] kemLabeledExtract(byte[] salt, String label, byte[] ikm) {
        return labeledExtract(salt, label, ikm, KEM_SUITE_ID);
    }

    byte[] kemLabeledExpand(byte[] prk, String label, byte[] info, int length) {
        return labeledExpand(prk, label, info, length, KEM_SUITE_ID);
    }

    byte[] hpkeLabeledExtract(byte[] salt, String label, byte[] ikm) {
        return labeledExtract(salt, label, ikm, hpkeSuiteId);
    }

    byte[] hpkeLabeledExpand(byte[] prk, String label, byte[] info, int length) {
        return labeledExpand(prk, label, info, length, hpkeSuiteId);
    }

    private byte[] labeledExtract(byte[] salt, String label, byte[] ikm, byte[] suiteId) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        byte[] labeledIkm = concat(HPKE_V1, suiteId, labelBytes, ikm);
        return hkdf.extract(salt.length == 0 ? hkdf.zeroSalt() : salt, labeledIkm);
    }

    private byte[] labeledExpand(byte[] prk, String label, byte[] info, int length, byte[] suiteId) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        byte[] labeledInfo = concat(u16(length), HPKE_V1, suiteId, labelBytes, info);
        return hkdf.expand(prk, labeledInfo, length);
    }

    private static byte[] dh(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(privateKey);
        ka.doPhase(publicKey, true);
        return ka.generateSecret();
    }

    private static PublicKey rawX25519Public(byte[] raw) throws GeneralSecurityException {
        byte[] wrapped = concat(X25519_HEADER, raw);
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(wrapped));
    }

    private static PrivateKey rawX25519Private(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("X25519").generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, raw));
    }

    static byte[] scheduleKeyForTest(SenderContext context) {
        return context.schedule.key;
    }

    static byte[] scheduleBaseNonceForTest(SenderContext context) {
        return context.schedule.baseNonce;
    }

    static byte[] scheduleKeyForTest(RecipientContext context) {
        return context.schedule.key;
    }

    static byte[] scheduleBaseNonceForTest(RecipientContext context) {
        return context.schedule.baseNonce;
    }

    static byte[] extractRawPublicForTest(PublicKey publicKey) {
        return extractRawPublic(publicKey);
    }

    static PublicKey rawX25519PublicForTest(byte[] raw) throws GeneralSecurityException {
        return rawX25519Public(raw);
    }

    static PrivateKey rawX25519PrivateForTest(byte[] raw) throws GeneralSecurityException {
        return rawX25519Private(raw);
    }

    static byte[] dhForTest(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        return dh(privateKey, publicKey);
    }

    private static byte[] extractRawPublic(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private static byte[] extractRawPrivate(PrivateKey privateKey) throws GeneralSecurityException {
        byte[] encoded = privateKey.getEncoded();
        if (encoded == null || encoded.length < 32) {
            throw new GeneralSecurityException("Cannot extract X25519 private key");
        }
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private static Cipher aead(int mode, int aeadId, byte[] key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher;
        if (aeadId == AEAD_CHACHA20_POLY1305) {
            cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        } else {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        }
        return cipher;
    }

    static byte[] seal(int aeadId, byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = aead(Cipher.ENCRYPT_MODE, aeadId, key, nonce);
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    static byte[] open(int aeadId, byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = aead(Cipher.DECRYPT_MODE, aeadId, key, nonce);
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    static byte[] nonceWithSeq(byte[] baseNonce, long seq) {
        byte[] nonce = Arrays.copyOf(baseNonce, NONCE_LENGTH);
        for (int i = 0; i < 8; i++) {
            int shift = (7 - i) * 8;
            nonce[NONCE_LENGTH - 8 + i] ^= (byte) ((seq >>> shift) & 0xff);
        }
        return nonce;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (int i = 0; i < parts.length; i++) {
            len += parts[i].length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, off, parts[i].length);
            off += parts[i].length;
        }
        return out;
    }

    private static byte[] u16(int value) {
        return new byte[] { (byte) ((value >>> 8) & 0xff), (byte) (value & 0xff) };
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static final class RawKeyPair {
        private final byte[] publicKey;
        private final byte[] privateKey;

        RawKeyPair(byte[] publicKey, byte[] privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        public byte[] getPublicKey() {
            return publicKey;
        }

        public byte[] getPrivateKey() {
            return privateKey;
        }
    }

    static final class KeySchedule {
        final byte[] secret;
        final byte[] key;
        final byte[] baseNonce;
        final byte[] exporterSecret;

        KeySchedule(byte[] secret, byte[] key, byte[] baseNonce, byte[] exporterSecret) {
            this.secret = secret;
            this.key = key;
            this.baseNonce = baseNonce;
            this.exporterSecret = exporterSecret;
        }
    }

    /**
     * HPKE sender context after {@link #setupBaseS}.
     */
    public static final class SenderContext {
        private final byte[] enc;
        final KeySchedule schedule;
        private final int aeadId;
        private long seq;

        SenderContext(byte[] enc, KeySchedule schedule, int aeadId) {
            this.enc = enc;
            this.schedule = schedule;
            this.aeadId = aeadId;
        }

        public byte[] getEnc() {
            return enc;
        }

        public int getKdfId() {
            return KDF_HKDF_SHA256;
        }

        public int getAeadId() {
            return aeadId;
        }

        public byte[] seal(byte[] aad, byte[] plaintext) throws GeneralSecurityException {
            byte[] nonce = nonceWithSeq(schedule.baseNonce, seq++);
            return Hpke.seal(aeadId, schedule.key, nonce, aad, plaintext);
        }
    }

    /**
     * HPKE recipient context after {@link #setupBaseR}.
     */
    public static final class RecipientContext {
        final KeySchedule schedule;
        private final int aeadId;
        private long seq;

        RecipientContext(KeySchedule schedule, int aeadId) {
            this.schedule = schedule;
            this.aeadId = aeadId;
        }

        public int getKdfId() {
            return KDF_HKDF_SHA256;
        }

        public int getAeadId() {
            return aeadId;
        }

        public byte[] open(byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
            byte[] nonce = nonceWithSeq(schedule.baseNonce, seq++);
            return Hpke.open(aeadId, schedule.key, nonce, aad, ciphertext);
        }
    }
}
