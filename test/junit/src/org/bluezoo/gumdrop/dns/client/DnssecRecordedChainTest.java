/*
 * DnssecChainValidatorTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.CannedResolver;
import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.message;
import static org.junit.Assert.*;

/**
 * Validates DNSSEC data recorded from the real DNS (see
 * {@code scripts/capture-dnssec-fixture.sh}) through the shipped IANA root
 * trust anchors, as of the time it was captured. No network is used. The
 * recordings cover RSA (algorithm 8) at the root and {@code org}, ECDSA
 * (algorithm 13) at the zones, and a zone that is not signed at all.
 *
 * <p>Each negative test damages the recorded text in one way and checks
 * that the chain no longer validates, so a real, correctly signed chain is
 * what is being broken.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnssecRecordedChainTest {

    private static final String[] SIGNED = {
        "cloudflare.com.zone", "www.ietf.org.zone", "iana.org.zone"
    };

    private DnssecStatus status;
    private int calls;

    private final DnssecValidationCallback callback = new DnssecValidationCallback() {
        @Override
        public void onValidated(DnssecStatus s, DnsMessage response) {
            status = s;
            calls++;
        }
    };

    @Before
    public void reset() {
        status = null;
        calls = 0;
    }

    private DnssecStatus validate(String recording, long atEpoch, DnssecTrustAnchor anchors)
            throws Exception {
        return validate(DnssecRecordedData.load(recording), atEpoch, anchors);
    }

    /** Validates a recording; atEpoch < 0 means the capture time plus a minute. */
    private DnssecStatus validate(DnssecRecordedData data, long atEpoch, DnssecTrustAnchor anchors) {
        CannedResolver resolver = new CannedResolver();
        data.installIn(resolver);
        // the root has no parent, so a real resolver answers a query for its DS with nothing
        resolver.put(".", DnsType.DS, message(Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()));
        DnssecChainValidator validator = new DnssecChainValidator(resolver, anchors);
        long at = atEpoch < 0 ? data.getCapturedAt() + 60 : atEpoch;
        validator.clock(Clock.fixed(Instant.ofEpochSecond(at), ZoneOffset.UTC));
        validator.validate(message(data.getAnswer(),
                Collections.<DnsResourceRecord>emptyList()), callback);
        assertEquals("validation must complete exactly once", 1, calls);
        return status;
    }

    private DnssecStatus validateText(String text) throws Exception {
        return validate(DnssecRecordedData.parse(text), -1, new DnssecTrustAnchor());
    }

    // ---- text damage helpers ----

    /** Removes every line that starts with the owner and has the given record type token. */
    private static String removeRecords(String text, String owner, String type) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            boolean match = t.length > 4 && t[0].equals(owner) && t[3].equals(type);
            if (!match) {
                out.append(lines[i]).append('\n');
            }
        }
        return out.toString();
    }

    private static String removeSignatures(String text, String owner, String covered) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            boolean match = t.length > 5 && t[0].equals(owner) && t[3].equals("RRSIG")
                    && t[4].equals(covered);
            if (!match) {
                out.append(lines[i]).append('\n');
            }
        }
        return out.toString();
    }

    /** Changes the first base64 character of the signature on lines matching owner and covered type. */
    private static String damageSignature(String text, String owner, String covered) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        boolean done = false;
        for (int i = 0; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            boolean match = !done && t.length > 12 && t[0].equals(owner) && t[3].equals("RRSIG")
                    && t[4].equals(covered);
            if (match) {
                char c = t[12].charAt(0);
                t[12] = (c == 'A' ? 'B' : 'A') + t[12].substring(1);
                out.append(String.join("\t", t)).append('\n');
                done = true;
            } else {
                out.append(lines[i]).append('\n');
            }
        }
        assertTrue("no signature on " + owner + " covering " + covered, done);
        return out.toString();
    }

    /** Changes the last hex digit of the digest of the DS for owner. */
    private static String damageDs(String text, String owner) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n");
        boolean done = false;
        for (int i = 0; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+");
            if (!done && t.length > 7 && t[0].equals(owner) && t[3].equals("DS")) {
                String last = t[t.length - 1];
                char c = last.charAt(last.length() - 1);
                t[t.length - 1] = last.substring(0, last.length() - 1) + (c == '0' ? '1' : '0');
                out.append(String.join("\t", t)).append('\n');
                done = true;
            } else {
                out.append(lines[i]).append('\n');
            }
        }
        assertTrue("no DS for " + owner, done);
        return out.toString();
    }

    // ---- the real chains validate ----

    @Test
    public void testRealChainsAreSecureAtCaptureTime() throws Exception {
        for (int i = 0; i < SIGNED.length; i++) {
            calls = 0;
            assertEquals(SIGNED[i], DnssecStatus.SECURE,
                    validate(SIGNED[i], -1, new DnssecTrustAnchor()));
        }
    }

    @Test
    public void testUnsignedZoneIsInsecure() throws Exception {
        assertEquals(DnssecStatus.INSECURE,
                validate("google.com.zone", -1, new DnssecTrustAnchor()));
    }

    @Test
    public void testNothingValidatesWithoutTheRootTrustAnchor() throws Exception {
        DnssecTrustAnchor none = new DnssecTrustAnchor();
        none.clear();
        assertNotEquals(DnssecStatus.SECURE, validate("cloudflare.com.zone", -1, none));
    }

    // ---- signature validity periods, against the recorded signatures ----

    @Test
    public void testAfterTheAnswersSignatureExpiredIsBogus() throws Exception {
        DnssecRecordedData data = DnssecRecordedData.load("cloudflare.com.zone");
        // the A signature runs to 2026-10-11 09:26 UTC, a day or so after capture
        assertEquals(DnssecStatus.BOGUS, validate(data, data.getCapturedAt() + 3 * 86400,
                new DnssecTrustAnchor()));
    }

    @Test
    public void testBeforeTheSignaturesWereIncepted() throws Exception {
        DnssecRecordedData data = DnssecRecordedData.load("cloudflare.com.zone");
        assertEquals(DnssecStatus.BOGUS, validate(data, data.getCapturedAt() - 30 * 86400,
                new DnssecTrustAnchor()));
    }

    // ---- damage to a real, correctly signed chain ----

    @Test
    public void testTamperedAnswerIsBogus() throws Exception {
        String text = DnssecRecordedData.read("cloudflare.com.zone")
                .replace("104.16.132.229", "192.0.2.99");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedAnswerSignatureIsBogus() throws Exception {
        String text = damageSignature(DnssecRecordedData.read("cloudflare.com.zone"),
                "cloudflare.com.", "A");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedDnskeySignatureIsBogus() throws Exception {
        String text = damageSignature(DnssecRecordedData.read("cloudflare.com.zone"),
                "cloudflare.com.", "DNSKEY");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    /**
     * With the zone's signature over its DNSKEY RRset gone, nothing ties the
     * key that signed the answer to the key the DS record names, so the
     * answer must not validate (the forged-zone-key attack, on real data).
     */
    @Test
    public void testMissingDnskeySignatureIsBogus() throws Exception {
        String text = removeSignatures(DnssecRecordedData.read("cloudflare.com.zone"),
                "cloudflare.com.", "DNSKEY");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedDsDigestIsBogus() throws Exception {
        String text = damageDs(DnssecRecordedData.read("cloudflare.com.zone"), "cloudflare.com.");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedParentDsSignatureIsBogus() throws Exception {
        String text = damageSignature(DnssecRecordedData.read("cloudflare.com.zone"),
                "cloudflare.com.", "DS");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedRootDnskeySignatureIsBogus() throws Exception {
        String text = damageSignature(DnssecRecordedData.read("cloudflare.com.zone"), ".", "DNSKEY");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }

    @Test
    public void testDamagedChainOnRsaZonesIsBogus() throws Exception {
        String text = damageSignature(DnssecRecordedData.read("iana.org.zone"), "org.", "DNSKEY");
        assertEquals(DnssecStatus.BOGUS, validateText(text));
    }
}
