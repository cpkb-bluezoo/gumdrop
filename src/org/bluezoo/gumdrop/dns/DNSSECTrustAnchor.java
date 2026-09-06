/*
 * DNSSECTrustAnchor.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNSSEC trust anchor management.
 * RFC 4033 section 5: a trust anchor is a configured DNSKEY or DS
 * record that forms the root of a chain of trust.
 *
 * <p>By default this class ships with the IANA root zone trust
 * anchors (the root KSK DS records). Custom trust anchors can be
 * added for private/split-horizon zones.
 *
 * <p>A trust anchor can also be a DNSKEY trusted directly rather than
 * through a DS digest, which is how {@link DNSSECTrustAnchorUpdater}
 * (RFC 5011 automated rollover) promotes a newly-observed key to
 * trusted -- RFC 5011 tracks and trusts DNSKEYs directly, since the
 * whole point is to keep trusting a zone whose parent-published DS
 * record a resolver may never re-fetch.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSSECTrustAnchorUpdater
 */
public final class DNSSECTrustAnchor {

    private final Map<String, List<AnchorDS>> anchors;
    private final Map<String, List<AnchorKey>> dnskeyAnchors;

    /**
     * Creates a trust anchor store with the IANA root zone
     * trust anchors pre-loaded.
     */
    public DNSSECTrustAnchor() {
        this.anchors = new ConcurrentHashMap<>();
        this.dnskeyAnchors = new ConcurrentHashMap<>();
        loadRootAnchors();
    }

    /**
     * Adds a DS-based trust anchor for a zone.
     *
     * @param zone the zone name (e.g. "." for root, "example.com")
     * @param keyTag the DNSKEY key tag
     * @param algorithm the DNSSEC algorithm number
     * @param digestType the DS digest type (2=SHA-256, 4=SHA-384)
     * @param digest the DS digest in hex
     */
    public void addAnchor(String zone, int keyTag, int algorithm,
                          int digestType, String digest) {
        String key = normalizeZone(zone);
        byte[] digestBytes = hexToBytes(digest);
        AnchorDS anchor = new AnchorDS(keyTag, algorithm,
                digestType, digestBytes);
        List<AnchorDS> list = anchors.get(key);
        if (list == null) {
            list = new ArrayList<>();
            anchors.put(key, list);
        }
        list.add(anchor);
    }

    /**
     * Returns the trust anchor DS entries for a zone.
     *
     * @param zone the zone name
     * @return the DS entries, or an empty list if no anchor exists
     */
    public List<AnchorDS> getAnchors(String zone) {
        String key = normalizeZone(zone);
        List<AnchorDS> list = anchors.get(key);
        if (list == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Returns true if a trust anchor exists for the given zone.
     *
     * @param zone the zone name
     * @return true if an anchor is configured
     */
    public boolean hasAnchor(String zone) {
        return !getAnchors(zone).isEmpty();
    }

    /**
     * Checks whether a DNSKEY is trusted -- either by matching it
     * against the configured DS trust anchors for its zone, or by
     * matching a DNSKEY trusted directly via {@link
     * #addDNSKEYAnchor(String, DNSResourceRecord)} (as RFC 5011
     * automated rollover does once it promotes a key).
     *
     * @param zone the zone the DNSKEY belongs to
     * @param dnskey the DNSKEY record
     * @return true if the DNSKEY matches a trust anchor
     */
    public boolean isDNSKEYTrusted(String zone,
                                   DNSResourceRecord dnskey) {
        int keyTag = dnskey.computeKeyTag();
        int algorithm = dnskey.getDNSKEYAlgorithm();

        List<AnchorDS> zoneAnchors = getAnchors(zone);
        for (int i = 0; i < zoneAnchors.size(); i++) {
            AnchorDS anchor = zoneAnchors.get(i);
            if (anchor.keyTag != keyTag
                    || anchor.algorithm != algorithm) {
                continue;
            }

            DNSResourceRecord syntheticDS = buildSyntheticDS(
                    zone, anchor);
            if (DNSSECValidator.verifyDS(dnskey, syntheticDS)) {
                return true;
            }
        }

        byte[] publicKey = dnskey.getDNSKEYPublicKey();
        List<AnchorKey> zoneKeyAnchors = getDNSKEYAnchors(zone);
        for (int i = 0; i < zoneKeyAnchors.size(); i++) {
            AnchorKey anchor = zoneKeyAnchors.get(i);
            if (anchor.algorithm == algorithm
                    && Arrays.equals(anchor.publicKey, publicKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a DNSKEY trusted directly, rather than through a DS digest.
     * Matched by algorithm and public key material only -- not the key
     * tag or flags -- since {@link DNSSECTrustAnchorUpdater} may need
     * to keep trusting a key across a REVOKE-bit flip, which changes
     * the key tag (RFC 5011 section 5.1) but not the key itself.
     *
     * @param zone the zone this key belongs to
     * @param dnskey the DNSKEY record to trust directly
     */
    public void addDNSKEYAnchor(String zone, DNSResourceRecord dnskey) {
        String key = normalizeZone(zone);
        AnchorKey anchor = new AnchorKey(
                dnskey.getDNSKEYAlgorithm(), dnskey.getDNSKEYPublicKey());
        List<AnchorKey> list = dnskeyAnchors.get(key);
        if (list == null) {
            list = new ArrayList<>();
            dnskeyAnchors.put(key, list);
        }
        if (!list.contains(anchor)) {
            list.add(anchor);
        }
    }

    /**
     * Removes a directly-trusted DNSKEY previously added via {@link
     * #addDNSKEYAnchor(String, DNSResourceRecord)}, matched the same
     * way: by algorithm and public key material.
     *
     * @param zone the zone this key belongs to
     * @param dnskey the DNSKEY record to stop trusting
     */
    public void removeDNSKEYAnchor(String zone, DNSResourceRecord dnskey) {
        List<AnchorKey> list = dnskeyAnchors.get(normalizeZone(zone));
        if (list == null) {
            return;
        }
        list.remove(new AnchorKey(
                dnskey.getDNSKEYAlgorithm(), dnskey.getDNSKEYPublicKey()));
    }

    /**
     * Returns the directly-trusted DNSKEY anchors for a zone.
     *
     * @param zone the zone name
     * @return the anchors, or an empty list if none exist
     */
    public List<AnchorKey> getDNSKEYAnchors(String zone) {
        List<AnchorKey> list = dnskeyAnchors.get(normalizeZone(zone));
        if (list == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Removes all trust anchors for a zone, DS-based and
     * directly-trusted DNSKEYs alike.
     *
     * @param zone the zone name
     */
    public void removeAnchors(String zone) {
        String key = normalizeZone(zone);
        anchors.remove(key);
        dnskeyAnchors.remove(key);
    }

    /**
     * Clears all trust anchors.
     */
    public void clear() {
        anchors.clear();
        dnskeyAnchors.clear();
    }

    // -- Root trust anchors --

    /**
     * Loads the IANA root zone trust anchors.
     * These are the DS records for the root zone KSK.
     *
     * <p>Key tag 20326: root KSK rolled in 2018 (RSA/SHA-256).
     * Key tag 38696: upcoming root KSK (RSA/SHA-256).
     *
     * <p>Source: https://data.iana.org/root-anchors/root-anchors.xml
     */
    private void loadRootAnchors() {
        // Root KSK 20326 (2018 roll), algorithm 8 (RSA/SHA-256)
        // DS digest type 2 (SHA-256)
        addAnchor(".", 20326, 8, 2,
                "E06D44B80B8F1D39A95C0B0D7C65D08458E880409BBC683457104237C7F8EC8D");

        // Root KSK 38696 (next scheduled roll), algorithm 8 (RSA/SHA-256)
        // DS digest type 2 (SHA-256)
        addAnchor(".", 38696, 8, 2,
                "683D2D0ACB8C9B712A1948B27F741219298D0A450D612C483AF444A4C0FB2B16");
    }

    // -- Helpers --

    private static String normalizeZone(String zone) {
        if (zone == null || zone.isEmpty()) {
            return ".";
        }
        String lower = zone.toLowerCase();
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.isEmpty()) {
            return ".";
        }
        return lower;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Builds a synthetic DS record from an anchor entry for use
     * with {@link DNSSECValidator#verifyDS}.
     */
    private static DNSResourceRecord buildSyntheticDS(
            String zone, AnchorDS anchor) {
        byte[] rdata = new byte[4 + anchor.digest.length];
        rdata[0] = (byte) ((anchor.keyTag >> 8) & 0xFF);
        rdata[1] = (byte) (anchor.keyTag & 0xFF);
        rdata[2] = (byte) anchor.algorithm;
        rdata[3] = (byte) anchor.digestType;
        System.arraycopy(anchor.digest, 0, rdata, 4,
                anchor.digest.length);
        return new DNSResourceRecord(zone, DNSType.DS, DNSClass.IN,
                0, rdata);
    }

    /**
     * A DS trust anchor entry.
     */
    public static final class AnchorDS {

        final int keyTag;
        final int algorithm;
        final int digestType;
        final byte[] digest;

        AnchorDS(int keyTag, int algorithm, int digestType,
                 byte[] digest) {
            this.keyTag = keyTag;
            this.algorithm = algorithm;
            this.digestType = digestType;
            this.digest = digest.clone();
        }

        /**
         * Returns the key tag.
         *
         * @return the key tag
         */
        public int getKeyTag() {
            return keyTag;
        }

        /**
         * Returns the algorithm number.
         *
         * @return the algorithm
         */
        public int getAlgorithm() {
            return algorithm;
        }

        /**
         * Returns the digest type.
         *
         * @return the digest type
         */
        public int getDigestType() {
            return digestType;
        }

        /**
         * Returns the digest bytes.
         *
         * @return a copy of the digest
         */
        public byte[] getDigest() {
            return digest.clone();
        }
    }

    /**
     * A directly-trusted DNSKEY anchor entry, identified by algorithm
     * and public key material (not key tag or flags -- see {@link
     * #addDNSKEYAnchor(String, DNSResourceRecord)}).
     */
    public static final class AnchorKey {

        final int algorithm;
        final byte[] publicKey;

        AnchorKey(int algorithm, byte[] publicKey) {
            this.algorithm = algorithm;
            this.publicKey = publicKey.clone();
        }

        /**
         * Returns the algorithm number.
         *
         * @return the algorithm
         */
        public int getAlgorithm() {
            return algorithm;
        }

        /**
         * Returns the public key bytes.
         *
         * @return a copy of the public key material
         */
        public byte[] getPublicKey() {
            return publicKey.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AnchorKey)) {
                return false;
            }
            AnchorKey other = (AnchorKey) o;
            return algorithm == other.algorithm
                    && Arrays.equals(publicKey, other.publicKey);
        }

        @Override
        public int hashCode() {
            return algorithm * 31 + Arrays.hashCode(publicKey);
        }
    }

}
