/*
 * KeyExchange.java
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

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;

/**
 * TLS 1.3 ephemeral key exchange (RFC 8446 section 4.2.8) over JCA, for
 * the groups in {@link NamedGroup}: the three classical curves and the
 * three RFC 10024 post-quantum/classical hybrids.
 *
 * <p>JCA encodes an asymmetric public key as an X.509
 * {@code SubjectPublicKeyInfo} DER structure, but TLS wants the bare key
 * material on the wire (a 32-byte X25519 u-coordinate, an uncompressed EC
 * point, a raw ML-KEM encapsulation key/ciphertext). For every algorithm
 * used here, the SPKI DER is a fixed-length {@code AlgorithmIdentifier}
 * header directly followed by the bare key material as the BIT STRING
 * content (confirmed against this project's pinned JDK, not assumed) --
 * so wire encoding/decoding is a matter of stripping or prepending that
 * fixed header, never a field-by-field ASN.1 walk or a big-integer
 * byte-order conversion.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.8">RFC 8446 section 4.2.8</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc10024">RFC 10024</a>
 */
public final class KeyExchange {

    // Fixed SubjectPublicKeyInfo AlgorithmIdentifier+length headers, byte-exact
    // for every key this class generates, since the key length per algorithm
    // never varies. Captured directly from this JDK's own encoder output.
    private static final byte[] X25519_HEADER = hex("302a300506032b656e032100");
    private static final byte[] SECP256R1_HEADER = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    private static final byte[] SECP384R1_HEADER = hex("3076301006072a8648ce3d020106052b81040022036200");
    private static final byte[] ML_KEM_768_HEADER = hex("308204b2300b0609608648016503040402038204a100");
    private static final byte[] ML_KEM_1024_HEADER = hex("30820632300b06096086480165030404030382062100");

    private static final int ML_KEM_768_PUBLIC_KEY_LENGTH = 1184;
    private static final int ML_KEM_768_CIPHERTEXT_LENGTH = 1088;
    private static final int ML_KEM_1024_PUBLIC_KEY_LENGTH = 1568;
    private static final int ML_KEM_1024_CIPHERTEXT_LENGTH = 1568;

    private final NamedGroup group;
    private final KeyPair classicalKeyPair;
    private final KeyPair mlkemKeyPair;

    private KeyExchange(NamedGroup group, KeyPair classicalKeyPair, KeyPair mlkemKeyPair) {
        this.group = group;
        this.classicalKeyPair = classicalKeyPair;
        this.mlkemKeyPair = mlkemKeyPair;
    }

    /**
     * Generates a fresh ephemeral key share to offer to the peer. Used by
     * the client for every group it offers in {@code key_share}, and by
     * the server for the classical groups (the server's half of a hybrid
     * exchange is produced by {@link #agreeAsServer}, not this method,
     * since the server never generates its own ML-KEM key pair -- it
     * encapsulates against the client's).
     *
     * @param group the group to generate a share for
     * @return a key exchange holding the freshly-generated private state
     * @throws GeneralSecurityException if the platform cannot generate
     *         keys for this group
     */
    public static KeyExchange generate(NamedGroup group) throws GeneralSecurityException {
        KeyPair classical = generateClassical(classicalComponent(group));
        KeyPair mlkem = null;
        if (group.isHybrid()) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(mlkemAlgorithm(group));
            mlkem = kpg.generateKeyPair();
        }
        return new KeyExchange(group, classical, mlkem);
    }

    /**
     * Returns the group this key exchange was generated for.
     *
     * @return the group
     */
    public NamedGroup getGroup() {
        return group;
    }

    /**
     * Encodes this side's share for the wire, in {@code KeyShareEntry}
     * format (RFC 8446 section 4.2.8): the bare classical public key for
     * a classical group, or the ML-KEM public key concatenated with the
     * classical public key (order per {@link NamedGroup#isPqFirst()}) for
     * a hybrid group.
     *
     * @return the wire-format key share bytes
     */
    public byte[] getShareBytes() {
        byte[] classicalRaw = stripHeader(classicalKeyPair.getPublic().getEncoded(), headerFor(classicalComponent(group)));
        if (mlkemKeyPair == null) {
            return classicalRaw;
        }
        byte[] mlkemRaw = stripHeader(mlkemKeyPair.getPublic().getEncoded(), mlkemHeader(group));
        return group.isPqFirst() ? combine(mlkemRaw, classicalRaw) : combine(classicalRaw, mlkemRaw);
    }

    /**
     * Client-side: completes the exchange against the server's response
     * share, producing the shared secret.
     *
     * @param peerShareBytes the server's {@code KeyShareEntry} bytes
     * @return the shared secret
     * @throws GeneralSecurityException if the peer's share is invalid
     *         for this group
     */
    public byte[] agree(byte[] peerShareBytes) throws GeneralSecurityException {
        NamedGroup classicalGroup = classicalComponent(group);
        if (mlkemKeyPair == null) {
            PublicKey peerPublic = classicalPublicKey(classicalGroup, peerShareBytes);
            return classicalAgree(classicalGroup, classicalKeyPair.getPrivate(), peerPublic);
        }
        int ciphertextLength = mlkemCiphertextLength(group);
        int classicalLength = classicalGroup.getClientShareLength();
        if (peerShareBytes.length != ciphertextLength + classicalLength) {
            throw new GeneralSecurityException("Malformed hybrid server key share: length "
                    + peerShareBytes.length);
        }
        byte[] ciphertext = new byte[ciphertextLength];
        byte[] serverClassicalRaw = new byte[classicalLength];
        if (group.isPqFirst()) {
            System.arraycopy(peerShareBytes, 0, ciphertext, 0, ciphertextLength);
            System.arraycopy(peerShareBytes, ciphertextLength, serverClassicalRaw, 0, classicalLength);
        } else {
            System.arraycopy(peerShareBytes, 0, serverClassicalRaw, 0, classicalLength);
            System.arraycopy(peerShareBytes, classicalLength, ciphertext, 0, ciphertextLength);
        }

        KEM kem = KEM.getInstance(mlkemAlgorithm(group));
        KEM.Decapsulator decapsulator = kem.newDecapsulator(mlkemKeyPair.getPrivate());
        SecretKey mlkemSecret = decapsulator.decapsulate(ciphertext);

        PublicKey serverClassicalPublic = classicalPublicKey(classicalGroup, serverClassicalRaw);
        byte[] classicalSecret = classicalAgree(classicalGroup, classicalKeyPair.getPrivate(), serverClassicalPublic);

        return group.isPqFirst() ? combine(mlkemSecret.getEncoded(), classicalSecret)
                : combine(classicalSecret, mlkemSecret.getEncoded());
    }

    /**
     * Server-side: given the client's offered share for a group, produces
     * the server's response share and the resulting shared secret in one
     * step. For a classical group this generates a fresh ephemeral server
     * key pair and performs ordinary ECDH/XDH agreement; for a hybrid
     * group the ML-KEM half is an encapsulation against the client's
     * public key (the server has no ML-KEM private key of its own) and
     * the classical half is ordinary ephemeral agreement.
     *
     * @param group the negotiated group
     * @param clientShareBytes the client's {@code KeyShareEntry} bytes
     * @return the server's response share and the shared secret
     * @throws GeneralSecurityException if the client's share is invalid
     *         for this group
     */
    public static ServerResult agreeAsServer(NamedGroup group, byte[] clientShareBytes)
            throws GeneralSecurityException {
        if (!group.isHybrid()) {
            KeyPair serverEphemeral = generateClassical(group);
            PublicKey clientPublic = classicalPublicKey(group, clientShareBytes);
            byte[] secret = classicalAgree(group, serverEphemeral.getPrivate(), clientPublic);
            byte[] serverShare = stripHeader(serverEphemeral.getPublic().getEncoded(), headerFor(group));
            return new ServerResult(serverShare, secret);
        }

        NamedGroup classicalGroup = classicalComponent(group);
        int publicKeyLength = mlkemPublicKeyLength(group);
        int classicalLength = classicalGroup.getClientShareLength();
        if (clientShareBytes.length != publicKeyLength + classicalLength) {
            throw new GeneralSecurityException("Malformed hybrid client key share: length "
                    + clientShareBytes.length);
        }
        byte[] mlkemPublicRaw = new byte[publicKeyLength];
        byte[] clientClassicalRaw = new byte[classicalLength];
        if (group.isPqFirst()) {
            System.arraycopy(clientShareBytes, 0, mlkemPublicRaw, 0, publicKeyLength);
            System.arraycopy(clientShareBytes, publicKeyLength, clientClassicalRaw, 0, classicalLength);
        } else {
            System.arraycopy(clientShareBytes, 0, clientClassicalRaw, 0, classicalLength);
            System.arraycopy(clientShareBytes, classicalLength, mlkemPublicRaw, 0, publicKeyLength);
        }

        String mlkemAlgorithm = mlkemAlgorithm(group);
        PublicKey clientMlkemPublic = wrapAndDecode(mlkemAlgorithm, mlkemHeader(group), mlkemPublicRaw);
        KEM kem = KEM.getInstance(mlkemAlgorithm);
        KEM.Encapsulator encapsulator = kem.newEncapsulator(clientMlkemPublic);
        KEM.Encapsulated encapsulated = encapsulator.encapsulate();
        byte[] ciphertext = encapsulated.encapsulation();
        byte[] mlkemSecret = encapsulated.key().getEncoded();

        KeyPair serverClassical = generateClassical(classicalGroup);
        PublicKey clientClassicalPublic = classicalPublicKey(classicalGroup, clientClassicalRaw);
        byte[] classicalSecret = classicalAgree(classicalGroup, serverClassical.getPrivate(), clientClassicalPublic);
        byte[] serverClassicalRaw = stripHeader(serverClassical.getPublic().getEncoded(), headerFor(classicalGroup));

        byte[] serverShare = group.isPqFirst() ? combine(ciphertext, serverClassicalRaw)
                : combine(serverClassicalRaw, ciphertext);
        byte[] combinedSecret = group.isPqFirst() ? combine(mlkemSecret, classicalSecret)
                : combine(classicalSecret, mlkemSecret);
        return new ServerResult(serverShare, combinedSecret);
    }

    /**
     * The server's response share and the resulting shared secret,
     * returned together from {@link KeyExchange#agreeAsServer}.
     */
    public static final class ServerResult {

        private final byte[] shareBytes;
        private final byte[] sharedSecret;

        private ServerResult(byte[] shareBytes, byte[] sharedSecret) {
            this.shareBytes = shareBytes;
            this.sharedSecret = sharedSecret;
        }

        /**
         * Returns the server's key share, to send back to the client.
         *
         * @return the wire-format server key share bytes
         */
        public byte[] getShareBytes() {
            return shareBytes;
        }

        /**
         * Returns the shared secret agreed with the client.
         *
         * @return the shared secret
         */
        public byte[] getSharedSecret() {
            return sharedSecret;
        }

    }

    private static NamedGroup classicalComponent(NamedGroup group) {
        switch (group) {
            case X25519_MLKEM768:
                return NamedGroup.X25519;
            case SECP256R1_MLKEM768:
                return NamedGroup.SECP256R1;
            case SECP384R1_MLKEM1024:
                return NamedGroup.SECP384R1;
            default:
                return group;
        }
    }

    private static String mlkemAlgorithm(NamedGroup group) {
        return group == NamedGroup.SECP384R1_MLKEM1024 ? "ML-KEM-1024" : "ML-KEM-768";
    }

    private static byte[] mlkemHeader(NamedGroup group) {
        return group == NamedGroup.SECP384R1_MLKEM1024 ? ML_KEM_1024_HEADER : ML_KEM_768_HEADER;
    }

    private static int mlkemPublicKeyLength(NamedGroup group) {
        return group == NamedGroup.SECP384R1_MLKEM1024 ? ML_KEM_1024_PUBLIC_KEY_LENGTH : ML_KEM_768_PUBLIC_KEY_LENGTH;
    }

    private static int mlkemCiphertextLength(NamedGroup group) {
        return group == NamedGroup.SECP384R1_MLKEM1024 ? ML_KEM_1024_CIPHERTEXT_LENGTH : ML_KEM_768_CIPHERTEXT_LENGTH;
    }

    private static KeyPair generateClassical(NamedGroup group) throws GeneralSecurityException {
        switch (group) {
            case X25519:
                return KeyPairGenerator.getInstance("X25519").generateKeyPair();
            case SECP256R1: {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                return kpg.generateKeyPair();
            }
            case SECP384R1: {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp384r1"));
                return kpg.generateKeyPair();
            }
            default:
                throw new GeneralSecurityException("Not a classical group: " + group);
        }
    }

    private static byte[] classicalAgree(NamedGroup group, PrivateKey privateKey, PublicKey peerPublic)
            throws GeneralSecurityException {
        String algorithm = (group == NamedGroup.X25519) ? "XDH" : "ECDH";
        KeyAgreement ka = KeyAgreement.getInstance(algorithm);
        ka.init(privateKey);
        ka.doPhase(peerPublic, true);
        return ka.generateSecret();
    }

    private static PublicKey classicalPublicKey(NamedGroup group, byte[] raw) throws GeneralSecurityException {
        int expected = group.getClientShareLength();
        if (raw.length != expected) {
            throw new GeneralSecurityException("Malformed " + group + " key share: expected "
                    + expected + " bytes, got " + raw.length);
        }
        String algorithm = (group == NamedGroup.X25519) ? "X25519" : "EC";
        return wrapAndDecode(algorithm, headerFor(group), raw);
    }

    private static PublicKey wrapAndDecode(String algorithm, byte[] header, byte[] raw) throws GeneralSecurityException {
        byte[] wrapped = new byte[header.length + raw.length];
        System.arraycopy(header, 0, wrapped, 0, header.length);
        System.arraycopy(raw, 0, wrapped, header.length, raw.length);
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        return kf.generatePublic(new X509EncodedKeySpec(wrapped));
    }

    private static byte[] headerFor(NamedGroup group) {
        switch (group) {
            case X25519:
                return X25519_HEADER;
            case SECP256R1:
                return SECP256R1_HEADER;
            case SECP384R1:
                return SECP384R1_HEADER;
            default:
                throw new IllegalArgumentException("Not a classical group: " + group);
        }
    }

    private static byte[] stripHeader(byte[] encoded, byte[] header) {
        byte[] raw = new byte[encoded.length - header.length];
        System.arraycopy(encoded, header.length, raw, 0, raw.length);
        return raw;
    }

    private static byte[] combine(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            out[i / 2] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

}
