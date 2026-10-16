/*
 * CryptoServices.java
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
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The source of every JCA service the TLS, DTLS and QUIC stacks use:
 * ciphers, MACs, digests, signatures, key generation and agreement,
 * certificate and trust-manager factories, and random numbers. One
 * instance belongs to each {@link org.bluezoo.gumdrop.Gumdrop} runtime,
 * and the handshake configurations, record engines and QUIC engines that
 * runtime creates all draw their services from it, so that the provider
 * those services come from is a property of the runtime rather than of
 * whichever provider happens to be first in the JVM's list.
 *
 * <p>An instance created with the no-argument constructor pins no
 * provider: each service is resolved the way an unqualified
 * {@code getInstance} would resolve it. This is the runtime's default,
 * and what standalone code (a {@link org.bluezoo.gumdrop.SelectorLoop}
 * created outside any runtime, or a unit test) uses. A runtime in FIPS
 * mode instead holds an instance from {@link #pinned}, which requests
 * every service from the one provider the operator named.
 *
 * <p>The service objects returned here are new on every call, since
 * {@link Cipher}, {@link Mac}, {@link MessageDigest}, {@link Signature} and
 * {@link KDF} carry per-operation state and are not safe to share between
 * threads. There is one {@link #secureRandom() random source} per thread:
 * the common implementations serialise {@code nextBytes} on the instance,
 * so a single shared generator would make every selector loop queue
 * behind the others for each handshake random, nonce and connection
 * identifier, where one per loop thread is uncontended.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CryptoServices {

    /** The TLS 1.3 label prefix prepended to every HKDF-Expand-Label label (RFC 8446 section 7.1). */
    private static final byte[] TLS13_LABEL_PREFIX = "tls13 ".getBytes(StandardCharsets.US_ASCII);

    /**
     * The algorithm name given to a pseudorandom key handed back to a KDF
     * for expansion. HKDF places no meaning on it.
     */
    private static final String PRK_ALGORITHM = "HKDF-PRK";

    private final Provider provider;

    // One KDF per hash per thread: a KDF carries per-operation state
    private final ThreadLocal<Map<String, KDF>> kdfs = new ThreadLocal<Map<String, KDF>>() {
        @Override
        protected Map<String, KDF> initialValue() {
            return new HashMap<String, KDF>();
        }
    };
    private final ThreadLocal<SecureRandom> random = new ThreadLocal<SecureRandom>() {
        @Override
        protected SecureRandom initialValue() {
            return newSecureRandom();
        }
    };

    /**
     * The algorithm the pinned provider's random source is requested by,
     * or null for its first registered {@code SecureRandom} service.
     */
    private final String secureRandomAlgorithm;

    /**
     * Creates services that pin no provider: every service is resolved
     * through the JVM's provider list, and random numbers come from
     * {@code new SecureRandom()}.
     */
    public CryptoServices() {
        this.provider = null;
        this.secureRandomAlgorithm = null;
    }

    private CryptoServices(Provider provider, String secureRandomAlgorithm) {
        this.provider = provider;
        this.secureRandomAlgorithm = secureRandomAlgorithm;
    }

    /**
     * Creates services pinned to one installed provider, for a runtime in
     * FIPS mode: every cryptographic service is requested from it and
     * nothing falls back to another provider. The provider is looked up by
     * name once, here, and checked for every service type and algorithm
     * the TLS, DTLS and QUIC stacks need, so that a module that is absent
     * or incomplete stops the runtime from booting rather than failing
     * its first handshake.
     *
     * <p>Key and trust manager factories, and the PKCS12 key store used
     * to hold trust anchors, are not themselves approved algorithms and
     * are commonly shipped in a separate JSSE provider from the validated
     * module. They are taken from the pinned provider when it offers
     * them, and from the JVM's provider list otherwise.
     *
     * @param providerName the name of an installed provider
     * @param secureRandomAlgorithm the provider's random source algorithm
     *        to use, or null for the first one it registers
     * @return the pinned services
     * @throws IllegalStateException if the provider is not installed, has
     *         no random source, or lacks a required service
     */
    public static CryptoServices pinned(String providerName, String secureRandomAlgorithm) {
        if (providerName == null) {
            throw new NullPointerException("providerName");
        }
        Provider provider = Security.getProvider(providerName);
        if (provider == null) {
            throw new IllegalStateException("FIPS provider " + providerName + " is not installed");
        }
        String randomAlgorithm = secureRandomAlgorithm;
        if (randomAlgorithm == null) {
            for (Provider.Service service : provider.getServices()) {
                if ("SecureRandom".equals(service.getType())) {
                    randomAlgorithm = service.getAlgorithm();
                    break;
                }
            }
        }
        // A null algorithm here means the provider registers no random
        // source at all; verifyRequiredServices reports it with the rest
        CryptoServices services = new CryptoServices(provider, randomAlgorithm);
        services.verifyRequiredServices();
        return services;
    }

    /**
     * Every service type and algorithm the TLS, DTLS and QUIC stacks ask
     * this object for, as {@code type:algorithm}.
     */
    private static final String[] REQUIRED_SERVICES = {
        "Cipher:AES/GCM/NoPadding",
        "Mac:HmacSHA256",
        "Mac:HmacSHA384",
        "MessageDigest:SHA-256",
        "MessageDigest:SHA-384",
        "KDF:HKDF-SHA256",
        "KDF:HKDF-SHA384",
        "Signature:SHA256withECDSA",
        "Signature:SHA384withECDSA",
        "Signature:SHA256withRSA",
        "Signature:RSASSA-PSS",
        "KeyPairGenerator:EC",
        "KeyAgreement:ECDH",
        "KeyFactory:EC",
        "KeyFactory:RSA",
        "CertificateFactory:X.509",
    };

    private void verifyRequiredServices() {
        List<String> missing = new ArrayList<String>();
        if (secureRandomAlgorithm == null) {
            missing.add("SecureRandom");
        } else {
            try {
                newSecureRandom();
            } catch (IllegalStateException e) {
                missing.add("SecureRandom " + secureRandomAlgorithm);
            }
        }
        for (int i = 0; i < REQUIRED_SERVICES.length; i++) {
            String[] parts = REQUIRED_SERVICES[i].split(":", 2);
            String type = parts[0];
            String algorithm = parts[1];
            try {
                if ("Cipher".equals(type)) {
                    cipher(algorithm);
                } else if ("Mac".equals(type)) {
                    mac(algorithm);
                } else if ("MessageDigest".equals(type)) {
                    messageDigest(algorithm);
                } else if ("KDF".equals(type)) {
                    kdf(algorithm);
                } else if ("Signature".equals(type)) {
                    signature(algorithm);
                } else if ("KeyPairGenerator".equals(type)) {
                    keyPairGenerator(algorithm);
                } else if ("KeyAgreement".equals(type)) {
                    keyAgreement(algorithm);
                } else if ("KeyFactory".equals(type)) {
                    keyFactory(algorithm);
                } else if ("CertificateFactory".equals(type)) {
                    certificateFactory(algorithm);
                }
            } catch (GeneralSecurityException e) {
                missing.add(type + " " + algorithm);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("FIPS provider " + provider.getName()
                    + " cannot supply required services: " + missing);
        }
    }

    /**
     * Returns the provider every service is taken from, or null when
     * services are resolved through the JVM's provider list.
     *
     * @return the pinned provider, or null
     */
    public Provider getProvider() {
        return provider;
    }

    /**
     * Returns the calling thread's source of random numbers, for handshake
     * random values, nonces, connection identifiers and the like. Callers
     * take it where they need it rather than holding on to it, so that
     * work moved to another thread draws on that thread's generator.
     *
     * @return the random source
     */
    public SecureRandom secureRandom() {
        return random.get();
    }

    /**
     * Creates a new random source, the first time a thread asks for one.
     *
     * @return a new generator
     */
    private SecureRandom newSecureRandom() {
        if (provider == null) {
            return new SecureRandom();
        }
        try {
            return SecureRandom.getInstance(secureRandomAlgorithm, provider);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("FIPS provider " + provider.getName()
                    + " cannot supply SecureRandom " + secureRandomAlgorithm, e);
        }
    }

    /**
     * Fills {@code bytes} with random bytes from {@link #secureRandom()}.
     *
     * @param bytes the array to fill
     */
    public void nextBytes(byte[] bytes) {
        random.get().nextBytes(bytes);
    }

    /**
     * Returns a new cipher for a transformation such as
     * {@code "AES/GCM/NoPadding"}.
     *
     * @param transformation the JCA transformation name
     * @return a new, uninitialised cipher
     * @throws GeneralSecurityException if the transformation is not available
     */
    public Cipher cipher(String transformation) throws GeneralSecurityException {
        return provider == null ? Cipher.getInstance(transformation)
                : Cipher.getInstance(transformation, provider);
    }

    /**
     * Returns a new MAC for an algorithm such as {@code "HmacSHA256"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new, uninitialised MAC
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public Mac mac(String algorithm) throws GeneralSecurityException {
        return provider == null ? Mac.getInstance(algorithm) : Mac.getInstance(algorithm, provider);
    }

    /**
     * Returns a new message digest for an algorithm such as {@code "SHA-256"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new digest
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public MessageDigest messageDigest(String algorithm) throws GeneralSecurityException {
        return provider == null ? MessageDigest.getInstance(algorithm)
                : MessageDigest.getInstance(algorithm, provider);
    }

    /**
     * Returns a new signature engine for an algorithm such as
     * {@code "SHA256withECDSA"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new, uninitialised signature engine
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public Signature signature(String algorithm) throws GeneralSecurityException {
        return provider == null ? Signature.getInstance(algorithm)
                : Signature.getInstance(algorithm, provider);
    }

    /**
     * Returns a new key pair generator for an algorithm such as
     * {@code "EC"} or {@code "X25519"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new generator
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KeyPairGenerator keyPairGenerator(String algorithm) throws GeneralSecurityException {
        return provider == null ? KeyPairGenerator.getInstance(algorithm)
                : KeyPairGenerator.getInstance(algorithm, provider);
    }

    /**
     * Returns a new key agreement for an algorithm such as {@code "ECDH"}
     * or {@code "XDH"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new, uninitialised key agreement
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KeyAgreement keyAgreement(String algorithm) throws GeneralSecurityException {
        return provider == null ? KeyAgreement.getInstance(algorithm)
                : KeyAgreement.getInstance(algorithm, provider);
    }

    /**
     * Returns a key factory for an algorithm such as {@code "EC"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a key factory
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KeyFactory keyFactory(String algorithm) throws GeneralSecurityException {
        return provider == null ? KeyFactory.getInstance(algorithm)
                : KeyFactory.getInstance(algorithm, provider);
    }

    /**
     * Returns a new key derivation function for an algorithm such as
     * {@code "HKDF-SHA256"}.
     *
     * @param algorithm the JCA algorithm name
     * @return a new KDF
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KDF kdf(String algorithm) throws GeneralSecurityException {
        return provider == null ? KDF.getInstance(algorithm) : KDF.getInstance(algorithm, provider);
    }

    /**
     * Returns a key encapsulation mechanism for an algorithm such as
     * {@code "ML-KEM-768"}.
     *
     * @param algorithm the JCA algorithm name
     * @return the KEM
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KEM kem(String algorithm) throws GeneralSecurityException {
        return provider == null ? KEM.getInstance(algorithm) : KEM.getInstance(algorithm, provider);
    }

    /**
     * Returns a certificate factory for a type such as {@code "X.509"}.
     *
     * @param type the certificate type
     * @return a certificate factory
     * @throws GeneralSecurityException if the type is not available
     */
    public CertificateFactory certificateFactory(String type) throws GeneralSecurityException {
        return provider == null ? CertificateFactory.getInstance(type)
                : CertificateFactory.getInstance(type, provider);
    }

    /**
     * Returns a new, unloaded key store of a type such as {@code "PKCS12"}.
     *
     * @param type the key store type
     * @return a new key store
     * @throws GeneralSecurityException if the type is not available
     */
    public KeyStore keyStore(String type) throws GeneralSecurityException {
        return offers("KeyStore", type) ? KeyStore.getInstance(type, provider) : KeyStore.getInstance(type);
    }

    /**
     * Returns a new trust manager factory for the platform's default
     * algorithm.
     *
     * @return a new, uninitialised trust manager factory
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public TrustManagerFactory trustManagerFactory() throws GeneralSecurityException {
        String algorithm = TrustManagerFactory.getDefaultAlgorithm();
        return offers("TrustManagerFactory", algorithm) ? TrustManagerFactory.getInstance(algorithm, provider)
                : TrustManagerFactory.getInstance(algorithm);
    }

    /**
     * Returns a new key manager factory for the platform's default
     * algorithm.
     *
     * @return a new, uninitialised key manager factory
     * @throws GeneralSecurityException if the algorithm is not available
     */
    public KeyManagerFactory keyManagerFactory() throws GeneralSecurityException {
        String algorithm = KeyManagerFactory.getDefaultAlgorithm();
        return offers("KeyManagerFactory", algorithm) ? KeyManagerFactory.getInstance(algorithm, provider)
                : KeyManagerFactory.getInstance(algorithm);
    }

    /**
     * Whether the pinned provider registers a service, for the JSSE-side
     * services that a validated module need not carry (see
     * {@link #pinned}).
     */
    private boolean offers(String type, String algorithm) {
        return provider != null && provider.getService(type, algorithm) != null;
    }

    // -- HKDF (RFC 5869) and HKDF-Expand-Label (RFC 8446 section 7.1) --

    /**
     * Returns the output length in bytes of a hash, for the hashes TLS
     * cipher suites use. This is also the check every HKDF and HMAC method
     * here applies to its {@code hash} argument: only SHA-256 and SHA-384
     * are accepted. SHA-1 and MD5 in particular are refused, whatever the
     * provider might offer under those names.
     *
     * @param hash the JCA digest name: {@code "SHA-256"} or {@code "SHA-384"}
     * @return 32 or 48
     * @throws IllegalArgumentException for any other hash
     */
    public static int hashLength(String hash) {
        if ("SHA-256".equals(hash)) {
            return 32;
        }
        if ("SHA-384".equals(hash)) {
            return 48;
        }
        throw new IllegalArgumentException("Not a TLS 1.3 cipher suite hash: " + hash);
    }

    /**
     * HKDF-Extract (RFC 5869 section 2.2): {@code HMAC-Hash(salt, ikm)},
     * performed by the provider's HKDF.
     *
     * @param hash the JCA digest name of the suite's hash, e.g. {@code "SHA-256"}
     * @param salt the salt (a non-secret random value); empty for the
     *             RFC's default of a hash length of zero bytes
     * @param ikm the input keying material
     * @return the pseudorandom key, one hash length long
     */
    public byte[] hkdfExtract(String hash, byte[] salt, byte[] ikm) {
        HKDFParameterSpec.Builder builder = HKDFParameterSpec.ofExtract().addIKM(ikm);
        if (salt.length > 0) {
            builder.addSalt(salt);
        }
        return hkdfDerive(hash, builder.extractOnly());
    }

    /**
     * HKDF-Expand (RFC 5869 section 2.3): expands a pseudorandom key into
     * {@code length} bytes of output keying material, performed by the
     * provider's HKDF.
     *
     * @param hash the JCA digest name of the suite's hash
     * @param prk the pseudorandom key, normally from {@link #hkdfExtract}
     * @param info context and application-specific information
     * @param length the length in bytes of output keying material
     * @return the output keying material, {@code length} bytes
     */
    public byte[] hkdfExpand(String hash, byte[] prk, byte[] info, int length) {
        return hkdfDerive(hash, HKDFParameterSpec.expandOnly(new SecretKeySpec(prk, PRK_ALGORITHM), info, length));
    }

    /**
     * HKDF-Expand-Label (RFC 8446 section 7.1): the TLS 1.3 key schedule's
     * wrapper around HKDF-Expand, using a length-prefixed
     * {@code "tls13 " + label} and an opaque context.
     *
     * <pre>
     *   struct {
     *       uint16 length = Length;
     *       opaque label&lt;7..255&gt; = "tls13 " + Label;
     *       opaque context&lt;0..255&gt; = Context;
     *   } HkdfLabel;
     * </pre>
     *
     * @param hash the JCA digest name of the suite's hash
     * @param secret the secret to expand from
     * @param label the label, without the {@code "tls13 "} prefix
     *              (e.g. {@code "quic key"})
     * @param context the context octets (empty for QUIC's uses)
     * @param length the length in bytes of output keying material
     * @return the output keying material, {@code length} bytes
     */
    public byte[] hkdfExpandLabel(String hash, byte[] secret, String label, byte[] context, int length) {
        return hkdfExpandLabel(hash, TLS13_LABEL_PREFIX, secret, label, context, length);
    }

    /**
     * HKDF-Expand-Label with an explicit prefix in place of
     * {@code "tls13 "}: RFC 9147 section 5.9 uses {@code "dtls13"}.
     *
     * @param hash the JCA digest name of the suite's hash
     * @param labelPrefix the prefix bytes
     * @param secret the secret to expand from
     * @param label the label without the prefix
     * @param context the context octets
     * @param length the length in bytes of output keying material
     * @return the output keying material
     */
    public byte[] hkdfExpandLabel(String hash, byte[] labelPrefix, byte[] secret, String label, byte[] context,
            int length) {
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        int fullLabelLength = labelPrefix.length + labelBytes.length;

        byte[] hkdfLabel = new byte[2 + 1 + fullLabelLength + 1 + context.length];
        int pos = 0;
        hkdfLabel[pos++] = (byte) ((length >> 8) & 0xff);
        hkdfLabel[pos++] = (byte) (length & 0xff);
        hkdfLabel[pos++] = (byte) fullLabelLength;
        System.arraycopy(labelPrefix, 0, hkdfLabel, pos, labelPrefix.length);
        pos += labelPrefix.length;
        System.arraycopy(labelBytes, 0, hkdfLabel, pos, labelBytes.length);
        pos += labelBytes.length;
        hkdfLabel[pos++] = (byte) context.length;
        System.arraycopy(context, 0, hkdfLabel, pos, context.length);

        return hkdfExpand(hash, secret, hkdfLabel, length);
    }

    /**
     * {@code HMAC-Hash(key, data)} under a suite's hash: the TLS 1.3
     * {@code Finished} message's verify data (RFC 8446 section 4.4.4),
     * which is keyed by an HKDF-Expand-Label-derived {@code finished_key}
     * but is not itself an HKDF step.
     *
     * @param hash the JCA digest name of the suite's hash
     * @param key the HMAC key
     * @param data the data to authenticate
     * @return the HMAC, one hash length long
     */
    public byte[] hmac(String hash, byte[] key, byte[] data) {
        hashLength(hash);
        String algorithm = "Hmac" + hash.replace("-", "");
        try {
            Mac mac = mac(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC key", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC algorithm not available: " + algorithm, e);
        }
    }

    private byte[] hkdfDerive(String hash, HKDFParameterSpec spec) {
        hashLength(hash);
        Map<String, KDF> perThread = kdfs.get();
        KDF kdf = perThread.get(hash);
        if (kdf == null) {
            String algorithm = "HKDF-" + hash.replace("-", "");
            try {
                kdf = kdf(algorithm);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("KDF algorithm not available: " + algorithm, e);
            }
            perThread.put(hash, kdf);
        }
        try {
            return kdf.deriveData(spec);
        } catch (GeneralSecurityException e) {
            // The inputs are hash-length secrets and bounded labels; the
            // provider rejecting them is a configuration error, not a
            // condition a handshake can recover from.
            throw new IllegalStateException("HKDF derivation failed", e);
        }
    }

}
