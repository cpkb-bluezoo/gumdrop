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
import java.util.List;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQueryCallback;
import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

/**
 * DMARC (Domain-based Message Authentication, Reporting and Conformance)
 * validator, originally defined in RFC 7489 and updated by RFC 9989
 * ("DMARCbis", FEAT-001) — largely additive and backward-compatible, so
 * existing {@code v=DMARC1} records continue to work unchanged.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9989">RFC 9989 - DMARCbis</a>
 *
 * <p>DMARC combines SPF and DKIM authentication results with domain alignment
 * checks to determine whether a message should be accepted, quarantined, or
 * rejected. This implementation is fully asynchronous and event-driven.
 *
 * <p>RFC 9989 additions implemented here: the {@code t=} testing-mode tag
 * (downgrades the enforced policy by one level without changing what's
 * published), the {@code np=} policy for non-existent subdomains, the
 * {@code psd=} Public Suffix Domain flag and a corresponding one-hop PSD
 * policy lookup when the Organizational Domain has no record of its own
 * (see {@link #lookupPsd}), and strict validation that {@code v=DMARC1}
 * is the first tag in the record.
 *
 * <p>This class implements both {@link SPFCallback} and {@link DKIMCallback},
 * allowing it to aggregate results from both validators. When the DKIM result
 * arrives (always last, after end-of-data), DMARC evaluation is triggered
 * automatically and the registered {@link DMARCCallback} is invoked.
 *
 * <p>DMARC alignment requires that:
 * <ul>
 *   <li>SPF passes AND the envelope domain aligns with the From domain, OR</li>
 *   <li>DKIM passes AND the signing domain aligns with the From domain</li>
 * </ul>
 *
 * <p>Event-driven usage (recommended):
 * <pre><code>
 * DNSResolver resolver = new DNSResolver();
 * resolver.useSystemResolvers();
 * resolver.open();
 *
 * DMARCValidator dmarc = new DMARCValidator(resolver, dmarcCallback);
 *
 * // Wire up SPF validator to report to DMARC
 * spfValidator.check(sender, clientIP, heloHost, dmarc);
 *
 * // Set From domain when parsed from message headers
 * // (typically via DMARCMessageHandler)
 * dmarc.setFromDomain("example.com");
 *
 * // Wire up DKIM validator to report to DMARC
 * // When dkimResult() is called, DMARC evaluates and calls dmarcCallback
 * dkimValidator.verify(dmarc);
 * </code></pre>
 *
 * <p>Direct usage (for testing or manual orchestration):
 * <pre><code>
 * dmarc.evaluate("example.com", SPFResult.PASS, "example.com",
 *     DKIMResult.PASS, "example.com", callback);
 * </code></pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489">RFC 7489 - DMARC</a>
 * @see DMARCMessageHandler
 */
public class DMARCValidator implements SPFCallback, DKIMCallback {


    private static final Logger LOGGER = Logger.getLogger(DMARCValidator.class.getName());

    private final DNSResolver resolver;
    private final SecureRandom random;

    // Callback for delivering DMARC results
    private final DMARCCallback callback;

    // Accumulated state from SPF and message parsing
    private SPFResult spfResult;
    private String spfDomain;
    private String fromDomain;

    /** The last parsed DMARC record, retained for aggregate reporting (RFC 7489 §7.1). */
    private DMARCRecord lastRecord;

    /**
     * Creates a new DMARC validator using the specified DNS resolver.
     *
     * <p>Use this constructor for direct/manual evaluation via
     * {@link #evaluate(String, SPFResult, String, DKIMResult, String, DMARCCallback)}.
     *
     * @param resolver the DNS resolver to use for policy lookups
     */
    public DMARCValidator(DNSResolver resolver) {
        this(resolver, null);
    }

    /**
     * Creates a new DMARC validator for event-driven usage.
     *
     * <p>This validator will implement {@link SPFCallback} and {@link DKIMCallback},
     * accumulating results. When {@link #dkimResult} is called, DMARC evaluation
     * triggers automatically.
     *
     * @param resolver the DNS resolver to use for policy lookups
     * @param callback the callback to receive DMARC results
     */
    public DMARCValidator(DNSResolver resolver, DMARCCallback callback) {
        this.resolver = resolver;
        this.callback = callback;
        this.random = new SecureRandom();
    }

    // -- Event-driven interface (SPFCallback, DKIMCallback) --

    /**
     * Receives the SPF result.
     *
     * <p>Called by {@link SPFValidator} when SPF check completes. The result
     * is stored for later DMARC evaluation.
     *
     * @param result the SPF result
     * @param explanation optional explanation (for FAIL results)
     */
    @Override
    public void spfResult(SPFResult result, String explanation) {
        this.spfResult = result;
    }

    /**
     * Sets the SPF domain (envelope sender domain).
     *
     * <p>This should be called at the same time as SPF check is initiated,
     * since the domain is known at MAIL FROM time.
     *
     * @param domain the envelope sender domain
     */
    public void setSpfDomain(String domain) {
        this.spfDomain = domain;
    }

    /**
     * Sets the RFC5322.From domain.
     *
     * <p>This is typically called by {@link DMARCMessageHandler} when the
     * From header is parsed.
     *
     * @param domain the From header domain
     */
    public void setFromDomain(String domain) {
        this.fromDomain = domain;
    }

    /**
     * Receives the DKIM result and triggers DMARC evaluation.
     *
     * <p>Called by {@link DKIMValidator} when DKIM verification completes.
     * Since DKIM is always the last result (at end-of-data), this triggers
     * DMARC evaluation with all accumulated state.
     *
     * @param result the DKIM result
     * @param signingDomain the DKIM signing domain (d= tag)
     * @param selector the DKIM selector (s= tag)
     */
    @Override
    public void dkimResult(DKIMResult result, String signingDomain, String selector) {
        if (callback == null) {
            return;
        }

        // DKIM result triggers DMARC evaluation
        evaluate(fromDomain, spfResult, spfDomain, result, signingDomain, callback);
    }

    /**
     * Resets the accumulated state for a new message.
     *
     * <p>Call this between messages when reusing the validator.
     */
    public void reset() {
        this.spfResult = null;
        this.spfDomain = null;
        this.fromDomain = null;
        this.lastRecord = null;
    }

    /**
     * RFC 7489 §7.1 — returns the aggregate report URIs (rua= tag) from the
     * last evaluated DMARC record, or null if not available.
     *
     * @return the list of rua= URIs, or null
     */
    public List<String> getLastRua() {
        return lastRecord != null ? lastRecord.rua : null;
    }

    /**
     * Returns the DKIM alignment mode from the last evaluated record.
     *
     * @return "r" (relaxed) or "s" (strict), or null
     */
    public String getLastAdkim() {
        return lastRecord != null ? lastRecord.adkim : null;
    }

    /**
     * Returns the SPF alignment mode from the last evaluated record.
     *
     * @return "r" (relaxed) or "s" (strict), or null
     */
    public String getLastAspf() {
        return lastRecord != null ? lastRecord.aspf : null;
    }

    /**
     * RFC 9989 §4.2 — returns the non-existent-subdomain policy (np= tag)
     * from the last evaluated record.
     *
     * @return the np= policy, or null if not present
     */
    public DMARCPolicy getLastNp() {
        return lastRecord != null ? lastRecord.np : null;
    }

    /**
     * RFC 9989 §4.2 — returns the testing mode (t= tag) from the last
     * evaluated record.
     *
     * @return "y" or "n" (default), or null if no record was evaluated
     */
    public String getLastT() {
        return lastRecord != null ? lastRecord.t : null;
    }

    /**
     * RFC 9989 §4.2 — returns the Public Suffix Domain flag (psd= tag)
     * from the last evaluated record.
     *
     * @return "y", "n", or "u" (undetermined, default), or null if no
     *         record was evaluated
     */
    public String getLastPsd() {
        return lastRecord != null ? lastRecord.psd : null;
    }

    /**
     * RFC 7489 §7.2 — returns the forensic report URIs (ruf= tag) from the
     * last evaluated DMARC record, or null if not available.
     *
     * @return the list of ruf= URIs, or null
     */
    public List<String> getLastRuf() {
        return lastRecord != null ? lastRecord.ruf : null;
    }

    /**
     * RFC 7489 §6.3 — returns the failure reporting options (fo= tag) from
     * the last evaluated DMARC record.
     *
     * @return the fo= value ("0", "1", "d", "s", or colon-separated), or null
     */
    public String getLastFo() {
        return lastRecord != null ? lastRecord.fo : null;
    }

    /**
     * RFC 7489 §6.3 — returns the failure report format (rf= tag) from
     * the last evaluated DMARC record.
     *
     * @return the rf= value (typically "afrf"), or null
     */
    public String getLastRf() {
        return lastRecord != null ? lastRecord.rf : null;
    }

    /**
     * Evaluates DMARC for a message.
     * RFC 7489 §6 — policy discovery and evaluation.
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
        this.lastRecord = record;

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
            lookupPsd(orgDomain, fromDomain, spfResult, spfDomain, dkimResult, dkimDomain, callback);
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
            // FEAT-001: RFC 9989 §5 — no record at the Organizational Domain
            // either; check one level up for a Public Suffix Domain (PSD)
            // record before giving up.
            lookupPsd(orgDomain, fromDomain, spfResult, spfDomain, dkimResult, dkimDomain, callback);
            return;
        }

        // Parse and evaluate
        DMARCRecord record = parseDMARCRecord(dmarcRecord);
        if (record == null) {
            callback.dmarcResult(DMARCResult.PERMERROR, null, fromDomain, AuthVerdict.NONE);
            return;
        }
        this.lastRecord = record;

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
            effectiveRecord.t = record.t;
        }

        DMARCResult result = evaluateAlignment(fromDomain, spfResult, spfDomain,
                dkimResult, dkimDomain, record);

        AuthVerdict verdict = computeVerdict(result, effectiveRecord);
        callback.dmarcResult(result, effectiveRecord.policy, fromDomain, verdict);
    }

    /**
     * FEAT-001: RFC 9989 §5 — Public Suffix Domain (PSD) policy lookup.
     *
     * <p>Called when neither the Author Domain nor its Organizational
     * Domain (as computed via {@link PublicSuffixList}, see SEC-048)
     * published a DMARC record. Queries one label above the
     * Organizational Domain; if a valid record is found there with
     * {@code psd=y}, its {@code np=} tag (falling back to {@code sp=},
     * then {@code p=}) governs — the PSD operator is declaring policy for
     * non-existent subdomains under it, which is exactly the situation
     * here (nothing published at the Organizational Domain).
     *
     * <p>This covers the common, spec-intended case of a PSD declaring
     * itself directly one level above the Organizational Domain (e.g. a
     * registry publishing {@code psd=y} at its own TLD/SLD). It does not
     * implement RFC 9989's full generic N-level DNS Tree Walk (which
     * keeps climbing indefinitely, up to 8 queries, looking for a
     * {@code psd=y}/{@code psd=n} record at any height) — that walk has
     * negligible practical value today given how recently {@code psd=}
     * was standardized, and {@link PublicSuffixList} already gives the
     * correct Organizational Domain for the overwhelming majority of
     * real-world domains without it.
     */
    private void lookupPsd(String orgDomain, final String fromDomain,
                            final SPFResult spfResult, final String spfDomain,
                            final DKIMResult dkimResult, final String dkimDomain,
                            final DMARCCallback callback) {

        int dot = orgDomain.indexOf('.');
        if (dot < 0) {
            // No further label to walk up to.
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }
        String psdCandidate = orgDomain.substring(dot + 1);

        String dmarcDomain = "_dmarc." + psdCandidate;
        resolver.queryTXT(dmarcDomain, new DNSQueryCallback() {
            @Override
            public void onResponse(DNSMessage response) {
                handlePsdResponse(response, fromDomain, spfResult, spfDomain,
                        dkimResult, dkimDomain, callback);
            }

            @Override
            public void onError(String error) {
                callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            }
        });
    }

    /**
     * Handles the DNS response for a PSD-level DMARC lookup (see
     * {@link #lookupPsd}).
     */
    private void handlePsdResponse(DNSMessage response, String fromDomain,
                                    SPFResult spfResult, String spfDomain,
                                    DKIMResult dkimResult, String dkimDomain,
                                    DMARCCallback callback) {

        if (response.getRcode() != DNSMessage.RCODE_NOERROR) {
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }

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

        DMARCRecord record = parseDMARCRecord(dmarcRecord);
        if (record == null || !"y".equals(record.psd)) {
            // Not a declared PSD - RFC 9989 §5 only applies np= when the
            // record found explicitly opts in with psd=y.
            callback.dmarcResult(DMARCResult.NONE, null, fromDomain, AuthVerdict.NONE);
            return;
        }
        this.lastRecord = record;

        DMARCPolicy effectivePolicy = record.np != null ? record.np
                : record.subdomainPolicy != null ? record.subdomainPolicy
                : record.policy;

        DMARCRecord effectiveRecord = new DMARCRecord();
        effectiveRecord.policy = effectivePolicy;
        effectiveRecord.adkim = record.adkim;
        effectiveRecord.aspf = record.aspf;
        effectiveRecord.pct = record.pct;
        effectiveRecord.t = record.t;

        DMARCResult result = evaluateAlignment(fromDomain, spfResult, spfDomain,
                dkimResult, dkimDomain, record);

        AuthVerdict verdict = computeVerdict(result, effectiveRecord);
        callback.dmarcResult(result, effectivePolicy, fromDomain, verdict);
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
     * RFC 7489 §6.3 — policy evaluation and verdict.
     *
     * <p>When pct is less than 100, the policy is only applied to that percentage
     * of failing messages. Messages outside the percentage are treated as if the
     * policy were "none".
     *
     * <p>FEAT-001: RFC 9989 §4.2 — the t= tag ("testing mode") downgrades
     * the declared policy by one level before it's applied: reject becomes
     * quarantine, and quarantine becomes none. This lets a domain owner
     * observe what a policy would do without actually enforcing it yet.
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
        if ("y".equals(record.t)) {
            if (policy == DMARCPolicy.REJECT) {
                policy = DMARCPolicy.QUARANTINE;
            } else if (policy == DMARCPolicy.QUARANTINE) {
                policy = DMARCPolicy.NONE;
            }
        }

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
     * RFC 7489 §3.1 — identifier alignment.
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
     * RFC 7489 §3.2 — organizational domain.
     *
     * <p>SEC-048: computed against the real Mozilla Public Suffix List via
     * {@link PublicSuffixList} rather than a hardcoded set of common
     * two-level TLDs, so this is correct for any TLD the PSL covers.
     *
     * @param domain the domain to find the organizational domain for
     * @return the organizational domain, or the input domain if it's already
     *         at or below the organizational level
     */
    private String getOrganizationalDomain(String domain) {
        if (domain == null) {
            return null;
        }
        int lastDot = domain.lastIndexOf('.');
        if (lastDot <= 0) {
            // Single label or starts with dot - return as-is
            return domain;
        }
        return PublicSuffixList.getInstance().getRegistrableDomain(domain.toLowerCase());
    }

    /**
     * Parses a DMARC record.
     * RFC 7489 §6.3 / RFC 9989 §4 — TXT record format.
     *
     * <p>FEAT-001: RFC 9989 §4.1 requires the {@code v} tag to be the
     * first tag in the record; a record where it is missing, not first,
     * or not exactly {@code DMARC1} MUST be discarded entirely. This is
     * validated here against the split, trimmed tag list (not just a raw
     * string prefix check, which wouldn't handle whitespace variations
     * correctly).
     */
    private DMARCRecord parseDMARCRecord(String record) {
        DMARCRecord result = new DMARCRecord();

        String[] parts = splitOnSemicolons(record);
        if (parts.length == 0) {
            return null;
        }
        String firstTag = parts[0].trim();
        if (!firstTag.equals("v=DMARC1")) {
            return null;
        }

        for (int i = 1; i < parts.length; i++) {
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
            } else if ("np".equals(tag)) {
                // RFC 9989 §4.2 — policy for non-existent subdomains
                result.np = DMARCPolicy.parse(value);
            } else if ("adkim".equals(tag)) {
                result.adkim = value;
            } else if ("aspf".equals(tag)) {
                result.aspf = value;
            } else if ("pct".equals(tag)) {
                result.pct = parseInt(value, 100);
            } else if ("t".equals(tag)) {
                // RFC 9989 §4.2 — testing mode ("y"/"n", default "n")
                result.t = value;
            } else if ("psd".equals(tag)) {
                // RFC 9989 §4.2 — Public Suffix Domain flag ("y"/"n"/"u")
                result.psd = value;
            } else if ("rua".equals(tag)) {
                // RFC 7489 §6.2 — aggregate report destination(s)
                result.rua = parseUriList(value);
            } else if ("ruf".equals(tag)) {
                // RFC 7489 §6.2 — forensic report destination(s)
                result.ruf = parseUriList(value);
            } else if ("fo".equals(tag)) {
                // RFC 7489 §6.3 — failure reporting options
                result.fo = value;
            } else if ("rf".equals(tag)) {
                // RFC 7489 §6.3 — failure report format
                result.rf = value;
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

    /**
     * Parses a comma-separated list of DMARC report URIs.
     * RFC 7489 §6.2 — each URI may have an optional size limit suffix.
     */
    private static List<String> parseUriList(String value) {
        List<String> uris = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ',') {
                String uri = value.substring(start, i).trim();
                if (!uri.isEmpty()) {
                    uris.add(uri);
                }
                start = i + 1;
            }
        }
        String last = value.substring(start).trim();
        if (!last.isEmpty()) {
            uris.add(last);
        }
        return uris;
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
     * RFC 7489 §6.3 — contains all parsed tags from the DNS TXT record.
     */
    static class DMARCRecord {
        DMARCPolicy policy;
        DMARCPolicy subdomainPolicy;
        /** RFC 9989 §4.2 — policy for non-existent subdomains (np= tag). */
        DMARCPolicy np;
        String adkim = "r"; // relaxed by default
        String aspf = "r";  // relaxed by default
        int pct = 100;
        /** RFC 9989 §4.2 — testing mode (t= tag): "y" or "n", default "n". */
        String t = "n";
        /** RFC 9989 §4.2 — Public Suffix Domain flag (psd= tag): "y", "n", or "u" (undetermined, default). */
        String psd = "u";
        /** RFC 7489 §6.2 — aggregate report URIs (rua= tag). */
        List<String> rua;
        /** RFC 7489 §6.2 — forensic report URIs (ruf= tag). */
        List<String> ruf;
        /** RFC 7489 §6.3 — failure reporting options (fo= tag, default "0"). */
        String fo = "0";
        /** RFC 7489 §6.3 — failure report format (rf= tag, default "afrf"). */
        String rf = "afrf";
    }

}


