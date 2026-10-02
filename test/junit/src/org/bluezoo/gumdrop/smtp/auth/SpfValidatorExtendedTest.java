/*
 * SpfValidatorExtendedTest.java
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

import java.net.InetAddress;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the less common {@link SpfValidator} paths: macro expansion (RFC
 * 7208 section 7), the DNS and void lookup limits, include, ptr, mx and
 * exists mechanisms with their error and fallthrough arms, and IPv6 clients.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpfValidatorExtendedTest {

    private static final class Outcome {
        SpfResult result;
        String explanation;
        int calls;
    }

    private StubDnsTable dns;
    private InetAddress v4;
    private InetAddress v6;

    @Before
    public void setUp() throws Exception {
        dns = new StubDnsTable();
        v4 = InetAddress.getByName("192.0.2.1");
        v6 = InetAddress.getByName("2001:db8::25");
    }

    private Outcome check(EmailAddress sender, InetAddress ip, String helo) {
        final Outcome o = new Outcome();
        SpfValidator validator = new SpfValidator(dns);
        validator.check(sender, ip, helo, new SpfCallback() {
            @Override
            public void spfResult(SpfResult result, String explanation) {
                o.result = result;
                o.explanation = explanation;
                o.calls++;
            }
        });
        return o;
    }

    private Outcome check(String record) {
        dns.replaceTxt("example.com", record);
        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        return check(sender, v4, "mail.example.net");
    }

    private Outcome checkV6(String record) {
        dns.replaceTxt("example.com", record);
        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        return check(sender, v6, "mail.example.net");
    }

    private boolean asked(String query) {
        return dns.queries().contains(query);
    }

    // ---- macros ----

    @Test
    public void macroLetterExpansionsReachExistsQuery() {
        check("v=spf1 exists:%{i}.%{l}.%{o}.%{d}.%{h}.%{v} -all");
        assertTrue(dns.queries().toString(),
                asked("A:192.0.2.1.user.example.com.example.com.mail.example.net.in-addr"));
    }

    @Test
    public void macroSenderAndEscapes() {
        check("v=spf1 exists:%{s}%%%_%-x -all");
        assertTrue(dns.queries().toString(), asked("A:<user@example.com>% %20x"));
    }

    @Test
    public void macroReverseAndTruncate() {
        check("v=spf1 exists:%{ir}.%{d2}.%{dr}.%{d1r} -all");
        assertTrue(dns.queries().toString(),
                asked("A:1.2.0.192.example.com.com.example.example"));
    }

    @Test
    public void macroCustomDelimiter() {
        check("v=spf1 exists:%{l-}.%{h2.} -all");
        assertTrue(dns.queries().toString(), asked("A:user.example.net"));
    }

    @Test
    public void macroDigitsLargerThanPartsKeepsAll() {
        check("v=spf1 exists:%{d9} -all");
        assertTrue(dns.queries().toString(), asked("A:example.com"));
    }

    @Test
    public void macroUnknownLettersAndPlaceholders() {
        check("v=spf1 exists:a%{p}b%{r}c%{z}d%{c}e%{}f -all");
        assertTrue(dns.queries().toString(), asked("A:aunknownbexample.comc"
                + "d192.0.2.1ef"));
    }

    @Test
    public void macroTimestampIsNumeric() {
        check("v=spf1 exists:%{t} -all");
        String q = dns.queries().get(1);
        assertTrue(q, q.startsWith("A:"));
        String digits = q.substring(2);
        assertTrue(q, digits.length() > 0);
        for (int i = 0; i < digits.length(); i++) {
            assertTrue(q, Character.isDigit(digits.charAt(i)));
        }
    }

    @Test
    public void macroMalformedSequencesAreLiteral() {
        check("v=spf1 exists:a%{d%x%");
        assertTrue(dns.queries().toString(), asked("A:a%{d%x%"));
    }

    @Test
    public void macroIpv6Expansions() {
        checkV6("v=spf1 exists:%{i}.%{v} -all");
        String expected = "A:2.0.0.1.0.d.b.8.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.2.5.ip6";
        boolean found = false;
        for (int i = 0; i < dns.queries().size(); i++) {
            String q = dns.queries().get(i);
            if (q.startsWith("A:2.0.0.1.0.d.b.8") && q.endsWith(".ip6")) {
                found = true;
            }
        }
        assertTrue(expected + " in " + dns.queries(), found);
    }

    @Test
    public void macroNullSenderUsesHelo() {
        dns.txt("mail.example.net", "v=spf1 exists:%{s}.%{l}.%{o} -all");
        check(null, v4, "mail.example.net");
        assertTrue(dns.queries().toString(),
                asked("A:postmaster@mail.example.net.postmaster.mail.example.net"));
    }

    @Test
    public void redirectMacroExpansion() {
        dns.txt("target.example.com", "v=spf1 +all");
        Outcome o = check("v=spf1 redirect=target.%{d}");
        assertEquals(SpfResult.PASS, o.result);
        assertTrue(dns.queries().toString(), asked("TXT:target.example.com"));
    }

    // ---- limits ----

    @Test
    public void tooManyExistsLookupsIsPermerror() {
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            sb.append(" exists:n").append(i).append(".example.org");
        }
        Outcome o = check(sb.toString());
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void tooManyALookupsIsPermerror() {
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            String name = "h" + i + ".example.org";
            dns.rcode(DnsType.A, name, DnsMessage.RCODE_NOERROR);
            sb.append(" a:").append(name);
        }
        Outcome o = check(sb.toString());
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void tooManyMxLookupsIsPermerror() {
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            String name = "h" + i + ".example.org";
            dns.rcode(DnsType.MX, name, DnsMessage.RCODE_NOERROR);
            sb.append(" mx:").append(name);
        }
        Outcome o = check(sb.toString());
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void tooManyPtrLookupsIsPermerror() {
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            sb.append(" ptr:h").append(i).append(".example.org");
        }
        Outcome o = check(sb.toString());
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void tooManyIncludesIsPermerror() {
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 12; i++) {
            String name = "i" + i + ".example.org";
            dns.txt(name, "v=spf1 -all");
            sb.append(" include:").append(name);
        }
        Outcome o = check(sb.toString());
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void redirectLoopHitsLookupLimit() {
        dns.txt("loop.example.com", "v=spf1 redirect=loop.example.com");
        Outcome o = check("v=spf1 redirect=loop.example.com");
        assertEquals(SpfResult.PERMERROR, o.result);
    }

    @Test
    public void tooManyMxHostLookupsIsPermerror() {
        dns.mx("example.com", 10, "m1.example.com");
        dns.mx("example.com", 20, "m2.example.com");
        dns.mx("example.com", 30, "m3.example.com");
        StringBuilder sb = new StringBuilder("v=spf1");
        for (int i = 0; i < 4; i++) {
            sb.append(" mx");
        }
        Outcome o = check(sb.toString());
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void tooManyPtrNameValidationsIsPermerror() {
        dns.ptr("1.2.0.192.in-addr.arpa", "a.example.com");
        for (int i = 0; i < 12; i++) {
            dns.ptr("1.2.0.192.in-addr.arpa", "h" + i + ".example.com");
        }
        Outcome o = check("v=spf1 ptr -all");
        assertEquals("Too many DNS lookups", o.explanation);
    }

    @Test
    public void voidLookupsOnAEventuallyPermerror() {
        Outcome o = check("v=spf1 a:v1.example.org a:v2.example.org a:v3.example.org -all");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Too many void lookups", o.explanation);
    }

    @Test
    public void voidLookupsOnMxEventuallyPermerror() {
        Outcome o = check("v=spf1 mx:v1.example.org mx:v2.example.org mx:v3.example.org -all");
        assertEquals("Too many void lookups", o.explanation);
    }

    @Test
    public void includeOfMissingDomainAfterVoidsIsTooManyVoids() {
        Outcome o = check("v=spf1 a:v1.example.org a:v2.example.org include:gone.example.org");
        assertEquals("Too many void lookups", o.explanation);
    }

    @Test
    public void includeOfMissingDomainIsPermerror() {
        Outcome o = check("v=spf1 include:gone.example.org -all");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Included domain does not exist", o.explanation);
    }

    // ---- include ----

    @Test
    public void includeWithoutSpfRecordIsPermerror() {
        dns.txt("inc.example.org", "unrelated");
        Outcome o = check("v=spf1 include:inc.example.org -all");
        assertEquals("No SPF record for included domain", o.explanation);
    }

    @Test
    public void includeWithMultipleSpfRecordsIsPermerror() {
        dns.txt("inc.example.org", "v=spf1 -all");
        dns.txt("inc.example.org", "v=spf1 +all");
        Outcome o = check("v=spf1 include:inc.example.org -all");
        assertEquals("Multiple SPF records", o.explanation);
    }

    @Test
    public void includePassAppliesQualifier() {
        dns.txt("inc.example.org", "v=spf1 ip4:192.0.2.0/24 -all");
        assertEquals(SpfResult.SOFTFAIL, check("v=spf1 ~include:inc.example.org -all").result);
    }

    @Test
    public void includeNoMatchContinuesWithNextMechanism() {
        dns.txt("inc.example.org", "v=spf1 ip4:203.0.113.0/24 -all");
        assertEquals(SpfResult.PASS, check("v=spf1 include:inc.example.org +all").result);
    }

    @Test
    public void includeInnerTemperrorPropagates() {
        dns.txt("inc.example.org", "v=spf1 a:broken.example.org -all");
        dns.fail(DnsType.A, "broken.example.org");
        assertEquals(SpfResult.TEMPERROR, check("v=spf1 include:inc.example.org -all").result);
    }

    @Test
    public void includeInnerDuplicateRedirectPropagatesPermerror() {
        dns.txt("inc.example.org", "v=spf1 redirect=a.example redirect=b.example");
        Outcome o = check("v=spf1 include:inc.example.org -all");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals("Duplicate redirect modifier", o.explanation);
    }

    @Test
    public void includeLookupErrorIsTemperror() {
        dns.fail(DnsType.TXT, "inc.example.org");
        assertEquals(SpfResult.TEMPERROR, check("v=spf1 include:inc.example.org -all").result);
    }

    // ---- ptr ----

    @Test
    public void ptrForwardConfirmedMatches() throws Exception {
        dns.ptr("1.2.0.192.in-addr.arpa", "other.example.org");
        dns.ptr("1.2.0.192.in-addr.arpa", "host.example.com");
        dns.a("host.example.com", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 ptr -all").result);
    }

    @Test
    public void ptrWithExplicitDomain() throws Exception {
        dns.ptr("1.2.0.192.in-addr.arpa", "host.example.org");
        dns.a("host.example.org", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 ptr:example.org -all").result);
    }

    @Test
    public void ptrForwardMismatchContinues() throws Exception {
        dns.ptr("1.2.0.192.in-addr.arpa", "host.example.com");
        dns.a("host.example.com", "203.0.113.5");
        assertEquals(SpfResult.FAIL, check("v=spf1 ptr -all").result);
    }

    @Test
    public void ptrForwardErrorTriesNext() throws Exception {
        dns.ptr("1.2.0.192.in-addr.arpa", "h1.example.com");
        dns.ptr("1.2.0.192.in-addr.arpa", "h2.example.com");
        dns.fail(DnsType.A, "h1.example.com");
        dns.a("h2.example.com", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 ptr -all").result);
    }

    @Test
    public void ptrNoRecordsContinues() {
        assertEquals(SpfResult.FAIL, check("v=spf1 ptr -all").result);
    }

    @Test
    public void ptrLookupErrorContinues() {
        dns.fail(DnsType.PTR, "1.2.0.192.in-addr.arpa");
        assertEquals(SpfResult.FAIL, check("v=spf1 ptr -all").result);
    }

    @Test
    public void ptrNameOutsideDomainIsSkipped() {
        dns.ptr("1.2.0.192.in-addr.arpa", "host.elsewhere.org");
        Outcome o = check("v=spf1 ptr -all");
        assertEquals(SpfResult.FAIL, o.result);
        assertTrue(dns.queries().toString(), !asked("A:host.elsewhere.org"));
    }

    @Test
    public void ptrIpv6UsesNibbleReverseName() throws Exception {
        boolean found = false;
        checkV6("v=spf1 ptr -all");
        for (int i = 0; i < dns.queries().size(); i++) {
            String q = dns.queries().get(i);
            if (q.startsWith("PTR:5.2.0.0.") && q.endsWith(".ip6.arpa")) {
                found = true;
            }
        }
        assertTrue(dns.queries().toString(), found);
    }

    @Test
    public void ptrIpv6ForwardConfirmedMatches() throws Exception {
        dns.txt("example.com", "v=spf1 ptr -all");
        StringBuilder name = new StringBuilder();
        byte[] bytes = v6.getAddress();
        for (int i = bytes.length - 1; i >= 0; i--) {
            int b = bytes[i] & 0xFF;
            name.append(Integer.toHexString(b & 0x0F)).append('.');
            name.append(Integer.toHexString((b >> 4) & 0x0F));
            if (i > 0) {
                name.append('.');
            }
        }
        name.append(".ip6.arpa");
        dns.ptr(name.toString(), "host.example.com");
        dns.aaaa("host.example.com", "2001:db8::25");
        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        assertEquals(SpfResult.PASS, check(sender, v6, "h").result);
    }

    // ---- mx ----

    @Test
    public void mxHostMatchesWithPrefix() throws Exception {
        dns.mx("example.com", 10, "mx.example.com");
        dns.a("mx.example.com", "192.0.2.77");
        assertEquals(SpfResult.PASS, check("v=spf1 mx/24 -all").result);
    }

    @Test
    public void mxDomainWithDualPrefix() throws Exception {
        dns.mx("other.example.org", 10, "mx.example.org");
        dns.a("mx.example.org", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 mx:other.example.org/32//64 -all").result);
    }

    @Test
    public void mxSecondHostMatchesAfterFirstErrors() throws Exception {
        dns.mx("example.com", 10, "m1.example.com");
        dns.mx("example.com", 20, "m2.example.com");
        dns.fail(DnsType.A, "m1.example.com");
        dns.a("m2.example.com", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 mx -all").result);
    }

    @Test
    public void mxHostWithoutMatchFallsThrough() throws Exception {
        dns.mx("example.com", 10, "m1.example.com");
        dns.a("m1.example.com", "203.0.113.1");
        assertEquals(SpfResult.FAIL, check("v=spf1 mx -all").result);
    }

    @Test
    public void mxNoRecordsFallsThrough() {
        assertEquals(SpfResult.FAIL, check("v=spf1 mx -all").result);
    }

    @Test
    public void mxLookupErrorIsTemperror() {
        dns.fail(DnsType.MX, "example.com");
        assertEquals(SpfResult.TEMPERROR, check("v=spf1 mx -all").result);
    }

    @Test
    public void mxIpv6ClientUsesAaaa() throws Exception {
        dns.mx("example.com", 10, "m1.example.com");
        dns.aaaa("m1.example.com", "2001:db8::99");
        assertEquals(SpfResult.PASS, checkV6("v=spf1 mx//64 -all").result);
    }

    // ---- a / exists ----

    @Test
    public void aIpv6ClientUsesAaaaAndDualPrefix() throws Exception {
        dns.aaaa("example.com", "2001:db8::99");
        assertEquals(SpfResult.PASS, checkV6("v=spf1 a/24//64 -all").result);
    }

    @Test
    public void aWithOnlyIpv6PrefixAppliesToIpv6Client() throws Exception {
        dns.aaaa("example.com", "2001:db8::99");
        assertEquals(SpfResult.PASS, checkV6("v=spf1 a//64 -all").result);
    }

    @Test
    public void aDomainWithOnlyIpv6PrefixLeavesIpv4Exact() throws Exception {
        dns.a("h.example.org", "192.0.2.200");
        assertEquals(SpfResult.FAIL, check("v=spf1 a:h.example.org//64 -all").result);
    }

    @Test
    public void aWithMalformedPrefixIsExactMatch() throws Exception {
        dns.a("example.com", "192.0.2.1");
        assertEquals(SpfResult.PASS, check("v=spf1 a/xx -all").result);
    }

    @Test
    public void existsMatchesAndMisses() throws Exception {
        dns.a("hit.example.org", "127.0.0.2");
        assertEquals(SpfResult.PASS, check("v=spf1 exists:hit.example.org -all").result);
        StubDnsTable fresh = new StubDnsTable();
        dns = fresh;
        assertEquals(SpfResult.FAIL, check("v=spf1 exists:miss.example.org -all").result);
    }

    @Test
    public void existsLookupErrorIsTemperror() {
        dns.fail(DnsType.A, "x.example.org");
        assertEquals(SpfResult.TEMPERROR, check("v=spf1 exists:x.example.org -all").result);
    }

    // ---- exp ----

    @Test
    public void explanationLookupErrorDeliversFailWithoutText() {
        dns.fail(DnsType.TXT, "why.example.com");
        Outcome o = check("v=spf1 -all exp=why.example.com");
        assertEquals(SpfResult.FAIL, o.result);
        assertNull(o.explanation);
    }

    @Test
    public void explanationTextIsDelivered() {
        dns.txt("why.example.com", "not allowed");
        Outcome o = check("v=spf1 -all exp=why.%{d}");
        assertEquals(SpfResult.FAIL, o.result);
        assertEquals("not allowed", o.explanation);
    }

    @Test
    public void explanationNotFetchedForSoftfail() {
        Outcome o = check("v=spf1 ~all exp=why.example.com");
        assertEquals(SpfResult.SOFTFAIL, o.result);
        assertTrue(dns.queries().toString(), !asked("TXT:why.example.com"));
    }

    @Test
    public void duplicateModifiersAreRejectedAndOnlyOnceReported() {
        Outcome o = check("v=spf1 exp=a.example exp=b.example");
        assertEquals(SpfResult.PERMERROR, o.result);
        assertEquals(1, o.calls);
    }

    // ---- ip4 / ip6 forms ----

    @Test
    public void ipLiteralsWithoutPrefixAndInvalidOnes() {
        assertEquals(SpfResult.PASS, check("v=spf1 ip4:192.0.2.1 -all").result);
        assertEquals(SpfResult.NEUTRAL, check("v=spf1 ip4:not-an-ip").result);
        assertEquals(SpfResult.NEUTRAL, check("v=spf1 ip6:zzzz").result);
    }

    @Test
    public void ip6WithoutPrefixMatchesExactly() {
        assertEquals(SpfResult.PASS, checkV6("v=spf1 ip6:2001:db8::25 -all").result);
    }

    @Test
    public void rcodeRefusedFromMxIsHandledAsNoMx() {
        dns.rcode(DnsType.MX, "example.com", DnsMessage.RCODE_REFUSED);
        assertEquals(SpfResult.FAIL, check("v=spf1 mx -all").result);
    }
}
