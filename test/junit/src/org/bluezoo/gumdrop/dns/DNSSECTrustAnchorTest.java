/*
 * DNSSECTrustAnchorTest.java
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

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSSECTrustAnchor}'s direct-DNSKEY trust
 * support, added for issue #411 (RFC 5011) -- {@link
 * DNSSECTrustAnchor#addDNSKEYAnchor}, {@link
 * DNSSECTrustAnchor#removeDNSKEYAnchor}, and the corresponding
 * extension to {@link DNSSECTrustAnchor#isDNSKEYTrusted}. The
 * pre-existing DS-based anchor methods are not covered here (no
 * behavioural change was made to them).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSSECTrustAnchorTest {

    private static final String ZONE = "example";

    @Test
    public void testAddDNSKEYAnchorMakesKeyTrusted() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        assertFalse(anchor.isDNSKEYTrusted(ZONE, key));
        anchor.addDNSKEYAnchor(ZONE, key);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, key));
    }

    @Test
    public void testRemoveDNSKEYAnchorStopsTrust() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        anchor.addDNSKEYAnchor(ZONE, key);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, key));
        anchor.removeDNSKEYAnchor(ZONE, key);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, key));
    }

    @Test
    public void testDNSKEYAnchorMatchedAcrossRevokeBitFlip() throws Exception {
        // RFC 5011 section 5.1: setting the REVOKE bit changes a key's
        // wire RDATA (and therefore its key tag), but addDNSKEYAnchor
        // must still recognize the same key -- it's matched by
        // algorithm and public key material only, not tag or flags.
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();
        DNSResourceRecord revokedForm = DNSResourceRecord.dnskey(
                ZONE, 3600, key.getDNSKEYFlags() | 0x0080,
                key.getDNSKEYAlgorithm(), key.getDNSKEYPublicKey());
        assertNotEquals(key.computeKeyTag(), revokedForm.computeKeyTag());

        anchor.addDNSKEYAnchor(ZONE, key);
        assertTrue(anchor.isDNSKEYTrusted(ZONE, revokedForm));

        anchor.removeDNSKEYAnchor(ZONE, revokedForm);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, key));
    }

    @Test
    public void testDNSKEYAnchorIsPerZone() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        anchor.addDNSKEYAnchor("zone-a", key);
        assertTrue(anchor.isDNSKEYTrusted("zone-a", key));
        assertFalse(anchor.isDNSKEYTrusted("zone-b", key));
    }

    @Test
    public void testAddDNSKEYAnchorDoesNotDuplicate() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        anchor.addDNSKEYAnchor(ZONE, key);
        anchor.addDNSKEYAnchor(ZONE, key);
        assertEquals(1, anchor.getDNSKEYAnchors(ZONE).size());
    }

    @Test
    public void testRemoveAnchorsClearsDNSKEYAnchorsToo() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        anchor.addDNSKEYAnchor(ZONE, key);
        anchor.removeAnchors(ZONE);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, key));
        assertTrue(anchor.getDNSKEYAnchors(ZONE).isEmpty());
    }

    @Test
    public void testClearRemovesDNSKEYAnchorsToo() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();

        anchor.addDNSKEYAnchor(ZONE, key);
        anchor.clear();
        assertFalse(anchor.isDNSKEYTrusted(ZONE, key));
    }

    @Test
    public void testDNSKEYAnchorDoesNotMatchDifferentAlgorithm() throws Exception {
        DNSSECTrustAnchor anchor = new DNSSECTrustAnchor();
        DNSResourceRecord key = buildKSK();
        // Same public key bytes, different algorithm number: must not match.
        DNSResourceRecord differentAlgorithm = DNSResourceRecord.dnskey(
                ZONE, 3600, key.getDNSKEYFlags(), 10, key.getDNSKEYPublicKey());

        anchor.addDNSKEYAnchor(ZONE, key);
        assertFalse(anchor.isDNSKEYTrusted(ZONE, differentAlgorithm));
    }

    // ── Helpers ──

    private static DNSResourceRecord buildKSK() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.generateKeyPair();
        return DNSResourceRecord.dnskey(ZONE, 3600, 0x0101,
                DNSSECAlgorithm.RSASHA256.getNumber(), rsaWireFormat(kp.getPublic()));
    }

    private static byte[] rsaWireFormat(PublicKey pub) {
        RSAPublicKey rsa = (RSAPublicKey) pub;
        byte[] exponent = stripLeadingZero(rsa.getPublicExponent().toByteArray());
        byte[] modulus = stripLeadingZero(rsa.getModulus().toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(exponent.length);
        out.write(exponent, 0, exponent.length);
        out.write(modulus, 0, modulus.length);
        return out.toByteArray();
    }

    private static byte[] stripLeadingZero(byte[] b) {
        if (b.length > 1 && b[0] == 0) {
            return Arrays.copyOfRange(b, 1, b.length);
        }
        return b;
    }
}
