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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

/**
 * Unit tests for {@link DmarcForensicReport} — DMARC forensic/failure reporting
 * (RFC 7489 §7.2, RFC 5965, RFC 6591).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DMARCForensicReportTest {

    // -- shouldReport() tests --

    @Test
    public void testShouldReportFo0AllFail() {
        assertTrue(DmarcForensicReport.shouldReport("0",
                SpfResult.FAIL, DkimResult.FAIL, DmarcResult.FAIL));
    }

    @Test
    public void testShouldReportFo0PassDoesNotTrigger() {
        assertFalse(DmarcForensicReport.shouldReport("0",
                SpfResult.PASS, DkimResult.PASS, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportFo1AnyFail() {
        assertTrue(DmarcForensicReport.shouldReport("1",
                SpfResult.PASS, DkimResult.FAIL, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportFo1AllPass() {
        assertFalse(DmarcForensicReport.shouldReport("1",
                SpfResult.PASS, DkimResult.PASS, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportFoDkimOnly() {
        assertTrue(DmarcForensicReport.shouldReport("d",
                SpfResult.PASS, DkimResult.FAIL, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportFoDkimPassNoTrigger() {
        assertFalse(DmarcForensicReport.shouldReport("d",
                SpfResult.FAIL, DkimResult.PASS, DmarcResult.FAIL));
    }

    @Test
    public void testShouldReportFoSpfOnly() {
        assertTrue(DmarcForensicReport.shouldReport("s",
                SpfResult.FAIL, DkimResult.PASS, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportFoSpfPassNoTrigger() {
        assertFalse(DmarcForensicReport.shouldReport("s",
                SpfResult.PASS, DkimResult.FAIL, DmarcResult.FAIL));
    }

    @Test
    public void testShouldReportNullFoDefaultsTo0() {
        assertTrue(DmarcForensicReport.shouldReport(null,
                SpfResult.FAIL, DkimResult.FAIL, DmarcResult.FAIL));
        assertFalse(DmarcForensicReport.shouldReport(null,
                SpfResult.PASS, DkimResult.PASS, DmarcResult.PASS));
    }

    @Test
    public void testShouldReportColonSeparatedFo() {
        // fo=0:d — triggers on all-fail OR dkim-fail
        assertTrue(DmarcForensicReport.shouldReport("0:d",
                SpfResult.PASS, DkimResult.FAIL, DmarcResult.PASS));
    }

    // -- MIME output tests --

    @Test
    public void testWriteMIMEContainsBoundaries() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "BOUNDARY123");

        assertTrue(mime.contains("--BOUNDARY123\r\n"));
        assertTrue(mime.contains("--BOUNDARY123--\r\n"));
    }

    @Test
    public void testWriteMIMEContainsHumanReadable() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: text/plain"));
        assertTrue(mime.contains("DMARC failure report"));
        assertTrue(mime.contains("192.0.2.1"));
    }

    @Test
    public void testWriteMIMEContainsFeedbackReport() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: message/feedback-report"));
        assertTrue(mime.contains("Feedback-Type: auth-failure"));
        assertTrue(mime.contains("User-Agent: gumdrop/DMARC"));
        assertTrue(mime.contains("Version: 1"));
    }

    @Test
    public void testWriteMIMEContainsSourceIP() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Source-IP: 192.0.2.1"));
    }

    @Test
    public void testWriteMIMEContainsReportedDomain() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Reported-Domain: receiver.example.com"));
    }

    @Test
    public void testWriteMIMEContainsOriginalHeaders() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: text/rfc822-headers"));
        assertTrue(mime.contains("From: sender@bad.example.com"));
    }

    @Test
    public void testWriteMIMEWithFullMessage() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setOriginalMessage(ByteBuffer.wrap(
                "From: sender@bad.example.com\r\nSubject: Test\r\n\r\nBody"
                        .getBytes(StandardCharsets.US_ASCII)));
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Content-Type: message/rfc822"));
        assertTrue(mime.contains("Body"));
    }

    @Test
    public void testWriteMIMEContainsAuthFailure() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Auth-Failure: dmarc"));
    }

    @Test
    public void testDeliveryResultReject() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setDmarcPolicy(DmarcPolicy.REJECT);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: reject"));
    }

    @Test
    public void testDeliveryResultQuarantine() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setDmarcPolicy(DmarcPolicy.QUARANTINE);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: smg-quarantine"));
    }

    @Test
    public void testDeliveryResultNone() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setDmarcPolicy(DmarcPolicy.NONE);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Delivery-Result: delivered"));
    }

    @Test
    public void testContentTypeHeader() {
        DmarcForensicReport report = new DmarcForensicReport();
        String ct = report.getContentType("myboundary");
        assertEquals("multipart/report; report-type=feedback-report; boundary=\"myboundary\"", ct);
    }

    @Test
    public void testHumanReadableShowsResults() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("DMARC result: fail"));
        assertTrue(mime.contains("SPF result: fail"));
        assertTrue(mime.contains("DKIM result: fail"));
        assertTrue(mime.contains("claimed to be from: bad.example.com"));
    }

    @Test
    public void testEnvelopeFields() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Original-Rcpt-To: user@receiver.example.com"));
    }

    @Test
    public void testDkimDomainIncluded() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setDkimDomain("bad.example.com");
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("DKIM-Domain: bad.example.com"));
    }

    @Test
    public void testArrivalDateIncluded() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setArrivalDate(new Date(1700000000000L));
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Arrival-Date:"));
    }

    @Test
    public void testAuthResultsIncluded() throws IOException {
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Authentication-Results: dmarc=fail header.from=bad.example.com"));
    }

    // -- fo= and ruf= parsing tests --

    @Test
    public void testParseFoTag() throws Exception {
        java.lang.reflect.Method method =
                DmarcValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DmarcValidator validator = new DmarcValidator(null);
        DmarcValidator.DmarcRecord rec = (DmarcValidator.DmarcRecord)
                method.invoke(validator, "v=DMARC1; p=reject; fo=1; ruf=mailto:f@example.com");

        assertNotNull(rec);
        assertEquals("1", rec.fo);
        assertNotNull(rec.ruf);
        assertEquals("mailto:f@example.com", rec.ruf.get(0));
    }

    @Test
    public void testParseRfTag() throws Exception {
        java.lang.reflect.Method method =
                DmarcValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DmarcValidator validator = new DmarcValidator(null);
        DmarcValidator.DmarcRecord rec = (DmarcValidator.DmarcRecord)
                method.invoke(validator, "v=DMARC1; p=none; rf=afrf");

        assertNotNull(rec);
        assertEquals("afrf", rec.rf);
    }

    @Test
    public void testDefaultFoAndRf() throws Exception {
        java.lang.reflect.Method method =
                DmarcValidator.class.getDeclaredMethod("parseDMARCRecord", String.class);
        method.setAccessible(true);
        DmarcValidator validator = new DmarcValidator(null);
        DmarcValidator.DmarcRecord rec = (DmarcValidator.DmarcRecord)
                method.invoke(validator, "v=DMARC1; p=none");

        assertNotNull(rec);
        assertEquals("0", rec.fo);
        assertEquals("afrf", rec.rf);
    }

    // -- Helpers --

    // -- FEAT-002: RFC 9991 Identity-Alignment --

    @Test
    public void testIdentityAlignmentBothFailedApproximatedFromResults() throws IOException {
        // createTestReport() has spfResult=FAIL, dkimResult=FAIL, and
        // never calls setSpfAligned/setDkimAligned - falls back to
        // approximating alignment from the raw pass/fail.
        DmarcForensicReport report = createTestReport();
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Identity-Alignment: dkim,spf"));
    }

    @Test
    public void testIdentityAlignmentNoneWhenBothExplicitlyAligned() throws IOException {
        DmarcForensicReport report = createTestReport();
        report.setSpfAligned(true);
        report.setDkimAligned(true);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Identity-Alignment: none"));
    }

    @Test
    public void testIdentityAlignmentListsOnlyFailedMechanism() throws IOException {
        DmarcForensicReport report = createTestReport();
        // SPF passed and aligned, but DKIM did not - a raw DKIM pass
        // without alignment is exactly the case setSpfAligned/
        // setDkimAligned exist to distinguish from setSpfResult/
        // setDkimResult alone.
        report.setSpfAligned(true);
        report.setDkimAligned(false);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Identity-Alignment: dkim"));
        assertFalse(mime.contains("Identity-Alignment: dkim,spf"));
    }

    @Test
    public void testExplicitAlignmentOverridesRawResultApproximation() throws IOException {
        DmarcForensicReport report = createTestReport();
        // Raw results are both FAIL (from createTestReport()), but if the
        // caller explicitly says both aligned, that must win.
        report.setSpfAligned(true);
        report.setDkimAligned(true);
        String mime = writeToString(report, "B1");

        assertTrue(mime.contains("Identity-Alignment: none"));
    }

    /**
     * {@code writeMIME} must accept any {@code WritableByteChannel}, not
     * just the {@code Channels.newChannel} adapter the other tests use
     * -- verify against a genuine {@link FileChannel}.
     */
    @Test
    public void testWriteMIMEAcceptsARealFileChannel() throws IOException {
        DmarcForensicReport report = createTestReport();
        Path tempFile = Files.createTempFile("dmarc-forensic-", ".eml");
        try {
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                report.writeMIME(channel, "B1");
            }
            String mime = new String(Files.readAllBytes(tempFile), "UTF-8");
            assertTrue(mime.contains("--B1"));
            assertTrue(mime.contains("Content-Type: message/feedback-report"));
            assertTrue(mime.endsWith("--B1--" + "\r\n"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * RFC 6591/RFC 5322: the original message/headers part must be
     * 8-bit clean -- the exact bytes a caller decoded into {@code
     * originalHeaders} must appear unchanged in the MIME output, not
     * re-encoded through UTF-8 (which would turn every byte >= 0x80
     * into a different, multi-byte sequence).
     */
    @Test
    public void testOriginalHeadersAreWrittenByteForByteNotReencoded() throws IOException {
        // Includes bytes >= 0x80 that aren't even a valid UTF-8 sequence
        // together -- if these were ever decoded to a String and
        // re-encoded through any Charset, this exact sequence could not
        // survive unchanged.
        byte[] rawHeaderBytes = new byte[] {
                'F', 'r', 'o', 'm', ':', ' ',
                (byte) 0x80, (byte) 0xE9, (byte) 0xFF,
                '\r', '\n'
        };

        DmarcForensicReport report = createTestReport();
        report.setOriginalHeaders(ByteBuffer.wrap(rawHeaderBytes));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.writeMIME(Channels.newChannel(out), "B1");
        byte[] mime = out.toByteArray();

        assertTrue("Original header bytes must appear verbatim in the "
                        + "output -- this content is wire bytes, not text, "
                        + "and must never be decoded/re-encoded",
                indexOf(mime, rawHeaderBytes) >= 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private DmarcForensicReport createTestReport() {
        DmarcForensicReport report = new DmarcForensicReport();
        report.setReporterDomain("receiver.example.com");
        report.setReporterEmail("postmaster@receiver.example.com");
        report.setSourceIP("192.0.2.1");
        report.setHeaderFrom("bad.example.com");
        report.setEnvelopeFrom("bounce@bad.example.com");
        report.setEnvelopeTo("user@receiver.example.com");
        report.setAuthResults("dmarc=fail header.from=bad.example.com");
        report.setDmarcResult(DmarcResult.FAIL);
        report.setDmarcPolicy(DmarcPolicy.REJECT);
        report.setSpfResult(SpfResult.FAIL);
        report.setDkimResult(DkimResult.FAIL);
        report.setOriginalHeaders(ByteBuffer.wrap(
                "From: sender@bad.example.com\r\nSubject: Test\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));
        return report;
    }

    private String writeToString(DmarcForensicReport report, String boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.writeMIME(Channels.newChannel(out), boundary);
        return out.toString("UTF-8");
    }

}
