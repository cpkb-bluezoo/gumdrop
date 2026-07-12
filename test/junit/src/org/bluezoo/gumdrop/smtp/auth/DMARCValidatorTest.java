/*
 * DMARCValidatorTest.java
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

import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.client.DNSResolver;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DMARCValidator} (FEAT-001, RFC 9989 "DMARCbis"
 * additions: {@code t=}, {@code np=}, {@code psd=} tags, PSD policy
 * lookup, and {@code v=DMARC1} first-tag validation).
 */
public class DMARCValidatorTest {

    private StubDNSResolver resolver;

    @Before
    public void setUp() {
        resolver = new StubDNSResolver();
    }

    @Test
    public void testVTagNotFirstIsRejected() throws Exception {
        // RFC 9989 §4.1: v= MUST be the first tag, or the record is
        // discarded entirely. Tested directly against parseDMARCRecord()
        // (like DMARCRuaParsingTest) rather than through evaluate() end
        // to end: the caller's own raw-string "v=DMARC1" prefix filter
        // (handleDMARCResponse) already treats a record with v= not
        // literally at the very start as "not a DMARC record at all"
        // before parseDMARCRecord() is ever reached, so testing through
        // evaluate() wouldn't actually exercise this validation.
        assertNull(parseDMARC("p=reject; v=DMARC1"));
    }

    @Test
    public void testVersionValueMustBeExact() throws Exception {
        // "v=DMARC10; ..." passes a naive raw-string startsWith("v=DMARC1")
        // check (it's a true prefix) but is not a valid v=DMARC1 tag - the
        // exact-match check inside parseDMARCRecord() is what actually
        // catches this.
        assertNull(parseDMARC("v=DMARC10; p=reject"));
    }

    @Test
    public void testVTagFirstParsesCorrectly() throws Exception {
        DMARCValidator.DMARCRecord rec = parseDMARC("v=DMARC1; p=reject");
        assertNotNull(rec);
        assertEquals(DMARCPolicy.REJECT, rec.policy);
    }

    /**
     * Uses reflection to invoke the private parseDMARCRecord method
     * (mirrors DMARCRuaParsingTest's helper).
     */
    private DMARCValidator.DMARCRecord parseDMARC(String txt) throws Exception {
        java.lang.reflect.Method method =
                DMARCValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DMARCValidator validator = new DMARCValidator(resolver);
        return (DMARCValidator.DMARCRecord) method.invoke(validator, txt);
    }

    @Test
    public void testWellFormedRecordStillWorks() {
        // RFC 7489 baseline: existing correctly-formed records are
        // unaffected by the stricter v= validation.
        resolver.addTxt("_dmarc.example.com", "v=DMARC1; p=reject");

        Result result = evaluate("example.com", SPFResult.PASS, "example.com",
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.PASS, result.result);
        assertEquals(AuthVerdict.PASS, result.verdict);
    }

    @Test
    public void testTestingModeDowngradesRejectToQuarantine() {
        resolver.addTxt("_dmarc.example.com", "v=DMARC1; p=reject; t=y");

        Result result = evaluate("example.com", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.FAIL, result.result);
        assertEquals(AuthVerdict.QUARANTINE, result.verdict);
    }

    @Test
    public void testTestingModeDowngradesQuarantineToNone() {
        resolver.addTxt("_dmarc.example.com", "v=DMARC1; p=quarantine; t=y");

        Result result = evaluate("example.com", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.FAIL, result.result);
        assertEquals(AuthVerdict.NONE, result.verdict);
    }

    @Test
    public void testDefaultTestingModeAppliesPolicyAsWritten() {
        resolver.addTxt("_dmarc.example.com", "v=DMARC1; p=reject");

        Result result = evaluate("example.com", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(AuthVerdict.REJECT, result.verdict);
    }

    @Test
    public void testPsdLookupAppliesNpWhenOrgDomainHasNoRecord() {
        // No record at the Author Domain or its Organizational Domain
        // ("example.co.uk"), but "co.uk" declares itself a PSD with np=.
        resolver.addTxt("_dmarc.co.uk", "v=DMARC1; p=none; psd=y; np=reject");

        Result result = evaluate("sub.example.co.uk", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.FAIL, result.result);
        assertEquals(AuthVerdict.REJECT, result.verdict);
        assertEquals(DMARCPolicy.REJECT, result.policy);
    }

    @Test
    public void testPsdLookupIgnoredWithoutPsdYFlag() {
        // Same shape, but the record at "co.uk" doesn't declare psd=y -
        // RFC 9989 §5 only applies np= when a record explicitly opts in.
        resolver.addTxt("_dmarc.co.uk", "v=DMARC1; p=none; np=reject");

        Result result = evaluate("sub.example.co.uk", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.NONE, result.result);
        assertEquals(AuthVerdict.NONE, result.verdict);
    }

    @Test
    public void testPsdLookupFallsBackToPWhenNpAbsent() {
        resolver.addTxt("_dmarc.co.uk", "v=DMARC1; p=quarantine; psd=y");

        Result result = evaluate("sub.example.co.uk", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(DMARCResult.FAIL, result.result);
        assertEquals(AuthVerdict.QUARANTINE, result.verdict);
    }

    @Test
    public void testOrgDomainRecordTakesPrecedenceOverPsd() {
        // "example.co.uk" (the Organizational Domain, computed via
        // PublicSuffixList) has its own record - the PSD at "co.uk" is
        // never even queried.
        resolver.addTxt("_dmarc.example.co.uk", "v=DMARC1; p=reject");
        resolver.addTxt("_dmarc.co.uk", "v=DMARC1; p=none; psd=y; np=none");

        Result result = evaluate("sub.example.co.uk", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(AuthVerdict.REJECT, result.verdict);
    }

    @Test
    public void testNpFallsBackToSubdomainPolicy() {
        resolver.addTxt("_dmarc.co.uk", "v=DMARC1; p=none; sp=quarantine; psd=y");

        Result result = evaluate("sub.example.co.uk", SPFResult.FAIL, null,
                DKIMResult.FAIL, null);

        assertEquals(AuthVerdict.QUARANTINE, result.verdict);
    }

    @Test
    public void testGetLastTagAccessors() {
        resolver.addTxt("_dmarc.example.com",
                "v=DMARC1; p=reject; t=y; psd=n");

        DMARCValidator validator = new DMARCValidator(resolver);
        final Result holder = new Result();
        validator.evaluate("example.com", SPFResult.FAIL, null,
                DKIMResult.FAIL, null, new DMARCCallback() {
                    @Override
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                             String domain, AuthVerdict verdict) {
                        holder.result = result;
                    }
                });

        assertEquals("y", validator.getLastT());
        assertEquals("n", validator.getLastPsd());
    }

    // -- Helpers --

    private Result evaluate(String fromDomain, SPFResult spfResult, String spfDomain,
                             DKIMResult dkimResult, String dkimDomain) {
        DMARCValidator validator = new DMARCValidator(resolver);
        final Result holder = new Result();
        validator.evaluate(fromDomain, spfResult, spfDomain, dkimResult, dkimDomain,
                new DMARCCallback() {
                    @Override
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                             String domain, AuthVerdict verdict) {
                        holder.result = result;
                        holder.policy = policy;
                        holder.verdict = verdict;
                    }
                });
        return holder;
    }

    private static final class Result {
        DMARCResult result;
        DMARCPolicy policy;
        AuthVerdict verdict;
    }

    /**
     * Synchronous stub resolver for DMARC TXT lookups (fully-qualified
     * {@code _dmarc.<domain>} names, unlike SPFValidatorTest's stub).
     */
    private static final class StubDNSResolver extends DNSResolver {

        private final Map<String, String> txtRecords = new HashMap<>();

        StubDNSResolver() {
            super();
        }

        void addTxt(String name, String text) {
            txtRecords.put(name.toLowerCase(), text);
        }

        @Override
        public void queryTXT(String name, DNSQueryCallback callback) {
            String text = txtRecords.get(name.toLowerCase());
            if (text == null) {
                callback.onResponse(nxdomainResponse());
            } else {
                callback.onResponse(txtResponse(name, text));
            }
        }

        private static DNSMessage txtResponse(String name, String text) {
            int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RA
                    | DNSMessage.RCODE_NOERROR;
            List<DNSResourceRecord> answers = Collections.singletonList(
                    DNSResourceRecord.txt(name, 300, text));
            return new DNSMessage(1, flags,
                    Collections.emptyList(),
                    answers,
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        private static DNSMessage nxdomainResponse() {
            int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RA
                    | DNSMessage.RCODE_NXDOMAIN;
            return new DNSMessage(1, flags,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }
    }
}
