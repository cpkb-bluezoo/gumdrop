/*
 * DmarcValidatorExtendedTest
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

import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the discovery, alignment, verdict and event-driven paths of
 * {@link DmarcValidator} with a deterministic table resolver: error and
 * fallthrough arms of the organizational-domain and PSD lookups, strict and
 * relaxed alignment, ARC policy substitution, pct sampling at its two
 * deterministic extremes, and the accumulated-state accessors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DmarcValidatorExtendedTest {

    private static final class Outcome {
        DmarcResult result;
        DmarcPolicy policy;
        String domain;
        AuthVerdict verdict;
        int calls;
    }

    private StubDnsTable dns;
    private DmarcValidator validator;

    @Before
    public void setUp() {
        dns = new StubDnsTable();
        validator = new DmarcValidator(dns);
    }

    private DmarcCallback into(final Outcome o) {
        return new DmarcCallback() {
            @Override
            public void dmarcResult(DmarcResult result, DmarcPolicy policy,
                                    String domain, AuthVerdict verdict) {
                o.result = result;
                o.policy = policy;
                o.domain = domain;
                o.verdict = verdict;
                o.calls++;
            }
        };
    }

    private Outcome eval(String from, SpfResult spf, String spfDomain,
                         DkimResult dkim, String dkimDomain) {
        Outcome o = new Outcome();
        validator.evaluate(from, spf, spfDomain, dkim, dkimDomain, into(o));
        return o;
    }

    private Outcome failing(String from) {
        return eval(from, SpfResult.FAIL, null, DkimResult.FAIL, null);
    }

    // ---- author domain lookup ----

    @Test
    public void emptyAndNullFromDomainGiveNone() {
        assertEquals(DmarcResult.NONE, failing(null).result);
        assertEquals(DmarcResult.NONE, failing("").result);
    }

    @Test
    public void transportErrorIsTemperror() {
        dns.fail(DnsType.TXT, "_dmarc.example.com");
        assertEquals(DmarcResult.TEMPERROR, failing("example.com").result);
    }

    @Test
    public void servfailIsTemperror() {
        dns.rcode(DnsType.TXT, "_dmarc.example.com", DnsMessage.RCODE_SERVFAIL);
        assertEquals(DmarcResult.TEMPERROR, failing("example.com").result);
    }

    @Test
    public void multipleRecordsIsPermerror() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=none");
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        assertEquals(DmarcResult.PERMERROR, failing("example.com").result);
    }

    @Test
    public void unrelatedTxtIsNone() {
        dns.txt("_dmarc.example.com", "something else");
        assertEquals(DmarcResult.NONE, failing("example.com").result);
    }

    @Test
    public void recordWithoutPolicyIsPermerror() {
        dns.txt("_dmarc.example.com", "v=DMARC1; rua=mailto:r@example.com");
        assertEquals(DmarcResult.PERMERROR, failing("example.com").result);
    }

    @Test
    public void missingRecordAtSingleLabelDomainIsNone() {
        assertEquals(DmarcResult.NONE, failing("localhost").result);
    }

    @Test
    public void missingRecordAtOrganizationalDomainIsNone() {
        Outcome o = failing("example.com");
        assertEquals(DmarcResult.NONE, o.result);
        assertNull(validator.getLastDiscoveryMethod());
    }

    // ---- organizational domain fallback ----

    @Test
    public void subdomainFallsBackToOrganizationalRecord() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        Outcome o = failing("mail.example.com");
        assertEquals(DmarcResult.FAIL, o.result);
        assertEquals(DmarcPolicy.REJECT, o.policy);
        assertEquals(AuthVerdict.REJECT, o.verdict);
        assertEquals("psl", validator.getLastDiscoveryMethod());
    }

    @Test
    public void organizationalSubdomainPolicyOverrides() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; sp=quarantine; t=y");
        Outcome o = failing("mail.example.com");
        assertEquals(DmarcPolicy.QUARANTINE, o.policy);
        assertEquals(AuthVerdict.NONE, o.verdict);
    }

    @Test
    public void organizationalRecordPassesOnAlignedDkim() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        Outcome o = eval("mail.example.com", SpfResult.FAIL, null,
                DkimResult.PASS, "example.com");
        assertEquals(DmarcResult.PASS, o.result);
        assertEquals(AuthVerdict.PASS, o.verdict);
        assertTrue(validator.isLastDkimAligned());
    }

    @Test
    public void organizationalLookupErrorIsNone() {
        dns.fail(DnsType.TXT, "_dmarc.example.com");
        assertEquals(DmarcResult.NONE, failing("mail.example.com").result);
    }

    @Test
    public void organizationalUnparsableRecordIsPermerror() {
        dns.txt("_dmarc.example.com", "v=DMARC1; sp=none");
        assertEquals(DmarcResult.PERMERROR, failing("mail.example.com").result);
    }

    @Test
    public void organizationalServfailTriesPsd() {
        dns.rcode(DnsType.TXT, "_dmarc.example.com", DnsMessage.RCODE_SERVFAIL);
        dns.txt("_dmarc.com", "v=DMARC1; p=reject; psd=y; np=quarantine");
        Outcome o = failing("mail.example.com");
        assertEquals(DmarcPolicy.QUARANTINE, o.policy);
        assertEquals("treewalk", validator.getLastDiscoveryMethod());
    }

    // ---- PSD lookup ----

    @Test
    public void psdNpTagGovernsNonExistentSubdomain() {
        dns.txt("_dmarc.com", "v=DMARC1; p=none; psd=y; np=reject");
        Outcome o = failing("mail.example.com");
        assertEquals(DmarcPolicy.REJECT, o.policy);
        assertEquals(AuthVerdict.REJECT, o.verdict);
        assertEquals("y", validator.getLastPsd());
        assertEquals(DmarcPolicy.REJECT, validator.getLastNp());
    }

    @Test
    public void psdFallsBackToSubdomainPolicyThenPolicy() {
        dns.txt("_dmarc.com", "v=DMARC1; p=none; psd=y; sp=quarantine");
        assertEquals(DmarcPolicy.QUARANTINE, failing("mail.example.com").policy);
        dns.replaceTxt("_dmarc.com", "v=DMARC1; p=reject; psd=y");
        assertEquals(DmarcPolicy.REJECT, failing("mail.example.com").policy);
    }

    @Test
    public void psdRecordWithoutPsdFlagIsIgnored() {
        dns.txt("_dmarc.com", "v=DMARC1; p=reject");
        assertEquals(DmarcResult.NONE, failing("mail.example.com").result);
    }

    @Test
    public void psdUnparsableRecordIsIgnored() {
        dns.txt("_dmarc.com", "v=DMARC1; psd=y");
        assertEquals(DmarcResult.NONE, failing("mail.example.com").result);
    }

    @Test
    public void psdNoDmarcRecordIsNone() {
        dns.txt("_dmarc.com", "unrelated");
        assertEquals(DmarcResult.NONE, failing("mail.example.com").result);
    }

    @Test
    public void psdErrorsAreNone() {
        dns.fail(DnsType.TXT, "_dmarc.com");
        assertEquals(DmarcResult.NONE, failing("mail.example.com").result);
        dns.rcode(DnsType.TXT, "_dmarc.org", DnsMessage.RCODE_SERVFAIL);
        assertEquals(DmarcResult.NONE, failing("a.example.org").result);
    }

    // ---- alignment ----

    @Test
    public void strictSpfAlignmentRequiresExactDomain() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; aspf=s");
        Outcome o = eval("example.com", SpfResult.PASS, "mail.example.com",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.FAIL, o.result);
        assertEquals(false, validator.isLastSpfAligned());
        o = eval("example.com", SpfResult.PASS, "EXAMPLE.com",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.PASS, o.result);
        assertTrue(validator.isLastSpfAligned());
    }

    @Test
    public void strictDkimAlignmentRequiresExactDomain() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; adkim=s");
        Outcome o = eval("example.com", SpfResult.FAIL, null,
                DkimResult.PASS, "sub.example.com");
        assertEquals(DmarcResult.FAIL, o.result);
        assertEquals("s", validator.getLastAdkim());
        assertEquals("r", validator.getLastAspf());
    }

    @Test
    public void relaxedAlignmentUsesOrganizationalDomain() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        Outcome o = eval("example.com", SpfResult.PASS, "bounce.example.com",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.PASS, o.result);
    }

    @Test
    public void passWithoutDomainDoesNotAlign() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=quarantine");
        Outcome o = eval("example.com", SpfResult.PASS, null, DkimResult.PASS, null);
        assertEquals(DmarcResult.FAIL, o.result);
        assertEquals(AuthVerdict.QUARANTINE, o.verdict);
    }

    @Test
    public void singleLabelDomainsAlignOnEquality() {
        dns.txt("_dmarc.localhost", "v=DMARC1; p=reject");
        Outcome o = eval("localhost", SpfResult.PASS, "localhost",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.PASS, o.result);
    }

    // ---- verdicts ----

    @Test
    public void pctZeroNeverAppliesPolicy() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; pct=0");
        Outcome o = failing("example.com");
        assertEquals(DmarcResult.FAIL, o.result);
        assertEquals(AuthVerdict.NONE, o.verdict);
    }

    @Test
    public void pctHundredAppliesPolicy() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=quarantine; pct=100");
        assertEquals(AuthVerdict.QUARANTINE, failing("example.com").verdict);
    }

    @Test
    public void malformedPctDefaultsToHundred() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; pct=abc");
        assertEquals(AuthVerdict.REJECT, failing("example.com").verdict);
    }

    @Test
    public void nonePolicyGivesNoVerdict() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=none");
        assertEquals(AuthVerdict.NONE, failing("example.com").verdict);
    }

    // ---- ARC policy ----

    private ArcValidationResult chain(ArcCvResult cv) {
        return new ArcValidationResult(cv, false, Collections.<ArcSet>emptyList());
    }

    private ArcDmarcPolicy snapshotPolicy(final ArcAuthSnapshot snapshot) {
        return new ArcDmarcPolicy() {
            @Override
            public ArcAuthSnapshot authSnapshot(ArcValidationResult chain,
                    String fromDomain, SpfResult localSpf, String localSpfDomain,
                    DkimResult localDkim, String localDkimDomain) {
                return snapshot;
            }
        };
    }

    @Test
    public void arcSnapshotSubstitutesAllIdentifiers() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        ArcAuthSnapshot snapshot = new ArcAuthSnapshot();
        snapshot.setSpfResult(SpfResult.PASS);
        snapshot.setSpfDomain("example.com");
        snapshot.setDkimResult(DkimResult.FAIL);
        snapshot.setDkimDomain("other.org");
        validator.setArcValidationResult(chain(ArcCvResult.PASS));
        validator.setArcDmarcPolicy(snapshotPolicy(snapshot));
        Outcome o = eval("example.com", SpfResult.FAIL, "elsewhere.org",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.PASS, o.result);
    }

    @Test
    public void arcSnapshotWithNullFieldsKeepsLocalResults() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        validator.setArcValidationResult(chain(ArcCvResult.PASS));
        validator.setArcDmarcPolicy(snapshotPolicy(new ArcAuthSnapshot()));
        Outcome o = eval("example.com", SpfResult.PASS, "example.com",
                DkimResult.FAIL, null);
        assertEquals(DmarcResult.PASS, o.result);
    }

    @Test
    public void arcNullSnapshotKeepsLocalResults() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        validator.setArcValidationResult(chain(ArcCvResult.PASS));
        validator.setArcDmarcPolicy(snapshotPolicy(null));
        assertEquals(DmarcResult.FAIL, failing("example.com").result);
    }

    @Test
    public void arcPolicyIgnoredUnlessChainPasses() {
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject");
        ArcAuthSnapshot snapshot = new ArcAuthSnapshot();
        snapshot.setSpfResult(SpfResult.PASS);
        snapshot.setSpfDomain("example.com");
        validator.setArcValidationResult(chain(ArcCvResult.NONE));
        validator.setArcDmarcPolicy(snapshotPolicy(snapshot));
        assertEquals(DmarcResult.FAIL, failing("example.com").result);
        validator.reset();
        validator.setArcDmarcPolicy(snapshotPolicy(snapshot));
        assertEquals(DmarcResult.FAIL, failing("example.com").result);
    }

    // ---- event-driven interface and accessors ----

    @Test
    public void eventDrivenEvaluationUsesAccumulatedState() {
        Outcome o = new Outcome();
        DmarcValidator ev = new DmarcValidator(dns, into(o));
        dns.txt("_dmarc.example.com", "v=DMARC1; p=reject; rua=mailto:a@x.org, mailto:b@x.org,; "
                + "ruf=mailto:f@x.org; fo=1:d; rf=afrf; junk; =novalue");
        ev.spfResult(SpfResult.PASS, null);
        ev.setSpfDomain("example.com");
        ev.setFromDomain("example.com");
        ev.dkimResult(DkimResult.FAIL, null, null);
        assertEquals(1, o.calls);
        assertEquals(DmarcResult.PASS, o.result);
        assertEquals(2, ev.getLastRua().size());
        assertEquals(1, ev.getLastRuf().size());
        assertEquals("1:d", ev.getLastFo());
        assertEquals("afrf", ev.getLastRf());
        assertEquals("n", ev.getLastT());
        assertEquals("author", ev.getLastDiscoveryMethod());
        ev.reset();
        assertNull(ev.getLastRua());
        assertNull(ev.getLastRuf());
        assertNull(ev.getLastFo());
        assertNull(ev.getLastRf());
        assertNull(ev.getLastAdkim());
        assertNull(ev.getLastAspf());
        assertNull(ev.getLastNp());
        assertNull(ev.getLastT());
        assertNull(ev.getLastPsd());
        assertNull(ev.getLastDiscoveryMethod());
        assertEquals(false, ev.isLastSpfAligned());
        assertEquals(false, ev.isLastDkimAligned());
    }

    @Test
    public void dkimResultWithoutCallbackIsIgnored() {
        validator.dkimResult(DkimResult.PASS, "example.com", "s1");
        assertTrue(dns.queries().isEmpty());
    }
}
