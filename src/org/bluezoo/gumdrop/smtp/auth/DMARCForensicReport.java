/*
 * DMARCForensicReport.java
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RFC 7489 §7.2 — DMARC forensic (failure) report generator.
 *
 * <p>Generates a per-message failure report in the Abuse Reporting Format
 * (ARF, RFC 5965) with DMARC-specific extensions (RFC 6591). The report
 * is a {@code multipart/report} MIME message with three parts:
 *
 * <ol>
 *   <li>A human-readable text description of the failure</li>
 *   <li>A machine-readable {@code message/feedback-report} part containing
 *       structured fields per RFC 5965 §3.1 and RFC 6591 §3</li>
 *   <li>The original message headers (as {@code text/rfc822-headers}) or
 *       the full original message (as {@code message/rfc822})</li>
 * </ol>
 *
 * <p>Forensic reports are sent to the domain owner's {@code ruf=} address
 * when the DMARC record's {@code fo=} tag conditions are met:
 * <ul>
 *   <li>{@code fo=0} (default) — generate report if all mechanisms fail</li>
 *   <li>{@code fo=1} — generate report if any mechanism fails</li>
 *   <li>{@code fo=d} — generate report if DKIM fails (regardless of alignment)</li>
 *   <li>{@code fo=s} — generate report if SPF fails (regardless of alignment)</li>
 * </ul>
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * DMARCForensicReport report = new DMARCForensicReport();
 * report.setReporterDomain("receiver.example.com");
 * report.setReporterEmail("postmaster@receiver.example.com");
 * report.setSourceIP("192.0.2.1");
 * report.setHeaderFrom("sender.example.com");
 * report.setEnvelopeFrom("bounce@sender.example.com");
 * report.setEnvelopeTo("user@receiver.example.com");
 * report.setAuthResults("dmarc=fail (p=reject) header.from=sender.example.com");
 * report.setDmarcResult(DMARCResult.FAIL);
 * report.setDmarcPolicy(DMARCPolicy.REJECT);
 * report.setSpfResult(SPFResult.FAIL);
 * report.setDkimResult(DKIMResult.FAIL);
 * report.setOriginalHeaders(headerText);
 *
 * report.writeMIME(outputStream, boundary);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DMARCValidator
 * @see DMARCAggregateReport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489#section-7.2">RFC 7489 §7.2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5965">RFC 5965 — ARF</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6591">RFC 6591 — ARF Extensions for Auth Failures</a>
 */
public class DMARCForensicReport {

    private static final String CRLF = "\r\n";

    private String reporterDomain;
    private String reporterEmail;
    private String sourceIP;
    private String headerFrom;
    private String envelopeFrom;
    private String envelopeTo;
    private String authResults;
    private DMARCResult dmarcResult;
    private DMARCPolicy dmarcPolicy;
    private SPFResult spfResult;
    private DKIMResult dkimResult;
    private String dkimDomain;
    private String originalHeaders;
    private String originalMessage;
    private String messageId;
    private String subject;
    private Date arrivalDate;

    /** Sets the reporting domain (RFC 5965 §3.1 — Reported-Domain). */
    public void setReporterDomain(String domain) {
        this.reporterDomain = domain;
    }

    /** Sets the reporter's email for the From header of the report message. */
    public void setReporterEmail(String email) {
        this.reporterEmail = email;
    }

    /** Sets the source IP of the sending MTA (RFC 5965 §3.1 — Source-IP). */
    public void setSourceIP(String ip) {
        this.sourceIP = ip;
    }

    /** Sets the RFC5322.From domain of the original message. */
    public void setHeaderFrom(String from) {
        this.headerFrom = from;
    }

    /** Sets the envelope sender (MAIL FROM) of the original message. */
    public void setEnvelopeFrom(String from) {
        this.envelopeFrom = from;
    }

    /** Sets the envelope recipient (RCPT TO) of the original message. */
    public void setEnvelopeTo(String to) {
        this.envelopeTo = to;
    }

    /**
     * Sets the Authentication-Results header value
     * (RFC 5965 §3.1 — Authentication-Results).
     */
    public void setAuthResults(String results) {
        this.authResults = results;
    }

    /** Sets the DMARC evaluation result. */
    public void setDmarcResult(DMARCResult result) {
        this.dmarcResult = result;
    }

    /** Sets the DMARC policy from the domain's record. */
    public void setDmarcPolicy(DMARCPolicy policy) {
        this.dmarcPolicy = policy;
    }

    /** Sets the SPF evaluation result. */
    public void setSpfResult(SPFResult result) {
        this.spfResult = result;
    }

    /** Sets the DKIM verification result. */
    public void setDkimResult(DKIMResult result) {
        this.dkimResult = result;
    }

    /** Sets the DKIM signing domain (d= tag), if available. */
    public void setDkimDomain(String domain) {
        this.dkimDomain = domain;
    }

    /**
     * Sets the original message headers for inclusion as
     * {@code text/rfc822-headers} (third MIME part).
     */
    public void setOriginalHeaders(String headers) {
        this.originalHeaders = headers;
    }

    /**
     * Sets the full original message for inclusion as {@code message/rfc822}
     * instead of headers only. If set, this takes precedence over
     * {@link #setOriginalHeaders}.
     */
    public void setOriginalMessage(String message) {
        this.originalMessage = message;
    }

    /** Sets the Message-ID of the original message. */
    public void setMessageId(String id) {
        this.messageId = id;
    }

    /** Sets the Subject of the original message. */
    public void setSubject(String subj) {
        this.subject = subj;
    }

    /** Sets the arrival date of the original message. */
    public void setArrivalDate(Date date) {
        this.arrivalDate = date;
    }

    /**
     * RFC 7489 §7.2 — determines whether a forensic report should be
     * generated, based on the {@code fo=} tag value and the authentication
     * results.
     *
     * @param foTag the fo= tag value from the DMARC record ("0", "1", "d", "s"),
     *              or null for the default behaviour (fo=0)
     * @param spf the SPF result for this message
     * @param dkim the DKIM result for this message
     * @param dmarc the DMARC result for this message
     * @return true if a forensic report should be generated
     */
    public static boolean shouldReport(String foTag, SPFResult spf,
                                       DKIMResult dkim, DMARCResult dmarc) {
        if (foTag == null || foTag.isEmpty()) {
            foTag = "0";
        }

        for (int i = 0; i < foTag.length(); i++) {
            char c = foTag.charAt(i);
            if (c == ':') {
                continue;
            }
            switch (c) {
                case '0':
                    // All mechanisms fail to produce an aligned pass
                    if (dmarc == DMARCResult.FAIL) {
                        return true;
                    }
                    break;
                case '1':
                    // Any mechanism fails
                    if (spf != SPFResult.PASS || dkim != DKIMResult.PASS) {
                        return true;
                    }
                    break;
                case 'd':
                    // DKIM signature fails verification
                    if (dkim == DKIMResult.FAIL) {
                        return true;
                    }
                    break;
                case 's':
                    // SPF fails
                    if (spf == SPFResult.FAIL) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * RFC 7489 §7.2, RFC 5965 §3, RFC 6591 §3 — writes the forensic report
     * as a {@code multipart/report} MIME message body.
     *
     * <p>The caller is responsible for wrapping this in appropriate SMTP
     * envelope headers (To: ruf address, From: reporter, Subject, etc.)
     * and for the outer Content-Type with the boundary parameter.
     *
     * @param out the output stream to write to
     * @param boundary the MIME boundary string to use
     * @throws IOException if an I/O error occurs
     */
    public void writeMIME(OutputStream out, String boundary) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        // Part 1: Human-readable description
        w.write("--" + boundary + CRLF);
        w.write("Content-Type: text/plain; charset=utf-8" + CRLF);
        w.write(CRLF);
        writeHumanReadable(w);
        w.write(CRLF);

        // Part 2: Machine-readable feedback report (RFC 5965 §3.1, RFC 6591 §3)
        w.write("--" + boundary + CRLF);
        w.write("Content-Type: message/feedback-report" + CRLF);
        w.write(CRLF);
        writeFeedbackReport(w);
        w.write(CRLF);

        // Part 3: Original message or headers
        writeOriginalPart(w, boundary);

        // Closing boundary
        w.write("--" + boundary + "--" + CRLF);
        w.flush();
    }

    /**
     * Returns the Content-Type header value for the outer message.
     *
     * @param boundary the MIME boundary string
     * @return the Content-Type value
     */
    public String getContentType(String boundary) {
        return "multipart/report; report-type=feedback-report; boundary=\"" + boundary + "\"";
    }

    /** RFC 5965 §3.1 — human-readable part describing the failure. */
    private void writeHumanReadable(Writer w) throws IOException {
        w.write("This is a DMARC failure report for a message received from ");
        w.write(sourceIP != null ? sourceIP : "unknown");
        w.write("." + CRLF);
        w.write(CRLF);
        if (headerFrom != null) {
            w.write("The message claimed to be from: " + headerFrom + CRLF);
        }
        if (dmarcResult != null) {
            w.write("DMARC result: " + dmarcResult.getValue());
            if (dmarcPolicy != null) {
                w.write(" (policy=" + dmarcPolicy.toString().toLowerCase() + ")");
            }
            w.write(CRLF);
        }
        if (spfResult != null) {
            w.write("SPF result: " + spfResult.getValue() + CRLF);
        }
        if (dkimResult != null) {
            w.write("DKIM result: " + dkimResult.getValue() + CRLF);
        }
    }

    /**
     * RFC 5965 §3.1, RFC 6591 §3 — machine-readable feedback report fields.
     */
    private void writeFeedbackReport(Writer w) throws IOException {
        // RFC 5965 §3.1 — required fields
        w.write("Feedback-Type: auth-failure" + CRLF);
        w.write("User-Agent: gumdrop/DMARC" + CRLF);
        w.write("Version: 1" + CRLF);

        // RFC 6591 §3 — Auth-Failure field
        if (dmarcResult == DMARCResult.FAIL) {
            w.write("Auth-Failure: dmarc" + CRLF);
        }

        if (sourceIP != null) {
            w.write("Source-IP: " + sourceIP + CRLF);
        }

        if (reporterDomain != null) {
            w.write("Reported-Domain: " + reporterDomain + CRLF);
        }

        if (reporterEmail != null) {
            w.write("Report-Contact: " + reporterEmail + CRLF);
        }

        // RFC 6591 §3 — policy and disposition
        if (dmarcPolicy != null) {
            w.write("Delivery-Result: ");
            switch (dmarcPolicy) {
                case REJECT:
                    w.write("reject");
                    break;
                case QUARANTINE:
                    w.write("smg-quarantine");
                    break;
                default:
                    w.write("delivered");
                    break;
            }
            w.write(CRLF);
        }

        if (headerFrom != null) {
            w.write("Identity-Alignment: " +
                    (dmarcResult == DMARCResult.PASS ? "dkim" : "none") + CRLF);
        }

        if (authResults != null) {
            w.write("Authentication-Results: " + authResults + CRLF);
        }

        // RFC 6591 §3 — original envelope attributes
        if (envelopeFrom != null) {
            w.write("Original-Envelope-Id: " + envelopeFrom + CRLF);
        }
        if (envelopeTo != null) {
            w.write("Original-Rcpt-To: " + envelopeTo + CRLF);
        }

        if (arrivalDate != null) {
            SimpleDateFormat sdf = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            w.write("Arrival-Date: " + sdf.format(arrivalDate) + CRLF);
        }

        if (messageId != null) {
            w.write("Original-Mail-From: " + (envelopeFrom != null ? envelopeFrom : "") + CRLF);
        }

        // RFC 6591 §3 — DKIM-specific
        if (dkimDomain != null) {
            w.write("DKIM-Domain: " + dkimDomain + CRLF);
        }

        if (subject != null) {
            w.write("Reported-URI: mid:" + (messageId != null ? messageId : "") + CRLF);
        }
    }

    /** Part 3: Original message headers or full message. */
    private void writeOriginalPart(Writer w, String boundary) throws IOException {
        if (originalMessage != null) {
            // Full original message
            w.write("--" + boundary + CRLF);
            w.write("Content-Type: message/rfc822" + CRLF);
            w.write(CRLF);
            w.write(originalMessage);
            w.write(CRLF);
        } else if (originalHeaders != null) {
            // Headers only (privacy-preserving)
            w.write("--" + boundary + CRLF);
            w.write("Content-Type: text/rfc822-headers" + CRLF);
            w.write(CRLF);
            w.write(originalHeaders);
            w.write(CRLF);
        }
    }

}
