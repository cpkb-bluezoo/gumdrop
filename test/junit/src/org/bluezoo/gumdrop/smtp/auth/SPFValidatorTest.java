/*
 * SPFValidatorTest.java
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
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link SPFValidator} modifier handling (RFC 7208 §6).
 */
public class SPFValidatorTest {

    private StubDNSResolver resolver;
    private InetAddress clientIP;
    private EmailAddress sender;

    @Before
    public void setUp() throws Exception {
        resolver = new StubDNSResolver();
        clientIP = InetAddress.getByName("192.0.2.1");
        sender = new EmailAddress(null, "user", "example.com", true);
    }

    @Test
    public void testTrailingExpModifierFetchesExplanationOnFail() {
        resolver.addTxt("example.com", "v=spf1 -all exp=explain.example.com");
        resolver.addTxt("explain.example.com", "Sender not authorized");

        SPFResultHolder result = check();

        assertEquals(SPFResult.FAIL, result.result);
        assertEquals("Sender not authorized", result.explanation);
    }

    @Test
    public void testLeadingExpModifierStillFetchesExplanationOnFail() {
        resolver.addTxt("example.com", "v=spf1 exp=explain.example.com -all");
        resolver.addTxt("explain.example.com", "Sender not authorized");

        SPFResultHolder result = check();

        assertEquals(SPFResult.FAIL, result.result);
        assertEquals("Sender not authorized", result.explanation);
    }

    @Test
    public void testDuplicateExpModifierReturnsPermerror() {
        resolver.addTxt("example.com",
                "v=spf1 -all exp=one.example.com exp=two.example.com");

        SPFResultHolder result = check();

        assertEquals(SPFResult.PERMERROR, result.result);
        assertEquals("Duplicate exp modifier", result.explanation);
    }

    @Test
    public void testDuplicateRedirectModifierReturnsPermerror() {
        resolver.addTxt("example.com",
                "v=spf1 redirect=one.example.com redirect=two.example.com -all");

        SPFResultHolder result = check();

        assertEquals(SPFResult.PERMERROR, result.result);
        assertEquals("Duplicate redirect modifier", result.explanation);
    }

    @Test
    public void testTrailingRedirectAfterUnmatchedInclude() {
        resolver.addTxt("example.com",
                "v=spf1 include:other.example.com redirect=redirected.example.com");
        resolver.addTxt("other.example.com", "v=spf1 ip4:10.0.0.1/32 -all");
        resolver.addTxt("redirected.example.com", "v=spf1 ip4:192.0.2.1/32 -all");

        SPFResultHolder result = check();

        assertEquals(SPFResult.PASS, result.result);
        assertNull(result.explanation);
    }

    private SPFResultHolder check() {
        final SPFResultHolder holder = new SPFResultHolder();
        SPFValidator validator = new SPFValidator(resolver);
        validator.check(sender, clientIP, "mail.example.com", new SPFCallback() {
            @Override
            public void spfResult(SPFResult result, String explanation) {
                holder.result = result;
                holder.explanation = explanation;
            }
        });
        return holder;
    }

    private static final class SPFResultHolder {
        SPFResult result;
        String explanation;
    }

    /**
     * Synchronous stub resolver for SPF TXT lookups.
     */
    private static final class StubDNSResolver extends DNSResolver {

        private final Map<String, String> txtRecords = new HashMap<>();

        StubDNSResolver() {
            super();
        }

        void addTxt(String domain, String text) {
            txtRecords.put(domain.toLowerCase(), text);
        }

        @Override
        public void queryTXT(String name, DNSQueryCallback callback) {
            String text = txtRecords.get(name.toLowerCase());
            if (text == null) {
                callback.onResponse(nxdomainResponse());
            } else {
                callback.onResponse(txtResponse(text));
            }
        }

        private static DNSMessage txtResponse(String text) {
            int flags = DNSMessage.FLAG_QR | DNSMessage.FLAG_RA
                    | DNSMessage.RCODE_NOERROR;
            List<DNSResourceRecord> answers = Collections.singletonList(
                    DNSResourceRecord.txt("example.com", 300, text));
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
