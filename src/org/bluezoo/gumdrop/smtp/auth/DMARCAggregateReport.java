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
 * RFC 7489 §7.1 — DMARC aggregate report generator.
 *
 * <p>Collects per-domain authentication results over a reporting period and
 * produces an XML aggregate report that conforms to the DMARC report schema
 * defined in RFC 7489 Appendix C. The report is intended to be gzip-compressed
 * and sent to the domain owner's {@code rua=} address.
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
 */
public class DMARCAggregateReport {

    private String reporterOrgName;
    private String reporterEmail;
    private String reporterExtraContactInfo;
    private String reportId;
    private long dateRangeBegin;
    private long dateRangeEnd;

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
     * RFC 7489 §7.1 — records a single message's authentication results.
     *
     * <p>Results are aggregated by (source IP, from domain, disposition) into
     * count-based rows as required by the aggregate report schema.
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

        DomainReport dr = domainReports.get(headerFrom);
        if (dr == null) {
            dr = new DomainReport(headerFrom, policy, adkim, aspf);
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
        w.write("  </report_metadata>\n");
    }

    /** RFC 7489 Appendix C — policy_published element. */
    private void writePolicyPublished(Writer w, DomainReport dr) throws IOException {
        w.write("  <policy_published>\n");
        writeElement(w, "    ", "domain", dr.domain);
        writeElement(w, "    ", "adkim", dr.adkim);
        writeElement(w, "    ", "aspf", dr.aspf);
        writeElement(w, "    ", "p", dr.policy != null ? dr.policy.toString().toLowerCase() : "none");
        writeElement(w, "    ", "pct", "100");
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
                if (ar.selector != null) {
                    writeElement(w, "        ", "selector", ar.selector);
                }
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
        final Map<String, RecordRow> rows = new HashMap<>();

        DomainReport(String domain, DMARCPolicy policy, String adkim, String aspf) {
            this.domain = domain;
            this.policy = policy;
            this.adkim = adkim != null ? adkim : "r";
            this.aspf = aspf != null ? aspf : "r";
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
