/*
 * SPFValidator.java
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.util.CIDRNetwork;

/**
 * SPF (Sender Policy Framework) validator as defined in RFC 7208.
 *
 * <p>SPF validates that a sending mail server is authorized to send
 * mail for a domain by checking DNS TXT records. This implementation
 * is fully asynchronous, using callbacks for DNS lookups.
 *
 * <p>Supported mechanisms:
 * <ul>
 *   <li>{@code all} - matches any IP</li>
 *   <li>{@code ip4:} - matches IPv4 address or CIDR</li>
 *   <li>{@code ip6:} - matches IPv6 address or CIDR</li>
 *   <li>{@code a} - matches domain's A/AAAA records</li>
 *   <li>{@code mx} - matches domain's MX records</li>
 *   <li>{@code include:} - includes another domain's SPF record</li>
 *   <li>{@code redirect=} - redirects to another domain's SPF record</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.useSystemResolvers();
 * resolver.open();
 *
 * SPFValidator spf = new SPFValidator(resolver);
 * InetAddress clientIP = InetAddress.getByName("192.0.2.1");
 *
 * spf.check("sender@example.com", clientIP, "mail.example.com",
 *     new SPFCallback() {
 *         &#64;Override
 *         public void onResult(SPFResult result, String explanation) {
 *             if (result == SPFResult.PASS) {
 *                 // Sender is authorized
 *             }
 *         }
 *     });
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7208">RFC 7208 - SPF</a>
 */
public class SPFValidator {

    private static final Logger LOGGER = Logger.getLogger(SPFValidator.class.getName());

    /** Maximum number of DNS lookups allowed (RFC 7208 section 4.6.4) */
    private static final int MAX_DNS_LOOKUPS = 10;

    /** Maximum number of void lookups (NXDOMAIN/empty) allowed */
    private static final int MAX_VOID_LOOKUPS = 2;

    private final DNSResolver resolver;

    /**
     * Creates a new SPF validator using the specified DNS resolver.
     *
     * @param resolver the DNS resolver to use for lookups
     */
    public SPFValidator(DNSResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Checks the SPF policy for a sender.
     *
     * @param sender the envelope sender (MAIL FROM) address, or null for bounce
     * @param clientIP the IP address of the connecting client
     * @param heloHost the HELO/EHLO hostname from the client
     * @param callback the callback to receive the result
     */
    public void check(EmailAddress sender, InetAddress clientIP, String heloHost,
                      SPFCallback callback) {

        // Get domain from sender, or use HELO for null sender (bounce messages)
        String domain;
        if (sender != null) {
            domain = sender.getDomain();
        } else {
            domain = heloHost;
        }

        if (domain == null || domain.isEmpty()) {
            callback.spfResult(SPFResult.NONE, null);
            return;
        }

        // Start the check
        CheckContext ctx = new CheckContext(domain, clientIP, sender, heloHost, callback);
        lookupSPF(ctx, domain);
    }

    /**
     * Looks up the SPF record for a domain.
     */
    private void lookupSPF(final CheckContext ctx, final String domain) {
        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;

        resolver.queryTXT(domain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleSPFResponse(ctx, domain, response);
            }

            @Override
            public void onError(String error) {
                ctx.callback.spfResult(SPFResult.TEMPERROR, error);
            }
        });
    }

    /**
     * Handles the DNS response for an SPF lookup.
     */
    private void handleSPFResponse(CheckContext ctx, String domain, DNSMessage response) {
        // Check for NXDOMAIN
        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            ctx.callback.spfResult(SPFResult.NONE, null);
            return;
        }

        if (rcode != DNSMessage.RCODE_NOERROR) {
            ctx.callback.spfResult(SPFResult.TEMPERROR, "DNS error: " + rcode);
            return;
        }

        // Find SPF record in TXT answers
        String spfRecord = null;
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.startsWith("v=spf1 ")) {
                    if (spfRecord != null) {
                        // Multiple SPF records is a permanent error
                        ctx.callback.spfResult(SPFResult.PERMERROR, "Multiple SPF records");
                        return;
                    }
                    spfRecord = txt;
                }
            }
        }

        if (spfRecord == null) {
            ctx.callback.spfResult(SPFResult.NONE, null);
            return;
        }

        // Parse and evaluate the SPF record
        evaluateSPF(ctx, domain, spfRecord);
    }

    /**
     * Evaluates an SPF record against the client IP.
     */
    private void evaluateSPF(CheckContext ctx, String domain, String spfRecord) {
        // Parse mechanisms from the SPF record
        // Format: "v=spf1 mechanism mechanism ... [redirect=domain]"
        String record = spfRecord.substring(7); // Skip "v=spf1 "
        String[] parts = splitOnSpaces(record);

        // Start evaluation from the beginning
        evaluateMechanisms(ctx, domain, parts, 0);
    }

    /**
     * Evaluates mechanisms starting from a given index.
     * This allows resuming after async lookups complete without a match.
     */
    private void evaluateMechanisms(CheckContext ctx, String domain, 
                                     String[] parts, int startIndex) {
        String redirect = null;

        for (int i = startIndex; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }

            // Check for modifiers (these don't affect mechanism order)
            if (part.startsWith("redirect=")) {
                redirect = expandMacros(part.substring(9), ctx, domain);
                continue;
            }
            if (part.startsWith("exp=")) {
                ctx.explanation = part.substring(4);
                continue;
            }

            // Parse qualifier (+ - ~ ?)
            char qualifier = '+';
            if (part.charAt(0) == '+' || part.charAt(0) == '-' ||
                part.charAt(0) == '~' || part.charAt(0) == '?') {
                qualifier = part.charAt(0);
                part = part.substring(1);
            }

            // Evaluate synchronous mechanisms first
            SPFResult mechResult = evaluateMechanism(ctx, domain, part);

            if (mechResult != null) {
                // Mechanism matched
                SPFResult result = qualifierToResult(qualifier);
                deliverResult(ctx, domain, result);
                return;
            }

            // Check if we need async lookup (include, a, mx, exists, ptr)
            if (part.startsWith("include:")) {
                String includeDomain = expandMacros(part.substring(8), ctx, domain);
                lookupInclude(ctx, domain, parts, i, includeDomain, qualifier);
                return;
            }

            if (part.equals("a") || part.startsWith("a:") || part.startsWith("a/")) {
                lookupA(ctx, domain, parts, i, part, qualifier);
                return;
            }

            if (part.equals("mx") || part.startsWith("mx:") || part.startsWith("mx/")) {
                lookupMX(ctx, domain, parts, i, part, qualifier);
                return;
            }

            if (part.startsWith("exists:")) {
                String existsDomain = expandMacros(part.substring(7), ctx, domain);
                lookupExists(ctx, domain, parts, i, existsDomain, qualifier);
                return;
            }

            if (part.equals("ptr") || part.startsWith("ptr:")) {
                lookupPTR(ctx, domain, parts, i, part, qualifier);
                return;
            }
        }

        // No mechanisms matched, check for redirect
        if (redirect != null) {
            lookupSPF(ctx, redirect);
            return;
        }

        // Default result is neutral
        ctx.callback.spfResult(SPFResult.NEUTRAL, null);
    }

    /**
     * Delivers a result, fetching explanation if needed.
     */
    private void deliverResult(final CheckContext ctx, final String domain, 
                                final SPFResult result) {
        // For FAIL results, try to fetch explanation if exp= was present
        if (result == SPFResult.FAIL && ctx.explanation != null) {
            String expDomain = expandMacros(ctx.explanation, ctx, domain);
            fetchExplanation(ctx, expDomain, result);
        } else {
            ctx.callback.spfResult(result, null);
        }
    }

    /**
     * Fetches the explanation TXT record for a FAIL result.
     */
    private void fetchExplanation(final CheckContext ctx, String expDomain,
                                   final SPFResult result) {
        // Don't count this as a mechanism lookup (it's optional)
        resolver.queryTXT(expDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                String explanation = null;
                List<DNSResourceRecord> answers = response.getAnswers();
                for (int i = 0; i < answers.size(); i++) {
                    DNSResourceRecord rr = answers.get(i);
                    if (rr.getType() == DNSType.TXT) {
                        explanation = rr.getText();
                        break;
                    }
                }
                ctx.callback.spfResult(result, explanation);
            }

            @Override
            public void onError(String error) {
                // Explanation lookup failed, deliver result without explanation
                ctx.callback.spfResult(result, null);
            }
        });
    }

    /**
     * Evaluates a single mechanism synchronously (ip4, ip6, all).
     * Returns non-null if the mechanism matches, null otherwise.
     */
    private SPFResult evaluateMechanism(CheckContext ctx, String domain, String mechanism) {
        if ("all".equals(mechanism)) {
            return SPFResult.PASS; // "all" always matches
        }

        if (mechanism.startsWith("ip4:")) {
            String network = mechanism.substring(4);
            if (matchesIP4(ctx.clientIP, network)) {
                return SPFResult.PASS;
            }
        }

        if (mechanism.startsWith("ip6:")) {
            String network = mechanism.substring(4);
            if (matchesIP6(ctx.clientIP, network)) {
                return SPFResult.PASS;
            }
        }

        // ptr mechanism is deprecated (RFC 7208) - skip it

        return null;
    }

    /**
     * Checks if an IP matches an IPv4 CIDR network.
     */
    private boolean matchesIP4(InetAddress ip, String network) {
        try {
            CIDRNetwork cidr = new CIDRNetwork(network);
            return cidr.matches(ip);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if an IP matches an IPv6 CIDR network.
     */
    private boolean matchesIP6(InetAddress ip, String network) {
        try {
            CIDRNetwork cidr = new CIDRNetwork(network);
            return cidr.matches(ip);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Looks up an included domain's SPF record.
     */
    private void lookupInclude(final CheckContext ctx, final String originalDomain,
                                final String[] parts, final int currentIndex,
                                final String includeDomain, final char qualifier) {
        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;

        resolver.queryTXT(includeDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleIncludeResponse(ctx, originalDomain, parts, currentIndex,
                                      includeDomain, response, qualifier);
            }

            @Override
            public void onError(String error) {
                ctx.callback.spfResult(SPFResult.TEMPERROR, error);
            }
        });
    }

    /**
     * Handles the response from an include lookup.
     */
    private void handleIncludeResponse(final CheckContext ctx, final String originalDomain,
                                        final String[] parts, final int currentIndex,
                                        String includeDomain, DNSMessage response,
                                        char qualifier) {
        // Check for NXDOMAIN - include with no domain is PermError
        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            ctx.voidLookups++;
            if (ctx.voidLookups > MAX_VOID_LOOKUPS) {
                ctx.callback.spfResult(SPFResult.PERMERROR, "Too many void lookups");
                return;
            }
            ctx.callback.spfResult(SPFResult.PERMERROR, "Included domain does not exist");
            return;
        }

        // Find SPF record
        String spfRecord = null;
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.TXT) {
                String txt = rr.getText();
                if (txt != null && txt.startsWith("v=spf1 ")) {
                    if (spfRecord != null) {
                        ctx.callback.spfResult(SPFResult.PERMERROR, "Multiple SPF records");
                        return;
                    }
                    spfRecord = txt;
                }
            }
        }

        if (spfRecord == null) {
            // Include with no SPF record is a permanent error
            ctx.callback.spfResult(SPFResult.PERMERROR, "No SPF record for included domain");
            return;
        }

        // Evaluate the included SPF record
        // If it returns Pass, we apply the qualifier
        // If it returns Fail, Softfail, or Neutral, we continue with next mechanism
        // If it returns TempError or PermError, we return that
        evaluateIncludedSPF(ctx, originalDomain, parts, currentIndex, 
                            includeDomain, spfRecord, qualifier);
    }

    /**
     * Evaluates an included SPF record with proper result handling.
     */
    private void evaluateIncludedSPF(final CheckContext ctx, final String originalDomain,
                                      final String[] originalParts, final int currentIndex,
                                      String includeDomain, String spfRecord, 
                                      final char qualifier) {
        // Create a sub-callback that interprets include results correctly
        final SPFCallback includeCallback = new SPFCallback() {
            @Override
            public void spfResult(SPFResult result, String explanation) {
                switch (result) {
                    case PASS:
                        // Include matched - apply qualifier
                        deliverResult(ctx, originalDomain, qualifierToResult(qualifier));
                        break;
                    case FAIL:
                    case SOFTFAIL:
                    case NEUTRAL:
                    case NONE:
                        // Include didn't match - continue with next mechanism
                        evaluateMechanisms(ctx, originalDomain, originalParts, currentIndex + 1);
                        break;
                    case TEMPERROR:
                    case PERMERROR:
                        // Error in include - propagate it
                        ctx.callback.spfResult(result, explanation);
                        break;
                }
            }
        };

        // Parse and evaluate the included record
        String record = spfRecord.substring(7); // Skip "v=spf1 "
        String[] includeParts = splitOnSpaces(record);

        // Create a temporary context with the include callback
        CheckContext includeCtx = new CheckContext(includeDomain, ctx.clientIP, 
                                                   ctx.sender, ctx.heloHost, includeCallback);
        includeCtx.dnsLookups = ctx.dnsLookups;
        includeCtx.voidLookups = ctx.voidLookups;
        includeCtx.explanation = ctx.explanation;

        evaluateMechanisms(includeCtx, includeDomain, includeParts, 0);

        // Update lookup counts back to original context
        ctx.dnsLookups = includeCtx.dnsLookups;
        ctx.voidLookups = includeCtx.voidLookups;
    }

    /**
     * Looks up A/AAAA records for the "a" mechanism.
     */
    private void lookupA(final CheckContext ctx, final String originalDomain,
                          final String[] parts, final int currentIndex,
                          String mechanism, final char qualifier) {
        // Parse mechanism: a, a:domain, a/cidr, a:domain/cidr, a:domain/cidr4//cidr6
        String targetDomain = originalDomain;
        int[] prefixes = new int[] { -1, -1 };

        if (mechanism.startsWith("a:")) {
            String rest = mechanism.substring(2);
            int slashPos = rest.indexOf('/');
            if (slashPos > 0) {
                targetDomain = rest.substring(0, slashPos);
                prefixes = parseDualPrefix(rest.substring(slashPos + 1));
            } else {
                targetDomain = rest;
            }
        } else if (mechanism.startsWith("a/")) {
            prefixes = parseDualPrefix(mechanism.substring(2));
        }

        // Expand macros in domain
        targetDomain = expandMacros(targetDomain, ctx, originalDomain);

        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;
        final int ip4Prefix = prefixes[0];
        final int ip6Prefix = prefixes[1];

        // Query for A record (or AAAA for IPv6)
        DNSType type = isIPv6(ctx.clientIP) ? DNSType.AAAA : DNSType.A;

        resolver.query(targetDomain, type, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                int prefix = isIPv6(ctx.clientIP) ? ip6Prefix : ip4Prefix;
                handleAResponse(ctx, originalDomain, parts, currentIndex, 
                                response, prefix, qualifier);
            }

            @Override
            public void onError(String error) {
                ctx.callback.spfResult(SPFResult.TEMPERROR, error);
            }
        });
    }

    /**
     * Handles the response from an A/AAAA lookup.
     */
    private void handleAResponse(CheckContext ctx, String originalDomain,
                                  String[] parts, int currentIndex,
                                  DNSMessage response, int prefixLen, char qualifier) {
        // Check for void lookup
        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            ctx.voidLookups++;
            if (ctx.voidLookups > MAX_VOID_LOOKUPS) {
                ctx.callback.spfResult(SPFResult.PERMERROR, "Too many void lookups");
                return;
            }
        }

        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.A || rr.getType() == DNSType.AAAA) {
                InetAddress addr = rr.getAddress();
                if (addr != null) {
                    boolean match;
                    if (prefixLen > 0) {
                        // CIDR match
                        String cidr = addr.getHostAddress() + "/" + prefixLen;
                        match = matchesIP4(ctx.clientIP, cidr) ||
                                matchesIP6(ctx.clientIP, cidr);
                    } else {
                        // Exact match
                        match = addr.equals(ctx.clientIP);
                    }

                    if (match) {
                        SPFResult result = qualifierToResult(qualifier);
                        deliverResult(ctx, originalDomain, result);
                        return;
                    }
                }
            }
        }

        // No match - continue to next mechanism
        evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
    }

    /**
     * Looks up PTR records for the "ptr" mechanism.
     * Note: This mechanism is deprecated (RFC 7208) due to performance and
     * reliability issues, but must still be supported.
     */
    private void lookupPTR(final CheckContext ctx, final String originalDomain,
                            final String[] parts, final int currentIndex,
                            String mechanism, final char qualifier) {
        // Parse mechanism: ptr, ptr:domain
        String targetDomain = originalDomain;
        if (mechanism.startsWith("ptr:")) {
            targetDomain = expandMacros(mechanism.substring(4), ctx, originalDomain);
        }

        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;
        final String ptrDomain = targetDomain;

        // First, do reverse DNS lookup on client IP
        String reverseName = getReverseDNSName(ctx.clientIP);

        resolver.queryPTR(reverseName, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handlePTRResponse(ctx, originalDomain, parts, currentIndex,
                                  ptrDomain, response, qualifier);
            }

            @Override
            public void onError(String error) {
                // PTR lookup failed - continue to next mechanism
                evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            }
        });
    }

    /**
     * Handles the response from a PTR lookup.
     */
    private void handlePTRResponse(final CheckContext ctx, final String originalDomain,
                                    final String[] parts, final int currentIndex,
                                    final String targetDomain, DNSMessage response,
                                    final char qualifier) {
        // Get PTR hostnames
        final List<String> ptrNames = new ArrayList<String>();
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.PTR) {
                String name = rr.getTargetName();
                if (name != null) {
                    ptrNames.add(name);
                }
            }
        }

        if (ptrNames.isEmpty()) {
            // No PTR records - continue to next mechanism
            evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            return;
        }

        // Validate PTR names by doing forward lookup
        validatePTRNames(ctx, originalDomain, parts, currentIndex, targetDomain,
                         ptrNames, 0, qualifier);
    }

    /**
     * Validates PTR names by checking that forward DNS matches the client IP.
     */
    private void validatePTRNames(final CheckContext ctx, final String originalDomain,
                                   final String[] parts, final int currentIndex,
                                   final String targetDomain, final List<String> ptrNames,
                                   final int ptrIndex, final char qualifier) {
        if (ptrIndex >= ptrNames.size()) {
            // Checked all PTR names, no valid match
            evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            return;
        }

        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;
        final String ptrName = ptrNames.get(ptrIndex);

        // Check if PTR name ends with target domain (or equals it)
        boolean domainMatch = ptrName.equalsIgnoreCase(targetDomain) ||
                              ptrName.toLowerCase().endsWith("." + targetDomain.toLowerCase());

        if (!domainMatch) {
            // This PTR name doesn't match target domain, try next
            validatePTRNames(ctx, originalDomain, parts, currentIndex, targetDomain,
                             ptrNames, ptrIndex + 1, qualifier);
            return;
        }

        // Forward lookup to validate
        DNSType type = isIPv6(ctx.clientIP) ? DNSType.AAAA : DNSType.A;

        resolver.query(ptrName, type, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                // Check if any returned IP matches client IP
                List<DNSResourceRecord> answers = response.getAnswers();
                for (int i = 0; i < answers.size(); i++) {
                    DNSResourceRecord rr = answers.get(i);
                    if (rr.getType() == DNSType.A || rr.getType() == DNSType.AAAA) {
                        InetAddress addr = rr.getAddress();
                        if (addr != null && addr.equals(ctx.clientIP)) {
                            // Valid forward-confirmed reverse DNS
                            SPFResult result = qualifierToResult(qualifier);
                            deliverResult(ctx, originalDomain, result);
                            return;
                        }
                    }
                }
                // Forward lookup didn't match, try next PTR name
                validatePTRNames(ctx, originalDomain, parts, currentIndex, targetDomain,
                                 ptrNames, ptrIndex + 1, qualifier);
            }

            @Override
            public void onError(String error) {
                // Forward lookup failed, try next PTR name
                validatePTRNames(ctx, originalDomain, parts, currentIndex, targetDomain,
                                 ptrNames, ptrIndex + 1, qualifier);
            }
        });
    }

    /**
     * Gets the reverse DNS name for an IP address.
     */
    private String getReverseDNSName(InetAddress ip) {
        byte[] bytes = ip.getAddress();
        StringBuilder sb = new StringBuilder();

        if (isIPv6(ip)) {
            // IPv6: nibbles in reverse, separated by dots, ending with ip6.arpa
            for (int i = bytes.length - 1; i >= 0; i--) {
                int b = bytes[i] & 0xFF;
                sb.append(Integer.toHexString(b & 0x0F));
                sb.append('.');
                sb.append(Integer.toHexString((b >> 4) & 0x0F));
                if (i > 0) {
                    sb.append('.');
                }
            }
            sb.append(".ip6.arpa");
        } else {
            // IPv4: octets in reverse, ending with in-addr.arpa
            for (int i = bytes.length - 1; i >= 0; i--) {
                sb.append(bytes[i] & 0xFF);
                if (i > 0) {
                    sb.append('.');
                }
            }
            sb.append(".in-addr.arpa");
        }

        return sb.toString();
    }

    /**
     * Looks up MX records for the "mx" mechanism.
     */
    private void lookupMX(final CheckContext ctx, final String originalDomain,
                           final String[] parts, final int currentIndex,
                           String mechanism, final char qualifier) {
        // Parse mechanism: mx, mx:domain, mx/cidr, mx:domain/cidr, mx:domain/cidr4//cidr6
        String targetDomain = originalDomain;
        int[] prefixes = new int[] { -1, -1 };

        if (mechanism.startsWith("mx:")) {
            String rest = mechanism.substring(3);
            int slashPos = rest.indexOf('/');
            if (slashPos > 0) {
                targetDomain = rest.substring(0, slashPos);
                prefixes = parseDualPrefix(rest.substring(slashPos + 1));
            } else {
                targetDomain = rest;
            }
        } else if (mechanism.startsWith("mx/")) {
            prefixes = parseDualPrefix(mechanism.substring(3));
        }

        // Expand macros in domain
        targetDomain = expandMacros(targetDomain, ctx, originalDomain);

        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;
        final int ip4Prefix = prefixes[0];
        final int ip6Prefix = prefixes[1];

        resolver.queryMX(targetDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                int prefix = isIPv6(ctx.clientIP) ? ip6Prefix : ip4Prefix;
                handleMXResponse(ctx, originalDomain, parts, currentIndex,
                                 response, prefix, qualifier);
            }

            @Override
            public void onError(String error) {
                ctx.callback.spfResult(SPFResult.TEMPERROR, error);
            }
        });
    }

    /**
     * Handles the response from an MX lookup.
     */
    private void handleMXResponse(final CheckContext ctx, final String originalDomain,
                                   final String[] parts, final int currentIndex,
                                   DNSMessage response, final int prefixLen, 
                                   final char qualifier) {
        // Check for void lookup
        int rcode = response.getRcode();
        if (rcode == DNSMessage.RCODE_NXDOMAIN) {
            ctx.voidLookups++;
            if (ctx.voidLookups > MAX_VOID_LOOKUPS) {
                ctx.callback.spfResult(SPFResult.PERMERROR, "Too many void lookups");
                return;
            }
        }

        // Get MX hostnames, then look up their A/AAAA records
        final List<String> mxHosts = new ArrayList<String>();
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.MX) {
                String mxHost = rr.getMXExchange();
                if (mxHost != null) {
                    mxHosts.add(mxHost);
                }
            }
        }

        if (mxHosts.isEmpty()) {
            // No MX records - continue to next mechanism
            evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            return;
        }

        // Look up MX hosts - we need to check all of them
        lookupMXHosts(ctx, originalDomain, parts, currentIndex, mxHosts, 0, 
                      prefixLen, qualifier);
    }

    /**
     * Looks up A/AAAA records for MX hosts, iterating through the list.
     */
    private void lookupMXHosts(final CheckContext ctx, final String originalDomain,
                                final String[] parts, final int currentIndex,
                                final List<String> mxHosts, final int mxIndex,
                                final int prefixLen, final char qualifier) {
        if (mxIndex >= mxHosts.size()) {
            // Checked all MX hosts, no match - continue to next mechanism
            evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            return;
        }

        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;
        String mxHost = mxHosts.get(mxIndex);
        DNSType type = isIPv6(ctx.clientIP) ? DNSType.AAAA : DNSType.A;

        resolver.query(mxHost, type, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handleMXHostResponse(ctx, originalDomain, parts, currentIndex,
                                     mxHosts, mxIndex, response, prefixLen, qualifier);
            }

            @Override
            public void onError(String error) {
                // Try next MX host on error
                lookupMXHosts(ctx, originalDomain, parts, currentIndex,
                              mxHosts, mxIndex + 1, prefixLen, qualifier);
            }
        });
    }

    /**
     * Handles the response from an MX host A/AAAA lookup.
     */
    private void handleMXHostResponse(CheckContext ctx, String originalDomain,
                                       String[] parts, int currentIndex,
                                       List<String> mxHosts, int mxIndex,
                                       DNSMessage response, int prefixLen, 
                                       char qualifier) {
        List<DNSResourceRecord> answers = response.getAnswers();
        for (int i = 0; i < answers.size(); i++) {
            DNSResourceRecord rr = answers.get(i);
            if (rr.getType() == DNSType.A || rr.getType() == DNSType.AAAA) {
                InetAddress addr = rr.getAddress();
                if (addr != null) {
                    boolean match;
                    if (prefixLen > 0) {
                        String cidr = addr.getHostAddress() + "/" + prefixLen;
                        match = matchesIP4(ctx.clientIP, cidr) ||
                                matchesIP6(ctx.clientIP, cidr);
                    } else {
                        match = addr.equals(ctx.clientIP);
                    }

                    if (match) {
                        SPFResult result = qualifierToResult(qualifier);
                        deliverResult(ctx, originalDomain, result);
                        return;
                    }
                }
            }
        }

        // No match in this MX host - try next one
        lookupMXHosts(ctx, originalDomain, parts, currentIndex,
                      mxHosts, mxIndex + 1, prefixLen, qualifier);
    }

    /**
     * Looks up a domain for the "exists" mechanism.
     */
    private void lookupExists(final CheckContext ctx, final String originalDomain,
                               final String[] parts, final int currentIndex,
                               final String existsDomain, final char qualifier) {
        if (ctx.dnsLookups >= MAX_DNS_LOOKUPS) {
            ctx.callback.spfResult(SPFResult.PERMERROR, "Too many DNS lookups");
            return;
        }

        ctx.dnsLookups++;

        resolver.queryA(existsDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                // exists matches if ANY A record exists
                List<DNSResourceRecord> answers = response.getAnswers();
                for (int i = 0; i < answers.size(); i++) {
                    DNSResourceRecord rr = answers.get(i);
                    if (rr.getType() == DNSType.A) {
                        // Match - apply qualifier
                        SPFResult result = qualifierToResult(qualifier);
                        deliverResult(ctx, originalDomain, result);
                        return;
                    }
                }
                // No A record - continue to next mechanism
                evaluateMechanisms(ctx, originalDomain, parts, currentIndex + 1);
            }

            @Override
            public void onError(String error) {
                ctx.callback.spfResult(SPFResult.TEMPERROR, error);
            }
        });
    }

    // -- Helper Methods --

    /**
     * Converts an SPF qualifier to a result.
     */
    private SPFResult qualifierToResult(char qualifier) {
        switch (qualifier) {
            case '+':
                return SPFResult.PASS;
            case '-':
                return SPFResult.FAIL;
            case '~':
                return SPFResult.SOFTFAIL;
            case '?':
                return SPFResult.NEUTRAL;
            default:
                return SPFResult.PASS;
        }
    }

    /**
     * Parses CIDR prefix(es) from a string.
     * Supports single prefix (e.g., "/24") and dual prefix (e.g., "/24//64").
     *
     * @return int[2] where [0] is IPv4 prefix, [1] is IPv6 prefix, -1 if not specified
     */
    private int[] parseDualPrefix(String s) {
        int[] result = new int[] { -1, -1 };
        if (s == null || s.isEmpty()) {
            return result;
        }

        int doubleSlash = s.indexOf("//");
        if (doubleSlash > 0) {
            // Dual CIDR: /24//64
            result[0] = parsePrefix(s.substring(0, doubleSlash));
            result[1] = parsePrefix(s.substring(doubleSlash + 2));
        } else {
            // Single CIDR applies to both
            int prefix = parsePrefix(s);
            result[0] = prefix;
            result[1] = prefix;
        }
        return result;
    }

    /**
     * Parses a CIDR prefix length.
     */
    private int parsePrefix(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Checks if an address is IPv6.
     */
    private boolean isIPv6(InetAddress addr) {
        return addr instanceof java.net.Inet6Address;
    }

    /**
     * Splits a string on whitespace without using regex.
     */
    private String[] splitOnSpaces(String s) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        boolean inWord = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                if (inWord) {
                    parts.add(s.substring(start, i));
                    inWord = false;
                }
            } else {
                if (!inWord) {
                    start = i;
                    inWord = true;
                }
            }
        }

        if (inWord) {
            parts.add(s.substring(start));
        }

        return parts.toArray(new String[0]);
    }

    // -- Macro Expansion (RFC 7208 Section 7) --

    /**
     * Expands SPF macros in a string.
     *
     * <p>Supported macros:
     * <ul>
     *   <li>%{s} - sender email address</li>
     *   <li>%{l} - local-part of sender</li>
     *   <li>%{o} - domain of sender</li>
     *   <li>%{d} - current domain being evaluated</li>
     *   <li>%{i} - client IP (dotted for IPv4, expanded for IPv6)</li>
     *   <li>%{p} - validated domain name of client IP (requires PTR lookup)</li>
     *   <li>%{v} - "in-addr" or "ip6" literal</li>
     *   <li>%{h} - HELO/EHLO hostname</li>
     * </ul>
     *
     * <p>Transformers (applied after macro):
     * <ul>
     *   <li>r - reverse order of dot-separated parts</li>
     *   <li>digits - take rightmost N parts</li>
     * </ul>
     *
     * @param input the string containing macros
     * @param ctx the check context with sender, IP, etc.
     * @param currentDomain the current domain being evaluated
     * @return the expanded string
     */
    private String expandMacros(String input, CheckContext ctx, String currentDomain) {
        if (input == null || !input.contains("%")) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '%' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '%') {
                    result.append('%');
                    i += 2;
                } else if (next == '_') {
                    result.append(' ');
                    i += 2;
                } else if (next == '-') {
                    result.append("%20");
                    i += 2;
                } else if (next == '{') {
                    // Find closing brace
                    int closePos = input.indexOf('}', i + 2);
                    if (closePos > 0) {
                        String macroSpec = input.substring(i + 2, closePos);
                        String expanded = expandMacro(macroSpec, ctx, currentDomain);
                        result.append(expanded);
                        i = closePos + 1;
                    } else {
                        result.append(c);
                        i++;
                    }
                } else {
                    result.append(c);
                    i++;
                }
            } else {
                result.append(c);
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Expands a single macro specification (the part inside %{...}).
     */
    private String expandMacro(String spec, CheckContext ctx, String currentDomain) {
        if (spec.isEmpty()) {
            return "";
        }

        // Parse: letter [digits] [r] [delimiters]
        char macroLetter = Character.toLowerCase(spec.charAt(0));
        boolean reverse = false;
        int digits = 0;
        String delimiter = ".";
        
        int pos = 1;
        
        // Parse optional digits
        while (pos < spec.length() && Character.isDigit(spec.charAt(pos))) {
            digits = digits * 10 + (spec.charAt(pos) - '0');
            pos++;
        }
        
        // Parse optional 'r' for reverse
        if (pos < spec.length() && spec.charAt(pos) == 'r') {
            reverse = true;
            pos++;
        }
        
        // Parse optional delimiter characters
        if (pos < spec.length()) {
            delimiter = spec.substring(pos);
            if (delimiter.isEmpty()) {
                delimiter = ".";
            }
        }

        // Get the raw value for this macro
        String value = getMacroValue(macroLetter, ctx, currentDomain);
        if (value == null) {
            return "";
        }

        // Apply transformations
        if (reverse || digits > 0) {
            // Split by delimiter(s)
            String[] parts = splitByDelimiters(value, delimiter);
            
            if (reverse) {
                // Reverse the parts
                String[] reversed = new String[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    reversed[i] = parts[parts.length - 1 - i];
                }
                parts = reversed;
            }
            
            if (digits > 0 && digits < parts.length) {
                // Take rightmost N parts
                String[] trimmed = new String[digits];
                int start = parts.length - digits;
                for (int i = 0; i < digits; i++) {
                    trimmed[i] = parts[start + i];
                }
                parts = trimmed;
            }
            
            // Rejoin with dots
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(parts[i]);
            }
            value = sb.toString();
        }

        return value;
    }

    /**
     * Gets the raw value for a macro letter.
     */
    private String getMacroValue(char letter, CheckContext ctx, String currentDomain) {
        switch (letter) {
            case 's': // sender
                if (ctx.sender != null) {
                    return ctx.sender.toString();
                }
                return "postmaster@" + ctx.heloHost;
                
            case 'l': // local-part of sender
                if (ctx.sender != null) {
                    return ctx.sender.getLocalPart();
                }
                return "postmaster";
                
            case 'o': // domain of sender
                if (ctx.sender != null) {
                    return ctx.sender.getDomain();
                }
                return ctx.heloHost;
                
            case 'd': // current domain
                return currentDomain;
                
            case 'i': // client IP
                return formatIPForMacro(ctx.clientIP);
                
            case 'p': // validated domain of client (requires PTR - return "unknown")
                // Full implementation would do PTR lookup, for now return unknown
                return "unknown";
                
            case 'v': // IP version literal
                return isIPv6(ctx.clientIP) ? "ip6" : "in-addr";
                
            case 'h': // HELO hostname
                return ctx.heloHost;
                
            case 'c': // SMTP client IP (readable form)
                return ctx.clientIP.getHostAddress();
                
            case 'r': // receiving domain (our domain)
                return currentDomain;
                
            case 't': // current timestamp
                return String.valueOf(System.currentTimeMillis() / 1000);
                
            default:
                return "";
        }
    }

    /**
     * Formats an IP address for the %{i} macro.
     * IPv4: dotted decimal (e.g., "192.0.2.1")
     * IPv6: dot-separated nibbles (e.g., "1.0.0.0.0.0.0.0...")
     */
    private String formatIPForMacro(InetAddress ip) {
        if (isIPv6(ip)) {
            // Expand to dot-separated nibbles
            byte[] bytes = ip.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                int b = bytes[i] & 0xFF;
                sb.append(Integer.toHexString((b >> 4) & 0x0F));
                sb.append('.');
                sb.append(Integer.toHexString(b & 0x0F));
            }
            return sb.toString();
        } else {
            return ip.getHostAddress();
        }
    }

    /**
     * Splits a string by any characters in the delimiter set.
     */
    private String[] splitByDelimiters(String s, String delimiters) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (delimiters.indexOf(c) >= 0) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        
        return parts.toArray(new String[0]);
    }

    // -- Context Class --

    /**
     * Context for an SPF check, tracking state across async operations.
     */
    private static class CheckContext {
        final String domain;
        final InetAddress clientIP;
        final EmailAddress sender;
        final String heloHost;
        final SPFCallback callback;

        int dnsLookups;
        int voidLookups;
        String explanation; // exp= modifier value

        CheckContext(String domain, InetAddress clientIP, EmailAddress sender,
                     String heloHost, SPFCallback callback) {
            this.domain = domain;
            this.clientIP = clientIP;
            this.sender = sender;
            this.heloHost = heloHost;
            this.callback = callback;
            this.dnsLookups = 0;
            this.voidLookups = 0;
        }
    }

}

