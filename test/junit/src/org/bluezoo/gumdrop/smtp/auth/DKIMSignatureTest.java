/*
 * DKIMSignatureTest.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.List;

/**
 * Unit tests for DKIMSignature parsing.
 */
public class DKIMSignatureTest {

    @Test
    public void testParseBasicSignature() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=selector1; " +
                "h=from:to:subject:date; bh=base64bodyhash==; b=base64signature==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("1", sig.getVersion());
        assertEquals("rsa-sha256", sig.getAlgorithm());
        assertEquals("example.com", sig.getDomain());
        assertEquals("selector1", sig.getSelector());
        assertEquals("base64bodyhash==", sig.getBodyHash());
        assertEquals("base64signature==", sig.getSignature());
    }

    @Test
    public void testParseSignedHeaders() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=From:To:Subject:Date:Message-ID; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        List<String> headers = sig.getSignedHeaders();
        assertEquals(5, headers.size());
        assertEquals("from", headers.get(0));
        assertEquals("to", headers.get(1));
        assertEquals("subject", headers.get(2));
        assertEquals("date", headers.get(3));
        assertEquals("message-id", headers.get(4));
    }

    @Test
    public void testParseCanonicalization() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "c=relaxed/simple; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("relaxed/simple", sig.getCanonicalization());
        assertEquals("relaxed", sig.getHeaderCanonicalization());
        assertEquals("simple", sig.getBodyCanonicalization());
    }

    @Test
    public void testParseRelaxedBoth() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "c=relaxed/relaxed; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("relaxed", sig.getHeaderCanonicalization());
        assertEquals("relaxed", sig.getBodyCanonicalization());
    }

    @Test
    public void testParseDefaultCanonicalization() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertNull(sig.getCanonicalization());
        assertEquals("simple", sig.getHeaderCanonicalization());
        assertEquals("simple", sig.getBodyCanonicalization());
    }

    @Test
    public void testParseBodyLength() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "l=1024; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals(1024, sig.getBodyLength());
    }

    @Test
    public void testParseNoBodyLength() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals(-1, sig.getBodyLength());
    }

    @Test
    public void testParseTimestamps() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "t=1234567890; x=1234657890; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals(1234567890L, sig.getTimestamp());
        assertEquals(1234657890L, sig.getExpiration());
    }

    @Test
    public void testParseIdentity() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "i=user@example.com; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("user@example.com", sig.getIdentity());
    }

    @Test
    public void testParseQueryMethod() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "q=dns/txt; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("dns/txt", sig.getQueryMethod());
    }

    @Test
    public void testGetKeyQueryName() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=dkim2024; " +
                "h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("dkim2024._domainkey.example.com", sig.getKeyQueryName());
    }

    @Test
    public void testParseFoldedSignature() {
        // Simulates a signature that spans multiple lines
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel;\r\n" +
                " h=from:to:subject; bh=hash==;\r\n" +
                " b=long\r\n signature\r\n value==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("longsignaturevalue==", sig.getSignature());
    }

    @Test
    public void testParseWithWhitespaceInHash() {
        // Whitespace in bh= and b= values should be removed
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash with spaces==; b=sig with tabs\t==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("hashwithspaces==", sig.getBodyHash());
        assertEquals("sigwithtabs==", sig.getSignature());
    }

    @Test
    public void testParseNullReturnsNull() {
        assertNull(DKIMSignature.parse(null));
    }

    @Test
    public void testParseMissingVersionReturnsNull() {
        String header = "a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingAlgorithmReturnsNull() {
        String header = "v=1; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingDomainReturnsNull() {
        String header = "v=1; a=rsa-sha256; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingSelectorReturnsNull() {
        String header = "v=1; a=rsa-sha256; d=example.com; " +
                "h=from; bh=hash==; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingHeadersReturnsNull() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "bh=hash==; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingBodyHashReturnsNull() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; b=sig==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseMissingSignatureReturnsNull() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==";

        assertNull(DKIMSignature.parse(header));
    }

    @Test
    public void testParseEd25519Algorithm() {
        String header = "v=1; a=ed25519-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("ed25519-sha256", sig.getAlgorithm());
    }

    @Test
    public void testRawHeader() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals(header, sig.getRawHeader());
    }

    @Test
    public void testParseHeaderOnlyCanonicalization() {
        String header = "v=1; a=rsa-sha256; d=example.com; s=sel; " +
                "c=relaxed; h=from; bh=hash==; b=sig==";

        DKIMSignature sig = DKIMSignature.parse(header);

        assertNotNull(sig);
        assertEquals("relaxed", sig.getHeaderCanonicalization());
        assertEquals("simple", sig.getBodyCanonicalization());
    }

}

