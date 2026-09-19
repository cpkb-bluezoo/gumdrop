/*
 * SpfValidatorMechanismsTest.java
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

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SpfValidator} mechanisms (RFC 7208 section 5) and
 * error paths, driven by an in-memory resolver.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpfValidatorMechanismsTest {

    /** Answers from a table; unknown names are NXDOMAIN. */
    private static final class TableResolver extends DnsResolver {
        private final Map<String, List<DnsResourceRecord>> table =
            new HashMap<String, List<DnsResourceRecord>>();
        private final Set<String> failing = new HashSet<String>();
        private int missingRcode = -1;

        TableResolver() {
            super();
        }

        void add(DnsType type, String name, DnsResourceRecord rr) {
            String key = type + ":" + name.toLowerCase();
            List<DnsResourceRecord> list = table.get(key);
            if (list == null) {
                list = new ArrayList<DnsResourceRecord>();
                table.put(key, list);
            }
            list.add(rr);
        }

        void txt(String name, String text) {
            add(DnsType.TXT, name, DnsResourceRecord.txt(name, 300, text));
        }

        void fail(DnsType type, String name) {
            failing.add(type + ":" + name.toLowerCase());
        }

        void rcodeForMissing(int rcode) {
            missingRcode = rcode;
        }

        private void answer(DnsType type, String name, DnsQueryCallback callback) {
            String key = type + ":" + name.toLowerCase();
            if (failing.contains(key)) {
                callback.onError("boom");
                return;
            }
            List<DnsResourceRecord> records = table.get(key);
            int rcode = DnsMessage.RCODE_NOERROR;
            if (records == null) {
                records = Collections.emptyList();
                rcode = missingRcode >= 0 ? missingRcode : DnsMessage.RCODE_NXDOMAIN;
            }
            int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RA | rcode;
            callback.onResponse(new DnsMessage(1, flags,
                Collections.<DnsQuestion>emptyList(),
                records, Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()));
        }

        @Override
        public void queryTXT(String name, DnsQueryCallback callback) {
            answer(DnsType.TXT, name, callback);
        }

        @Override
        public void queryA(String name, DnsQueryCallback callback) {
            answer(DnsType.A, name, callback);
        }

        @Override
        public void queryAAAA(String name, DnsQueryCallback callback) {
            answer(DnsType.AAAA, name, callback);
        }

        @Override
        public void queryMX(String name, DnsQueryCallback callback) {
            answer(DnsType.MX, name, callback);
        }

        @Override
        public void queryPTR(String name, DnsQueryCallback callback) {
            answer(DnsType.PTR, name, callback);
        }

        @Override
        public void query(String name, DnsType type, DnsQueryCallback callback) {
            answer(type, name, callback);
        }
    }

    private static final class Outcome {
        SpfResult result;
        String explanation;
        int calls;
    }

    private TableResolver resolver;
    private InetAddress client;

    @Before
    public void setUp() throws Exception {
        resolver = new TableResolver();
        client = InetAddress.getByName("192.0.2.1");
    }

    private Outcome check(String domain) {
        return check(new EmailAddress(null, "user", domain, true), client);
    }

    private Outcome check(EmailAddress sender, InetAddress ip) {
        final Outcome outcome = new Outcome();
        SpfValidator validator = new SpfValidator(resolver);
        validator.check(sender, ip, "mail.example.net", new SpfCallback() {
            @Override
            public void spfResult(SpfResult result, String explanation) {
                outcome.result = result;
                outcome.explanation = explanation;
                outcome.calls++;
            }
        });
        return outcome;
    }

    private static DnsResourceRecord a(String name, String ip) throws Exception {
        return DnsResourceRecord.a(name, 300, InetAddress.getByName(ip));
    }

    @Test
    public void nullSenderWithoutHeloDomainIsNone() {
        final Outcome o = new Outcome();
        new SpfValidator(resolver).check(null, client, "", new SpfCallback() {
            @Override
            public void spfResult(SpfResult result, String explanation) {
                o.result = result;
            }
        });
        assertEquals(SpfResult.NONE, o.result);
    }

    @Test
    public void nullSenderFallsBackToHeloDomain() {
        resolver.txt("mail.example.net", "v=spf1 ip4:192.0.2.1 -all");
        Outcome o = check(null, client);
        assertEquals(SpfResult.PASS, o.result);
    }

    @Test
    public void nxdomainIsNone() {
        assertEquals(SpfResult.NONE, check("example.com").result);
    }

    @Test
    public void servfailIsTemperror() {
        resolver.rcodeForMissing(DnsMessage.RCODE_SERVFAIL);
        Outcome o = check("example.com");
        assertEquals(SpfResult.TEMPERROR, o.result);
        assertTrue(o.explanation.contains("DNS error"));
    }

    @Test
    public void lookupErrorIsTemperror() {
        resolver.fail(DnsType.TXT, "example.com");
        assertEquals(SpfResult.TEMPERROR, check("example.com").result);
    }

    @Test
    public void domainWithoutSpfRecordIsNone() {
        resolver.txt("example.com", "google-site-verification=abc");
        assertEquals(SpfResult.NONE, check("example.com").result);
    }

    @Test
    public void multipleSpfRecordsIsPermerror() {
        resolver.txt("example.com", "v=spf1 -all");
        resolver.txt("example.com", "v=spf1 +all");
        Outcome o = check("example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Multiple SPF records", o.explanation);
    }

    @Test
    public void qualifiersMapToResults() {
        resolver.txt("fail.example", "v=spf1 -all");
        resolver.txt("soft.example", "v=spf1 ~all");
        resolver.txt("neutral.example", "v=spf1 ?all");
        resolver.txt("pass.example", "v=spf1 +all");
        resolver.txt("nomatch.example", "v=spf1 ip4:10.0.0.0/8");
        assertEquals(SpfResult.FAIL, check("fail.example").result);
        assertEquals(SpfResult.SOFTFAIL, check("soft.example").result);
        assertEquals(SpfResult.NEUTRAL, check("neutral.example").result);
        assertEquals(SpfResult.PASS, check("pass.example").result);
        assertEquals(SpfResult.NEUTRAL, check("nomatch.example").result);
    }

    @Test
    public void ip4CidrMatching() {
        resolver.txt("in.example", "v=spf1 ip4:192.0.2.0/24 -all");
        resolver.txt("out.example", "v=spf1 ip4:198.51.100.0/24 -all");
        assertEquals(SpfResult.PASS, check("in.example").result);
        assertEquals(SpfResult.FAIL, check("out.example").result);
    }

    @Test
    public void ip6MechanismMatchesIpv6Client() throws Exception {
        resolver.txt("v6.example", "v=spf1 ip6:2001:db8::/32 -all");
        InetAddress v6 = InetAddress.getByName("2001:db8::1");
        InetAddress other = InetAddress.getByName("2001:db9::1");
        EmailAddress sender = new EmailAddress(null, "u", "v6.example", true);
        assertEquals(SpfResult.PASS, check(sender, v6).result);
        assertEquals(SpfResult.FAIL, check(sender, other).result);
        assertEquals(SpfResult.FAIL, check(sender, client).result);
    }

    @Test
    public void aMechanismMatchesDomainAddress() throws Exception {
        resolver.txt("example.com", "v=spf1 a -all");
        resolver.add(DnsType.A, "example.com", a("example.com", "192.0.2.1"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void aMechanismWithExplicitDomainAndPrefix() throws Exception {
        resolver.txt("example.com", "v=spf1 a:hosts.example.org/24 -all");
        resolver.add(DnsType.A, "hosts.example.org", a("hosts.example.org", "192.0.2.77"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void aMechanismWithOnlyPrefix() throws Exception {
        resolver.txt("example.com", "v=spf1 a/24 -all");
        resolver.add(DnsType.A, "example.com", a("example.com", "192.0.2.200"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void aMechanismNoMatchFallsThrough() throws Exception {
        resolver.txt("example.com", "v=spf1 a -all");
        resolver.add(DnsType.A, "example.com", a("example.com", "203.0.113.9"));
        assertEquals(SpfResult.FAIL, check("example.com").result);
    }

    @Test
    public void aMechanismLookupErrorIsTemperror() {
        resolver.txt("example.com", "v=spf1 a -all");
        resolver.fail(DnsType.A, "example.com");
        assertEquals(SpfResult.TEMPERROR, check("example.com").result);
    }

    @Test
    public void mxMechanismMatchesMailHost() throws Exception {
        resolver.txt("example.com", "v=spf1 mx -all");
        resolver.add(DnsType.MX, "example.com", DnsResourceRecord.mx("example.com", 300, 10, "mx.example.com"));
        resolver.add(DnsType.A, "mx.example.com", a("mx.example.com", "192.0.2.1"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void mxMechanismNoMatchFails() throws Exception {
        resolver.txt("example.com", "v=spf1 mx -all");
        resolver.add(DnsType.MX, "example.com", DnsResourceRecord.mx("example.com", 300, 10, "mx.example.com"));
        resolver.add(DnsType.A, "mx.example.com", a("mx.example.com", "203.0.113.5"));
        assertEquals(SpfResult.FAIL, check("example.com").result);
    }

    @Test
    public void existsMechanism() throws Exception {
        resolver.txt("example.com", "v=spf1 exists:probe.example.com -all");
        resolver.add(DnsType.A, "probe.example.com", a("probe.example.com", "127.0.0.2"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void existsMechanismMissingFails() {
        resolver.txt("example.com", "v=spf1 exists:probe.example.com -all");
        assertEquals(SpfResult.FAIL, check("example.com").result);
    }

    @Test
    public void ptrMechanismValidatesForwardDns() throws Exception {
        resolver.txt("example.com", "v=spf1 ptr -all");
        resolver.add(DnsType.PTR, "1.2.0.192.in-addr.arpa", DnsResourceRecord.ptr(
            "1.2.0.192.in-addr.arpa", 300, "host.example.com"));
        resolver.add(DnsType.A, "host.example.com", a("host.example.com", "192.0.2.1"));
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void ptrMechanismWithUnrelatedNameFails() {
        resolver.txt("example.com", "v=spf1 ptr -all");
        resolver.add(DnsType.PTR, "1.2.0.192.in-addr.arpa", DnsResourceRecord.ptr(
            "1.2.0.192.in-addr.arpa", 300, "host.other.net"));
        assertEquals(SpfResult.FAIL, check("example.com").result);
    }

    @Test
    public void ptrMechanismWithoutRecordsFails() {
        resolver.txt("example.com", "v=spf1 ptr:example.com -all");
        assertEquals(SpfResult.FAIL, check("example.com").result);
    }

    @Test
    public void includePassPropagatesQualifier() {
        resolver.txt("example.com", "v=spf1 include:_spf.example.org -all");
        resolver.txt("_spf.example.org", "v=spf1 ip4:192.0.2.0/24 -all");
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void includeFailFallsThroughToNextMechanism() {
        resolver.txt("example.com", "v=spf1 include:_spf.example.org +all");
        resolver.txt("_spf.example.org", "v=spf1 ip4:203.0.113.0/24 -all");
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void includeOfMissingDomainIsPermerror() {
        resolver.txt("example.com", "v=spf1 include:gone.example.org -all");
        Outcome o = check("example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Included domain does not exist", o.explanation);
    }

    @Test
    public void includeWithoutSpfRecordIsPermerror() {
        resolver.txt("example.com", "v=spf1 include:plain.example.org -all");
        resolver.txt("plain.example.org", "hello");
        Outcome o = check("example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("No SPF record for included domain", o.explanation);
    }

    @Test
    public void includeWithMultipleRecordsIsPermerror() {
        resolver.txt("example.com", "v=spf1 include:dup.example.org -all");
        resolver.txt("dup.example.org", "v=spf1 -all");
        resolver.txt("dup.example.org", "v=spf1 +all");
        Outcome o = check("example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Multiple SPF records", o.explanation);
    }

    @Test
    public void includeLookupErrorIsTemperror() {
        resolver.txt("example.com", "v=spf1 include:err.example.org -all");
        resolver.fail(DnsType.TXT, "err.example.org");
        assertEquals(SpfResult.TEMPERROR, check("example.com").result);
    }

    @Test
    public void redirectReplacesPolicyWhenNothingMatches() {
        resolver.txt("example.com", "v=spf1 redirect=other.example.org");
        resolver.txt("other.example.org", "v=spf1 ip4:192.0.2.1 -all");
        assertEquals(SpfResult.PASS, check("example.com").result);
    }

    @Test
    public void tooManyDnsLookupsIsPermerror() throws Exception {
        StringBuilder policy = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            String host = "h" + i + ".example.com";
            policy.append(" a:").append(host);
            resolver.add(DnsType.A, host, a(host, "203.0.113." + (i + 1)));
        }
        policy.append(" -all");
        resolver.txt("example.com", policy.toString());
        Outcome o = check("example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Too many DNS lookups", o.explanation);
        assertEquals(1, o.calls);
    }
}
