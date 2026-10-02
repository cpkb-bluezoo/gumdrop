/*
 * TestCertificates.java
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

package org.bluezoo.gumdrop.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * In-memory X.509 v3 certificate generator for unit tests. Certificates are
 * assembled as DER by hand (the JDK has no public certificate builder) and
 * signed with the JCA, so tests need no external tools or processes and no
 * checked-in key material.
 *
 * <p>Supports EC P-256 (SHA256withECDSA), EC P-384 (SHA384withECDSA) and RSA
 * 2048/4096 (SHA256withRSA), self-signed or issued by a CA created here. The
 * shared per-JVM instances ({@link #ec256()}, {@link #ec384()},
 * {@link #rsa2048()}, {@link #rsa4096()}) are generated lazily because RSA key
 * generation is slow. Helpers produce PEM text and files, PKCS12 key stores,
 * {@link ServerCredentials} and trust managers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TestCertificates {

    /** Default subject common name and first DNS subjectAltName. */
    public static final String SERVER_NAME = "test.gumdrop.local";

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private static final String OID_EC_SHA256 = "1.2.840.10045.4.3.2";
    private static final String OID_EC_SHA384 = "1.2.840.10045.4.3.3";
    private static final String OID_RSA_SHA256 = "1.2.840.113549.1.1.11";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_SKI = "2.5.29.14";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_SAN = "2.5.29.17";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_AKI = "2.5.29.35";
    private static final String OID_EKU = "2.5.29.37";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    /** Key and signature algorithm choices. */
    public enum KeyKind {
        EC_P256("EC", "secp256r1", 0, "SHA256withECDSA", OID_EC_SHA256, false),
        EC_P384("EC", "secp384r1", 0, "SHA384withECDSA", OID_EC_SHA384, false),
        RSA_2048("RSA", null, 2048, "SHA256withRSA", OID_RSA_SHA256, true),
        RSA_4096("RSA", null, 4096, "SHA256withRSA", OID_RSA_SHA256, true);

        final String algorithm;
        final String curve;
        final int bits;
        final String signatureName;
        final String signatureOid;
        final boolean rsa;

        KeyKind(String algorithm, String curve, int bits, String signatureName,
                String signatureOid, boolean rsa) {
            this.algorithm = algorithm;
            this.curve = curve;
            this.bits = bits;
            this.signatureName = signatureName;
            this.signatureOid = signatureOid;
            this.rsa = rsa;
        }

        /**
         * Returns the JCA signature algorithm name.
         *
         * @return e.g. SHA256withECDSA
         */
        public String getSignatureName() {
            return signatureName;
        }

        KeyPair generate() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (curve != null) {
                ECGenParameterSpec spec = new ECGenParameterSpec(curve);
                generator.initialize(spec);
            } else {
                generator.initialize(bits);
            }
            return generator.generateKeyPair();
        }
    }

    /** A certificate (with its DER bytes), its private key and chain. */
    public static final class Identity {
        private final KeyKind kind;
        private final X509Certificate certificate;
        private final byte[] der;
        private final PrivateKey privateKey;
        private final List<X509Certificate> chain;

        Identity(KeyKind kind, X509Certificate certificate, byte[] der, PrivateKey privateKey,
                List<X509Certificate> chain) {
            this.kind = kind;
            this.certificate = certificate;
            this.der = der;
            this.privateKey = privateKey;
            this.chain = chain;
        }

        /**
         * Returns the key kind.
         *
         * @return the kind
         */
        public KeyKind getKind() {
            return kind;
        }

        /**
         * Returns the end-entity certificate as parsed by the JDK.
         *
         * @return the certificate
         */
        public X509Certificate getCertificate() {
            return certificate;
        }

        /**
         * Returns the exact DER bytes the generator produced.
         *
         * @return a copy of the DER encoding
         */
        public byte[] getDer() {
            return der.clone();
        }

        /**
         * Returns the private key matching the certificate.
         *
         * @return the key
         */
        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        /**
         * Returns the chain to present: the certificate, then its issuing CA
         * if there is one.
         *
         * @return an unmodifiable chain
         */
        public List<X509Certificate> getChain() {
            return chain;
        }

        /**
         * Returns server (or client) credentials for the chain and key.
         *
         * @return new credentials
         */
        public ServerCredentials credentials() {
            return new ServerCredentials(chain, privateKey);
        }

        /**
         * Returns a trust manager that trusts exactly this identity's chain
         * (the certificate itself and any CA).
         *
         * @return the trust manager
         */
        public X509TrustManager trustManager() throws Exception {
            return CertificateVerifier.trustManagerFromCertificates(chain);
        }
    }

    private static final Object LOCK = new Object();
    private static Identity ec256;
    private static Identity ec384;
    private static Identity rsa2048;
    private static Identity rsa4096;

    private TestCertificates() {
    }

    /**
     * Returns the shared self-signed P-256 identity for {@link #SERVER_NAME},
     * localhost, 127.0.0.1 and ::1.
     *
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity ec256() throws Exception {
        synchronized (LOCK) {
            if (ec256 == null) {
                ec256 = newSelfSigned(KeyKind.EC_P256, SERVER_NAME);
            }
            return ec256;
        }
    }

    /**
     * Returns the shared self-signed P-384 identity.
     *
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity ec384() throws Exception {
        synchronized (LOCK) {
            if (ec384 == null) {
                ec384 = newSelfSigned(KeyKind.EC_P384, SERVER_NAME);
            }
            return ec384;
        }
    }

    /**
     * Returns the shared self-signed RSA 2048 identity.
     *
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity rsa2048() throws Exception {
        synchronized (LOCK) {
            if (rsa2048 == null) {
                rsa2048 = newSelfSigned(KeyKind.RSA_2048, SERVER_NAME);
            }
            return rsa2048;
        }
    }

    /**
     * Returns the shared self-signed RSA 4096 identity (a large certificate
     * for fragmentation tests).
     *
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity rsa4096() throws Exception {
        synchronized (LOCK) {
            if (rsa4096 == null) {
                rsa4096 = newSelfSigned(KeyKind.RSA_4096, SERVER_NAME);
            }
            return rsa4096;
        }
    }

    /**
     * Generates a fresh self-signed P-256 identity with the given common
     * name (so tests needing several distinct certificates can have them).
     *
     * @param commonName the subject CN
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity newEc256(String commonName) throws Exception {
        return newSelfSigned(KeyKind.EC_P256, commonName);
    }

    /**
     * Generates a fresh self-signed end-entity identity with a new key.
     *
     * @param kind the key kind
     * @param commonName the subject CN, also the first DNS SAN
     * @return the identity
     * @throws Exception on failure
     */
    public static Identity newSelfSigned(KeyKind kind, String commonName) throws Exception {
        KeyPair pair = kind.generate();
        return build(kind, pair, commonName, null, false, sans(commonName));
    }

    /**
     * Generates a fresh self-signed CA (basicConstraints cA, keyCertSign).
     *
     * @param kind the key kind
     * @param commonName the subject CN
     * @return the CA identity
     * @throws Exception on failure
     */
    public static Identity newCa(KeyKind kind, String commonName) throws Exception {
        KeyPair pair = kind.generate();
        return build(kind, pair, commonName, null, true, new String[0]);
    }

    /**
     * Generates a fresh end-entity certificate issued by the given CA. The
     * leaf's chain is the leaf followed by the CA certificate.
     *
     * @param ca the issuing CA
     * @param kind the leaf key kind
     * @param commonName the leaf subject CN, also the first DNS SAN
     * @return the leaf identity
     * @throws Exception on failure
     */
    public static Identity newIssued(Identity ca, KeyKind kind, String commonName)
            throws Exception {
        KeyPair pair = kind.generate();
        return build(kind, pair, commonName, ca, false, sans(commonName));
    }

    private static String[] sans(String commonName) {
        List<String> names = new ArrayList<String>();
        names.add("dns:" + commonName);
        if (!"localhost".equals(commonName)) {
            names.add("dns:localhost");
        }
        names.add("ip:127.0.0.1");
        names.add("ip:::1");
        return names.toArray(new String[0]);
    }

    // ---- helpers for the forms callers need ----

    /**
     * Encodes bytes as a PEM block with 64-column lines.
     *
     * @param label the PEM label, e.g. CERTIFICATE
     * @param der the DER bytes
     * @return the PEM text
     */
    public static String pem(String label, byte[] der) {
        Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        String body = encoder.encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /**
     * Returns the PEM text of every certificate in the list, in order.
     *
     * @param chain the certificates
     * @return concatenated PEM blocks
     * @throws CertificateException if a certificate cannot be encoded
     */
    public static String certificatesPem(List<X509Certificate> chain) throws CertificateException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            X509Certificate cert = chain.get(i);
            byte[] encoded = cert.getEncoded();
            sb.append(pem("CERTIFICATE", encoded));
        }
        return sb.toString();
    }

    /**
     * Returns the PKCS#8 PEM text of a private key.
     *
     * @param key the key
     * @return the PEM text
     */
    public static String privateKeyPem(PrivateKey key) {
        byte[] encoded = key.getEncoded();
        return pem("PRIVATE KEY", encoded);
    }

    /**
     * Writes the identity's chain as PEM to a file in the given directory.
     *
     * @param dir an existing directory (e.g. a TemporaryFolder root)
     * @param name the file name
     * @param identity the identity
     * @return the file path
     * @throws Exception on failure
     */
    public static Path writeCertificatePem(Path dir, String name, Identity identity)
            throws Exception {
        Path file = dir.resolve(name);
        String text = certificatesPem(identity.getChain());
        Files.write(file, text.getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    /**
     * Writes the identity's private key as PKCS#8 PEM to a file.
     *
     * @param dir an existing directory
     * @param name the file name
     * @param identity the identity
     * @return the file path
     * @throws Exception on failure
     */
    public static Path writePrivateKeyPem(Path dir, String name, Identity identity)
            throws Exception {
        Path file = dir.resolve(name);
        PrivateKey key = identity.getPrivateKey();
        String text = privateKeyPem(key);
        Files.write(file, text.getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    /**
     * Builds an in-memory PKCS12 key store holding the identity's key and
     * chain under the given alias.
     *
     * @param identity the identity
     * @param alias the entry alias
     * @param password the store and key password
     * @return the loaded key store
     * @throws Exception on failure
     */
    public static KeyStore keyStore(Identity identity, String alias, char[] password)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        List<X509Certificate> chain = identity.getChain();
        X509Certificate[] array = chain.toArray(new X509Certificate[0]);
        store.setKeyEntry(alias, identity.getPrivateKey(), password, array);
        return store;
    }

    /**
     * Writes a PKCS12 key store file for the identity.
     *
     * @param dir an existing directory
     * @param name the file name
     * @param identity the identity
     * @param alias the entry alias
     * @param password the store and key password
     * @return the file path
     * @throws Exception on failure
     */
    public static Path writeKeyStore(Path dir, String name, Identity identity, String alias,
            char[] password) throws Exception {
        KeyStore store = keyStore(identity, alias, password);
        Path file = dir.resolve(name);
        OutputStream out = Files.newOutputStream(file);
        try {
            store.store(out, password);
        } finally {
            out.close();
        }
        return file;
    }

    /**
     * Returns a trust manager trusting exactly the given certificates.
     *
     * @param chain the trusted certificates
     * @return the trust manager
     */
    public static X509TrustManager trustManager(List<X509Certificate> chain)
            throws Exception {
        return CertificateVerifier.trustManagerFromCertificates(chain);
    }

    /**
     * Returns a trust manager that accepts any chain.
     *
     * @return the trust manager
     */
    public static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Returns a trust manager that rejects every chain.
     *
     * @return the trust manager
     */
    public static X509TrustManager trustNone() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("rejected");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("rejected");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    // ---- certificate assembly ----

    private static Identity build(KeyKind kind, KeyPair pair, String commonName, Identity issuer,
            boolean ca, String[] sanSpecs) throws Exception {
        KeyKind signerKind = kind;
        PrivateKey signerKey = pair.getPrivate();
        if (issuer != null) {
            signerKind = issuer.getKind();
            signerKey = issuer.getPrivateKey();
        }
        byte[] spki = pair.getPublic().getEncoded();
        byte[] subjectKeyId = keyIdentifier(spki);
        byte[] subject = name(commonName);
        byte[] issuerName = subject;
        byte[] authorityKeyId = subjectKeyId;
        if (issuer != null) {
            X509Certificate issuerCert = issuer.getCertificate();
            issuerName = issuerCert.getSubjectX500Principal().getEncoded();
            byte[] issuerSpki = issuerCert.getPublicKey().getEncoded();
            authorityKeyId = keyIdentifier(issuerSpki);
        }

        byte[] sigAlg = algorithmIdentifier(signerKind);
        byte[] versionInt = tlv(0x02, new byte[] {2});
        byte[] version = tlv(0xa0, versionInt);
        SecureRandom random = new SecureRandom();
        byte[] serialBytes = new byte[8];
        random.nextBytes(serialBytes);
        serialBytes[0] = (byte) (serialBytes[0] & 0x7f);
        BigInteger serialNumber = new BigInteger(1, serialBytes);
        byte[] serial = tlv(0x02, serialNumber.toByteArray());

        long now = System.currentTimeMillis();
        long notBeforeMillis = (now / 1000L) * 1000L - DAY_MILLIS;
        long notAfterMillis = notBeforeMillis + 365L * DAY_MILLIS;
        byte[] notBefore = utcTime(notBeforeMillis);
        byte[] notAfter = utcTime(notAfterMillis);
        byte[] validity = seq(notBefore, notAfter);

        byte[] extensions = extensions(kind, ca, sanSpecs, subjectKeyId, authorityKeyId);
        byte[] extensionsTagged = tlv(0xa3, extensions);
        byte[] tbs = seq(version, serial, sigAlg, issuerName, validity, subject, spki,
                extensionsTagged);

        Signature signature = Signature.getInstance(signerKind.signatureName);
        signature.initSign(signerKey);
        signature.update(tbs);
        byte[] sigBytes = signature.sign();
        byte[] sigBits = new byte[sigBytes.length + 1];
        System.arraycopy(sigBytes, 0, sigBits, 1, sigBytes.length);
        byte[] sigBitString = tlv(0x03, sigBits);
        byte[] der = seq(tbs, sigAlg, sigBitString);

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream in = new ByteArrayInputStream(der);
        X509Certificate cert = (X509Certificate) factory.generateCertificate(in);
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        chain.add(cert);
        if (issuer != null) {
            chain.addAll(issuer.getChain());
        }
        List<X509Certificate> unmodifiable = Collections.unmodifiableList(chain);
        return new Identity(kind, cert, der, pair.getPrivate(), unmodifiable);
    }

    private static byte[] extensions(KeyKind kind, boolean ca, String[] sanSpecs,
            byte[] subjectKeyId, byte[] authorityKeyId) throws Exception {
        List<byte[]> list = new ArrayList<byte[]>();

        byte[] bcValue;
        if (ca) {
            byte[] yes = tlv(0x01, new byte[] {(byte) 0xff});
            bcValue = seq(yes);
        } else {
            bcValue = seq();
        }
        list.add(extension(OID_BASIC_CONSTRAINTS, true, bcValue));

        int usage;
        if (ca) {
            usage = 0x06;
        } else if (kind.rsa) {
            usage = 0xa0;
        } else {
            usage = 0x80;
        }
        int unused = 0;
        while (((usage >> unused) & 1) == 0) {
            unused++;
        }
        byte[] kuBits = tlv(0x03, new byte[] {(byte) unused, (byte) usage});
        list.add(extension(OID_KEY_USAGE, true, kuBits));

        if (!ca) {
            byte[] serverAuth = oid(OID_SERVER_AUTH);
            byte[] clientAuth = oid(OID_CLIENT_AUTH);
            byte[] eku = seq(serverAuth, clientAuth);
            list.add(extension(OID_EKU, false, eku));
        }

        byte[] skiOctets = tlv(0x04, subjectKeyId);
        list.add(extension(OID_SKI, false, skiOctets));
        byte[] akiKey = tlv(0x80, authorityKeyId);
        byte[] aki = seq(akiKey);
        list.add(extension(OID_AKI, false, aki));

        if (sanSpecs.length > 0) {
            List<byte[]> names = new ArrayList<byte[]>();
            for (int i = 0; i < sanSpecs.length; i++) {
                String spec = sanSpecs[i];
                if (spec.startsWith("dns:")) {
                    String dns = spec.substring(4);
                    byte[] bytes = dns.getBytes(StandardCharsets.US_ASCII);
                    names.add(tlv(0x82, bytes));
                } else {
                    String ip = spec.substring(3);
                    InetAddress address = InetAddress.getByName(ip);
                    byte[] bytes = address.getAddress();
                    names.add(tlv(0x87, bytes));
                }
            }
            byte[] san = seqOf(names);
            list.add(extension(OID_SAN, false, san));
        }
        return seqOf(list);
    }

    private static byte[] extension(String oidString, boolean critical, byte[] value) {
        byte[] id = oid(oidString);
        byte[] octets = tlv(0x04, value);
        if (critical) {
            byte[] flag = tlv(0x01, new byte[] {(byte) 0xff});
            return seq(id, flag, octets);
        }
        return seq(id, octets);
    }

    private static byte[] algorithmIdentifier(KeyKind signerKind) {
        byte[] id = oid(signerKind.signatureOid);
        if (signerKind.rsa) {
            byte[] nul = tlv(0x05, new byte[0]);
            return seq(id, nul);
        }
        return seq(id);
    }

    private static byte[] name(String commonName) {
        byte[] type = oid(OID_CN);
        byte[] value = tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8));
        byte[] attribute = seq(type, value);
        byte[] rdn = tlv(0x31, attribute);
        return seq(rdn);
    }

    private static byte[] utcTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date = new Date(millis);
        String text = format.format(date);
        return tlv(0x17, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** RFC 5280 method 1: SHA-1 of the subjectPublicKey bit string value. */
    private static byte[] keyIdentifier(byte[] spki) throws Exception {
        int pos = 0;
        pos++;
        pos += lengthSize(spki, pos);
        // skip AlgorithmIdentifier
        pos++;
        int algLen = readLength(spki, pos);
        pos += lengthSize(spki, pos) + algLen;
        // BIT STRING
        pos++;
        int bitLen = readLength(spki, pos);
        pos += lengthSize(spki, pos);
        byte[] bits = new byte[bitLen - 1];
        System.arraycopy(spki, pos + 1, bits, 0, bits.length);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return sha1.digest(bits);
    }

    private static int lengthSize(byte[] data, int pos) {
        int first = data[pos] & 0xff;
        if (first < 0x80) {
            return 1;
        }
        return 1 + (first & 0x7f);
    }

    private static int readLength(byte[] data, int pos) {
        int first = data[pos] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int count = first & 0x7f;
        int len = 0;
        for (int i = 0; i < count; i++) {
            len = (len << 8) | (data[pos + 1 + i] & 0xff);
        }
        return len;
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return tlv(0x30, out.toByteArray());
    }

    private static byte[] seqOf(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            byte[] part = parts.get(i);
            out.write(part, 0, part.length);
        }
        return tlv(0x30, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] oid(String dotted) {
        List<Long> arcs = new ArrayList<Long>();
        int start = 0;
        while (start <= dotted.length()) {
            int dot = dotted.indexOf('.', start);
            if (dot < 0) {
                dot = dotted.length();
            }
            String part = dotted.substring(start, dot);
            arcs.add(Long.valueOf(part));
            start = dot + 1;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long first = arcs.get(0).longValue();
        long second = arcs.get(1).longValue();
        out.write((int) (first * 40 + second));
        for (int i = 2; i < arcs.size(); i++) {
            long value = arcs.get(i).longValue();
            int groups = 1;
            while ((value >> (7 * groups)) > 0) {
                groups++;
            }
            for (int g = groups - 1; g >= 0; g--) {
                int b = (int) ((value >> (7 * g)) & 0x7f);
                if (g > 0) {
                    b |= 0x80;
                }
                out.write(b);
            }
        }
        return tlv(0x06, out.toByteArray());
    }
}
