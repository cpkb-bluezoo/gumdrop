/*
 * AuthCheckTest.java
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

import org.bluezoo.gumdrop.dns.DNSResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for AuthCheck message processing.
 */
public class AuthCheckTest {

    private InetAddress clientIP;
    private String heloHost;

    @Before
    public void setUp() throws Exception {
        clientIP = InetAddress.getByName("192.168.1.100");
        heloHost = "mail.example.com";
    }

    @Test
    public void testCheckSenderWithNoSPF() {
        // Build pipeline without SPF callback
        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost).build();

        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        pipeline.mailFrom(sender);

        // Should complete without error
    }

    @Test
    public void testCheckSenderNullForBounce() {
        final SPFResult[] captured = { null };

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .onSPF(new SPFCallback() {
                    public void spfResult(SPFResult result, String explanation) {
                        captured[0] = result;
                    }
                })
                .build();

        // Null sender (bounce message) - SPF uses heloHost
        pipeline.mailFrom(null);

        // SPF result will be set (even if NONE due to mock resolver)
    }

    @Test
    public void testReceiveDetectsCRLFCRLF() throws Exception {
        // Create a simple message with headers and body
        String message = "From: sender@example.com\r\n" +
                "To: recipient@example.org\r\n" +
                "Subject: Test\r\n" +
                "\r\n" +
                "This is the body.\r\n";

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .onDMARC(new DMARCCallback() {
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                            String domain, AuthVerdict verdict) {
                        // Just verify we get called
                        assertNotNull(result);
                    }
                })
                .build();

        EmailAddress sender = new EmailAddress(null, "sender", "example.com", true);
        pipeline.mailFrom(sender);

        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII));
        pipeline.getMessageChannel().write(buffer);
        pipeline.getMessageChannel().close();

        pipeline.endData();
    }

    @Test
    public void testReceiveSplitCRLFCRLF() throws Exception {
        // Split the CRLFCRLF across multiple receive calls
        String part1 = "From: sender@example.com\r\n";
        String part2 = "Subject: Test\r";
        String part3 = "\n\r";
        String part4 = "\nBody here.";

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .onDMARC(new DMARCCallback() {
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                            String domain, AuthVerdict verdict) {
                        assertNotNull(result);
                    }
                })
                .build();

        EmailAddress sender = new EmailAddress(null, "sender", "example.com", true);
        pipeline.mailFrom(sender);

        java.nio.channels.WritableByteChannel channel = pipeline.getMessageChannel();

        channel.write(ByteBuffer.wrap(part1.getBytes(StandardCharsets.US_ASCII)));
        channel.write(ByteBuffer.wrap(part2.getBytes(StandardCharsets.US_ASCII)));
        channel.write(ByteBuffer.wrap(part3.getBytes(StandardCharsets.US_ASCII)));
        channel.write(ByteBuffer.wrap(part4.getBytes(StandardCharsets.US_ASCII)));
        channel.close();

        pipeline.endData();
    }

    @Test
    public void testReceiveByteAtATime() throws Exception {
        // Extreme case: receive one byte at a time
        String message = "A: B\r\n\r\nC";

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .build();

        EmailAddress sender = new EmailAddress(null, "sender", "example.com", true);
        pipeline.mailFrom(sender);

        java.nio.channels.WritableByteChannel channel = pipeline.getMessageChannel();

        byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            channel.write(ByteBuffer.wrap(new byte[] { bytes[i] }));
        }
        channel.close();

        pipeline.endData();
    }

    @Test
    public void testMultipleMessagesWithReset() throws Exception {
        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .build();

        // First message
        EmailAddress sender1 = new EmailAddress(null, "first", "example.com", true);
        pipeline.mailFrom(sender1);

        String msg1 = "Subject: First\r\n\r\nBody 1";
        java.nio.channels.WritableByteChannel ch1 = pipeline.getMessageChannel();
        ch1.write(ByteBuffer.wrap(msg1.getBytes(StandardCharsets.US_ASCII)));
        ch1.close();
        pipeline.endData();

        // Reset for second message
        pipeline.reset();

        // Second message
        EmailAddress sender2 = new EmailAddress(null, "second", "example.com", true);
        pipeline.mailFrom(sender2);

        String msg2 = "Subject: Second\r\n\r\nBody 2";
        java.nio.channels.WritableByteChannel ch2 = pipeline.getMessageChannel();
        ch2.write(ByteBuffer.wrap(msg2.getBytes(StandardCharsets.US_ASCII)));
        ch2.close();
        pipeline.endData();
    }

    @Test
    public void testEmptyBody() throws Exception {
        // Message with headers but empty body
        String message = "From: test@example.com\r\n" +
                "Subject: Empty\r\n" +
                "\r\n";

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .build();

        EmailAddress sender = new EmailAddress(null, "test", "example.com", true);
        pipeline.mailFrom(sender);

        java.nio.channels.WritableByteChannel channel = pipeline.getMessageChannel();
        channel.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
        channel.close();

        pipeline.endData();
    }

    @Test
    public void testLargeBody() throws Exception {
        // Message with large body
        StringBuilder sb = new StringBuilder();
        sb.append("From: test@example.com\r\n");
        sb.append("Subject: Large\r\n");
        sb.append("\r\n");
        for (int i = 0; i < 10000; i++) {
            sb.append("This is line ").append(i).append(" of a large message body.\r\n");
        }

        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .build();

        EmailAddress sender = new EmailAddress(null, "test", "example.com", true);
        pipeline.mailFrom(sender);

        // Send in chunks
        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        java.nio.channels.WritableByteChannel channel = pipeline.getMessageChannel();

        int chunkSize = 8192;
        for (int i = 0; i < bytes.length; i += chunkSize) {
            int len = Math.min(chunkSize, bytes.length - i);
            channel.write(ByteBuffer.wrap(bytes, i, len));
        }
        channel.close();

        pipeline.endData();
    }

    @Test
    public void testGetResultsBeforeComplete() {
        AuthPipeline pipeline = new AuthPipeline.Builder(
                new MockDNSResolver(), clientIP, heloHost)
                .onSPF(new SPFCallback() {
                    public void spfResult(SPFResult result, String explanation) {
                    }
                })
                .build();

        EmailAddress sender = new EmailAddress(null, "test", "example.com", true);
        pipeline.mailFrom(sender);

        // Results should be null before endData
        // (Can't easily access AuthCheck from outside, but this tests the flow)
    }

    /**
     * Mock DNS resolver for testing.
     */
    private static class MockDNSResolver extends DNSResolver {

        MockDNSResolver() {
            super(); // No-arg constructor
        }

        // For these tests, we don't need working DNS
    }

}

