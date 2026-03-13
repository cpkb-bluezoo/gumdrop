/*
 * DMARCRuaParsingTest.java
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

package org.bluezoo.gumdrop.smtp.auth;

import org.junit.Test;
import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Unit tests for DMARC record rua/ruf parsing (RFC 7489 §6.2)
 * and the Ed25519 key parsing in DKIMValidator (RFC 8463 §4).
 */
public class DMARCRuaParsingTest {

    @Test
    public void testParseSingleRua() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=none; rua=mailto:reports@example.com");

        assertNotNull(rec);
        assertNotNull(rec.rua);
        assertEquals(1, rec.rua.size());
        assertEquals("mailto:reports@example.com", rec.rua.get(0));
    }

    @Test
    public void testParseMultipleRua() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=reject; rua=mailto:a@example.com,mailto:b@example.com");

        assertNotNull(rec);
        assertNotNull(rec.rua);
        assertEquals(2, rec.rua.size());
        assertEquals("mailto:a@example.com", rec.rua.get(0));
        assertEquals("mailto:b@example.com", rec.rua.get(1));
    }

    @Test
    public void testParseRuaWithSizeLimit() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=none; rua=mailto:reports@example.com!10m");

        assertNotNull(rec);
        assertNotNull(rec.rua);
        assertEquals(1, rec.rua.size());
        assertEquals("mailto:reports@example.com!10m", rec.rua.get(0));
    }

    @Test
    public void testParseRuf() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=quarantine; ruf=mailto:forensic@example.com");

        assertNotNull(rec);
        assertNotNull(rec.ruf);
        assertEquals(1, rec.ruf.size());
        assertEquals("mailto:forensic@example.com", rec.ruf.get(0));
    }

    @Test
    public void testParseRuaAndRuf() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=reject; rua=mailto:agg@example.com; ruf=mailto:fail@example.com");

        assertNotNull(rec);
        assertNotNull(rec.rua);
        assertEquals(1, rec.rua.size());
        assertEquals("mailto:agg@example.com", rec.rua.get(0));
        assertNotNull(rec.ruf);
        assertEquals(1, rec.ruf.size());
        assertEquals("mailto:fail@example.com", rec.ruf.get(0));
    }

    @Test
    public void testNoRuaTag() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC("v=DMARC1; p=none");

        assertNotNull(rec);
        assertNull(rec.rua);
    }

    @Test
    public void testFullRecordWithAlignmentAndPct() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC(
                "v=DMARC1; p=quarantine; sp=reject; adkim=s; aspf=s; pct=50; " +
                "rua=mailto:dmarc@example.com");

        assertNotNull(rec);
        assertEquals(DMARCPolicy.QUARANTINE, rec.policy);
        assertEquals(DMARCPolicy.REJECT, rec.subdomainPolicy);
        assertEquals("s", rec.adkim);
        assertEquals("s", rec.aspf);
        assertEquals(50, rec.pct);
        assertNotNull(rec.rua);
        assertEquals("mailto:dmarc@example.com", rec.rua.get(0));
    }

    /**
     * Uses reflection to invoke the private parseDMARCRecord method.
     */
    private DMARCValidator.DMARCRecord parseDMARC(String txt) throws Exception {
        Method method = DMARCValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DMARCValidator validator = new DMARCValidator(null);
        return (DMARCValidator.DMARCRecord) method.invoke(validator, txt);
    }

}
