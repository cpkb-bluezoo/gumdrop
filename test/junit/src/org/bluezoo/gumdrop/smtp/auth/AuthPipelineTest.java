/*
 * AuthPipelineTest.java
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
import java.nio.channels.WritableByteChannel;

/**
 * Unit tests for AuthPipeline builder and SMTPPipeline implementation.
 */
public class AuthPipelineTest {

    private MockDNSResolver resolver;
    private InetAddress clientIP;
    private String heloHost;

    @Before
    public void setUp() throws Exception {
        resolver = new MockDNSResolver();
        clientIP = InetAddress.getByName("192.168.1.100");
        heloHost = "mail.example.com";
    }

    @Test
    public void testBuilderRequiresResolver() {
        try {
            new AuthPipeline.Builder(null, clientIP, heloHost);
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertEquals("resolver must not be null", e.getMessage());
        }
    }

    @Test
    public void testBuildMinimalPipeline() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .build();

        assertNotNull(pipeline);
        // With no callbacks, nothing is enabled
        assertFalse(pipeline.isSPFEnabled());
        assertFalse(pipeline.isDKIMEnabled());
        assertFalse(pipeline.isDMARCEnabled());
    }

    @Test
    public void testBuildWithSPFCallback() {
        final boolean[] called = { false };

        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onSPF(new SPFCallback() {
                    public void spfResult(SPFResult result, String explanation) {
                        called[0] = true;
                    }
                })
                .build();

        assertTrue(pipeline.isSPFEnabled());
        assertFalse(pipeline.isDKIMEnabled());
        assertFalse(pipeline.isDMARCEnabled());
    }

    @Test
    public void testBuildWithDKIMCallback() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onDKIM(new DKIMCallback() {
                    public void dkimResult(DKIMResult result, String domain, String selector) {
                    }
                })
                .build();

        assertFalse(pipeline.isSPFEnabled());
        assertTrue(pipeline.isDKIMEnabled());
        assertFalse(pipeline.isDMARCEnabled());
    }

    @Test
    public void testBuildWithDMARCCallback() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onDMARC(new DMARCCallback() {
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy, 
                                            String domain, AuthVerdict verdict) {
                    }
                })
                .build();

        assertFalse(pipeline.isSPFEnabled());
        // DKIM is also enabled because DMARC needs it
        assertTrue(pipeline.isDKIMEnabled());
        assertTrue(pipeline.isDMARCEnabled());
    }

    @Test
    public void testBuildWithAllCallbacks() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onSPF(new SPFCallback() {
                    public void spfResult(SPFResult result, String explanation) {
                    }
                })
                .onDKIM(new DKIMCallback() {
                    public void dkimResult(DKIMResult result, String domain, String selector) {
                    }
                })
                .onDMARC(new DMARCCallback() {
                    public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                            String domain, AuthVerdict verdict) {
                    }
                })
                .build();

        assertTrue(pipeline.isSPFEnabled());
        assertTrue(pipeline.isDKIMEnabled());
        assertTrue(pipeline.isDMARCEnabled());
    }

    @Test
    public void testPipelineMailFrom() {
        final EmailAddress[] captured = { null };

        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onSPF(new SPFCallback() {
                    public void spfResult(SPFResult result, String explanation) {
                        // Will be called with NONE since DNS resolver returns nothing
                    }
                })
                .build();

        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        pipeline.mailFrom(sender);

        // Just verify no exceptions
    }

    @Test
    public void testPipelineRcptTo() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .build();

        EmailAddress recipient = new EmailAddress(null, "dest", "example.org", true);
        // Should not throw - auth doesn't care about recipients
        pipeline.rcptTo(recipient);
    }

    @Test
    public void testPipelineGetMessageChannelWithDKIM() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onDKIM(new DKIMCallback() {
                    public void dkimResult(DKIMResult result, String domain, String selector) {
                    }
                })
                .build();

        // Need to call mailFrom first to create the check
        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        pipeline.mailFrom(sender);

        WritableByteChannel channel = pipeline.getMessageChannel();
        assertNotNull(channel);
        assertTrue(channel.isOpen());
    }

    @Test
    public void testPipelineGetMessageChannelWithoutMailFrom() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onDKIM(new DKIMCallback() {
                    public void dkimResult(DKIMResult result, String domain, String selector) {
                    }
                })
                .build();

        // No mailFrom called - channel should be null
        WritableByteChannel channel = pipeline.getMessageChannel();
        assertNull(channel);
    }

    @Test
    public void testPipelineReset() {
        AuthPipeline pipeline = new AuthPipeline.Builder(resolver, clientIP, heloHost)
                .onDKIM(new DKIMCallback() {
                    public void dkimResult(DKIMResult result, String domain, String selector) {
                    }
                })
                .build();

        EmailAddress sender = new EmailAddress(null, "user", "example.com", true);
        pipeline.mailFrom(sender);
        assertNotNull(pipeline.getMessageChannel());

        pipeline.reset();

        // After reset, channel should be null (check cleared)
        assertNull(pipeline.getMessageChannel());
    }

    @Test
    public void testBuilderFluentChaining() {
        // Verify all builder methods return the builder for chaining
        AuthPipeline.Builder builder = new AuthPipeline.Builder(resolver, clientIP, heloHost);

        assertSame(builder, builder.onSPF(new SPFCallback() {
            public void spfResult(SPFResult result, String explanation) {
            }
        }));
        assertSame(builder, builder.onDKIM(new DKIMCallback() {
            public void dkimResult(DKIMResult result, String domain, String selector) {
            }
        }));
        assertSame(builder, builder.onDMARC(new DMARCCallback() {
            public void dmarcResult(DMARCResult result, DMARCPolicy policy,
                                    String domain, AuthVerdict verdict) {
            }
        }));
    }

    /**
     * Mock DNS resolver that returns empty results.
     */
    private static class MockDNSResolver extends DNSResolver {

        MockDNSResolver() {
            super(); // No-arg constructor
        }

        // Methods will fail if actually called, but builder doesn't call them
    }

}

