/*
 * SmtpServerCompositionTest.java
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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.smtp.SmtpListener;
import org.bluezoo.gumdrop.smtp.SmtpProtocolHandler;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.*;

/**
 * Tests for {@link SmtpServer} composition, the stock session providers and
 * {@link SimpleRelayHandler} behind a real protocol handler with a stubbed
 * DNS resolver.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpServerCompositionTest {

    private static final class NullHandler implements ClientConnected {
        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
        }

        @Override
        public void disconnected() {
        }
    }

    private static final class StubFactory implements MailboxFactory {
        @Override
        public MailboxStore createStore() {
            return null;
        }
    }

    private static Supplier<ClientConnected> supplier() {
        return new Supplier<ClientConnected>() {
            @Override
            public ClientConnected get() {
                return new NullHandler();
            }
        };
    }

    @Test
    public void testComposerBuildsServer() {
        SmtpListener listener = new SmtpListener();
        StubFactory factory = new StubFactory();
        SmtpServer server = SmtpServer.compose()
                .listener(listener)
                .sessionPerConnection(supplier())
                .mailboxFactory(factory)
                .maxMessageSize(1234L)
                .maxRecipients(5)
                .maxTransactionsPerSession(9)
                .authRequired(true)
                .server();
        assertEquals(1234L, server.getMaxMessageSize());
        assertEquals(5, server.getMaxRecipients());
        assertEquals(9, server.getMaxTransactionsPerSession());
        assertTrue(server.isAuthRequired());
        assertSame(factory, server.getMailboxFactory());
        assertNull(server.getRealm());
        assertEquals(1, server.getListeners().size());
        ClientConnected session = server.openSession(listener);
        assertTrue(session instanceof NullHandler);
        ClientConnected viaDeprecated = server.createHandler(listener);
        assertNotNull(viaDeprecated);
    }

    @Test
    public void testComposerUsesSoleListenerProvider() {
        SmtpListener listener = new SmtpListener().sessionProvider(
                SmtpServerSessionProviders.perSession(supplier()));
        SmtpServer server = SmtpServer.builder().listener(listener).build();
        assertNotNull(server);
    }

    @Test
    public void testComposerValidation() {
        try {
            SmtpServer.compose().listener(null);
            fail("null listener");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        try {
            SmtpServer.compose().sessionProvider(null);
            fail("null provider");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        try {
            SmtpServer.compose().listener(new SmtpListener()).server();
            fail("no provider");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        try {
            SmtpServer.compose().sessionPerConnection(supplier()).server();
            fail("no listener");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testServerSettersAndListeners() {
        SmtpServer server = SmtpServer.compose()
                .listener(new SmtpListener())
                .sessionPerConnection(supplier())
                .server();
        server.setMaxMessageSize(10L);
        server.setMaxRecipients(2);
        server.setMaxTransactionsPerSession(3);
        server.setAuthRequired(true);
        server.setMailboxFactory(null);
        server.setRealm(null);
        assertEquals(10L, server.getMaxMessageSize());
        assertEquals(2, server.getMaxRecipients());
        assertEquals(3, server.getMaxTransactionsPerSession());
        server.addListener(new SmtpListener());
        assertEquals(2, server.getListeners().size());
        List<Object> more = new ArrayList<Object>();
        more.add(new SmtpListener());
        server.setListeners(more);
        assertEquals(3, server.getListeners().size());
    }

    @Test
    public void testServerStartWiresListenersAndStops() {
        final List<String> events = new ArrayList<String>();
        SmtpListener recording = new SmtpListener() {
            @Override
            public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
                events.add("start");
            }

            @Override
            public void stop() {
                events.add("stop");
            }
        };
        SmtpListener failing = new SmtpListener() {
            @Override
            public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
                throw new IllegalStateException("cannot start");
            }

            @Override
            public void stop() {
                throw new IllegalStateException("cannot stop");
            }
        };
        SmtpServer server = SmtpServer.compose()
                .listener(recording)
                .listener(failing)
                .sessionPerConnection(supplier())
                .realm(null)
                .mailboxFactory(new StubFactory())
                .maxMessageSize(77L)
                .server();
        server.start(null);
        assertEquals(1, events.size());
        assertEquals(77L, recording.getMaxMessageSize());
        assertSame(server, recording.getServer());
        assertNotNull(recording.getSessionProvider());
        assertNotNull(recording.getMailboxFactory());
        server.stop();
        assertEquals("stop", events.get(1));
    }

    @Test
    public void testServerStartWithRelayProvider() {
        SimpleRelaySessionProvider relay = SmtpServerSessionProviders.relay()
                .hostname("h.example.com")
                .dnsResolver(new FailingResolver());
        SmtpServer server = SmtpServer.compose()
                .listener(new SmtpListener() {
                    @Override
                    public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
                    }

                    @Override
                    public void stop() {
                    }
                })
                .sessionProvider(relay)
                .server();
        server.start(null);
        server.stop();
    }

    @Test
    public void testPerSessionProviderRejectsNull() {
        try {
            SmtpServerSessionProviders.perSession(null);
            fail("null supplier");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testLocalDeliveryProviderValidation() {
        LocalDeliverySessionProvider provider = SmtpServerSessionProviders.localDelivery();
        assertEquals("localhost", provider.getHostname());
        assertNull(provider.getLocalDomain());
        SmtpListener listener = new SmtpListener();
        try {
            provider.openSession(listener);
            fail("no factory");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        provider.mailboxFactory(new StubFactory());
        try {
            provider.openSession(listener);
            fail("no domain");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        provider.localDomain("example.com").hostname("mx.example.com");
        assertEquals("example.com", provider.getLocalDomain());
        assertEquals("mx.example.com", provider.getHostname());
        assertTrue(provider.openSession(listener) instanceof LocalDeliveryHandler);
        try {
            provider.openSession(new NotSmtpListener());
            fail("wrong listener type");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }

    /** A TCP listener that is not an SMTP listener. */
    private static final class NotSmtpListener extends TcpListener {
        @Override
        public String getDescription() {
            return "not smtp";
        }

        @Override
        protected org.bluezoo.gumdrop.ProtocolHandler createHandler() {
            return null;
        }
    }

    @Test
    public void testRelayProviderConfiguration() throws Exception {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay();
        try {
            provider.openSession(new SmtpListener());
            fail("not started");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        InetAddress loopback = InetAddress.getLoopbackAddress();
        provider.hostname("relay.example.com")
                .timeoutMs(1500L)
                .deliveryPort(2525)
                .server(loopback)
                .server(loopback, 5353)
                .servers(loopback);
        assertEquals("relay.example.com", provider.getHostname());
        assertEquals(1500L, provider.getTimeoutMs());
        provider.dnsServer("127.0.0.1");
        assertEquals("127.0.0.1", provider.getDnsServer());
        try {
            provider.server(null);
            fail("null address");
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        provider.servers((InetAddress[]) null);
        provider.start();
        provider.start();
        assertTrue(provider.openSession(new SmtpListener()) instanceof SimpleRelayHandler);
        provider.stop();
        provider.stop();
    }

    @Test
    public void testRelayProviderCustomResolverAndDefaultHost() {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay();
        provider.dnsResolver(new FailingResolver());
        provider.start();
        assertNotNull(provider.getHostname());
        assertNotNull(provider.openSession(new SmtpListener()));
        provider.stop();
    }

    @Test
    public void testRelayProviderWithLegacyDnsServer() {
        SimpleRelaySessionProvider provider = SmtpServerSessionProviders.relay();
        provider.hostname("h.example.com").dnsServer("127.0.0.1");
        provider.start();
        provider.stop();
    }

    @Test
    public void testRelayHandlerConstructorValidation() {
        try {
            new SimpleRelayHandler(new FailingResolver(), "h", 0);
            fail("bad port");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        try {
            new SimpleRelayHandler(new FailingResolver(), "h", 70000);
            fail("bad port");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        assertNotNull(new SimpleRelayHandler(new FailingResolver(), "h"));
    }

    // -- relay handler behind the protocol handler --

    private RecordingStubEndpoint endpoint;
    private SmtpProtocolHandler handler;

    private void startRelay() {
        handler = new SmtpProtocolHandler(new SmtpListener(),
                new SimpleRelayHandler(new FailingResolver(), "relay.example.com"));
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

    @Test
    public void testRelayHandlerHappyPathToMxFailure() {
        startRelay();
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@example.org> MT-PRIORITY=1 BY=60;R RET=HDRS ENVID=e1", "250");
        expect("RCPT TO:<a@one.example>", "250");
        expect("RCPT TO:<b@two.example>", "250");
        expect("DATA", "354");
        raw("Subject: x\r\n\r\nbody\r\n.\r\n");
        assertTrue(last(), last().startsWith("4"));
    }

    @Test
    public void testRelayHandlerRejectsFutureRelease() {
        startRelay();
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@example.org> HOLDFOR=60", "5");
    }

    @Test
    public void testRelayHandlerResetAbortAndQuit() {
        startRelay();
        expect("EHLO c.example.com", "250");
        expect("MAIL FROM:<s@example.org>", "250");
        expect("RCPT TO:<a@one.example>", "250");
        expect("RSET", "250");
        expect("MAIL FROM:<s@example.org>", "250");
        expect("RCPT TO:<a@one.example>", "250");
        expect("DATA", "354");
        raw("partial");
        handler.disconnected();
        startRelay();
        expect("EHLO c.example.com", "250");
        expect("QUIT", "221");
    }

    /** Resolver whose MX queries always fail without any network use. */
    private static final class FailingResolver extends DnsResolver {
        @Override
        public void queryMX(String name, DnsQueryCallback callback) {
            callback.onError("stub failure for " + name);
        }
    }
}
