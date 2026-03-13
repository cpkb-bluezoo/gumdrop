/*
 * DMARCForensicReportTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;

/**
 * Unit tests for {@link DMARCForensicReport} — DMARC forensic/failure reporting
 * (RFC 7489 §7.2, RFC 5965, RFC 6591).
 */
public class DMARCForensicReportTest {

    // -- shouldReport() tests --

    @Test
    public void testShouldReportFo0AllFail() {
        assertTrue(DMARCForensicReport.shouldReport("0",
                SPFResult.FAIL, DKIMResult.FAIL, DMARCResult.FAIL));
    }

    @Test
    public void testShouldReportFo0PassDoesNotTrigger() {
        assertFalse(DMARCForensicReport.shouldReport("0",
                SPFResult.PASS, DKIMResult.PASS, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportFo1AnyFail() {
        assertTrue(DMARCForensicReport.shouldReport("1",
                SPFResult.PASS, DKIMResult.FAIL, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportFo1AllPass() {
        assertFalse(DMARCForensicReport.shouldReport("1",
                SPFResult.PASS, DKIMResult.PASS, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportFoDkimOnly() {
        assertTrue(DMARCForensicReport.shouldReport("d",
                SPFResult.PASS, DKIMResult.FAIL, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportFoDkimPassNoTrigger() {
        assertFalse(DMARCForensicReport.shouldReport("d",
                SPFResult.FAIL, DKIMResult.PASS, DMARCResult.FAIL));
    }

    @Test
    public void testShouldReportFoSpfOnly() {
        assertTrue(DMARCForensicReport.shouldReport("s",
                SPFResult.FAIL, DKIMResult.PASS, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportFoSpfPassNoTrigger() {
        assertFalse(DMARCForensicReport.shouldReport("s",
                SPFResult.PASS, DKIMResult.FAIL, DMARCResult.FAIL));
    }

    @Test
    public void testShouldReportNullFoDefaultsTo0() {
        assertTrue(DMARCForensicReport.shouldReport(null,
                SPFResult.FAIL, DKIMResult.FAIL, DMARCResult.FAIL));
        assertFalse(DMARCForensicReport.shouldReport(null,
                SPFResult.PASS, DKIMResult.PASS, DMARCResult.PASS));
    }

    @Test
    public void testShouldReportColonSeparatedFo() {
        // fo=0:d — triggers on all-fail OR dkim-fail
        assertTrue(DMARCForensicReport.shouldReport("0:d",
                SPFResult.PASS, DKIMResult.FAIL, DMARCResult.PASS));
    }

    // -- MIME output tests --

    @Test
    public void testWriteMIMEContainsBoundaries() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "BOUNDARY123");

        assertTrue(mime.contains("--BOUNDARY123\r\n"));
        assertTrue(mime.contains("--BOUNDARY123--\r\n"));
    }

    @Test
    public void testWriteMIMEContainsHumanReadable() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: text/plain"));
        assertTrue(mime.contains("DMARC failure report"));
        assertTrue(mime.contains("192.0.2.1"));
    }

    @Test
    public void testWriteMIMEContainsFeedbackReport() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: message/feedback-report"));
        assertTrue(mime.contains("Feedback-Type: auth-failure"));
        assertTrue(mime.contains("User-Agent: gumdrop/DMARC"));
        assertTrue(mime.contains("Version: 1"));
    }

    @Test
    public void testWriteMIMEContainsSourceIP() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Source-IP: 192.0.2.1"));
    }

    @Test
    public void testWriteMIMEContainsReportedDomain() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Reported-Domain: receiver.example.com"));
    }

    @Test
    public void testWriteMIMEContainsOriginalHeaders() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: text/rfc822-headers"));
        assertTrue(mime.contains("From: sender@bad.example.com"));
    }

    @Test
    public void testWriteMIMEWithFullMessage() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setOriginalMessage("From: sender@bad.example.com\r\nSubject: Test\r\n\r\nBody");
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: message/rfc822"));
        assertTrue(mime.contains("Body"));
    }

    @Test
    public void testWriteMIMEContainsAuthFailure() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Auth-Failure: dmarc"));
    }

    @Test
    public void testDeliveryResultReject() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setDmarcPolicy(DMARCPolicy.REJECT);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: reject"));
    }

    @Test
    public void testDeliveryResultQuarantine() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setDmarcPolicy(DMARCPolicy.QUARANTINE);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: smg-quarantine"));
    }

    @Test
    public void testDeliveryResultNone() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setDmarcPolicy(DMARCPolicy.NONE);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: delivered"));
    }

    @Test
    public void testContentTypeHeader() {
        DMARCForensicReport report = new DMARCForensicReport();
        String ct = report.getContentType("myboundary");
        assertEquals("multipart/report; report-type=feedback-report; boundary=\"myboundary\"", ct);
    }

    @Test
    public void testHumanReadableShowsResults() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("DMARC result: fail"));
        assertTrue(mime.contains("SPF result: fail"));
        assertTrue(mime.contains("DKIM result: fail"));
        assertTrue(mime.contains("claimed to be from: bad.example.com"));
    }

    @Test
    public void testEnvelopeFields() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Original-Rcpt-To: user@receiver.example.com"));
    }

    @Test
    public void testDkimDomainIncluded() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setDkimDomain("bad.example.com");
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("DKIM-Domain: bad.example.com"));
    }

    @Test
    public void testArrivalDateIncluded() throws IOException {
        DMARCForensicReport report = createTestReport();
        report.setArrivalDate(new Date(1700000000000L));
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Arrival-Date:"));
    }

    @Test
    public void testAuthResultsIncluded() throws IOException {
        DMARCForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Authentication-Results: dmarc=fail header.from=bad.example.com"));
    }

    // -- fo= and ruf= parsing tests --

    @Test
    public void testParseFoTag() throws Exception {
        java.lang.reflect.Method method =
                DMARCValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DMARCValidator validator = new DMARCValidator(null);
        DMARCValidator.DMARCRecord rec = (DMARCValidator.DMARCRecord)
                method.invoke(validator, "v=DMARC1; p=reject; fo=1; ruf=mailto:f@example.com");

        assertNotNull(rec);
        assertEquals("1", rec.fo);
        assertNotNull(rec.ruf);
        assertEquals("mailto:f@example.com", rec.ruf.get(0));
    }

    @Test
    public void testParseRfTag() throws Exception {
        java.lang.reflect.Method method =
                DMARCValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DMARCValidator validator = new DMARCValidator(null);
        DMARCValidator.DMARCRecord rec = (DMARCValidator.DMARCRecord)
                method.invoke(validator, "v=DMARC1; p=none; rf=afrf");

        assertNotNull(rec);
        assertEquals("afrf", rec.rf);
    }

    @Test
    public void testDefaultFoAndRf() throws Exception {
        java.lang.reflect.Method method =
                DMARCValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DMARCValidator validator = new DMARCValidator(null);
        DMARCValidator.DMARCRecord rec = (DMARCValidator.DMARCRecord)
                method.invoke(validator, "v=DMARC1; p=none");

        assertNotNull(rec);
        assertEquals("0", rec.fo);
        assertEquals("afrf", rec.rf);
    }

    // -- Helpers --

    private DMARCForensicReport createTestReport() {
        DMARCForensicReport report = new DMARCForensicReport();
        report.setReporterDomain("receiver.example.com");
        report.setReporterEmail("postmaster@receiver.example.com");
        report.setSourceIP("192.0.2.1");
        report.setHeaderFrom("bad.example.com");
        report.setEnvelopeFrom("bounce@bad.example.com");
        report.setEnvelopeTo("user@receiver.example.com");
        report.setAuthResults("dmarc=fail header.from=bad.example.com");
        report.setDmarcResult(DMARCResult.FAIL);
        report.setDmarcPolicy(DMARCPolicy.REJECT);
        report.setSpfResult(SPFResult.FAIL);
        report.setDkimResult(DKIMResult.FAIL);
        report.setOriginalHeaders("From: sender@bad.example.com\r\nSubject: Test\r\n");
        return report;
    }

    private String writeToString(DMARCForensicReport report, String boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.writeMIME(out, boundary);
        return out.toString("UTF-8");
    }

}
