/*
 * PemCredentials.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * Loads PEM-encoded certificate chain and private key files into gumdrop's
 * own {@link ServerCredentials}. Used when listeners are configured with
 * {@code cert-file} / {@code key-file} instead of a Java keystore.
 *
 * <p>The private key must be in PKCS8 form (a
 * {@code -----BEGIN PRIVATE KEY-----} block, RSA or EC) -- the older
 * PKCS1 traditional format ({@code -----BEGIN RSA PRIVATE KEY-----})
 * is not supported; convert with
 * {@code openssl pkcs8 -topk8 -nocrypt} if needed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class PemCredentials {

    // The key types the handshake engine can sign with, tried in turn:
    // a PKCS8 key names its own algorithm, but KeyFactory wants it first.
    private static final String[] KEY_ALGORITHMS = new String[] { "RSA", "EC", "Ed25519", "ML-DSA" };

    private PemCredentials() {
    }

    /**
     * Loads a certificate chain and private key from PEM files and
     * builds {@link ServerCredentials} from them.
     *
     * @param certFile the PEM certificate chain file
     * @param keyFile the PEM PKCS8 private key file
     * @return the server credentials
     * @throws IOException if either file cannot be read or parsed
     * @throws GeneralSecurityException if the key cannot be parsed
     */
    public static ServerCredentials loadServerCredentials(Path certFile, Path keyFile)
            throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = loadCertificateChain(certFile);
        PrivateKey key = loadPrivateKey(keyFile);
        return new ServerCredentials(chain, key);
    }

    /**
     * Loads a certificate chain from a PEM file.
     *
     * @param certFile the PEM certificate chain file
     * @return the certificate chain, in file order
     * @throws IOException if the file cannot be read
     * @throws GeneralSecurityException if the certificates cannot be parsed
     */
    public static List<X509Certificate> loadCertificateChain(Path certFile)
            throws IOException, GeneralSecurityException {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        try (InputStream in = Files.newInputStream(certFile)) {
            // CertificateFactory reads PEM directly, and generateCertificates
            // handles a file containing more than one concatenated certificate.
            Collection<? extends Certificate> certs = certificateFactory.generateCertificates(in);
            for (Certificate cert : certs) {
                chain.add((X509Certificate) cert);
            }
        }
        if (chain.isEmpty()) {
            throw new IOException("No certificates found in " + certFile);
        }
        return chain;
    }

    /**
     * Loads a PEM CA certificate file as a trust manager, for verifying
     * peer certificates against a private/custom CA rather than the
     * platform default trust store.
     *
     * @param caFile the PEM CA certificate file (one or more concatenated certificates)
     * @return the trust manager
     * @throws IOException if the file cannot be read
     * @throws GeneralSecurityException if the certificates cannot be parsed
     *                                  or the trust manager cannot be built
     */
    public static X509TrustManager loadTrustManager(Path caFile) throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = loadCertificateChain(caFile);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int i = 0; i < chain.size(); i++) {
            trustStore.setCertificateEntry("ca" + i, chain.get(i));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available from " + caFile);
    }

    /**
     * Loads a PEM private key file: a PKCS8 {@code PRIVATE KEY} block, or
     * a SEC1 {@code EC PRIVATE KEY} block (RFC 5915, which is what
     * {@code openssl ecparam -genkey} and {@code openssl ec} write unless
     * told otherwise), optionally accompanied by an {@code EC PARAMETERS}
     * block naming the curve.
     *
     * @param keyFile the PEM private key file
     * @return the private key
     * @throws IOException if the file cannot be read or does not contain
     *                     a private key block
     * @throws GeneralSecurityException if the key bytes cannot be parsed
     *                                  as an RSA, EC, Ed25519 or ML-DSA
     *                                  private key
     */
    public static PrivateKey loadPrivateKey(Path keyFile) throws IOException, GeneralSecurityException {
        String pem = new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII);
        byte[] der;
        if (findPemBlock(pem, "EC PRIVATE KEY") != null) {
            der = sec1ToPkcs8(decodePemBlock(pem, "EC PRIVATE KEY", keyFile),
                    findPemBlock(pem, "EC PARAMETERS"), keyFile);
        } else {
            der = decodePemBlock(pem, "PRIVATE KEY", keyFile);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        InvalidKeySpecException failure = null;
        for (int i = 0; i < KEY_ALGORITHMS.length; i++) {
            try {
                return KeyFactory.getInstance(KEY_ALGORITHMS[i]).generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                failure = e;
            }
        }
        throw new InvalidKeyException(
                "Unsupported private key format (expected PKCS8 RSA, EC, Ed25519 or ML-DSA): " + keyFile,
                failure);
    }

    /** DER OBJECT IDENTIFIER id-ecPublicKey (1.2.840.10045.2.1), RFC 5480. */
    private static final byte[] ID_EC_PUBLIC_KEY = {
        0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01
    };

    /**
     * Wraps an RFC 5915 ECPrivateKey in the PKCS8 PrivateKeyInfo that
     * {@link KeyFactory} accepts. The curve comes from the key's own
     * {@code [0] parameters}, or failing that from the file's
     * {@code EC PARAMETERS} block; only named curves are supported, which
     * is also all the JDK supports.
     *
     * @param sec1 the DER ECPrivateKey
     * @param parameters the DER content of the EC PARAMETERS block, or null
     * @param source the file, for error messages
     */
    static byte[] sec1ToPkcs8(byte[] sec1, byte[] parameters, Path source) throws GeneralSecurityException {
        byte[] curve = null;
        try {
            int[] sequence = readTlv(sec1, 0);
            if (sequence[0] != 0x30) {
                throw new InvalidKeyException("EC PRIVATE KEY in " + source + " is not a SEQUENCE");
            }
            int pos = sequence[1];
            while (pos < sequence[2]) {
                int[] element = readTlv(sec1, pos);
                if (element[0] == 0xA0) {
                    curve = Arrays.copyOfRange(sec1, element[1], element[2]);
                }
                pos = element[2];
            }
        } catch (IndexOutOfBoundsException e) {
            throw new InvalidKeyException("Malformed EC PRIVATE KEY in " + source, e);
        }
        if (curve == null) {
            curve = parameters;
        }
        if (curve == null) {
            throw new InvalidKeyException("EC PRIVATE KEY in " + source
                    + " names no curve: neither [0] parameters nor an EC PARAMETERS block");
        }
        if (curve.length < 2 || curve[0] != 0x06) {
            throw new InvalidKeyException("EC PRIVATE KEY in " + source
                    + " does not use a named curve; only named curves are supported");
        }
        byte[] algorithm = der(0x30, ID_EC_PUBLIC_KEY, curve);
        return der(0x30, der(0x02, new byte[] {0}), algorithm, der(0x04, sec1));
    }

    /**
     * Reads one DER tag-length-value at {@code offset}.
     *
     * @return the tag, the offset of the content, and the offset just past it
     * @throws IndexOutOfBoundsException if the encoding runs past the input
     */
    private static int[] readTlv(byte[] buf, int offset) {
        int tag = buf[offset] & 0xFF;
        int pos = offset + 1;
        int length = buf[pos++] & 0xFF;
        if (length >= 0x80) {
            int count = length & 0x7F;
            if (count == 0 || count > 4) {
                throw new IndexOutOfBoundsException("unsupported DER length encoding");
            }
            length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << 8) | (buf[pos++] & 0xFF);
            }
        }
        if (length < 0 || pos + length > buf.length) {
            throw new IndexOutOfBoundsException("DER length runs past the end of the input");
        }
        return new int[] {tag, pos, pos + length};
    }

    /**
     * Encodes one DER tag-length-value whose content is the concatenation
     * of {@code parts}.
     */
    private static byte[] der(int tag, byte[]... parts) {
        int length = 0;
        for (int i = 0; i < parts.length; i++) {
            length += parts[i].length;
        }
        int lengthBytes = length < 0x80 ? 1 : length < 0x100 ? 2 : length < 0x10000 ? 3 : 4;
        byte[] out = new byte[1 + lengthBytes + length];
        out[0] = (byte) tag;
        int pos = 1;
        if (lengthBytes == 1) {
            out[pos++] = (byte) length;
        } else {
            out[pos++] = (byte) (0x80 | (lengthBytes - 1));
            for (int shift = (lengthBytes - 2) * 8; shift >= 0; shift -= 8) {
                out[pos++] = (byte) (length >> shift);
            }
        }
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, pos, parts[i].length);
            pos += parts[i].length;
        }
        return out;
    }

    /**
     * Returns the decoded content of the first {@code label} block in
     * {@code pem}, or null if there is none.
     */
    private static byte[] findPemBlock(String pem, String label) {
        String beginMarker = "-----BEGIN " + label + "-----";
        String endMarker = "-----END " + label + "-----";
        int begin = pem.indexOf(beginMarker);
        int end = begin < 0 ? -1 : pem.indexOf(endMarker, begin);
        if (begin < 0 || end < 0) {
            return null;
        }
        String body = pem.substring(begin + beginMarker.length(), end);
        StringBuilder base64 = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isWhitespace(c)) {
                base64.append(c);
            }
        }
        return Base64.getDecoder().decode(base64.toString());
    }

    private static byte[] decodePemBlock(String pem, String label, Path source) throws IOException {
        byte[] der = findPemBlock(pem, label);
        if (der == null) {
            throw new IOException("No " + label + " block found in " + source);
        }
        return der;
    }
}
