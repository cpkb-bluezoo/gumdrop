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

/**
 * Unit tests for {@link DMARCAggregateReport} — DMARC aggregate reporting
 * (RFC 7489 §7.1, Appendix C).
 */
public class DMARCAggregateReportTest {

    @Test
    public void testReportMetadata() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Receiver Corp");
        report.setReporterEmail("reports@receiver.example.com");
        report.setReportId("rpt-001");
        report.setDateRange(1700000000L, 1700086400L);

        report.addResult("192.0.2.1", "sender.example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "sender.example.com",
                DKIMResult.PASS, "sender.example.com", "sel1");

        String xml = writeToString(report);

        assertTrue(xml.contains("<org_name>Receiver Corp</org_name>"));
        assertTrue(xml.contains("<email>reports@receiver.example.com</email>"));
        assertTrue(xml.contains("<report_id>rpt-001</report_id>"));
        assertTrue(xml.contains("<begin>1700000000</begin>"));
        assertTrue(xml.contains("<end>1700086400</end>"));
    }

    @Test
    public void testReportContainsXMLDeclaration() throws IOException {
        DMARCAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(xml.contains("<feedback>"));
        assertTrue(xml.contains("</feedback>"));
    }

    @Test
    public void testPolicyPublished() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("test@test.com");
        report.setReportId("001");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "strict.example.com",
                DMARCPolicy.REJECT, "s", "s",
                DMARCResult.FAIL, SPFResult.FAIL, "strict.example.com",
                DKIMResult.FAIL, "strict.example.com", "sel1");

        String xml = writeToString(report);

        assertTrue(xml.contains("<policy_published>"));
        assertTrue(xml.contains("<domain>strict.example.com</domain>"));
        assertTrue(xml.contains("<adkim>s</adkim>"));
        assertTrue(xml.contains("<aspf>s</aspf>"));
        assertTrue(xml.contains("<p>reject</p>"));
    }

    @Test
    public void testRecordRow() throws IOException {
        DMARCAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.contains("<record>"));
        assertTrue(xml.contains("<source_ip>192.0.2.1</source_ip>"));
        assertTrue(xml.contains("<count>1</count>"));
        assertTrue(xml.contains("<disposition>none</disposition>"));
        assertTrue(xml.contains("<header_from>sender.example.com</header_from>"));
    }

    @Test
    public void testAuthResults() throws IOException {
        DMARCAggregateReport report = createMinimalReport();
        String xml = writeToString(report);

        assertTrue(xml.contains("<auth_results>"));
        assertTrue(xml.contains("<spf>"));
        assertTrue(xml.contains("<dkim>"));
    }

    @Test
    public void testCountAggregation() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("002");
        report.setDateRange(0, 1);

        // Two messages from same IP + same disposition → count=2
        for (int i = 0; i < 2; i++) {
            report.addResult("10.0.0.5", "example.com",
                    DMARCPolicy.NONE, "r", "r",
                    DMARCResult.PASS, SPFResult.PASS, "example.com",
                    DKIMResult.PASS, "example.com", "sel1");
        }

        String xml = writeToString(report);
        assertTrue(xml.contains("<count>2</count>"));
    }

    @Test
    public void testDifferentIPsCreateSeparateRows() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("003");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "example.com",
                DKIMResult.PASS, "example.com", "sel1");

        report.addResult("10.0.0.2", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "example.com",
                DKIMResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<source_ip>10.0.0.1</source_ip>"));
        assertTrue(xml.contains("<source_ip>10.0.0.2</source_ip>"));
    }

    @Test
    public void testDispositionReject() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("004");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "bad.example.com",
                DMARCPolicy.REJECT, "r", "r",
                DMARCResult.FAIL, SPFResult.FAIL, "bad.example.com",
                DKIMResult.FAIL, "bad.example.com", null);

        String xml = writeToString(report);
        assertTrue(xml.contains("<disposition>reject</disposition>"));
    }

    @Test
    public void testDispositionQuarantine() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReportId("005");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "suspect.example.com",
                DMARCPolicy.QUARANTINE, "r", "r",
                DMARCResult.FAIL, SPFResult.FAIL, "suspect.example.com",
                DKIMResult.FAIL, "suspect.example.com", null);

        String xml = writeToString(report);
        assertTrue(xml.contains("<disposition>quarantine</disposition>"));
    }

    @Test
    public void testExtraContactInfo() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("t@t.com");
        report.setReporterExtraContactInfo("https://example.com/dmarc-info");
        report.setReportId("006");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "example.com",
                DKIMResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<extra_contact_info>https://example.com/dmarc-info</extra_contact_info>"));
    }

    @Test
    public void testXmlEscaping() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test & <Corp>");
        report.setReporterEmail("t@t.com");
        report.setReportId("007");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "example.com",
                DKIMResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("Test &amp; &lt;Corp&gt;"));
    }

    @Test
    public void testDkimSelectorIncluded() throws IOException {
        DMARCAggregateReport report = createMinimalReport();
        String xml = writeToString(report);
        assertTrue(xml.contains("<selector>sel1</selector>"));
    }

    @Test
    public void testDomainCount() {
        DMARCAggregateReport report = new DMARCAggregateReport();
        assertEquals(0, report.getDomainCount());

        report.addResult("10.0.0.1", "a.example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "a.example.com",
                DKIMResult.PASS, "a.example.com", "s");

        assertEquals(1, report.getDomainCount());

        report.addResult("10.0.0.1", "b.example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "b.example.com",
                DKIMResult.PASS, "b.example.com", "s");

        assertEquals(2, report.getDomainCount());
    }

    @Test
    public void testDkimPassInPolicyEvaluated() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("008");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.FAIL, "example.com",
                DKIMResult.PASS, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<dkim>pass</dkim>"));
        assertTrue(xml.contains("<spf>fail</spf>"));
    }

    @Test
    public void testSpfPassInPolicyEvaluated() throws IOException {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("T");
        report.setReporterEmail("t@t.com");
        report.setReportId("009");
        report.setDateRange(0, 1);

        report.addResult("10.0.0.1", "example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "example.com",
                DKIMResult.FAIL, "example.com", "sel1");

        String xml = writeToString(report);
        assertTrue(xml.contains("<spf>pass</spf>"));
    }

    // -- Helpers --

    private DMARCAggregateReport createMinimalReport() {
        DMARCAggregateReport report = new DMARCAggregateReport();
        report.setReporterOrgName("Test");
        report.setReporterEmail("test@test.com");
        report.setReportId("min-001");
        report.setDateRange(1700000000L, 1700086400L);

        report.addResult("192.0.2.1", "sender.example.com",
                DMARCPolicy.NONE, "r", "r",
                DMARCResult.PASS, SPFResult.PASS, "sender.example.com",
                DKIMResult.PASS, "sender.example.com", "sel1");

        return report;
    }

    private String writeToString(DMARCAggregateReport report) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.writeXML(out);
        return out.toString("UTF-8");
    }

}
