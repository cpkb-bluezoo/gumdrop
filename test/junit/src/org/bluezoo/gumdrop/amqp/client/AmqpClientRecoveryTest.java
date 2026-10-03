/*
 * AmqpClientRecoveryTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.amqp.AmqpFrame;
import org.bluezoo.gumdrop.amqp.AmqpMethod;
import org.bluezoo.gumdrop.amqp.FieldTable;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests {@link AmqpClientRecovery} against in-memory brokers: a connector
 * stand-in hands each connection attempt a recording endpoint and the test
 * plays the broker side frame by frame; the retry timer is replaced by a
 * scheduler whose captured tasks the test fires by hand. Mechanism
 * selection, the automatic handshake, loss and recovery notifications,
 * retry exhaustion and the configuration surface are all exercised on the
 * test thread with no sockets, threads, runtime or clock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AmqpClientRecoveryTest {

    /** One connection attempt as seen by the stand-in connector. */
    private static final class Conn {
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        final ProtocolHandler handler;

        Conn(ProtocolHandler handler) {
            this.handler = handler;
        }
    }

    private static final class MockConnector implements AmqpClientRecovery.Connector {
        final List<Conn> conns = new ArrayList<Conn>();
        IOException failure;
        int attempts;

        @Override
        public void connect(ProtocolHandler handler) throws IOException {
            attempts++;
            if (failure != null) {
                throw failure;
            }
            Conn c = new Conn(handler);
            handler.connected(c.endpoint);
            conns.add(c);
        }

        Conn next() {
            assertFalse("no connection attempt arrived", conns.isEmpty());
            return conns.remove(0);
        }
    }

    private static final class HandScheduler implements AmqpClientRecovery.RetryScheduler {
        final List<Runnable> tasks = new ArrayList<Runnable>();
        final List<TimerHandle> handles = new ArrayList<TimerHandle>();

        @Override
        public TimerHandle schedule(long delayMs, Runnable task) {
            tasks.add(task);
            final boolean[] cancelled = new boolean[1];
            TimerHandle handle = new TimerHandle() {
                @Override
                public void cancel() {
                    cancelled[0] = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled[0];
                }
            };
            handles.add(handle);
            return handle;
        }

        void fireNext() {
            assertFalse("no retry scheduled", tasks.isEmpty());
            Runnable task = tasks.remove(0);
            handles.remove(0);
            task.run();
        }
    }

    private final List<String> events = new ArrayList<String>();
    private final List<Exception> causes = new ArrayList<Exception>();
    private MockConnector connector;
    private HandScheduler scheduler;
    private AmqpClientRecovery client;

    private final class Listener implements RecoveryListener {
        @Override
        public void onConnectionLost(Exception cause) {
            causes.add(cause);
            events.add("lost");
        }

        @Override
        public void onReconnecting(int attempt, long delayMs) {
            events.add("reconnecting:" + attempt);
        }

        @Override
        public void onRecovered() {
            events.add("recovered");
        }

        @Override
        public void onRecoveryFailed(Exception cause) {
            causes.add(cause);
            events.add("failed");
        }
    }

    private final class First implements RecoveryHandler {
        ClientConnection connection;

        @Override
        public void onFirstConnect(ClientConnection c) {
            connection = c;
            events.add("first");
        }
    }

    @Before
    public void setUp() {
        connector = new MockConnector();
        scheduler = new HandScheduler();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private RecoveryPolicy fastPolicy(int maxAttempts) {
        return new RecoveryPolicy().withInitialDelayMs(1).withMaxDelayMs(5).withMultiplier(1.0)
                .withMaxAttempts(maxAttempts);
    }

    private AmqpClientRecovery newClient() {
        client = new AmqpClientRecovery(InetAddress.getLoopbackAddress(), 5672);
        client.useConnectorForTesting(connector);
        client.useRetrySchedulerForTesting(scheduler);
        client.recoveryPolicy(fastPolicy(0)).recoveryListener(new Listener());
        return client;
    }

    // ── broker frames ──

    private static ByteBuffer startFrame(String mechanisms) {
        FieldTable props = new FieldTable().put("product", "TestBroker");
        ByteBuffer encodedProps = props.encode();
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + encodedProps.remaining() + 4 + 64 + 4 + 5);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_START);
        args.put((byte) 0);
        args.put((byte) 9);
        args.putInt(encodedProps.remaining());
        args.put(encodedProps);
        FieldTable.putLongString(args, mechanisms);
        FieldTable.putLongString(args, "en_US");
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer tuneFrame() {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + 4 + 2);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_TUNE);
        args.putShort((short) 0);
        args.putInt(131072);
        args.putShort((short) 0);
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer openOkFrame() {
        ByteBuffer args = ByteBuffer.allocate(4 + 1);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_OPEN_OK);
        FieldTable.putShortString(args, "");
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    private static ByteBuffer closeFrame(int code, String text) {
        ByteBuffer args = ByteBuffer.allocate(4 + 2 + FieldTable.shortStringEncodedSize(text) + 4);
        args.putShort((short) AmqpMethod.CLASS_CONNECTION);
        args.putShort((short) AmqpMethod.CONNECTION_CLOSE);
        args.putShort((short) code);
        FieldTable.putShortString(args, text);
        args.putShort((short) 0);
        args.putShort((short) 0);
        args.flip();
        return AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args);
    }

    /** Extracts the mechanism name from a connection.start-ok payload. */
    private static String startOkMechanism(ByteBuffer payload) {
        payload.getShort();
        payload.getShort();
        int tableLen = payload.getInt();
        payload.position(payload.position() + tableLen);
        int len = payload.get() & 0xFF;
        byte[] name = new byte[len];
        payload.get(name);
        return new String(name, StandardCharsets.US_ASCII);
    }

    /** The payloads (class id, method id, arguments) of the frames the client sent. */
    private static List<ByteBuffer> clientFrames(BinaryRecordingEndpoint endpoint) {
        byte[] all = endpoint.getAllBytes();
        List<ByteBuffer> frames = new ArrayList<ByteBuffer>();
        int pos = 0;
        if (all.length >= 8 && all[0] == 'A' && all[1] == 'M' && all[2] == 'Q' && all[3] == 'P') {
            pos = 8;
        }
        while (pos + AmqpFrame.HEADER_SIZE <= all.length) {
            int size = ByteBuffer.wrap(all, pos + 3, 4).getInt();
            byte[] payload = Arrays.copyOfRange(all, pos + AmqpFrame.HEADER_SIZE,
                    pos + AmqpFrame.HEADER_SIZE + size);
            frames.add(ByteBuffer.wrap(payload));
            pos += AmqpFrame.HEADER_SIZE + size + 1;
        }
        return frames;
    }

    private static int methodOf(ByteBuffer payload) {
        ByteBuffer copy = payload.duplicate();
        copy.getShort();
        return copy.getShort() & 0xFFFF;
    }

    /** Drives a connection through start, tune and open; returns the chosen mechanism. */
    private static String handshake(Conn c, String offered) {
        c.handler.receive(startFrame(offered));
        List<ByteBuffer> afterStart = clientFrames(c.endpoint);
        assertEquals(AmqpMethod.CONNECTION_START_OK, methodOf(afterStart.get(0)));
        String mechanism = startOkMechanism(afterStart.get(0).duplicate());
        c.handler.receive(tuneFrame());
        List<ByteBuffer> afterTune = clientFrames(c.endpoint);
        assertEquals(AmqpMethod.CONNECTION_TUNE_OK, methodOf(afterTune.get(1)));
        assertEquals(AmqpMethod.CONNECTION_OPEN, methodOf(afterTune.get(2)));
        c.handler.receive(openOkFrame());
        return mechanism;
    }

    // ── tests ──

    @Test
    public void plainHandshakeThenLossAndRecovery() throws Exception {
        First first = new First();
        newClient().credentials("u", "p").virtualHost("/v").connect(null, first);
        Conn c1 = connector.next();
        assertEquals("PLAIN", handshake(c1, "PLAIN"));
        assertNotNull(first.connection);

        c1.handler.disconnected();
        assertTrue(events.toString(), events.contains("lost"));
        assertTrue(events.toString(), events.contains("reconnecting:1"));
        scheduler.fireNext();
        Conn c2 = connector.next();
        handshake(c2, "PLAIN");
        assertTrue(events.toString(), events.indexOf("lost") < events.indexOf("recovered"));
        assertEquals("first runs once", 1, java.util.Collections.frequency(events, "first"));
    }

    @Test
    public void amqplainMechanismIsUsedWhenRequested() throws Exception {
        newClient().mechanism("AMQPLAIN").connect(null, new First());
        assertEquals("AMQPLAIN", handshake(connector.next(), "PLAIN AMQPLAIN"));
    }

    @Test
    public void externalMechanismIsUsedWhenOffered() throws Exception {
        newClient().mechanism("external").connect(null, new First());
        assertEquals("EXTERNAL", handshake(connector.next(), "PLAIN EXTERNAL"));
    }

    @Test
    public void unofferedMechanismExhaustsRetriesWithPermanentFailure() throws Exception {
        First first = new First();
        newClient().mechanism("EXTERNAL").recoveryPolicy(fastPolicy(1)).connect(null, first);
        connector.next().handler.receive(startFrame("PLAIN"));
        assertTrue(events.toString(), events.contains("lost"));
        scheduler.fireNext();
        connector.next().handler.receive(startFrame("PLAIN"));
        assertTrue(events.toString(), events.contains("failed"));
        Exception last = causes.get(causes.size() - 1);
        assertTrue(last.getMessage(), last.getMessage().startsWith("Broker does not offer the requested SASL mechanism"));
        assertFalse(events.contains("first"));
    }

    @Test
    public void gssapiWithoutCredentialsFailsTheAttempt() throws Exception {
        newClient().mechanism("GSSAPI").recoveryPolicy(fastPolicy(1)).connect(null, new First());
        connector.next().handler.receive(startFrame("PLAIN GSSAPI"));
        assertTrue(events.toString(), events.contains("lost"));
        assertTrue(causes.get(0).getMessage(), causes.get(0).getMessage().contains("gssapiCredentials"));
    }

    @Test
    public void brokerInitiatedCloseTriggersReconnect() throws Exception {
        First first = new First();
        newClient().connect(null, first);
        Conn c1 = connector.next();
        handshake(c1, "PLAIN");
        c1.handler.receive(closeFrame(320, "CONNECTION_FORCED"));
        assertTrue(events.toString(), events.contains("lost"));
        assertTrue(causes.get(0).getMessage(), causes.get(0).getMessage().contains("320"));
        scheduler.fireNext();
        handshake(connector.next(), "PLAIN");
        assertTrue(events.toString(), events.contains("recovered"));
    }

    @Test
    public void transportErrorTriggersReconnect() throws Exception {
        newClient().connect(null, new First());
        Conn c1 = connector.next();
        c1.handler.error(new IOException("wire broke"));
        assertTrue(events.toString(), events.contains("lost"));
        scheduler.fireNext();
        assertNotNull(connector.next());
    }

    @Test
    public void refusedConnectionsAreRetriedThenAbandoned() throws Exception {
        connector.failure = new ConnectException("Connection refused");
        newClient().recoveryPolicy(fastPolicy(2)).connect(null, new First());
        scheduler.fireNext();
        scheduler.fireNext();
        assertTrue(events.toString(), events.contains("failed"));
        assertTrue(events.toString(), events.contains("reconnecting:1"));
        assertTrue(events.toString(), events.contains("reconnecting:2"));
        assertFalse(events.toString(), events.contains("reconnecting:3"));
        assertEquals("initial attempt plus two retries", 3, connector.attempts);
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void closeStopsFurtherReconnects() throws Exception {
        newClient().connect(null, new First());
        Conn c = connector.next();
        handshake(c, "PLAIN");
        c.handler.disconnected();
        assertEquals(1, scheduler.handles.size());
        TimerHandle pending = scheduler.handles.get(0);
        client.close();
        assertTrue(pending.isCancelled());
        scheduler.fireNext();
        assertEquals("no reconnect after close()", 1, connector.attempts);
        assertFalse(events.contains("recovered"));
    }

    @Test
    public void connectAfterCloseDoesNothing() throws Exception {
        newClient();
        client.close();
        client.connect(null, new First());
        assertEquals(0, connector.attempts);
    }

    @Test
    public void hostNameAndUnixSocketConstructorsAndSetters() {
        AmqpClientRecovery byName = new AmqpClientRecovery("localhost", 5672)
                .setSecure(false).setClientCredentials(null).setTrustManager(null)
                .setKeystoreFile(null).setKeystorePass("x").setKeystoreFormat(null)
                .gssapiCredentials(null, "amqp@localhost", null);
        assertNotNull(byName);
        AmqpClientRecovery path = new AmqpClientRecovery("/tmp/amqp.sock");
        assertNotNull(path);
        try {
            new AmqpClientRecovery(null, (String) null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("socketPath", expected.getMessage());
        }
        // closing a never-connected client is harmless
        byName.close();
        path.close();
    }

    private AmqpClientRecovery wired(AmqpClientRecovery configured) {
        client = configured;
        client.useConnectorForTesting(connector);
        client.useRetrySchedulerForTesting(scheduler);
        client.recoveryPolicy(fastPolicy(0)).recoveryListener(new Listener());
        return client;
    }

    @Test
    public void everyAddressingModeBuildsItsEndpointAndConnects() throws Exception {
        SelectorLoop loop = new InlineSelectorLoop();
        AmqpClientRecovery[] clients = {
            new AmqpClientRecovery("localhost", 5672),
            new AmqpClientRecovery(loop, "localhost", 5672),
            new AmqpClientRecovery(InetAddress.getLoopbackAddress(), 5672),
            new AmqpClientRecovery(loop, InetAddress.getLoopbackAddress(), 5672),
            new AmqpClientRecovery("/tmp/none-for-test.sock"),
            new AmqpClientRecovery(loop, "/tmp/none-for-test.sock")
        };
        for (int i = 0; i < clients.length; i++) {
            wired(clients[i]).connect(null, new First());
            assertEquals("mode " + i, i + 1, connector.attempts);
            clients[i].close();
        }
    }

    @Test
    public void transportSecurityOptionsAreAppliedToEveryAttempt() throws Exception {
        TestCertificates.Identity identity = TestCertificates.ec256();
        wired(new AmqpClientRecovery(InetAddress.getLoopbackAddress(), 5672))
                .setSecure(true).setClientCredentials(identity.credentials())
                .setTrustManager(identity.trustManager()).setKeystorePass("changeit")
                .setKeystoreFormat(KeystoreFormat.PKCS12);
        connector.failure = new ConnectException("Connection refused");
        client.recoveryPolicy(fastPolicy(1)).connect(null, new First());
        scheduler.fireNext();
        assertEquals(2, connector.attempts);
        assertTrue(events.toString(), events.contains("failed"));
    }

    @Test
    public void retryLossIsLoggedAtEveryLevel() throws Exception {
        Logger logger = Logger.getLogger(AmqpClientRecovery.class.getName());
        Level saved = logger.getLevel();
        try {
            logger.setLevel(Level.FINE);
            connector.failure = new ConnectException("Connection refused");
            newClient().recoveryPolicy(fastPolicy(1)).connect(null, new First());
            scheduler.fireNext();
            assertTrue(events.toString(), events.contains("failed"));
            client.close();
            logger.setLevel(Level.OFF);
            events.clear();
            connector.failure = new IOException("Connection closed");
            newClient().recoveryPolicy(fastPolicy(1)).connect(null, new First());
            scheduler.fireNext();
            assertTrue(events.toString(), events.contains("failed"));
        } finally {
            logger.setLevel(saved);
        }
    }

    @Test
    public void listenerDefaultsAreNoOpsAndPolicyBacksOff() {
        RecoveryListener quiet = new RecoveryListener() {
        };
        quiet.onConnectionLost(new IOException("x"));
        quiet.onReconnecting(1, 10L);
        quiet.onRecovered();
        quiet.onRecoveryFailed(new IOException("y"));
        RecoveryPolicy policy = new RecoveryPolicy();
        assertEquals(1000L, policy.getInitialDelayMs());
        assertEquals(30000L, policy.getMaxDelayMs());
        assertEquals(2.0, policy.getMultiplier(), 0.0001);
        assertEquals(0, policy.getMaxAttempts());
        assertEquals(1000L, policy.delayFor(0));
        assertEquals(1000L, policy.delayFor(1));
        assertEquals(2000L, policy.delayFor(2));
        assertEquals(4000L, policy.delayFor(3));
        assertEquals(30000L, policy.delayFor(30));
    }
}
