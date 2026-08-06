/*
 * DMARCAggregateReport.java
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 7489 §7.1 / RFC 9990 — DMARC aggregate report generator.
 *
 * <p>Collects per-domain authentication results over a reporting period and
 * produces an XML aggregate report. The report is intended to be
 * gzip-compressed and sent to the domain owner's {@code rua=} address.
 *
 * <p>FEAT-002: emits the RFC 9990 {@code policy_published} additions
 * ({@code np}, {@code testing}, {@code discovery_method}) and the optional
 * {@code report_metadata/generator}, and enforces RFC 9990's requirement
 * that a single report cover exactly one Policy Domain (a second, distinct
 * {@code headerFrom} passed to {@link #addResult} throws
 * {@link IllegalStateException} rather than silently producing a
 * multi-domain report the way this class previously allowed).
 *
 * <p><b>{@code pct} retained for backward compatibility</b>: RFC 9990
 * drops the {@code pct}/{@code ri} tags from {@code policy_published}
 * entirely. {@code ri} was never implemented here, but {@code pct} is
 * still emitted (now reflecting the actual configured value rather than a
 * hardcoded "100") since existing report consumers built against RFC 7489
 * may still expect it, and a receiver that doesn't recognise it can
 * simply ignore an extra field — dropping it outright would be a breaking
 * change with no compensating benefit for those consumers.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * DMARCAggregateReport report = new DMARCAggregateReport();
 * report.setReporterOrgName("example-receiver.com");
 * report.setReporterEmail("dmarc-reports@example-receiver.com");
 * report.setReportId("unique-report-id-12345");
 * report.setDateRange(beginEpoch, endEpoch);
 *
 * // Record authentication results for each message
 * report.addResult("192.0.2.1", "sender.example.com",
 *     DMARCPolicy.NONE, "r", "r",
 *     DMARCResult.PASS, SPFResult.PASS, "sender.example.com",
 *     DKIMResult.PASS, "sender.example.com", "sel1");
 *
 * // Generate the XML report
 * report.writeXML(outputStream);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DMARCValidator
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489#section-7.1">RFC 7489 §7.1</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489#appendix-C">RFC 7489 Appendix C — XML Schema</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9990">RFC 9990 — DMARCbis Aggregate Reports</a>
 */
public class DMARCAggregateReport {

    private String reporterOrgName;
    private String reporterEmail;
    private String reporterExtraContactInfo;
    private String reportId;
    private long dateRangeBegin;
    private long dateRangeEnd;
    /** RFC 9990 — optional report_metadata/generator (reporting software + version). */
    private String generator;

    /**
     * RFC 9990 — the single Policy Domain this report covers, established
     * by the first {@link #addResult} call and enforced against every
     * subsequent one.
     */
    private String reportDomain;

    private final Map<String, DomainReport> domainReports = new HashMap<>();

    /** Sets the reporting organization name (RFC 7489 §7.1 — report_metadata/org_name). */
    public void setReporterOrgName(String name) {
        this.reporterOrgName = name;
    }

    /** Sets the reporter's email (RFC 7489 §7.1 — report_metadata/email). */
    public void setReporterEmail(String email) {
        this.reporterEmail = email;
    }

    /** Sets optional extra contact info (RFC 7489 §7.1 — report_metadata/extra_contact_info). */
    public void setReporterExtraContactInfo(String info) {
        this.reporterExtraContactInfo = info;
    }

    /** Sets the unique report identifier (RFC 7489 §7.1 — report_metadata/report_id). */
    public void setReportId(String id) {
        this.reportId = id;
    }

    /**
     * Sets the reporting period (RFC 7489 §7.1 — report_metadata/date_range).
     *
     * @param begin start of period, Unix timestamp (seconds)
     * @param end end of period, Unix timestamp (seconds)
     */
    public void setDateRange(long begin, long end) {
        this.dateRangeBegin = begin;
        this.dateRangeEnd = end;
    }

    /**
     * RFC 9990 — sets the optional report_metadata/generator field
     * (identifies the reporting software and version).
     */
    public void setGenerator(String generator) {
        this.generator = generator;
    }

    /**
     * RFC 7489 §7.1 — records a single message's authentication results.
     *
     * <p>Results are aggregated by (source IP, from domain, disposition) into
     * count-based rows as required by the aggregate report schema.
     *
     * <p>Equivalent to calling the FEAT-001/FEAT-002-aware overload with
     * {@code np=null}, {@code testing="n"}, {@code pct=100}, and
     * {@code discoveryMethod=null} — for RFC 9989/9990-aware policy
     * reporting, use {@link #addResult(String, String, DMARCPolicy, String,
     * String, DMARCResult, SPFResult, String, DKIMResult, String, String,
     * DMARCPolicy, String, int, String)} instead, typically passing values
     * straight from the {@link DMARCValidator} that produced this result
     * ({@code getLastNp()}, {@code getLastT()}, {@code getLastDiscoveryMethod()}).
     *
     * @param sourceIP the connecting MTA IP address
     * @param headerFrom the RFC5322.From domain
     * @param policy the published DMARC policy
     * @param adkim DKIM alignment mode ("r" or "s")
     * @param aspf SPF alignment mode ("r" or "s")
     * @param dmarcResult the overall DMARC evaluation result
     * @param spfResult the SPF evaluation result
     * @param spfDomain the domain checked by SPF (envelope sender)
     * @param dkimResult the DKIM verification result
     * @param dkimDomain the DKIM signing domain (d=)
     * @param dkimSelector the DKIM selector (s=), may be null
     */
    public void addResult(String sourceIP, String headerFrom,
                       DMARCPolicy policy, String adkim, String aspf,
                       DMARCResult dmarcResult,
                       SPFResult spfResult, String spfDomain,
                       DKIMResult dkimResult, String dkimDomain,
                       String dkimSelector) {
        addResult(sourceIP, headerFrom, policy, adkim, aspf, dmarcResult,
                spfResult, spfDomain, dkimResult, dkimDomain, dkimSelector,
                null, "n", 100, null);
    }

    /**
     * RFC 7489 §7.1, RFC 9989/9990 — records a single message's
     * authentication results, including the DMARCbis policy fields.
     *
     * @param sourceIP the connecting MTA IP address
     * @param headerFrom the RFC5322.From domain
     * @param policy the published DMARC policy
     * @param adkim DKIM alignment mode ("r" or "s")
     * @param aspf SPF alignment mode ("r" or "s")
     * @param dmarcResult the overall DMARC evaluation result
     * @param spfResult the SPF evaluation result
     * @param spfDomain the domain checked by SPF (envelope sender)
     * @param dkimResult the DKIM verification result
     * @param dkimDomain the DKIM signing domain (d=)
     * @param dkimSelector the DKIM selector (s=), may be null
     * @param np the np= policy for non-existent subdomains, may be null
     * @param testing the t= tag value ("y"/"n"), may be null (treated as "n")
     * @param pct the pct= sampling percentage (0-100, RFC 7489 backward
     *        compatibility only — see the class Javadoc)
     * @param discoveryMethod how the record was found ("author", "psl", or
     *        "treewalk" — see {@link DMARCValidator#getLastDiscoveryMethod()}),
     *        may be null
     * @throws IllegalStateException if {@code headerFrom} differs from the
     *         Policy Domain already established by an earlier call (RFC
     *         9990 requires a single report to cover exactly one domain)
     */
    public void addResult(String sourceIP, String headerFrom,
                       DMARCPolicy policy, String adkim, String aspf,
                       DMARCResult dmarcResult,
                       SPFResult spfResult, String spfDomain,
                       DKIMResult dkimResult, String dkimDomain,
                       String dkimSelector,
                       DMARCPolicy np, String testing, int pct, String discoveryMethod) {

        if (reportDomain == null) {
            reportDomain = headerFrom;
        } else if (!reportDomain.equals(headerFrom)) {
            throw new IllegalStateException("RFC 9990 requires a single aggregate report to "
                    + "cover exactly one Policy Domain; this report already covers '"
                    + reportDomain + "', got '" + headerFrom + "' - use a separate "
                    + "DMARCAggregateReport instance per domain");
        }

        DomainReport dr = domainReports.get(headerFrom);
        if (dr == null) {
            dr = new DomainReport(headerFrom, policy, adkim, aspf, np, testing, pct, discoveryMethod);
            domainReports.put(headerFrom, dr);
        }

        String disposition = dispositionFor(dmarcResult, policy);
        String rowKey = sourceIP + "|" + disposition + "|" + dmarcResult.getValue();
        RecordRow row = dr.rows.get(rowKey);
        if (row == null) {
            row = new RecordRow();
            row.sourceIP = sourceIP;
            row.disposition = disposition;
            row.dmarcResult = dmarcResult;
            dr.rows.put(rowKey, row);
        }
        row.count++;

        row.spfResults.add(new AuthResult(spfDomain, spfResult.getValue()));
        row.dkimResults.add(new AuthResult(dkimDomain, dkimResult.getValue(),
                dkimDomain, dkimSelector));
    }

    /**
     * Returns the number of distinct domains that have recorded results.
     */
    public int getDomainCount() {
        return domainReports.size();
    }

    /**
     * RFC 7489 §7.1, Appendix C — writes the aggregate report as XML.
     *
     * @param out the output stream to write to
     * @throws IOException if an I/O error occurs
     */
    public void writeXML(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<feedback>\n");

        writeReportMetadata(w);

        for (DomainReport dr : domainReports.values()) {
            writePolicyPublished(w, dr);
            for (RecordRow row : dr.rows.values()) {
                writeRecord(w, row, dr.domain);
            }
        }

        w.write("</feedback>\n");
        w.flush();
    }

    /** RFC 7489 Appendix C — report_metadata element. */
    private void writeReportMetadata(Writer w) throws IOException {
        w.write("  <report_metadata>\n");
        writeElement(w, "    ", "org_name", reporterOrgName);
        writeElement(w, "    ", "email", reporterEmail);
        if (reporterExtraContactInfo != null) {
            writeElement(w, "    ", "extra_contact_info", reporterExtraContactInfo);
        }
        writeElement(w, "    ", "report_id", reportId);
        w.write("    <date_range>\n");
        writeElement(w, "      ", "begin", String.valueOf(dateRangeBegin));
        writeElement(w, "      ", "end", String.valueOf(dateRangeEnd));
        w.write("    </date_range>\n");
        if (generator != null) {
            // RFC 9990 — optional generator field.
            writeElement(w, "    ", "generator", generator);
        }
        w.write("  </report_metadata>\n");
    }

    /** RFC 7489 Appendix C / RFC 9990 — policy_published element. */
    private void writePolicyPublished(Writer w, DomainReport dr) throws IOException {
        w.write("  <policy_published>\n");
        writeElement(w, "    ", "domain", dr.domain);
        writeElement(w, "    ", "adkim", dr.adkim);
        writeElement(w, "    ", "aspf", dr.aspf);
        writeElement(w, "    ", "p", dr.policy != null ? dr.policy.toString().toLowerCase() : "none");
        if (dr.np != null) {
            // RFC 9990 — policy for non-existent subdomains.
            writeElement(w, "    ", "np", dr.np.toString().toLowerCase());
        }
        // RFC 7489 backward compatibility only - see class Javadoc for why
        // this is retained despite RFC 9990 dropping it.
        writeElement(w, "    ", "pct", String.valueOf(dr.pct));
        // RFC 9990 additions.
        writeElement(w, "    ", "testing", dr.testing != null ? dr.testing : "n");
        if (dr.discoveryMethod != null) {
            writeElement(w, "    ", "discovery_method", dr.discoveryMethod);
        }
        w.write("  </policy_published>\n");
    }

    /** RFC 7489 Appendix C — record element. */
    private void writeRecord(Writer w, RecordRow row, String headerFrom) throws IOException {
        w.write("  <record>\n");

        w.write("    <row>\n");
        writeElement(w, "      ", "source_ip", row.sourceIP);
        writeElement(w, "      ", "count", String.valueOf(row.count));
        w.write("      <policy_evaluated>\n");
        writeElement(w, "        ", "disposition", row.disposition);
        writeElement(w, "        ", "dkim",
                hasDkimPass(row) ? "pass" : "fail");
        writeElement(w, "        ", "spf",
                hasSpfPass(row) ? "pass" : "fail");
        w.write("      </policy_evaluated>\n");
        w.write("    </row>\n");

        w.write("    <identifiers>\n");
        writeElement(w, "      ", "header_from", headerFrom);
        w.write("    </identifiers>\n");

        w.write("    <auth_results>\n");
        writeUniqueSpfResults(w, row);
        writeUniqueDkimResults(w, row);
        w.write("    </auth_results>\n");

        w.write("  </record>\n");
    }

    private void writeUniqueSpfResults(Writer w, RecordRow row) throws IOException {
        List<String> seen = new ArrayList<>();
        for (AuthResult ar : row.spfResults) {
            String key = ar.domain + "|" + ar.result;
            if (!seen.contains(key)) {
                seen.add(key);
                w.write("      <spf>\n");
                writeElement(w, "        ", "domain", ar.domain);
                writeElement(w, "        ", "result", ar.result);
                w.write("      </spf>\n");
            }
        }
    }

    private void writeUniqueDkimResults(Writer w, RecordRow row) throws IOException {
        List<String> seen = new ArrayList<>();
        for (AuthResult ar : row.dkimResults) {
            String key = ar.domain + "|" + ar.result + "|" + ar.signingDomain + "|" + ar.selector;
            if (!seen.contains(key)) {
                seen.add(key);
                w.write("      <dkim>\n");
                writeElement(w, "        ", "domain", ar.domain);
                writeElement(w, "        ", "result", ar.result);
                if (ar.signingDomain != null) {
                    writeElement(w, "        ", "human_result", ar.signingDomain);
                }
                // RFC 9990 — selector is now required (not just best-effort)
                // for a reported DKIM signature; emit it even if unknown.
                writeElement(w, "        ", "selector", ar.selector != null ? ar.selector : "");
                w.write("      </dkim>\n");
            }
        }
    }

    private boolean hasDkimPass(RecordRow row) {
        for (AuthResult ar : row.dkimResults) {
            if ("pass".equals(ar.result)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSpfPass(RecordRow row) {
        for (AuthResult ar : row.spfResults) {
            if ("pass".equals(ar.result)) {
                return true;
            }
        }
        return false;
    }

    private static String dispositionFor(DMARCResult result, DMARCPolicy policy) {
        if (result == DMARCResult.PASS || policy == null || policy == DMARCPolicy.NONE) {
            return "none";
        }
        if (policy == DMARCPolicy.REJECT) {
            return "reject";
        }
        if (policy == DMARCPolicy.QUARANTINE) {
            return "quarantine";
        }
        return "none";
    }

    private static void writeElement(Writer w, String indent, String name, String value)
            throws IOException {
        w.write(indent);
        w.write("<");
        w.write(name);
        w.write(">");
        if (value != null) {
            w.write(escapeXml(value));
        }
        w.write("</");
        w.write(name);
        w.write(">\n");
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // -- Inner classes --

    private static class DomainReport {
        final String domain;
        final DMARCPolicy policy;
        final String adkim;
        final String aspf;
        final DMARCPolicy np;
        final String testing;
        final int pct;
        final String discoveryMethod;
        final Map<String, RecordRow> rows = new HashMap<>();

        DomainReport(String domain, DMARCPolicy policy, String adkim, String aspf,
                     DMARCPolicy np, String testing, int pct, String discoveryMethod) {
            this.domain = domain;
            this.policy = policy;
            this.adkim = adkim != null ? adkim : "r";
            this.aspf = aspf != null ? aspf : "r";
            this.np = np;
            this.testing = testing;
            this.pct = pct;
            this.discoveryMethod = discoveryMethod;
        }
    }

    private static class RecordRow {
        String sourceIP;
        String disposition;
        DMARCResult dmarcResult;
        int count;
        final List<AuthResult> spfResults = new ArrayList<>();
        final List<AuthResult> dkimResults = new ArrayList<>();
    }

    private static class AuthResult {
        final String domain;
        final String result;
        final String signingDomain;
        final String selector;

        AuthResult(String domain, String result) {
            this(domain, result, null, null);
        }

        AuthResult(String domain, String result, String signingDomain, String selector) {
            this.domain = domain;
            this.result = result;
            this.signingDomain = signingDomain;
            this.selector = selector;
        }
    }

}
