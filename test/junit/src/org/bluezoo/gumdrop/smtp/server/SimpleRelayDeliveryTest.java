/*
 * SimpleRelayDeliveryTest.java
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
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
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
import org.bluezoo.gumdrop.smtp.SmtpListener;
import org.bluezoo.gumdrop.smtp.SmtpProtocolHandler;
import org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link SimpleRelayHandler} end to end with a stub resolver and an
 * in-memory outbound transport: the remote MX server is played by feeding
 * scripted replies into the relay's SMTP client protocol handler.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleRelayDeliveryTest {

    /** One outbound connection the relay attempted. */
    private static final class Outbound {
        final String host;
        final SmtpClientProtocolHandler client;
        final RecordingStubEndpoint wire = new RecordingStubEndpoint(25);

        Outbound(String host, SmtpClientProtocolHandler client) {
            this.host = host;
            this.client = client;
        }

        void reply(String text) {
            client.receive(ByteBuffer.wrap(
                    (text + "\r\n").getBytes(StandardCharsets.US_ASCII)));
        }

        boolean sent(String prefix) {
            return wire.findLineStartingWith(prefix) != null;
        }
    }

    /** Resolver answering MX queries from a canned table. */
    private static final class StubResolver extends DnsResolver {
        final List<String> queried = new ArrayList<String>();
        boolean failAll;
        boolean noMx;

        @Override
        public void queryMX(String name, DnsQueryCallback callback) {
            queried.add(name);
            if (failAll) {
                callback.onError("SERVFAIL");
                return;
            }
            List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
            if (!noMx) {
                answers.add(DnsResourceRecord.mx(name, 60, 20, "backup." + name));
                answers.add(DnsResourceRecord.mx(name, 60, 10, "mx." + name));
            }
            DnsMessage msg = new DnsMessage(1, 0x8180,
                    new ArrayList<DnsQuestion>(), answers,
                    new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>());
            callback.onResponse(msg);
        }
    }

    /** Relay whose outbound connections are captured instead of opened. */
    private static final class CapturingRelay extends SimpleRelayHandler {
        final List<Outbound> outbound = new ArrayList<Outbound>();
        IOException connectFailure;

        CapturingRelay(DnsResolver resolver) {
            super(resolver, "relay.example.com");
        }

        @Override
        void connectDelivery(String host, SmtpClientProtocolHandler ph)
                throws IOException {
            if (connectFailure != null) {
                throw connectFailure;
            }
            Outbound o = new Outbound(host, ph);
            ph.connected(o.wire);
            outbound.add(o);
        }
    }

    private StubResolver resolver;
    private CapturingRelay relay;
    private RecordingStubEndpoint endpoint;
    private SmtpProtocolHandler handler;

    @Before
    public void setUp() {
        resolver = new StubResolver();
        relay = new CapturingRelay(resolver);
        handler = new SmtpProtocolHandler(new SmtpListener(), relay);
        endpoint = new RecordingStubEndpoint(25);
        handler.connected(endpoint);
        endpoint.clearResponses();
    }

    private void raw(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private String last() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    private void expect(String command, String code) {
        endpoint.clearResponses();
        raw(command + "\r\n");
        String response = last();
        assertTrue(command + " -> " + response, response.startsWith(code));
    }

    private void submit(String... recipients) {
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@example.org>", "250");
        for (int i = 0; i < recipients.length; i++) {
            expect("RCPT TO:<" + recipients[i] + ">", "250");
        }
        expect("DATA", "354");
        endpoint.clearResponses();
        raw("Subject: x\r\n\r\nbody\r\n.\r\n");
    }

    private void greetAndEhlo(Outbound o, String ehloExtensions) {
        o.reply("220 mx ESMTP");
        assertTrue(o.sent("EHLO relay.example.com"));
        o.reply("250-mx");
        o.reply("250 " + ehloExtensions);
    }

    @Test
    public void testSuccessfulDeliveryUsesLowestPreferenceMx() {
        submit("a@one.example", "b@one.example");
        assertEquals(1, relay.outbound.size());
        Outbound o = relay.outbound.get(0);
        assertEquals("mx.one.example", o.host);
        greetAndEhlo(o, "SIZE 1000");
        assertTrue(o.sent("MAIL FROM:<s@example.org>"));
        o.reply("250 sender ok");
        o.reply("250 rcpt ok");
        o.reply("250 rcpt ok");
        o.reply("354 go");
        o.reply("250 2.0.0 queued as Q1");
        assertTrue(last(), last().startsWith("250"));
        assertTrue(o.sent("QUIT"));
    }

    @Test
    public void testNoMxRecordsFallsBackToDomainItself() {
        resolver.noMx = true;
        submit("a@bare.example");
        assertEquals("bare.example", relay.outbound.get(0).host);
    }

    @Test
    public void testConnectFailureIsTemporaryRejection() {
        relay.connectFailure = new IOException("refused");
        submit("a@one.example");
        assertTrue(last(), last().startsWith("4"));
        assertTrue(relay.outbound.isEmpty());
    }

    @Test
    public void testMxLookupFailureIsTemporaryRejection() {
        resolver.failAll = true;
        submit("a@one.example");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testGreetingRefusalFailsDomain() {
        submit("a@one.example");
        relay.outbound.get(0).reply("554 no service");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testServiceClosingFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("421 closing");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testTransportErrorFailsDomainOnlyOnce() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        o.client.error(new IOException("reset"));
        assertTrue(last(), last().startsWith("4"));
        endpoint.clearResponses();
        o.client.error(new IOException("again"));
        assertTrue(endpoint.getResponses().isEmpty());
    }

    @Test
    public void testHeloFallbackWhenEhloUnsupported() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        o.reply("220 mx");
        o.reply("502 no ehlo");
        assertTrue(o.sent("HELO relay.example.com"));
        o.reply("250 mx");
        assertTrue(o.sent("MAIL FROM:<s@example.org>"));
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("354 go");
        o.reply("250 queued");
        assertTrue(last(), last().startsWith("250"));
    }

    @Test
    public void testAllRecipientsRejectedFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 sender ok");
        o.reply("550 no such user");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testAllRecipientsTemporarilyFailedFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 sender ok");
        o.reply("450 try later");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testSomeRecipientsRejectedStillSendsData() {
        submit("a@one.example", "b@one.example", "c@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 sender ok");
        o.reply("550 no such user");
        o.reply("450 try later");
        o.reply("250 ok");
        assertTrue(o.sent("DATA"));
        o.reply("354 go");
        o.reply("250 queued");
        assertTrue(last(), last().startsWith("250"));
    }

    @Test
    public void testLastRecipientRejectedAfterAcceptedStillSendsData() {
        submit("a@one.example", "b@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 sender ok");
        o.reply("250 ok");
        o.reply("550 gone");
        assertTrue(o.sent("DATA"));
        o.reply("354 go");
        o.reply("250 queued");
        assertTrue(last(), last().startsWith("250"));
    }

    @Test
    public void testLastRecipientTempFailAfterAcceptedStillSendsData() {
        submit("a@one.example", "b@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 sender ok");
        o.reply("250 ok");
        o.reply("451 later");
        assertTrue(o.sent("DATA"));
    }

    @Test
    public void testMailFromRefusedFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("550 sender refused");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testDataRefusalsFailDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("451 data later");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testDataPermanentRefusalFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("554 no data");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testMessageTemporaryFailureFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("354 go");
        o.reply("452 disk full");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testMessagePermanentFailureFailsDomain() {
        submit("a@one.example");
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("354 go");
        o.reply("554 spam");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testSecondDomainDeliveredAfterFirstFails() {
        submit("a@one.example", "b@two.example");
        assertEquals(1, relay.outbound.size());
        relay.outbound.get(0).reply("554 no service");
        assertEquals(2, relay.outbound.size());
        Outbound second = relay.outbound.get(1);
        greetAndEhlo(second, "SIZE 1000");
        second.reply("250 ok");
        second.reply("250 ok");
        second.reply("354 go");
        second.reply("250 queued");
        assertTrue(last(), last().startsWith("250"));
    }

    // ---- REQUIRETLS (RFC 8689) ----

    private void submitRequireTls() {
        endpoint = new RecordingStubEndpoint(25);
        endpoint.setSecure(true);
        handler = new SmtpProtocolHandler(new SmtpListener(), relay);
        handler.connected(endpoint);
        handler.securityEstablished(new FakeSecurityInfo());
        endpoint.clearResponses();
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@example.org> REQUIRETLS", "250");
        expect("RCPT TO:<a@one.example>", "250");
        expect("DATA", "354");
        endpoint.clearResponses();
        raw("Subject: x\r\n\r\nbody\r\n.\r\n");
        assertFalse(endpoint.getResponses().toString(), relay.outbound.isEmpty());
    }

    @Test
    public void testRequireTlsWithoutStartTlsFailsDomain() {
        submitRequireTls();
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "SIZE 1000");
        assertTrue(o.sent("QUIT"));
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testRequireTlsWithoutEsmtpFailsDomain() {
        submitRequireTls();
        Outbound o = relay.outbound.get(0);
        o.reply("220 mx");
        o.reply("502 no ehlo");
        assertTrue(o.sent("QUIT"));
        assertFalse(o.sent("HELO"));
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testRequireTlsUpgradesThenDelivers() {
        submitRequireTls();
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "STARTTLS");
        assertTrue(o.sent("STARTTLS"));
        o.reply("220 ready");
        o.client.securityEstablished(new FakeSecurityInfo());
        o.wire.clearResponses();
        o.reply("250-mx");
        o.reply("250 STARTTLS");
        assertTrue(o.sent("MAIL FROM:<s@example.org>"));
        o.reply("250 ok");
        o.reply("250 ok");
        o.reply("354 go");
        o.reply("250 queued");
        assertTrue(last(), last().startsWith("250"));
    }

    @Test
    public void testRequireTlsRefusedStartTlsFailsDomain() {
        submitRequireTls();
        Outbound o = relay.outbound.get(0);
        greetAndEhlo(o, "STARTTLS");
        o.reply("454 tls not available");
        assertTrue(o.sent("QUIT"));
        assertTrue(last(), last().startsWith("4"));
    }

    private static final class FakeSecurityInfo implements SecurityInfo {
        @Override
        public String getProtocol() {
            return "TLSv1.3";
        }

        @Override
        public String getCipherSuite() {
            return "TLS_AES_128_GCM_SHA256";
        }

        @Override
        public int getKeySize() {
            return 128;
        }

        @Override
        public Certificate[] getPeerCertificates() {
            return null;
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public String getApplicationProtocol() {
            return null;
        }

        @Override
        public long getHandshakeDurationMs() {
            return 0;
        }

        @Override
        public boolean isSessionResumed() {
            return false;
        }
    }
}
