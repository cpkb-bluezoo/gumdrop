/*
 * SimpleRelayHandlerDirectTest
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

package org.bluezoo.gumdrop.smtp.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.DeliveryRequirements;
import org.bluezoo.gumdrop.smtp.DsnReturn;
import org.bluezoo.gumdrop.smtp.SmtpPipeline;
import org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Calls the handler callbacks of {@link SimpleRelayHandler} directly with a
 * recording state object: sender policy for delivery requirements, session
 * lifecycle callbacks, the in-memory message pipeline, and message completion
 * with no recipients or only a non-MX answer from DNS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleRelayHandlerDirectTest {

    /** Mutable delivery requirements. */
    private static final class Requirements implements DeliveryRequirements {
        boolean requireTls;
        Integer priority;
        Instant release;
        Instant deadline;
        Boolean byReturn;
        DsnReturn ret;
        String envid;

        public boolean isRequireTls() { return requireTls; }
        public Integer getPriority() { return priority; }
        public Instant getReleaseTime() { return release; }
        public Instant getDeliverByDeadline() { return deadline; }
        public Boolean isDeliverByReturn() { return byReturn; }
        public DsnReturn getDsnReturn() { return ret; }
        public String getDsnEnvelopeId() { return envid; }
    }

    /** Resolver answering every MX query with a fixed answer list. */
    private static final class AnswerResolver extends DnsResolver {
        final List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        final List<String> queried = new ArrayList<String>();

        @Override
        public void queryMX(String name, DnsQueryCallback callback) {
            queried.add(name);
            DnsMessage msg = new DnsMessage(1, 0x8180,
                    new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>());
            callback.onResponse(msg);
        }
    }

    private static final class MockSecurity implements SecurityInfo {
        public String getProtocol() { return "TLSv1.3"; }
        public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
        public int getKeySize() { return 128; }
        public java.security.cert.Certificate[] getPeerCertificates() { return null; }
        public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        public String getApplicationProtocol() { return null; }
        public long getHandshakeDurationMs() { return 0; }
        public boolean isSessionResumed() { return false; }
    }

    private AnswerResolver resolver;
    private SimpleRelayHandler relay;
    private RecordingSmtpStates states;
    private List<String> connects;

    @Before
    public void setUp() {
        resolver = new AnswerResolver();
        connects = new ArrayList<String>();
        final List<String> sink = connects;
        relay = new SimpleRelayHandler(resolver, "relay.example.com") {
            @Override
            void connectDelivery(String host, SmtpClientProtocolHandler ph)
                    throws IOException {
                sink.add(host);
            }
        };
        states = new RecordingSmtpStates();
    }

    private static EmailAddress addr(String local, String domain) {
        return new EmailAddress(null, local, domain, true);
    }

    @Test
    public void deliveryPortMustBeInRange() {
        try {
            new SimpleRelayHandler(resolver, "h", 0);
            fail("port 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("0"));
        }
        try {
            new SimpleRelayHandler(resolver, "h", 65536);
            fail("port 65536");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("65536"));
        }
        new SimpleRelayHandler(resolver, "h", 65535);
    }

    @Test
    public void connectedGreetsWithHostname() {
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(25);
        relay.connected(states, endpoint);
        assertEquals("acceptConnection:relay.example.com ESMTP SimpleRelay",
                states.calls.get(0));
    }

    @Test
    public void helloAndAuthenticatedAreAccepted() {
        relay.hello(states, true, "client.example.org");
        relay.authenticated(states, new Principal() {
            public String getName() {
                return "alice";
            }
        });
        relay.tlsEstablished(new MockSecurity());
        assertEquals("acceptHello", states.calls.get(0));
        assertEquals("accept", states.calls.get(1));
    }

    @Test
    public void futureReleaseIsRefusedByPolicy() {
        Requirements req = new Requirements();
        req.release = Instant.ofEpochSecond(2000000000L);
        relay.mailFrom(states, addr("a", "example.org"), false, req);
        assertEquals("rejectSenderPolicy", states.calls.get(0));
        assertTrue(states.policyMessage, states.policyMessage.contains("FUTURERELEASE"));
    }

    @Test
    public void deliverByAndPriorityAreAcceptedWithoutEnforcement() {
        Requirements req = new Requirements();
        req.deadline = Instant.ofEpochSecond(2000000000L);
        req.byReturn = Boolean.TRUE;
        req.priority = Integer.valueOf(3);
        req.requireTls = true;
        req.ret = DsnReturn.FULL;
        req.envid = "env-1";
        relay.mailFrom(states, addr("a", "example.org"), false, req);
        assertEquals("acceptSender", states.calls.get(0));
    }

    @Test
    public void nullSenderAndNullRequirementsAreAccepted() {
        relay.mailFrom(states, null, false, null);
        assertEquals("acceptSender", states.calls.get(0));
    }

    @Test
    public void resetAcceptsAndClearsPipeline() {
        SmtpPipeline pipeline = relay.getPipeline();
        relay.mailFrom(states, addr("a", "example.org"), false, null);
        relay.reset(states);
        assertEquals("acceptReset", states.calls.get(states.calls.size() - 1));
        assertFalse(pipeline == null);
    }

    @Test
    public void recipientAndMessageStartAreAccepted() {
        relay.rcptTo(states, addr("b", "example.com"), null);
        relay.startMessage(states);
        assertEquals("acceptRecipient", states.calls.get(0));
        assertEquals("acceptMessage", states.calls.get(1));
        assertFalse(relay.wantsPause());
        relay.setResumeCallback(null);
        relay.messageContent(ByteBuffer.wrap(new byte[] {1}));
    }

    @Test
    public void lifecycleCallbacksClearTransaction() {
        relay.mailFrom(states, addr("a", "example.org"), false, null);
        relay.rcptTo(states, addr("b", "example.com"), null);
        relay.messageAborted();
        relay.quit();
        relay.disconnected();
        // With the transaction cleared, completing a message has nobody to
        // deliver to and is accepted.
        relay.getPipeline();
        relay.messageComplete(states);
        assertEquals("acceptMessageDelivery", states.calls.get(states.calls.size() - 1));
        assertTrue(connects.isEmpty());
    }

    @Test
    public void pipelineBuffersHeapAndDirectContent() throws IOException {
        SmtpPipeline pipeline = relay.getPipeline();
        pipeline.mailFrom(addr("a", "example.org"));
        pipeline.rcptTo(addr("b", "example.com"));
        WritableByteChannel channel = pipeline.getMessageChannel();
        assertTrue(channel.isOpen());
        ByteBuffer heap = ByteBuffer.wrap("abc".getBytes("US-ASCII"));
        assertEquals(3, channel.write(heap));
        ByteBuffer direct = ByteBuffer.allocateDirect(3);
        direct.put("def".getBytes("US-ASCII"));
        direct.flip();
        assertEquals(3, channel.write(direct));
        assertFalse(direct.hasRemaining());
        pipeline.endData();
        channel.close();
        assertFalse(channel.isOpen());
        pipeline.reset();
        relay.rcptTo(states, addr("b", "example.com"), null);
        relay.messageComplete(states);
        assertEquals(1, resolver.queried.size());
        assertEquals(1, connects.size());
    }

    @Test
    public void nonMxAnswersFallBackToTheDomainItself() throws Exception {
        resolver.answers.add(DnsResourceRecord.a("example.com", 60,
                java.net.InetAddress.getByName("192.0.2.9")));
        relay.getPipeline();
        relay.rcptTo(states, addr("b", "example.com"), null);
        relay.messageComplete(states);
        assertEquals(1, connects.size());
        assertEquals("example.com", connects.get(0));
    }

    @Test
    public void lowestPreferenceMxIsChosenRegardlessOfAnswerOrder() {
        resolver.answers.add(DnsResourceRecord.mx("example.com", 60, 30, "c.example.com"));
        resolver.answers.add(DnsResourceRecord.mx("example.com", 60, 5, "a.example.com"));
        resolver.answers.add(DnsResourceRecord.mx("example.com", 60, 20, "b.example.com"));
        relay.getPipeline();
        relay.rcptTo(states, addr("B", "Example.COM"), null);
        relay.messageComplete(states);
        assertEquals("a.example.com", connects.get(0));
        assertEquals("example.com", resolver.queried.get(0));
    }
}
