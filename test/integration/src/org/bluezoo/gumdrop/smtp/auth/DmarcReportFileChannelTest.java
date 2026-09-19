/*
 * DmarcReportFileChannelTest.java
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

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/**
 * DMARC report writers against a genuine {@link FileChannel}. Real file I/O,
 * so this lives with the integration tests; the in-memory cases are in
 * {@code DMARCAggregateReportTest} and {@code DMARCForensicReportTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DmarcReportFileChannelTest {

    /**
     * {@code writeMIME} must accept any {@code WritableByteChannel}, not
     * just the {@code Channels.newChannel} adapter the other tests use
     * -- verify against a genuine {@link FileChannel}.
     */
    @Test
    public void testWriteMIMEAcceptsARealFileChannel() throws IOException {
        DmarcForensicReport report = forensicReport();
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
     * {@code writeXML} must accept any {@code WritableByteChannel}, not
     * just the {@code Channels.newChannel} adapter the other tests use
     * -- verify against a genuine {@link FileChannel}.
     */
    @Test
    public void testWriteXMLAcceptsARealFileChannel() throws IOException {
        DmarcAggregateReport report = aggregateReport();
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

    private DmarcForensicReport forensicReport() {
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

    private DmarcAggregateReport aggregateReport() {
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

}
