/*
 * DMARCValidator.java
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.DNSResolver;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

/**
 * DMARC (Domain-based Message Authentication, Reporting and Conformance)
 * validator as defined in RFC 7489.
 *
 * <p>DMARC combines SPF and DKIM authentication results with domain alignment
 * checks to determine whether a message should be accepted, quarantined, or
 * rejected. This implementation is fully asynchronous.
 *
 * <p>DMARC alignment requires that:
 * <ul>
 *   <li>SPF passes AND the envelope domain aligns with the From domain, OR</li>
 *   <li>DKIM passes AND the signing domain aligns with the From domain</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.useSystemResolvers();
 * resolver.open();
 *
 * DMARCValidator dmarc = new DMARCValidator(resolver);
 *
 * dmarc.evaluate("example.com", SPFResult.PASS, "example.com",
 *     DKIMResult.PASS, "example.com",
 *     new DMARCCallback() {
 *         &#64;Override
 *         public void dmarcResult(DMARCResult result, DMARCPolicy policy,
 *                                 String fromDomain, AuthVerdict verdict) {
 *             if (verdict == AuthVerdict.PASS) {
 *                 // Message authenticated
 *             } else if (verdict == AuthVerdict.REJECT) {
 *                 // Domain requests rejection
 *             }
 *         }
 *     });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 */
public class DMARCValidator {

    private static final Logger LOGGER = Logger.getLogger(DMARCValidator.class.getName());

    /**
     * Common two-level TLDs (public suffixes) that require three labels
     * for an organizational domain. This is a subset of the full Public
     * Suffix List (PSL) covering the most common cases.
     */
    private static final Set<String> TWO_LEVEL_TLDS = new HashSet<String>();
    static {
        // United Kingdom
        TWO_LEVEL_TLDS.add("co.uk");
        TWO_LEVEL_TLDS.add("org.uk");
        TWO_LEVEL_TLDS.add("me.uk");
        TWO_LEVEL_TLDS.add("ltd.uk");
        TWO_LEVEL_TLDS.add("plc.uk");
        TWO_LEVEL_TLDS.add("net.uk");
        TWO_LEVEL_TLDS.add("sch.uk");
        TWO_LEVEL_TLDS.add("ac.uk");
        TWO_LEVEL_TLDS.add("gov.uk");
        TWO_LEVEL_TLDS.add("nhs.uk");
        TWO_LEVEL_TLDS.add("police.uk");
        // Australia
        TWO_LEVEL_TLDS.add("com.au");
        TWO_LEVEL_TLDS.add("net.au");
        TWO_LEVEL_TLDS.add("org.au");
        TWO_LEVEL_TLDS.add("edu.au");
        TWO_LEVEL_TLDS.add("gov.au");
        TWO_LEVEL_TLDS.add("id.au");
        // New Zealand
        TWO_LEVEL_TLDS.add("co.nz");
        TWO_LEVEL_TLDS.add("net.nz");
        TWO_LEVEL_TLDS.add("org.nz");
        TWO_LEVEL_TLDS.add("govt.nz");
        TWO_LEVEL_TLDS.add("ac.nz");
        // Japan
        TWO_LEVEL_TLDS.add("co.jp");
        TWO_LEVEL_TLDS.add("or.jp");
        TWO_LEVEL_TLDS.add("ne.jp");
        TWO_LEVEL_TLDS.add("ac.jp");
        TWO_LEVEL_TLDS.add("go.jp");
        // Brazil
        TWO_LEVEL_TLDS.add("com.br");
        TWO_LEVEL_TLDS.add("net.br");
        TWO_LEVEL_TLDS.add("org.br");
        TWO_LEVEL_TLDS.add("gov.br");
        TWO_LEVEL_TLDS.add("edu.br");
        // South Africa
        TWO_LEVEL_TLDS.add("co.za");
        TWO_LEVEL_TLDS.add("org.za");
        TWO_LEVEL_TLDS.add("net.za");
        TWO_LEVEL_TLDS.add("gov.za");
        TWO_LEVEL_TLDS.add("edu.za");
        // India
        TWO_LEVEL_TLDS.add("co.in");
        TWO_LEVEL_TLDS.add("net.in");
        TWO_LEVEL_TLDS.add("org.in");
        TWO_LEVEL_TLDS.add("gov.in");
        TWO_LEVEL_TLDS.add("ac.in");
        // China
        TWO_LEVEL_TLDS.add("com.cn");
        TWO_LEVEL_TLDS.add("net.cn");
        TWO_LEVEL_TLDS.add("org.cn");
        TWO_LEVEL_TLDS.add("gov.cn");
        TWO_LEVEL_TLDS.add("edu.cn");
        // Hong Kong
        TWO_LEVEL_TLDS.add("com.hk");
        TWO_LEVEL_TLDS.add("org.hk");
        TWO_LEVEL_TLDS.add("net.hk");
        TWO_LEVEL_TLDS.add("gov.hk");
        TWO_LEVEL_TLDS.add("edu.hk");
        // Taiwan
        TWO_LEVEL_TLDS.add("com.tw");
        TWO_LEVEL_TLDS.add("org.tw");
        TWO_LEVEL_TLDS.add("net.tw");
        TWO_LEVEL_TLDS.add("gov.tw");
        TWO_LEVEL_TLDS.add("edu.tw");
        // South Korea
        TWO_LEVEL_TLDS.add("co.kr");
        TWO_LEVEL_TLDS.add("or.kr");
        TWO_LEVEL_TLDS.add("ne.kr");
        TWO_LEVEL_TLDS.add("go.kr");
        TWO_LEVEL_TLDS.add("ac.kr");
        // Singapore
        TWO_LEVEL_TLDS.add("com.sg");
        TWO_LEVEL_TLDS.add("org.sg");
        TWO_LEVEL_TLDS.add("net.sg");
        TWO_LEVEL_TLDS.add("gov.sg");
        TWO_LEVEL_TLDS.add("edu.sg");
        // Malaysia
        TWO_LEVEL_TLDS.add("com.my");
        TWO_LEVEL_TLDS.add("org.my");
        TWO_LEVEL_TLDS.add("net.my");
        TWO_LEVEL_TLDS.add("gov.my");
        TWO_LEVEL_TLDS.add("edu.my");
        // Indonesia
        TWO_LEVEL_TLDS.add("co.id");
        TWO_LEVEL_TLDS.add("or.id");
        TWO_LEVEL_TLDS.add("web.id");
        TWO_LEVEL_TLDS.add("go.id");
        TWO_LEVEL_TLDS.add("ac.id");
        // Thailand
        TWO_LEVEL_TLDS.add("co.th");
        TWO_LEVEL_TLDS.add("or.th");
        TWO_LEVEL_TLDS.add("go.th");
        TWO_LEVEL_TLDS.add("ac.th");
        // Vietnam
        TWO_LEVEL_TLDS.add("com.vn");
        TWO_LEVEL_TLDS.add("net.vn");
        TWO_LEVEL_TLDS.add("org.vn");
        TWO_LEVEL_TLDS.add("gov.vn");
        TWO_LEVEL_TLDS.add("edu.vn");
        // Philippines
        TWO_LEVEL_TLDS.add("com.ph");
        TWO_LEVEL_TLDS.add("org.ph");
        TWO_LEVEL_TLDS.add("net.ph");
        TWO_LEVEL_TLDS.add("gov.ph");
        TWO_LEVEL_TLDS.add("edu.ph");
        // Mexico
        TWO_LEVEL_TLDS.add("com.mx");
        TWO_LEVEL_TLDS.add("org.mx");
        TWO_LEVEL_TLDS.add("net.mx");
        TWO_LEVEL_TLDS.add("gob.mx");
        TWO_LEVEL_TLDS.add("edu.mx");
        // Argentina
        TWO_LEVEL_TLDS.add("com.ar");
        TWO_LEVEL_TLDS.add("org.ar");
        TWO_LEVEL_TLDS.add("net.ar");
        TWO_LEVEL_TLDS.add("gov.ar");
        TWO_LEVEL_TLDS.add("edu.ar");
        // Chile
        TWO_LEVEL_TLDS.add("co.cl");
        // Colombia
        TWO_LEVEL_TLDS.add("com.co");
        TWO_LEVEL_TLDS.add("org.co");
        TWO_LEVEL_TLDS.add("net.co");
        TWO_LEVEL_TLDS.add("gov.co");
        TWO_LEVEL_TLDS.add("edu.co");
        // Peru
        TWO_LEVEL_TLDS.add("com.pe");
        TWO_LEVEL_TLDS.add("org.pe");
        TWO_LEVEL_TLDS.add("net.pe");
        TWO_LEVEL_TLDS.add("gob.pe");
        TWO_LEVEL_TLDS.add("edu.pe");
        // Venezuela
        TWO_LEVEL_TLDS.add("com.ve");
        TWO_LEVEL_TLDS.add("org.ve");
        TWO_LEVEL_TLDS.add("net.ve");
        TWO_LEVEL_TLDS.add("gov.ve");
        TWO_LEVEL_TLDS.add("edu.ve");
        // Turkey
        TWO_LEVEL_TLDS.add("com.tr");
        TWO_LEVEL_TLDS.add("org.tr");
        TWO_LEVEL_TLDS.add("net.tr");
        TWO_LEVEL_TLDS.add("gov.tr");
        TWO_LEVEL_TLDS.add("edu.tr");
        // Russia
        TWO_LEVEL_TLDS.add("com.ru");
        TWO_LEVEL_TLDS.add("org.ru");
        TWO_LEVEL_TLDS.add("net.ru");
        // Ukraine
        TWO_LEVEL_TLDS.add("com.ua");
        TWO_LEVEL_TLDS.add("org.ua");
        TWO_LEVEL_TLDS.add("net.ua");
        TWO_LEVEL_TLDS.add("gov.ua");
        TWO_LEVEL_TLDS.add("edu.ua");
        // Poland
        TWO_LEVEL_TLDS.add("com.pl");
        TWO_LEVEL_TLDS.add("org.pl");
        TWO_LEVEL_TLDS.add("net.pl");
        TWO_LEVEL_TLDS.add("gov.pl");
        TWO_LEVEL_TLDS.add("edu.pl");
        // Israel
        TWO_LEVEL_TLDS.add("co.il");
        TWO_LEVEL_TLDS.add("org.il");
        TWO_LEVEL_TLDS.add("net.il");
        TWO_LEVEL_TLDS.add("gov.il");
        TWO_LEVEL_TLDS.add("ac.il");
        // Egypt
        TWO_LEVEL_TLDS.add("com.eg");
        TWO_LEVEL_TLDS.add("org.eg");
        TWO_LEVEL_TLDS.add("net.eg");
        TWO_LEVEL_TLDS.add("gov.eg");
        TWO_LEVEL_TLDS.add("edu.eg");
        // Pakistan
        TWO_LEVEL_TLDS.add("com.pk");
        TWO_LEVEL_TLDS.add("org.pk");
        TWO_LEVEL_TLDS.add("net.pk");
        TWO_LEVEL_TLDS.add("gov.pk");
        TWO_LEVEL_TLDS.add("edu.pk");
        // Bangladesh
        TWO_LEVEL_TLDS.add("com.bd");
        TWO_LEVEL_TLDS.add("org.bd");
        TWO_LEVEL_TLDS.add("net.bd");
        TWO_LEVEL_TLDS.add("gov.bd");
        TWO_LEVEL_TLDS.add("edu.bd");
        // Nigeria
        TWO_LEVEL_TLDS.add("com.ng");
        TWO_LEVEL_TLDS.add("org.ng");
        TWO_LEVEL_TLDS.add("net.ng");
        TWO_LEVEL_TLDS.add("gov.ng");
        TWO_LEVEL_TLDS.add("edu.ng");
        // Kenya
        TWO_LEVEL_TLDS.add("co.ke");
        TWO_LEVEL_TLDS.add("or.ke");
        TWO_LEVEL_TLDS.add("ne.ke");
        TWO_LEVEL_TLDS.add("go.ke");
        TWO_LEVEL_TLDS.add("ac.ke");
    }

    private final DNSResolver resolver;
    private final Random random;

    /**
     * Creates a new DMARC validator using the specified DNS resolver.
     *
     * @param resolver the DNS resolver to use for policy lookups
     */
    public DMARCValidator(DNSResolver resolver) {
        this.resolver = resolver;
        this.random = new Random();
    }

    /**
     * Evaluates DMARC for a message.
     *
     * @param fromDomain the RFC5322.From domain
     * @param spfResult the SPF check result
     * @param spfDomain the domain checked by SPF (envelope sender domain)
     * @param dkimResult the DKIM verification result
     * @param dkimDomain the DKIM signing domain (d= tag)
     * @param callback the callback to receive the result
     */
    public void evaluate(final String fromDomain,
                         final SPFResult spfResult, final String spfDomain,
                         final DKIMResult dkimResult, final String dkimDomain,
                         final DMARCCallback callback) {

        if (fromDomain == null || fromDomain.isEmpty()) {
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Look up DMARC record
        String dmarcDomain = "_dmarc." + fromDomain;
        resolver.queryTXT(dmarcDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleDMARCResponse(response, fromDomain, spfResult, spfDomain,
                        dkimResult, dkimDomain, callback);
            }

            @Override
            public void onError(String error) {
                callback.dmarcResult(DMARCResult.TEMPERROR, null, fromDomain, AuthVerdict.NONE);
            }
        });
    }

    /**
     * Handles the DNS response for a DMARC lookup.
     */
    private void handleDMARCResponse(DNSMessage response, String fromDomain,
                                      SPFResult spfResult, String spfDomain,
                                      DKIMResult dkimResult, String dkimDomain,
                                      DMARCCallback callback) {

        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            // Try organizational domain (parent domain)
            String orgDomain = getOrganizationalDomain(fromDomain);
            if (orgDomain != null && !orgDomain.equals(fromDomain)) {
                lookupOrgDomain(orgDomain, fromDomain, spfResult, spfDomain,
                        dkimResult, dkimDomain, callback);
                return;
            }
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        if (rcode != DNSMessage.RCODE_NOERROR) {
            callback.dmarcResult(DMARCResult.TEMPERROR, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Find DMARC record
        String dmarcRecord = null;
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.startsWith("v=DMARC1")) {
                    if (dmarcRecord != null) {
                        // Multiple DMARC records is a permanent error
                        callback.dmarcResult(DMARCResult.PERMERROR, null, fromDomain, AuthVerdict.NONE);
                        return;
                    }
                    dmarcRecord = txt;
                }
            }
        }

        if (dmarcRecord == null) {
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Parse DMARC record
        DMARCRecord record = parseDMARCRecord(dmarcRecord);
        if (record == null) {
            callback.dmarcResult(DMARCResult.PERMERROR, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Evaluate alignment
        DMARCResult result = evaluateAlignment(fromDomain, spfResult, spfDomain,
                dkimResult, dkimDomain, record);

        AuthVerdict verdict = computeVerdict(result, record);
        callback.dmarcResult(result, record.policy, fromDomain, verdict);
    }

    /**
     * Looks up DMARC at the organizational domain.
     */
    private void lookupOrgDomain(final String orgDomain, final String fromDomain,
                                  final SPFResult spfResult, final String spfDomain,
                                  final DKIMResult dkimResult, final String dkimDomain,
                                  final DMARCCallback callback) {

        String dmarcDomain = "_dmarc." + orgDomain;
        resolver.queryTXT(dmarcDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleOrgDMARCResponse(response, orgDomain, fromDomain,
                        spfResult, spfDomain, dkimResult, dkimDomain, callback);
            }

            @Override
            public void onError(String error) {
                callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            }
        });
    }

    /**
     * Handles the DNS response for organizational domain DMARC lookup.
     */
    private void handleOrgDMARCResponse(DNSMessage response, String orgDomain,
                                         String fromDomain,
                                         SPFResult spfResult, String spfDomain,
                                         DKIMResult dkimResult, String dkimDomain,
                                         DMARCCallback callback) {

        int rcode = response.getRcode();
        if (rcode != DNSMessage.RCODE_NOERROR) {
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Find DMARC record
        String dmarcRecord = null;
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.startsWith("v=DMARC1")) {
                    dmarcRecord = txt;
                    break;
                }
            }
        }

        if (dmarcRecord == null) {
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Parse and evaluate
        DMARCRecord record = parseDMARCRecord(dmarcRecord);
        if (record == null) {
            callback.dmarcResult(DMARCResult.PERMERROR, null, fromDomain, AuthVerdict.NONE);
            return;
        }

        // Use subdomain policy if available
        DMARCRecord effectiveRecord = record;
        if (record.subdomainPolicy != null) {
            // Create a copy with subdomain policy as the main policy for verdict computation
            effectiveRecord = new DMARCRecord();
            effectiveRecord.policy = record.subdomainPolicy;
            effectiveRecord.subdomainPolicy = record.subdomainPolicy;
            effectiveRecord.adkim = record.adkim;
            effectiveRecord.aspf = record.aspf;
            effectiveRecord.pct = record.pct;
        }

        DMARCResult result = evaluateAlignment(fromDomain, spfResult, spfDomain,
                dkimResult, dkimDomain, record);

        AuthVerdict verdict = computeVerdict(result, effectiveRecord);
        callback.dmarcResult(result, effectiveRecord.policy, fromDomain, verdict);
    }

    /**
     * Evaluates SPF and DKIM alignment with the From domain.
     */
    private DMARCResult evaluateAlignment(String fromDomain,
                                           SPFResult spfResult, String spfDomain,
                                           DKIMResult dkimResult, String dkimDomain,
                                           DMARCRecord record) {

        boolean spfAligned = false;
        boolean dkimAligned = false;

        // Check SPF alignment
        if (spfResult == SPFResult.PASS && spfDomain != null) {
            spfAligned = checkAlignment(fromDomain, spfDomain, record.aspf);
        }

        // Check DKIM alignment
        if (dkimResult == DKIMResult.PASS && dkimDomain != null) {
            dkimAligned = checkAlignment(fromDomain, dkimDomain, record.adkim);
        }

        // DMARC passes if either mechanism is aligned
        if (spfAligned || dkimAligned) {
            return DMARCResult.PASS;
        }

        return DMARCResult.FAIL;
    }

    /**
     * Computes the authentication verdict based on DMARC result, policy, and
     * the pct= percentage tag.
     *
     * <p>When pct is less than 100, the policy is only applied to that percentage
     * of failing messages. Messages outside the percentage are treated as if the
     * policy were "none".
     *
     * @param result the DMARC evaluation result
     * @param record the parsed DMARC record containing policy and pct
     * @return the computed verdict
     */
    private AuthVerdict computeVerdict(DMARCResult result, DMARCRecord record) {
        if (result == DMARCResult.PASS) {
            return AuthVerdict.PASS;
        }

        DMARCPolicy policy = record.policy;
        if (result == DMARCResult.FAIL && policy != null) {
            // Apply pct= sampling
            if (record.pct < 100) {
                int roll = random.nextInt(100);
                if (roll >= record.pct) {
                    // Outside the percentage - don't apply policy
                    return AuthVerdict.NONE;
                }
            }

            if (policy == DMARCPolicy.REJECT) {
                return AuthVerdict.REJECT;
            }
            if (policy == DMARCPolicy.QUARANTINE) {
                return AuthVerdict.QUARANTINE;
            }
        }
        return AuthVerdict.NONE;
    }

    /**
     * Checks domain alignment.
     *
     * @param fromDomain the From header domain
     * @param authDomain the authenticated domain (SPF or DKIM)
     * @param mode alignment mode: "r" (relaxed) or "s" (strict)
     * @return true if domains align
     */
    private boolean checkAlignment(String fromDomain, String authDomain, String mode) {
        String from = fromDomain.toLowerCase();
        String auth = authDomain.toLowerCase();

        if ("s".equals(mode)) {
            // Strict: exact match required
            return from.equals(auth);
        } else {
            // Relaxed (default): organizational domain match
            String fromOrg = getOrganizationalDomain(from);
            String authOrg = getOrganizationalDomain(auth);
            if (fromOrg == null) {
                fromOrg = from;
            }
            if (authOrg == null) {
                authOrg = auth;
            }
            return fromOrg.equals(authOrg);
        }
    }

    /**
     * Gets the organizational domain (registered domain).
     *
     * <p>This implementation uses a partial Public Suffix List covering common
     * two-level TLDs like co.uk, com.au, etc. For domains with these suffixes,
     * the organizational domain requires three labels (e.g., example.co.uk).
     * For other domains, the organizational domain is the last two labels.
     *
     * @param domain the domain to find the organizational domain for
     * @return the organizational domain, or the input domain if it's already
     *         at or below the organizational level
     */
    private String getOrganizationalDomain(String domain) {
        if (domain == null) {
            return null;
        }

        String lower = domain.toLowerCase();

        // Find the last dot
        int lastDot = lower.lastIndexOf('.');
        if (lastDot <= 0) {
            // Single label or starts with dot - return as-is
            return domain;
        }

        // Find the second-to-last dot
        int secondLastDot = lower.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0) {
            // Only two labels - this is already organizational domain
            return domain;
        }

        // Check if the last two labels form a two-level TLD
        String lastTwoLabels = lower.substring(secondLastDot + 1);
        if (TWO_LEVEL_TLDS.contains(lastTwoLabels)) {
            // Need three labels for organizational domain
            int thirdLastDot = lower.lastIndexOf('.', secondLastDot - 1);
            if (thirdLastDot < 0) {
                // Only three labels - this is the organizational domain
                return domain;
            }
            // Return last three labels
            return domain.substring(thirdLastDot + 1);
        }

        // Standard case: last two labels are the organizational domain
        return domain.substring(secondLastDot + 1);
    }

    /**
     * Parses a DMARC record.
     */
    private DMARCRecord parseDMARCRecord(String record) {
        DMARCRecord result = new DMARCRecord();

        String[] parts = splitOnSemicolons(record);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int eqPos = part.indexOf('=');
            if (eqPos <= 0) {
                continue;
            }

            String tag = part.substring(0, eqPos).trim();
            String value = part.substring(eqPos + 1).trim();

            if ("p".equals(tag)) {
                result.policy = DMARCPolicy.parse(value);
            } else if ("sp".equals(tag)) {
                result.subdomainPolicy = DMARCPolicy.parse(value);
            } else if ("adkim".equals(tag)) {
                result.adkim = value;
            } else if ("aspf".equals(tag)) {
                result.aspf = value;
            } else if ("pct".equals(tag)) {
                result.pct = parseInt(value, 100);
            }
        }

        if (result.policy == null) {
            return null;
        }

        return result;
    }

    // -- Helper Methods --

    private static String[] splitOnSemicolons(String s) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ';') {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts.toArray(new String[0]);
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -- Inner Classes --

    /**
     * Parsed DMARC record.
     */
    private static class DMARCRecord {
        DMARCPolicy policy;
        DMARCPolicy subdomainPolicy;
        String adkim = "r"; // relaxed by default
        String aspf = "r";  // relaxed by default
        int pct = 100;
    }

}


