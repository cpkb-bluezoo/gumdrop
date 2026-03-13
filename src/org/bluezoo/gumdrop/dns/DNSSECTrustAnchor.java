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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSSECTrustAnchor {

    private final Map<String, List<AnchorDS>> anchors;

    /**
     * Creates a trust anchor store with the IANA root zone
     * trust anchors pre-loaded.
     */
    public DNSSECTrustAnchor() {
        this.anchors = new ConcurrentHashMap<>();
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
     * Checks whether a DNSKEY is directly trusted by matching it
     * against the configured DS trust anchors for its zone.
     *
     * @param zone the zone the DNSKEY belongs to
     * @param dnskey the DNSKEY record
     * @return true if the DNSKEY matches a trust anchor DS
     */
    public boolean isDNSKEYTrusted(String zone,
                                   DNSResourceRecord dnskey) {
        List<AnchorDS> zoneAnchors = getAnchors(zone);
        if (zoneAnchors.isEmpty()) {
            return false;
        }

        int keyTag = dnskey.computeKeyTag();
        int algorithm = dnskey.getDNSKEYAlgorithm();

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
        return false;
    }

    /**
     * Removes all trust anchors for a zone.
     *
     * @param zone the zone name
     */
    public void removeAnchors(String zone) {
        anchors.remove(normalizeZone(zone));
    }

    /**
     * Clears all trust anchors.
     */
    public void clear() {
        anchors.clear();
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

}
