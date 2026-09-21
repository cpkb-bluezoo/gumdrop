/*
 * SimpleRelayIntegrationTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.server.SyncDnsQueryHandler;
import org.bluezoo.gumdrop.smtp.client.AcceptAllService;
import org.bluezoo.gumdrop.smtp.server.SimpleRelaySessionProvider;
import org.bluezoo.gumdrop.smtp.server.SmtpServer;
import org.bluezoo.gumdrop.smtp.server.SmtpServerSessionProviders;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end test for {@link org.bluezoo.gumdrop.smtp.server.SimpleRelayHandler}:
 * inbound SMTP on a high port, MX lookup via a {@link DnsResolver} with a local
 * handler, outbound delivery to a sink on a non-privileged port.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SimpleRelayIntegrationTest extends AbstractServerIntegrationTest {

    private static final int RELAY_PORT = 12651;
    private static final int DOWNSTREAM_SMTP_PORT = 2525;
    private static final String TEST_HOST = "::1";
    private static final String RECIPIENT_DOMAIN = "relay-target.test";
    private static final String MX_HOST = "mx.relay-target.test";
    private static final String TEST_BODY = "Simple relay integration payload.";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private AcceptAllService downstreamSink;
    private SimpleRelaySessionProvider relayProvider;

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        downstreamSink = new AcceptAllService();
        downstreamSink.addListener(new SmtpListener()
                .port(DOWNSTREAM_SMTP_PORT)
                .bindWildcard());

        final InetAddress loopbackV4 = InetAddress.getByName("127.0.0.1");
        final InetAddress loopbackV6 = InetAddress.getByName(TEST_HOST);
        DnsResolver resolver = new DnsResolver().handler(new SyncDnsQueryHandler() {
            @Override
            protected DnsMessage resolveQuery(DnsMessage query) {
                DnsQuestion q = query.getQuestions().get(0);
                String name = stripTrailingDot(q.getName());
                if (q.getType() == DnsType.MX
                        && name.equalsIgnoreCase(RECIPIENT_DOMAIN)) {
                    return query.createResponse(Collections.singletonList(
                            DnsResourceRecord.mx(q.getName(), 60, 10, MX_HOST)));
                }
                if (name.equalsIgnoreCase(stripTrailingDot(MX_HOST))) {
                    if (q.getType() == DnsType.A) {
                        return query.createResponse(Collections.singletonList(
                                DnsResourceRecord.a(q.getName(), 60, loopbackV4)));
                    }
                    if (q.getType() == DnsType.AAAA) {
                        return query.createResponse(Collections.singletonList(
                                DnsResourceRecord.aaaa(q.getName(), 60, loopbackV6)));
                    }
                }
                return null;
            }
        });

        relayProvider = SmtpServerSessionProviders.relay()
                .hostname("relay.integration.test")
                .deliveryPort(DOWNSTREAM_SMTP_PORT)
                .dnsResolver(resolver);

        SmtpServer relayServer = SmtpServer.compose()
                .listener(new SmtpListener()
                        .port(RELAY_PORT)
                        .addresses(InetAddress.getByName(TEST_HOST)))
                .sessionProvider(relayProvider)
                .server();

        List<Server> servers = new ArrayList<Server>();
        servers.add(downstreamSink);
        servers.add(relayServer);
        return servers;
    }

    @Test
    public void relayDeliversViaInjectedMxRecords() throws Exception {
        downstreamSink.clearMessages();

        String recipient = "user@" + RECIPIENT_DOMAIN;
        SMTPClientHelper.SmtpResponse response = SMTPClientHelper.sendEmail(
                TEST_HOST,
                RELAY_PORT,
                "sender@example.com",
                recipient,
                "Relay test",
                TEST_BODY);

        assertTrue("Relay should accept the message: " + response.message,
                response.isPositiveCompletion());

        assertTrue("Timed out waiting for downstream delivery",
                waitForDownstreamMessage(10, TimeUnit.SECONDS));

        List<AcceptAllService.ReceivedMessage> delivered =
                downstreamSink.getReceivedMessages();
        assertEquals(1, delivered.size());

        AcceptAllService.ReceivedMessage msg = delivered.get(0);
        assertEquals(1, msg.getRecipients().size());
        assertEquals(recipient, msg.getRecipients().get(0).getAddress());

        String content = new String(msg.getContent(), StandardCharsets.UTF_8);
        assertTrue("Body should contain test payload", content.contains(TEST_BODY));
    }

    private boolean waitForDownstreamMessage(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (downstreamSink.getMessageCount() > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return downstreamSink.getMessageCount() > 0;
    }

    private static String stripTrailingDot(String name) {
        if (name != null && name.endsWith(".")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }
}
