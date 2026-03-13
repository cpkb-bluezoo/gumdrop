/*
 * DNSSECValidator.java
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

package org.bluezoo.gumdrop.dns;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core DNSSEC validation engine.
 * All methods are synchronous and CPU-bound (safe for the event loop).
 *
 * <p>RFC 4034 section 3 defines RRSIG verification. RFC 4034 section 5
 * defines DS verification. RFC 4034 section 4 and RFC 5155 define
 * NSEC/NSEC3 denial-of-existence proofs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSSECValidator {

    private static final Logger LOGGER =
            Logger.getLogger(DNSSECValidator.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private DNSSECValidator() {
    }

    // -- RRSIG verification (RFC 4034 section 3.1.8) --

    /**
     * Verifies an RRSIG over an RRset using the given DNSKEY.
     * RFC 4034 section 3.1.8.1: the signed data is
     * RRSIG_RDATA (minus signature) | canonical RRset.
     *
     * @param rrset the resource records covered by the RRSIG
     * @param rrsig the RRSIG record
     * @param dnskey the DNSKEY to verify with
     * @return true if the signature is valid
     */
    public static boolean verifyRRSIG(List<DNSResourceRecord> rrset,
                                      DNSResourceRecord rrsig,
                                      DNSResourceRecord dnskey) {
        int algNum = rrsig.getRRSIGAlgorithm();
        DNSSECAlgorithm algorithm = DNSSECAlgorithm.fromNumber(algNum);
        if (algorithm == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.unsupported_algorithm"),
                        algNum));
            }
            return false;
        }

        try {
            PublicKey pubKey = buildPublicKey(dnskey, algorithm);
            if (pubKey == null) {
                return false;
            }

            byte[] signedData = buildSignedData(rrset, rrsig);
            byte[] sigBytes = rrsig.getRRSIGSignature();

            if (algorithm == DNSSECAlgorithm.ECDSAP256SHA256
                    || algorithm == DNSSECAlgorithm.ECDSAP384SHA384) {
                sigBytes = ecdsaRawToDER(sigBytes,
                        algorithm.getECDSACurveSize());
            }

            Signature sig = Signature.getInstance(
                    algorithm.getSignatureAlgorithm());
            sig.initVerify(pubKey);
            sig.update(signedData);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("dnssec.verify_failed"),
                        rrsig.getRRSIGSignerName()), e);
            }
            return false;
        }
    }

    /**
     * Checks whether an RRSIG is temporally valid.
     * RFC 4034 section 3.1.5: the current time must be between
     * inception and expiration.
     *
     * @param rrsig the RRSIG record
     * @return true if the signature is within its validity period
     */
    public static boolean isRRSIGCurrent(DNSResourceRecord rrsig) {
        long now = System.currentTimeMillis() / 1000;
        long inception = rrsig.getRRSIGInception();
        long expiration = rrsig.getRRSIGExpiration();
        return now >= inception && now <= expiration;
    }

    // -- DS verification (RFC 4034 section 5.2) --

    /**
     * Verifies that a DNSKEY matches a DS record.
     * RFC 4034 section 5.1.4: digest = hash(owner name | DNSKEY RDATA).
     *
     * @param dnskey the DNSKEY record to verify
     * @param ds the DS record
     * @return true if the DNSKEY matches the DS digest
     */
    public static boolean verifyDS(DNSResourceRecord dnskey,
                                   DNSResourceRecord ds) {
        int digestType = ds.getDSDigestType();
        String digestAlg = DNSSECAlgorithm.dsDigestAlgorithm(digestType);
        if (digestAlg == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("dnssec.unsupported_digest"),
                        digestType));
            }
            return false;
        }

        try {
            MessageDigest md = MessageDigest.getInstance(digestAlg);
            byte[] ownerWire = DNSMessage.encodeName(
                    canonicalizeName(dnskey.getName()));
            md.update(ownerWire);
            md.update(dnskey.getRData());
            byte[] computed = md.digest();
            return Arrays.equals(computed, ds.getDSDigest());
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, MessageFormat.format(
                        L10N.getString("dnssec.ds_verify_failed"),
                        dnskey.getName()), e);
            }
            return false;
        }
    }

    // -- NSEC denial-of-existence (RFC 4034 section 5.4) --

    /**
     * Verifies that NSEC records prove the non-existence of a name
     * or the absence of a record type.
     * RFC 4035 section 5.4: the query name must fall in the gap
     * between two consecutive NSEC records, or an NSEC covering
     * the name must lack the queried type in its type bitmap.
     *
     * @param queryName the queried domain name
     * @param queryType the queried record type
     * @param nsecRecords the NSEC records from the authority section
     * @return true if denial of existence is proven
     */
    public static boolean verifyNSEC(String queryName, DNSType queryType,
                                     List<DNSResourceRecord> nsecRecords) {
        String qLower = canonicalizeName(queryName);
        int qTypeVal = queryType.getValue();

        for (int i = 0; i < nsecRecords.size(); i++) {
            DNSResourceRecord nsec = nsecRecords.get(i);
            if (nsec.getType() != DNSType.NSEC) {
                continue;
            }

            String owner = canonicalizeName(nsec.getName());
            String next = canonicalizeName(nsec.getNSECNextDomainName());

            if (owner.equalsIgnoreCase(qLower)) {
                List<Integer> types = nsec.getNSECTypeBitMaps();
                if (!types.contains(qTypeVal)
                        && !types.contains(DNSType.CNAME.getValue())) {
                    return true;
                }
            }

            if (isNameBetween(owner, qLower, next)) {
                return true;
            }
        }
        return false;
    }

    // -- NSEC3 denial-of-existence (RFC 5155 section 8) --

    /**
     * Verifies that NSEC3 records prove the non-existence of a name.
     * RFC 5155 section 8: the hashed owner name must fall in the gap
     * between two consecutive NSEC3 records.
     *
     * @param queryName the queried domain name
     * @param queryType the queried record type
     * @param nsec3Records the NSEC3 records from the authority section
     * @return true if denial of existence is proven
     */
    public static boolean verifyNSEC3(String queryName, DNSType queryType,
                                      List<DNSResourceRecord> nsec3Records) {
        if (nsec3Records.isEmpty()) {
            return false;
        }

        DNSResourceRecord first = nsec3Records.get(0);
        int hashAlg = first.getNSEC3HashAlgorithm();
        int iterations = first.getNSEC3Iterations();
        byte[] salt = first.getNSEC3Salt();

        byte[] queryHash = nsec3Hash(queryName, hashAlg, iterations, salt);
        if (queryHash == null) {
            return false;
        }

        String qHashB32 = base32HexEncode(queryHash);

        for (int i = 0; i < nsec3Records.size(); i++) {
            DNSResourceRecord nsec3 = nsec3Records.get(i);
            if (nsec3.getType() != DNSType.NSEC3) {
                continue;
            }

            String ownerLabel = nsec3.getName();
            int dot = ownerLabel.indexOf('.');
            String ownerHash = dot > 0
                    ? ownerLabel.substring(0, dot).toUpperCase()
                    : ownerLabel.toUpperCase();
            String nextHash = base32HexEncode(
                    nsec3.getNSEC3NextHashedOwner());

            if (ownerHash.equalsIgnoreCase(qHashB32)) {
                List<Integer> types = nsec3.getNSEC3TypeBitMaps();
                if (!types.contains(queryType.getValue())
                        && !types.contains(DNSType.CNAME.getValue())) {
                    return true;
                }
            }

            if (isHashBetween(ownerHash, qHashB32, nextHash)) {
                return true;
            }
        }
        return false;
    }

    // -- Public key construction --

    /**
     * Builds a JCA PublicKey from DNSKEY RDATA.
     *
     * @param dnskey the DNSKEY record
     * @param algorithm the DNSSEC algorithm
     * @return the public key, or null if construction fails
     */
    static PublicKey buildPublicKey(DNSResourceRecord dnskey,
                                   DNSSECAlgorithm algorithm) {
        byte[] keyData = dnskey.getDNSKEYPublicKey();
        try {
            switch (algorithm) {
                case RSASHA256:
                case RSASHA512:
                    return buildRSAPublicKey(keyData);
                case ECDSAP256SHA256:
                case ECDSAP384SHA384:
                    return buildECPublicKey(keyData, algorithm);
                case ED25519:
                case ED448:
                    return buildEdDSAPublicKey(keyData, algorithm);
                default:
                    return null;
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Failed to build public key", e);
            }
            return null;
        }
    }

    /**
     * RFC 3110 section 2: RSA public key wire format.
     * 1 or 3 byte exponent length prefix, then exponent, then modulus.
     */
    private static PublicKey buildRSAPublicKey(byte[] keyData)
            throws Exception {
        int offset = 0;
        int expLen = keyData[offset] & 0xFF;
        offset++;
        if (expLen == 0) {
            expLen = ((keyData[offset] & 0xFF) << 8)
                    | (keyData[offset + 1] & 0xFF);
            offset += 2;
        }
        byte[] expBytes = new byte[expLen];
        System.arraycopy(keyData, offset, expBytes, 0, expLen);
        offset += expLen;

        byte[] modBytes = new byte[keyData.length - offset];
        System.arraycopy(keyData, offset, modBytes, 0, modBytes.length);

        BigInteger exponent = new BigInteger(1, expBytes);
        BigInteger modulus = new BigInteger(1, modBytes);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    /**
     * RFC 6605 section 4: ECDSA public key is the uncompressed
     * point (x || y), each coordinate curveSize bytes.
     */
    private static PublicKey buildECPublicKey(byte[] keyData,
                                             DNSSECAlgorithm algorithm)
            throws Exception {
        int curveSize = algorithm.getECDSACurveSize();
        byte[] xBytes = new byte[curveSize];
        byte[] yBytes = new byte[curveSize];
        System.arraycopy(keyData, 0, xBytes, 0, curveSize);
        System.arraycopy(keyData, curveSize, yBytes, 0, curveSize);

        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);
        ECPoint point = new ECPoint(x, y);

        java.security.AlgorithmParameters params =
                java.security.AlgorithmParameters.getInstance("EC");
        params.init(new java.security.spec.ECGenParameterSpec(
                algorithm.getECCurveName()));
        ECParameterSpec ecSpec =
                params.getParameterSpec(ECParameterSpec.class);

        ECPublicKeySpec spec = new ECPublicKeySpec(point, ecSpec);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePublic(spec);
    }

    /**
     * RFC 8080 section 3: EdDSA public key is the raw key bytes.
     */
    private static PublicKey buildEdDSAPublicKey(byte[] keyData,
                                                 DNSSECAlgorithm algorithm)
            throws Exception {
        NamedParameterSpec paramSpec;
        if (algorithm == DNSSECAlgorithm.ED25519) {
            paramSpec = NamedParameterSpec.ED25519;
        } else {
            paramSpec = NamedParameterSpec.ED448;
        }

        boolean msb = (keyData[keyData.length - 1] & 0x80) != 0;
        byte[] reversed = new byte[keyData.length];
        for (int i = 0; i < keyData.length; i++) {
            reversed[i] = keyData[keyData.length - 1 - i];
        }
        BigInteger y = new BigInteger(1, reversed);
        EdECPoint point = new EdECPoint(msb, y);
        EdECPublicKeySpec spec = new EdECPublicKeySpec(paramSpec, point);
        KeyFactory kf = KeyFactory.getInstance("EdDSA");
        return kf.generatePublic(spec);
    }

    // -- Canonical RRset construction (RFC 4034 section 6.3) --

    /**
     * Builds the signed data for RRSIG verification.
     * RFC 4034 section 3.1.8.1: RRSIG_RDATA (header, no signature)
     * followed by the canonicalized RRset.
     */
    private static byte[] buildSignedData(List<DNSResourceRecord> rrset,
                                          DNSResourceRecord rrsig) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] rrsigHeader = rrsig.getRRSIGHeaderBytes();
        out.write(rrsigHeader, 0, rrsigHeader.length);

        List<byte[]> canonicalRecords = buildCanonicalRRset(rrset, rrsig);
        for (int i = 0; i < canonicalRecords.size(); i++) {
            byte[] rec = canonicalRecords.get(i);
            out.write(rec, 0, rec.length);
        }
        return out.toByteArray();
    }

    /**
     * Builds the canonical form of an RRset for signature verification.
     * RFC 4034 section 6.3:
     * <ol>
     * <li>Owner name lowercased and in wire format</li>
     * <li>Type, class, original TTL from RRSIG</li>
     * <li>RDLENGTH and RDATA</li>
     * <li>Records sorted by RDATA in canonical order</li>
     * </ol>
     */
    static List<byte[]> buildCanonicalRRset(List<DNSResourceRecord> rrset,
                                            DNSResourceRecord rrsig) {
        String ownerLower = canonicalizeName(
                rrset.get(0).getName());
        byte[] ownerWire = DNSMessage.encodeName(ownerLower);
        int typeCovered = rrsig.getRRSIGTypeCovered();
        int originalTTL = rrsig.getRRSIGOriginalTTL();

        List<byte[]> records = new ArrayList<>(rrset.size());
        for (int i = 0; i < rrset.size(); i++) {
            DNSResourceRecord rr = rrset.get(i);
            byte[] rdata = rr.getRData();
            ByteArrayOutputStream rec = new ByteArrayOutputStream();

            rec.write(ownerWire, 0, ownerWire.length);
            // TYPE (2 bytes)
            rec.write((typeCovered >> 8) & 0xFF);
            rec.write(typeCovered & 0xFF);
            // CLASS (2 bytes) — IN = 1
            rec.write(0);
            rec.write(1);
            // TTL (4 bytes)
            rec.write((originalTTL >> 24) & 0xFF);
            rec.write((originalTTL >> 16) & 0xFF);
            rec.write((originalTTL >> 8) & 0xFF);
            rec.write(originalTTL & 0xFF);
            // RDLENGTH (2 bytes)
            rec.write((rdata.length >> 8) & 0xFF);
            rec.write(rdata.length & 0xFF);
            // RDATA
            rec.write(rdata, 0, rdata.length);

            records.add(rec.toByteArray());
        }

        Collections.sort(records, CANONICAL_RR_COMPARATOR);
        return records;
    }

    private static final Comparator<byte[]> CANONICAL_RR_COMPARATOR =
            new Comparator<byte[]>() {
                @Override
                public int compare(byte[] a, byte[] b) {
                    int len = Math.min(a.length, b.length);
                    for (int i = 0; i < len; i++) {
                        int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
                        if (diff != 0) {
                            return diff;
                        }
                    }
                    return a.length - b.length;
                }
            };

    // -- ECDSA signature format conversion --

    /**
     * Converts a DNSSEC ECDSA raw signature (r || s) to DER format
     * as expected by JCA.
     * RFC 6605 section 4: ECDSA signatures in DNSSEC are (r || s),
     * each value curveSize bytes. JCA expects DER-encoded SEQUENCE
     * of two INTEGERs.
     *
     * @param raw the raw (r || s) bytes
     * @param curveSize the coordinate size in bytes
     * @return the DER-encoded signature
     */
    static byte[] ecdsaRawToDER(byte[] raw, int curveSize) {
        byte[] rBytes = new byte[curveSize];
        byte[] sBytes = new byte[curveSize];
        System.arraycopy(raw, 0, rBytes, 0, curveSize);
        System.arraycopy(raw, curveSize, sBytes, 0, curveSize);

        BigInteger r = new BigInteger(1, rBytes);
        BigInteger s = new BigInteger(1, sBytes);

        byte[] rDer = r.toByteArray();
        byte[] sDer = s.toByteArray();

        int seqLen = 2 + rDer.length + 2 + sDer.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                seqLen + 2);
        out.write(0x30); // SEQUENCE
        if (seqLen < 128) {
            out.write(seqLen);
        } else {
            out.write(0x81);
            out.write(seqLen);
        }
        out.write(0x02); // INTEGER
        out.write(rDer.length);
        out.write(rDer, 0, rDer.length);
        out.write(0x02); // INTEGER
        out.write(sDer.length);
        out.write(sDer, 0, sDer.length);
        return out.toByteArray();
    }

    // -- NSEC3 hashing (RFC 5155 section 5) --

    /**
     * Computes the NSEC3 hash for a domain name.
     * RFC 5155 section 5: IH(salt, x, 0) = H(x || salt),
     * IH(salt, x, k) = H(IH(salt, x, k-1) || salt).
     *
     * @param name the domain name
     * @param hashAlg the hash algorithm (1 = SHA-1)
     * @param iterations the iteration count
     * @param salt the salt bytes
     * @return the hash, or null if the algorithm is unsupported
     */
    static byte[] nsec3Hash(String name, int hashAlg,
                            int iterations, byte[] salt) {
        if (hashAlg != 1) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] wire = DNSMessage.encodeName(canonicalizeName(name));
            md.update(wire);
            md.update(salt);
            byte[] hash = md.digest();
            for (int i = 0; i < iterations; i++) {
                md.reset();
                md.update(hash);
                md.update(salt);
                hash = md.digest();
            }
            return hash;
        } catch (Exception e) {
            return null;
        }
    }

    // -- Name utilities --

    /**
     * RFC 4034 section 6.1: canonical DNS name form is lowercase.
     */
    static String canonicalizeName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".")) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    /**
     * Canonical DNS name ordering per RFC 4034 section 6.1.
     * Names are compared label-by-label from the rightmost (TLD first).
     */
    static int compareCanonical(String a, String b) {
        String[] aLabels = splitLabels(canonicalizeName(a));
        String[] bLabels = splitLabels(canonicalizeName(b));

        int aLen = aLabels.length;
        int bLen = bLabels.length;
        int minLen = Math.min(aLen, bLen);

        for (int i = 0; i < minLen; i++) {
            String aLabel = aLabels[aLen - 1 - i];
            String bLabel = bLabels[bLen - 1 - i];
            int cmp = aLabel.compareTo(bLabel);
            if (cmp != 0) {
                return cmp;
            }
        }
        return aLen - bLen;
    }

    private static String[] splitLabels(String name) {
        if (name.isEmpty()) {
            return new String[0];
        }
        List<String> labels = new ArrayList<>();
        int start = 0;
        int len = name.length();
        while (start < len) {
            int dot = name.indexOf('.', start);
            if (dot < 0) {
                labels.add(name.substring(start));
                break;
            } else {
                labels.add(name.substring(start, dot));
                start = dot + 1;
            }
        }
        return labels.toArray(new String[0]);
    }

    /**
     * Tests whether {@code name} falls strictly between {@code owner}
     * and {@code next} in canonical DNS ordering. Handles the wrap-around
     * case where next &lt; owner (last NSEC in a zone).
     */
    static boolean isNameBetween(String owner, String name,
                                 String next) {
        int ownerCmp = compareCanonical(name, owner);
        int nextCmp = compareCanonical(name, next);
        int wrapCmp = compareCanonical(next, owner);

        if (wrapCmp > 0) {
            return ownerCmp > 0 && nextCmp < 0;
        } else {
            return ownerCmp > 0 || nextCmp < 0;
        }
    }

    /**
     * Tests whether a hash falls between two NSEC3 hashes
     * (base32hex encoded, case-insensitive).
     */
    private static boolean isHashBetween(String owner, String hash,
                                         String next) {
        String oUp = owner.toUpperCase();
        String hUp = hash.toUpperCase();
        String nUp = next.toUpperCase();

        int ownerCmp = hUp.compareTo(oUp);
        int nextCmp = hUp.compareTo(nUp);
        int wrapCmp = nUp.compareTo(oUp);

        if (wrapCmp > 0) {
            return ownerCmp > 0 && nextCmp < 0;
        } else {
            return ownerCmp > 0 || nextCmp < 0;
        }
    }

    // -- Base32hex (RFC 4648 section 7, RFC 5155 section 3.3) --

    private static final char[] BASE32HEX =
            "0123456789ABCDEFGHIJKLMNOPQRSTUV".toCharArray();

    /**
     * Encodes bytes to unpadded base32hex (RFC 4648 section 7).
     */
    static String base32HexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < data.length; i++) {
            buffer = (buffer << 8) | (data[i] & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32HEX[(buffer >> bitsLeft) & 0x1F]);
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32HEX[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    /**
     * Finds the matching DNSKEY for an RRSIG from a list of DNSKEYs
     * by matching the key tag and algorithm.
     *
     * @param rrsig the RRSIG record
     * @param dnskeys the available DNSKEY records
     * @return the matching DNSKEY, or null if not found
     */
    public static DNSResourceRecord findMatchingDNSKEY(
            DNSResourceRecord rrsig,
            List<DNSResourceRecord> dnskeys) {
        int keyTag = rrsig.getRRSIGKeyTag();
        int algorithm = rrsig.getRRSIGAlgorithm();
        for (int i = 0; i < dnskeys.size(); i++) {
            DNSResourceRecord dnskey = dnskeys.get(i);
            if (dnskey.getType() != DNSType.DNSKEY) {
                continue;
            }
            if (dnskey.getDNSKEYAlgorithm() == algorithm
                    && dnskey.computeKeyTag() == keyTag) {
                return dnskey;
            }
        }
        return null;
    }

    /**
     * Extracts RRSIGs from a record list that cover a specific type.
     *
     * @param records the records to search
     * @param coveredType the type value to match
     * @return list of matching RRSIG records
     */
    public static List<DNSResourceRecord> findRRSIGs(
            List<DNSResourceRecord> records, int coveredType) {
        List<DNSResourceRecord> result = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            DNSResourceRecord rr = records.get(i);
            if (rr.getType() == DNSType.RRSIG
                    && rr.getRRSIGTypeCovered() == coveredType) {
                result.add(rr);
            }
        }
        return result;
    }

    /**
     * Extracts records of a specific type from a record list.
     *
     * @param records the records to search
     * @param type the type to match
     * @return list of matching records
     */
    public static List<DNSResourceRecord> filterByType(
            List<DNSResourceRecord> records, DNSType type) {
        List<DNSResourceRecord> result = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            DNSResourceRecord rr = records.get(i);
            if (rr.getType() == type) {
                result.add(rr);
            }
        }
        return result;
    }

}
