/*
 * PublicSuffixListTest.java
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

/**
 * Unit tests for {@link PublicSuffixList} (SEC-048) — DMARC organizational
 * domain computation against the real Mozilla Public Suffix List.
 *
 * <p>Test cases mirror a representative subset of the official PSL test
 * vectors (<a href="https://github.com/publicsuffix/list/blob/master/tests/test_psl.txt">
 * test_psl.txt</a>), adapted to this class's contract: {@code
 * getRegistrableDomain} always returns a non-null string, returning the
 * input unchanged when it's already at or above the organizational level
 * (the official test file uses {@code null} for that case instead).
 */
public class PublicSuffixListTest {

    private final PublicSuffixList psl = PublicSuffixList.getInstance();

    @Test
    public void testSimpleTld() {
        assertEquals("example.com", psl.getRegistrableDomain("example.com"));
        assertEquals("example.com", psl.getRegistrableDomain("www.example.com"));
        assertEquals("example.com", psl.getRegistrableDomain("a.b.example.com"));
    }

    @Test
    public void testBareTldHasNoRoomToStrip() {
        // A bare public suffix, with nothing to its left, is returned as-is
        // per this class's contract (rather than null, per the official
        // PSL test file's convention).
        assertEquals("com", psl.getRegistrableDomain("com"));
        assertEquals("biz", psl.getRegistrableDomain("biz"));
    }

    @Test
    public void testTldWithOnlyOneRule() {
        assertEquals("domain.biz", psl.getRegistrableDomain("domain.biz"));
        assertEquals("domain.biz", psl.getRegistrableDomain("b.domain.biz"));
        assertEquals("domain.biz", psl.getRegistrableDomain("a.b.domain.biz"));
    }

    @Test
    public void testTldWithTwoLevelRule() {
        // "uk.com" is itself a public suffix (not a registrable domain).
        assertEquals("example.uk.com", psl.getRegistrableDomain("example.uk.com"));
        assertEquals("example.uk.com", psl.getRegistrableDomain("b.example.uk.com"));
        assertEquals("example.uk.com", psl.getRegistrableDomain("a.b.example.uk.com"));
    }

    @Test
    public void testUkCcTld() {
        assertEquals("example.co.uk", psl.getRegistrableDomain("example.co.uk"));
        assertEquals("example.co.uk", psl.getRegistrableDomain("www.example.co.uk"));
        assertEquals("example.co.uk", psl.getRegistrableDomain("a.b.example.co.uk"));
    }

    @Test
    public void testAustraliaCcTld() {
        assertEquals("example.com.au", psl.getRegistrableDomain("example.com.au"));
        assertEquals("example.com.au", psl.getRegistrableDomain("sub.example.com.au"));
    }

    @Test
    public void testUnlistedTldFallsBackToTwoLabels() {
        // Not in the PSL at all -> implicit "*" rule: public suffix is
        // just the last label, so the registrable domain is two labels.
        assertEquals("example.example", psl.getRegistrableDomain("example.example"));
        assertEquals("example.example", psl.getRegistrableDomain("b.example.example"));
        assertEquals("example.example", psl.getRegistrableDomain("a.b.example.example"));
    }

    @Test
    public void testWildcardRule() {
        // "*.ck" (Cook Islands): public suffix is wildcard-label + "ck".
        assertEquals("b.ck", psl.getRegistrableDomain("b.ck"));
        assertEquals("a.b.ck", psl.getRegistrableDomain("a.b.ck"));
        assertEquals("a.b.ck", psl.getRegistrableDomain("x.a.b.ck"));
    }

    @Test
    public void testExceptionToWildcardRule() {
        // "!www.ck" carves "www.ck" itself out of the "*.ck" wildcard's
        // coverage, making it a registrable domain in its own right
        // (unlike sibling "b.ck" above, which needs the wildcard label).
        assertEquals("www.ck", psl.getRegistrableDomain("www.ck"));
    }

    @Test
    public void testJapanKawasakiExceptionRule() {
        // "*.kawasaki.jp" with exception "!city.kawasaki.jp".
        assertEquals("foo.kawasaki.jp", psl.getRegistrableDomain("foo.kawasaki.jp"));
        assertEquals("city.kawasaki.jp", psl.getRegistrableDomain("city.kawasaki.jp"));
        assertEquals("city.kawasaki.jp", psl.getRegistrableDomain("www.city.kawasaki.jp"));
    }

    @Test
    public void testCaseInsensitivity() {
        // DMARCValidator lowercases before calling this, but the PSL data
        // itself is stored lowercase regardless of input case.
        assertEquals("example.co.uk", psl.getRegistrableDomain("example.co.uk"));
    }

    @Test
    public void testIdnTldMatchesPunycodeForm() {
        // The .dat file stores IDN TLD rules in native Unicode (e.g.
        // "公司.cn"), but domains extracted from RFC 5322 headers arrive
        // as ASCII/punycode ("xn--55qx5d.cn") - both forms must match.
        assertEquals("xn--85x722f.xn--55qx5d.cn",
                psl.getRegistrableDomain("xn--85x722f.xn--55qx5d.cn"));
        assertEquals("xn--85x722f.xn--55qx5d.cn",
                psl.getRegistrableDomain("www.xn--85x722f.xn--55qx5d.cn"));
    }

    @Test
    public void testPrivateSectionDomain() {
        // github.io is a PRIVATE-section entry (not ICANN-delegated), but
        // still a real registrable-domain boundary for DMARC purposes.
        assertEquals("bar.github.io", psl.getRegistrableDomain("foo.bar.github.io"));
    }
}
