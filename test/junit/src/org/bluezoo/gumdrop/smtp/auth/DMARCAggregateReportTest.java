/*
 * DMARCAggregateReportTest.java
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
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Unit tests for {@link DmarcAggregateReport} — DMARC aggregate reporting
 * (RFC 7489 §7.1, Appendix C).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DMARCAggregateReportTest {

    @Test
    public void testReportMetadata() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Receiver Corp");
        report.setReporterEmail("reports@receiver.example.com");
        report.setReportId("rpt-001");
        report.setDateRange(1700000000L, 1700086400L);

        report.addResult("192.0.2.1", "sender.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "sender.example.com",
                DkimResult.PASS, "sender.example.com", "sel1");

        String xml = writeToString(report);

        assertTrue(xml.contains("<org_name>Receiver Corp</org_name>"));
        assertTrue(xml.contains("<email>reports@receiver.example.com</email>"));
        assertTrue(xml.contains("<report_id>rpt-001</report_id>"));
        assertTrue(xml.contains("<begin>1700000000</begin>"));
        assertTrue(xml.contains("<end>1700086400</end>"));
    }

    @Test
    public void testReportContainsXMLDeclaration() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<feedback>"));
        assertTrue(xml.contains("</feedback>"));
    }

    @Test
    public void testPolicyPublished() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("test@test.com");
        report.setReportId("001");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "strict.example.com",
                DmarcPolicy.REJECT, "s", "s",
                DmarcResult.FAIL, SpfResult.FAIL, "strict.example.com",
                DkimResult.FAIL, "strict.example.com", "sel1");

        String xml = writeToString(report);

        assertTrue(xml.contains("<policy_published>"));
        assertTrue(xml.contains("<domain>strict.example.com</domain>"));
        assertTrue(xml.contains("<adkim>s</adkim>"));
        assertTrue(xml.contains("<aspf>s</aspf>"));
        assertTrue(xml.contains("<p>reject</p>"));
    }

    @Test
    public void testRecordRow() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.contains("<record>"));
        assertTrue(xml.contains("<source_ip>192.0.2.1</source_ip>"));
        assertTrue(xml.contains("<count>1</count>"));
        assertTrue(xml.contains("<disposition>none</disposition>"));
        assertTrue(xml.contains("<header_from>sender.example.com</header_from>"));
    }

    @Test
    public void testAuthResults() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.contains("<auth_results>"));
        assertTrue(xml.contains("<spf>"));
        assertTrue(xml.contains("<dkim>"));
    }

    @Test
    public void testCountAggregation() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("002");
        report.setDateRange(0, 1);

        // Two messages from same IP + same disposition → count=2
        for (int i = 0; i < 2; i++) {
            report.addResult("10.0.0.5", "example.com",
                    DmarcPolicy.NONE, "r", "r",
                    DmarcResult.PASS, SpfResult.PASS, "example.com",
                    DkimResult.PASS, "example.com", "sel1");
        }

        String xml = writeToString(report);
        assertTrue(xml.contains("<count>2</count>"));
    }

    @Test
    public void testDifferentIPsCreateSeparateRows() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("003");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", "sel1");

        report.addResult("10.0.0.2", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<source_ip>10.0.0.1</source_ip>"));
        assertTrue(xml.contains("<source_ip>10.0.0.2</source_ip>"));
    }

    @Test
    public void testDispositionReject() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("004");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "bad.example.com",
                DmarcPolicy.REJECT, "r", "r",
                DmarcResult.FAIL, SpfResult.FAIL, "bad.example.com",
                DkimResult.FAIL, "bad.example.com", null);

        String xml = writeToString(report);
        assertTrue(xml.contains("<disposition>reject</disposition>"));
    }

    @Test
    public void testDispositionQuarantine() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("005");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "suspect.example.com",
                DmarcPolicy.QUARANTINE, "r", "r",
                DmarcResult.FAIL, SpfResult.FAIL, "suspect.example.com",
                DkimResult.FAIL, "suspect.example.com", null);

        String xml = writeToString(report);
        assertTrue(xml.contains("<disposition>quarantine</disposition>"));
    }

    @Test
    public void testExtraContactInfo() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReporterExtraContactInfo("https://example.com/dmarc-info");
        report.setReportId("006");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<extra_contact_info>https://example.com/dmarc-info</extra_contact_info>"));
    }

    @Test
    public void testXmlEscaping() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test & <Corp>");
        report.setReporterEmail("t@t.com");
        report.setReportId("007");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("Test &amp; &lt;Corp&gt;"));
    }

    @Test
    public void testDkimSelectorIncluded() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        String xml = writeToString(report);
        assertTrue(xml.contains("<selector>sel1</selector>"));
    }

    @Test
    public void testDomainCount() {
        DmarcAggregateReport report = new DmarcAggregateReport();
        assertEquals(0, report.getDomainCount());

        report.addResult("10.0.0.1", "a.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "a.example.com",
                DkimResult.PASS, "a.example.com", "s");

        assertEquals(1, report.getDomainCount());
    }

    /**
     * FEAT-002: RFC 9990 requires a single aggregate report to cover
     * exactly one Policy Domain - a second, distinct headerFrom must be
     * rejected rather than silently producing a multi-domain report.
     */
    @Test
    public void testSecondDistinctDomainIsRejected() {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.addResult("10.0.0.1", "a.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "a.example.com",
                DkimResult.PASS, "a.example.com", "s");

        try {
            report.addResult("10.0.0.1", "b.example.com",
                    DmarcPolicy.NONE, "r", "r",
                    DmarcResult.PASS, SpfResult.PASS, "b.example.com",
                    DkimResult.PASS, "b.example.com", "s");
            fail("Expected IllegalStateException for a second Policy Domain");
        } catch (IllegalStateException expected) {
            // pass
        }
    }

    /**
     * Repeated results for the SAME domain (the normal case - many
     * messages from one Policy Domain over the reporting period) must
     * still work.
     */
    @Test
    public void testSameDomainRepeatedIsAllowed() {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.addResult("10.0.0.1", "a.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "a.example.com",
                DkimResult.PASS, "a.example.com", "s");
        report.addResult("10.0.0.2", "a.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "a.example.com",
                DkimResult.PASS, "a.example.com", "s");

        assertEquals(1, report.getDomainCount());
    }

    @Test
    public void testDkimPassInPolicyEvaluated() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("008");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.FAIL, "example.com",
                DkimResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<dkim>pass</dkim>"));
        assertTrue(xml.contains("<spf>fail</spf>"));
    }

    @Test
    public void testSpfPassInPolicyEvaluated() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("009");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.FAIL, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<spf>pass</spf>"));
    }

    // -- FEAT-002: RFC 9990 additions --

    @Test
    public void testNpTestingAndDiscoveryMethodInPolicyPublished() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("feat002-1");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", "sel1",
                DmarcPolicy.REJECT, "y", 50, "treewalk");

        String xml = writeToString(report);
        assertTrue(xml.contains("<np>reject</np>"));
        assertTrue(xml.contains("<testing>y</testing>"));
        assertTrue(xml.contains("<discovery_method>treewalk</discovery_method>"));
        // RFC 7489 backward compatibility: pct is retained and reflects
        // the actual value, not a hardcoded 100.
        assertTrue(xml.contains("<pct>50</pct>"));
    }

    @Test
    public void testDefaultTestingIsNWhenOmitted() throws IOException {
        // The plain 11-arg addResult() overload defaults testing to "n"
        // and omits np/discovery_method entirely.
        DmarcAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.contains("<testing>n</testing>"));
        assertFalse(xml.contains("<np>"));
        assertFalse(xml.contains("<discovery_method>"));
        assertTrue(xml.contains("<pct>100</pct>"));
    }

    @Test
    public void testGeneratorIsOptional() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        assertFalse(writeToString(report).contains("<generator>"));

        report.setGenerator("gumdrop/2.1");
        assertTrue(writeToString(report).contains("<generator>gumdrop/2.1</generator>"));
    }

    @Test
    public void testSelectorRequiredEvenWhenUnknown() throws IOException {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("feat002-2");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "example.com",
                DkimResult.PASS, "example.com", null);

        String xml = writeToString(report);
        // RFC 9990: selector is required, emitted as empty rather than
        // omitted when unknown. gonzalez's XMLWriter renders an empty
        // element as self-closing.
        assertTrue(xml.contains("<selector/>"));
    }

    // -- Helpers --

    private DmarcAggregateReport createMinimalReport() {
        DmarcAggregateReport report = new DmarcAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("test@test.com");
        report.setReportId("min-001");
        report.setDateRange(1700000000L, 1700086400L);

        report.addResult("192.0.2.1", "sender.example.com",
                DmarcPolicy.NONE, "r", "r",
                DmarcResult.PASS, SpfResult.PASS, "sender.example.com",
                DkimResult.PASS, "sender.example.com", "sel1");

        return report;
    }

    /**
     * {@code writeXML} must accept any {@code WritableByteChannel}, not
     * just the {@code Channels.newChannel} adapter the other tests use
     * -- verify against a genuine {@link FileChannel}.
     */
    @Test
    public void testWriteXMLAcceptsARealFileChannel() throws IOException {
        DmarcAggregateReport report = createMinimalReport();
        Path tempFile = Files.createTempFile("dmarc-aggregate-", ".xml");
        try {
            try (FileChannel channel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                report.writeXML(channel);
            }
            String xml = new String(Files.readAllBytes(tempFile), "UTF-8");
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
            assertTrue(xml.contains("<report_id>min-001</report_id>"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String writeToString(DmarcAggregateReport report) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.writeXML(Channels.newChannel(out));
        return out.toString("UTF-8");
    }

}
