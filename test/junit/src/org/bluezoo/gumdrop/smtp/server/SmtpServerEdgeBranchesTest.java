/*
 * LocalDeliveryHandlerDirectTest
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

import org.bluezoo.gumdrop.auth.SynchronousRealm;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler;
import org.bluezoo.gumdrop.smtp.SmtpListener;
import org.bluezoo.gumdrop.smtp.server.LocalDeliveryFlowTest.MockFactory;
import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Covers composition and local delivery edge cases that the main suites leave
 * out: a composer carrying a realm, server construction guards, constructor
 * validation of {@link LocalDeliveryHandler}, a recipient without a domain,
 * consecutive messages in one session and multiple failing delivery targets.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpServerEdgeBranchesTest {

    private MockFactory factory;
    private LocalDeliveryHandler handler;
    private RecordingSmtpStates states;

    @Before
    public void setUp() {
        factory = new MockFactory();
        handler = new LocalDeliveryHandler(factory, "example.com", "mx.example.com");
        states = new RecordingSmtpStates();
        handler.connected(states, new RecordingStubEndpoint(25));
    }

    private static EmailAddress addr(String local, String domain) {
        return new EmailAddress(null, local, domain, true);
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private String lastCall() {
        return states.calls.get(states.calls.size() - 1);
    }

    private void begin(String... locals) {
        handler.mailFrom(states, addr("s", "remote.example.net"), false, null);
        for (int i = 0; i < locals.length; i++) {
            handler.rcptTo(states, addr(locals[i], "example.com"), null);
        }
    }

    // -- composition --

    @Test
    public void testComposerCarriesRealmToServer() {
        MockRealm realm = new MockRealm();
        SmtpServer server = SmtpServer.compose()
                .listener(new SmtpListener())
                .sessionPerConnection(supplier())
                .realm(realm)
                .server();
        assertSame(realm, server.getRealm());
    }

    @Test
    public void testComposerWithSeveralListenersNeedsExplicitProvider() {
        try {
            SmtpServer.compose().listener(new SmtpListener()).listener(new SmtpListener()).server();
            fail("no provider");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testComposedServerRequiresSessionProvider() {
        try {
            new ComposedSmtpServer(null);
            fail("null provider");
        } catch (NullPointerException expected) {
            assertEquals("sessionProvider", expected.getMessage());
        }
    }

    @Test
    public void testStartWiresRealmIntoListeners() {
        BareServer server = new BareServer();
        RecordingListener listener = new RecordingListener();
        MockRealm realm = new MockRealm();
        server.addListener(listener);
        server.realm(realm);
        server.start(null);
        assertSame(realm, listener.getRealm());
        server.stop();
    }

    // -- relay without delivery requirements --

    @Test
    public void testRelayWithoutRequirementsUsesPlainEhloSession() {
        final List<SmtpClientProtocolHandler> clients = new ArrayList<SmtpClientProtocolHandler>();
        final RecordingStubEndpoint wire = new RecordingStubEndpoint(25);
        AnswerResolver resolver = new AnswerResolver();
        SimpleRelayHandler relay = new SimpleRelayHandler(resolver, "relay.example.com") {
            @Override
            void connectDelivery(String host, SmtpClientProtocolHandler ph) {
                ph.connected(wire);
                clients.add(ph);
            }
        };
        relay.getPipeline();
        relay.mailFrom(states, addr("s", "example.org"), false, null);
        relay.rcptTo(states, addr("r", "one.example"), null);
        relay.messageComplete(states);
        assertEquals(1, clients.size());
        SmtpClientProtocolHandler client = clients.get(0);
        reply(client, "220 mx ESMTP");
        reply(client, "250-mx");
        reply(client, "250 SIZE 1000");
        String mail = wire.findLineStartingWith("MAIL FROM:<s@example.org>");
        assertNotNull(mail);
        reply(client, "250 ok");
        reply(client, "250 ok");
        reply(client, "354 go");
        reply(client, "250 queued");
        assertEquals("acceptMessageDelivery", lastCall());
    }

    private static void reply(SmtpClientProtocolHandler client, String text) {
        client.receive(bytes(text + "\r\n"));
    }

    // -- local delivery --

    @Test
    public void testConstructorValidation() {
        try {
            new LocalDeliveryHandler(null, "example.com");
            fail("null factory");
        } catch (NullPointerException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new LocalDeliveryHandler(factory, null);
            fail("null domain");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new LocalDeliveryHandler(factory, "");
            fail("empty domain");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testSecondMessageInSameSessionStartsFreshBuffer() {
        begin("alice");
        handler.startMessage(states);
        handler.messageContent(bytes("first"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        begin("bob");
        handler.startMessage(states);
        assertEquals("acceptMessage", lastCall());
        handler.messageContent(bytes("second"));
        handler.messageComplete(states);
        assertEquals("acceptMessageDelivery", lastCall());
        assertEquals("second", factory.deliveries.get(1));
    }

    @Test
    public void testSeveralFailingWritersReportOnlyTheFirstError() {
        factory.async = true;
        factory.failFinish = true;
        begin("alice", "bob");
        handler.startMessage(states);
        handler.messageContent(bytes("data"));
        handler.messageComplete(states);
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals(true, states.lastMessage.contains("Delivery failed"));
        assertEquals(2, factory.writers.size());
        assertEquals(true, factory.writers.get(1).finished);
    }

    @Test
    public void testInterruptDuringSeveralTargetsKeepsFirstReport() {
        factory.async = true;
        begin("alice", "bob");
        handler.startMessage(states);
        handler.messageContent(bytes("x"));
        Thread.currentThread().interrupt();
        try {
            handler.messageComplete(states);
        } finally {
            Thread.interrupted();
        }
        assertEquals("rejectMessageTemporary", lastCall());
        assertEquals("Interrupted during delivery", states.lastMessage);
    }

    // -- mocks --

    /** Minimal server with no session provider. */
    private static final class BareServer extends SmtpServer {
        @Override
        public ClientConnected openSession(org.bluezoo.gumdrop.TcpListener listener) {
            return null;
        }
    }

    /** Listener that skips binding on start and stop. */
    private static final class RecordingListener extends SmtpListener {
        @Override
        public void start(org.bluezoo.gumdrop.Gumdrop gumdrop) {
        }

        @Override
        public void stop() {
        }
    }

    private static final class NullHandler implements ClientConnected {
        @Override
        public void connected(ConnectedState state, Endpoint endpoint) {
        }

        @Override
        public void disconnected() {
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

    /** Resolver answering every MX query with an empty answer list. */
    private static final class AnswerResolver extends DnsResolver {
        @Override
        public void queryMX(String name, DnsQueryCallback callback) {
            DnsMessage msg = new DnsMessage(1, 0x8180,
                    new ArrayList<DnsQuestion>(), new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>(),
                    new ArrayList<DnsResourceRecord>());
            callback.onResponse(msg);
        }
    }

    /** Realm that authenticates nobody. */
    private static final class MockRealm implements SynchronousRealm {

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return java.util.EnumSet.noneOf(SaslMechanism.class);
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return false;
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }


        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }
}
