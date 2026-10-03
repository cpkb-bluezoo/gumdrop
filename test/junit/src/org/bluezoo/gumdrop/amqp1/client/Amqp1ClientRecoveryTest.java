/*
 * Amqp1ClientRecoveryTest.java
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


package org.bluezoo.gumdrop.amqp1.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.amqp1.client.MockAmqp1Peer.Out;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Encoder;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.bluezoo.gumdrop.amqp1.codec.MessageWriter;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.SaslInit;
import org.bluezoo.gumdrop.amqp1.codec.SaslMechanisms;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link Amqp1ClientRecovery} against in-memory peers: a connector
 * stand-in hands each connection attempt a {@link MockAmqp1Peer}, and the
 * retry timer is replaced by a scheduler whose captured tasks the test
 * fires by hand, so the SASL, open and session handshakes, link recording
 * and replay, loss detection and retry policy are all driven byte by byte
 * on the test thread: no sockets, threads, runtime or clock involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1ClientRecoveryTest {

    private static final int PEER_CHANNEL = ClientHarness.PEER_CHANNEL;

    /** One connection attempt as seen by the stand-in connector. */
    private static final class Conn {
        final MockAmqp1Peer peer = new MockAmqp1Peer();
        final ProtocolHandler handler;

        Conn(ProtocolHandler handler) {
            this.handler = handler;
        }
    }

    /** Connector that hands each connect to the test, or fails it. */
    private static class MockConnector implements Amqp1ClientRecovery.Connector {
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
            handler.connected(c.peer);
            conns.add(c);
        }

        Conn next() {
            assertFalse("no connection attempt arrived", conns.isEmpty());
            return conns.remove(0);
        }
    }

    /** Retry scheduler whose captured tasks are fired by hand. */
    private static final class HandScheduler implements Amqp1ClientRecovery.RetryScheduler {
        final List<Runnable> tasks = new ArrayList<Runnable>();
        final List<Long> delays = new ArrayList<Long>();
        final List<TimerHandle> handles = new ArrayList<TimerHandle>();

        @Override
        public TimerHandle schedule(long delayMs, Runnable task) {
            tasks.add(task);
            delays.add(Long.valueOf(delayMs));
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

    private final class Events implements Amqp1RecoveryListener {
        final List<String> log = new ArrayList<String>();
        final List<Exception> causes = new ArrayList<Exception>();

        @Override
        public void onConnectionLost(Exception cause) {
            causes.add(cause);
            log.add("lost");
        }

        @Override
        public void onReconnecting(int attempt, long delayMs) {
            log.add("reconnecting:" + attempt);
        }

        @Override
        public void onRecovered() {
            log.add("recovered");
        }

        @Override
        public void onRecoveryFailed(Exception cause) {
            causes.add(cause);
            log.add("failed");
        }
    }

    private Amqp1ClientRecovery client;
    private MockConnector connector;
    private HandScheduler scheduler;
    private Events events;
    private Amqp1RecoverableSession recoverable;
    private boolean firstConnected;

    @Before
    public void setUp() {
        connector = new MockConnector();
        scheduler = new HandScheduler();
        events = new Events();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private Amqp1RecoveryPolicy fastPolicy(int maxAttempts) {
        return new Amqp1RecoveryPolicy().withInitialDelayMs(1).withMaxDelayMs(5).withMultiplier(1.0)
                .withMaxAttempts(maxAttempts);
    }

    private Amqp1ClientRecovery newClient() {
        client = new Amqp1ClientRecovery(InetAddress.getLoopbackAddress(), 5672);
        client.useConnectorForTesting(connector);
        client.useRetrySchedulerForTesting(scheduler);
        client.recoveryPolicy(fastPolicy(0)).recoveryListener(events);
        return client;
    }

    private Amqp1RecoveryHandler appHandler(final Amqp1SenderHandler sender, final Amqp1ReceiverHandler receiver) {
        return new Amqp1RecoveryHandler() {
            @Override
            public void onFirstConnect(Amqp1RecoverableSession session) {
                recoverable = session;
                if (sender != null) {
                    session.attachSender("out", "queue://q", sender);
                }
                if (receiver != null) {
                    session.attachReceiver("in", "queue://q", receiver);
                }
                firstConnected = true;
            }
        };
    }

    // ---- driving the broker side ----

    private static void feed(Conn c, ByteBuffer... parts) {
        c.handler.receive(ByteBuffer.wrap(MockAmqp1Peer.concat(parts)));
    }

    private static void offerMechanisms(Conn c, String... mechanisms) {
        feed(c, MockAmqp1Peer.saslHeader(),
                MockAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList(mechanisms))));
    }

    private static void finishSaslAndOpen(Conn c) {
        feed(c, MockAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
        feed(c, MockAmqp1Peer.amqpHeader(), MockAmqp1Peer.amqpFrame(0, new Open("broker")));
    }

    private static void beginSession(Conn c) {
        Begin begin = new Begin(0, 2048, 2048);
        begin.setRemoteChannel(Integer.valueOf(0));
        feed(c, MockAmqp1Peer.amqpFrame(PEER_CHANNEL, begin));
    }

    /** Runs a connection to an active session using ANONYMOUS. */
    private static void bringUp(Conn c) {
        offerMechanisms(c, "ANONYMOUS");
        finishSaslAndOpen(c);
        beginSession(c);
    }

    private static List<Attach> attachesSentOn(Conn c) {
        List<Attach> result = new ArrayList<Attach>();
        for (Out o : c.peer.output()) {
            if (o.performative instanceof Attach) {
                result.add((Attach) o.performative);
            }
        }
        return result;
    }

    private static void acceptAttaches(Conn c) {
        for (Attach ours : attachesSentOn(c)) {
            feed(c, ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, ours.getHandle())));
        }
    }

    private static boolean sentSaslMechanism(Conn c, String mechanism) {
        for (Out o : c.peer.output()) {
            if (o.performative instanceof SaslInit
                    && mechanism.equals(((SaslInit) o.performative).getMechanism())) {
                return true;
            }
        }
        return false;
    }

    // ---- recorders ----

    private static final class SenderApp implements Amqp1SenderHandler {
        Amqp1Sender sender;
        Attach peer;
        int credits;
        int writable;
        int outcomes;
        int detaches;
        boolean lastDetachPermanent;

        @Override
        public void handleAttached(Amqp1Sender s, Attach peerAttach) {
            sender = s;
            peer = peerAttach;
        }

        @Override
        public void handleCredit(Amqp1Sender s) {
            credits++;
        }

        @Override
        public void handleWritable(Amqp1OutgoingDelivery delivery) {
            writable++;
        }

        @Override
        public void handleOutcome(Amqp1OutgoingDelivery delivery, DeliveryState state, boolean settled) {
            outcomes++;
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            detaches++;
            lastDetachPermanent = closed;
        }
    }

    private static final class ReceiverApp implements Amqp1ReceiverHandler {
        Amqp1Receiver receiver;
        final List<String> seen = new ArrayList<String>();
        int detaches;
        boolean lastDetachPermanent;

        @Override
        public void handleAttached(Amqp1Receiver r, Attach peerAttach) {
            receiver = r;
            seen.add("attached");
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery delivery) {
            seen.add("start");
        }

        @Override
        public void handleAborted(Amqp1IncomingDelivery delivery) {
            seen.add("aborted");
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            detaches++;
            lastDetachPermanent = closed;
            seen.add("detached");
        }

        @Override
        public void header(MessageHeader header) {
            seen.add("header");
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> annotations) {
            seen.add("deliveryAnnotations");
        }

        @Override
        public void messageAnnotations(Map<Object, Object> annotations) {
            seen.add("messageAnnotations");
        }

        @Override
        public void properties(MessageProperties properties) {
            seen.add("properties");
        }

        @Override
        public void applicationProperties(Map<Object, Object> properties) {
            seen.add("applicationProperties");
        }

        @Override
        public void startData(long length) {
            seen.add("startData");
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
            seen.add("dataChunk");
        }

        @Override
        public void endData() {
            seen.add("endData");
        }

        @Override
        public void amqpSequence(List<Object> row) {
            seen.add("amqpSequence");
        }

        @Override
        public void amqpValue(Object value) {
            seen.add("amqpValue");
        }

        @Override
        public void footer(Map<Object, Object> footer) {
            seen.add("footer");
        }

        @Override
        public void endMessage() {
            seen.add("endMessage");
        }

        @Override
        public void messageError(String message) {
            seen.add("messageError");
        }
    }

    private static byte[] fullMessage() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageHeader header = new MessageHeader();
        header.setDeliveryCount(1);
        MessageWriter.writeHeader(e, header);
        Map<Object, Object> da = new LinkedHashMap<Object, Object>();
        da.put("x-da", "1");
        MessageWriter.writeDeliveryAnnotations(e, da);
        Map<Object, Object> ma = new LinkedHashMap<Object, Object>();
        ma.put("x-ma", "2");
        MessageWriter.writeMessageAnnotations(e, ma);
        MessageProperties p = new MessageProperties();
        p.setSubject("subj");
        MessageWriter.writeProperties(e, p);
        Map<Object, Object> ap = new LinkedHashMap<Object, Object>();
        ap.put("k", "v");
        MessageWriter.writeApplicationProperties(e, ap);
        byte[] head = e.toByteArray();
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        ByteBuffer prefix = MessageWriter.dataSectionPrefix(body.length);
        byte[] pre = new byte[prefix.remaining()];
        prefix.get(pre);
        Amqp1Encoder tail = new Amqp1Encoder();
        Map<Object, Object> footer = new LinkedHashMap<Object, Object>();
        footer.put("f", "g");
        MessageWriter.writeFooter(tail, footer);
        byte[] foot = tail.toByteArray();
        byte[] all = new byte[head.length + pre.length + body.length + foot.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(pre, 0, all, head.length, pre.length);
        System.arraycopy(body, 0, all, head.length + pre.length, body.length);
        System.arraycopy(foot, 0, all, head.length + pre.length + body.length, foot.length);
        return all;
    }

    private static byte[] sequenceAndValueMessage() {
        Amqp1Encoder e = new Amqp1Encoder();
        List<Object> row = new ArrayList<Object>();
        row.add("a");
        MessageWriter.writeAmqpSequence(e, row);
        return e.toByteArray();
    }

    private static byte[] valueMessage() {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeAmqpValue(e, "text");
        return e.toByteArray();
    }

    private static Transfer firstTransfer(long handle, long id, String tag) {
        Transfer t = new Transfer(handle);
        t.setFirst(id, tag.getBytes(StandardCharsets.UTF_8), false);
        return t;
    }

    // ---- tests ----

    @Test
    public void linksAreAttachedForwardedLostAndReplayedAfterReconnect() throws Exception {
        SenderApp sender = new SenderApp();
        ReceiverApp receiver = new ReceiverApp();
        newClient().connect(null, appHandler(sender, receiver));
        Conn first = connector.next();
        bringUp(first);
        assertTrue(firstConnected);
        assertEquals(2, attachesSentOn(first).size());
        acceptAttaches(first);
        assertNotNull(sender.sender);
        assertNotNull(receiver.receiver);

        assertEquals("out", sender.sender.getName());
        assertEquals("in", receiver.receiver.getName());
        assertNotNull(sender.sender.getPeerAttach());
        assertNotNull(receiver.receiver.getPeerAttach());
        assertSame(sender.peer, sender.sender.getPeerAttach());

        long senderHandle = 0;
        long receiverHandle = 0;
        for (Attach a : attachesSentOn(first)) {
            if (a.isReceiver()) {
                receiverHandle = a.getHandle();
            } else {
                senderHandle = a.getHandle();
            }
        }
        Flow senderCredit = new Flow(Long.valueOf(0), 2048, 0, 2048);
        senderCredit.setLink(senderHandle, 0, 10);
        feed(first, ClientHarness.brokerFrame(senderCredit));
        assertEquals(10L, sender.sender.getLinkCredit());
        assertEquals(1, sender.credits);
        Amqp1OutgoingDelivery delivery = sender.sender.send(new byte[] {1}, null, null,
                ByteBuffer.wrap(new byte[] {9, 9}));
        assertNotNull(delivery);
        Amqp1OutgoingDelivery streamed = sender.sender.startDelivery(new byte[] {2}, null, null, null, false);
        assertNotNull(streamed);
        streamed.abort();

        receiver.receiver.addCredit(5);
        assertEquals(5L, receiver.receiver.getLinkCredit());
        feed(first, ClientHarness.brokerTransfer(firstTransfer(receiverHandle, 0, "m0"), fullMessage()));
        assertTrue(receiver.seen.toString(), receiver.seen.containsAll(Arrays.asList("start", "header",
                "deliveryAnnotations", "messageAnnotations", "properties", "applicationProperties",
                "startData", "dataChunk", "endData", "footer", "endMessage")));
        feed(first, ClientHarness.brokerTransfer(firstTransfer(receiverHandle, 1, "m1"),
                sequenceAndValueMessage()));
        feed(first, ClientHarness.brokerTransfer(firstTransfer(receiverHandle, 2, "m2"), valueMessage()));
        assertTrue(receiver.seen.contains("amqpSequence"));
        assertTrue(receiver.seen.contains("amqpValue"));
        Transfer aborted = new Transfer(receiverHandle);
        aborted.setFirst(3, new byte[] {3}, false);
        aborted.setMore(true);
        feed(first, ClientHarness.brokerTransfer(aborted, new byte[] {0}));
        Transfer abortFrame = new Transfer(receiverHandle);
        abortFrame.setAborted(true);
        feed(first, ClientHarness.brokerTransfer(abortFrame, new byte[0]));
        assertTrue(receiver.seen.contains("aborted"));

        // the connection drops: the session is the unit of recovery
        first.handler.disconnected();
        assertTrue(events.log.toString(), events.log.contains("lost"));
        assertTrue(events.log.toString(), events.log.contains("lost"));
        try {
            sender.sender.send(new byte[] {7}, null, null, ByteBuffer.wrap(new byte[1]));
            fail("expected IllegalStateException while recovering");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not attached"));
        }
        assertEquals(0L, sender.sender.getLinkCredit());
        assertNull(sender.sender.getPeerAttach());
        assertNull(receiver.receiver.getPeerAttach());
        assertEquals(0L, receiver.receiver.getLinkCredit());
        try {
            receiver.receiver.addCredit(1);
            fail("expected IllegalStateException while recovering");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            receiver.receiver.detach(null, true);
            fail("expected IllegalStateException while recovering");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            sender.sender.startDelivery(new byte[] {8}, null, null, null, false);
            fail("expected IllegalStateException while recovering");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            sender.sender.detach(null, false);
            fail("expected IllegalStateException while recovering");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        scheduler.fireNext();
        Conn second = connector.next();
        bringUp(second);
        assertTrue(events.log.toString(), events.log.contains("recovered"));
        assertEquals("both links are replayed on the new session", 2, attachesSentOn(second).size());
        acceptAttaches(second);
        assertNotNull(sender.sender.getPeerAttach());

        // a permanent close removes the link from the replay set
        sender.sender.detach(null, true);
        feed(second, ClientHarness.brokerFrame(new Detach(attachesSentOn(second).get(0).getHandle(), true, null)));
        assertTrue(sender.lastDetachPermanent);
        recoverable.attachSender("out", "queue://q", new SenderApp());
    }

    @Test
    public void brokerCloseSessionEndAndTransportErrorAllTriggerReconnect() throws Exception {
        newClient().connect(null, appHandler(null, null));
        Conn c1 = connector.next();
        offerMechanisms(c1, "ANONYMOUS");
        feed(c1, MockAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
        feed(c1, MockAmqp1Peer.amqpHeader());
        feed(c1, MockAmqp1Peer.amqpFrame(0, new Close(new Amqp1Error(Amqp1Error.NOT_FOUND, "gone"))));
        assertTrue(events.log.toString(), events.log.contains("lost"));
        assertTrue(events.causes.toString(), events.causes.get(0).getMessage().contains("Connection closed by broker"));
        scheduler.fireNext();
        Conn c2 = connector.next();
        bringUp(c2);
        assertTrue(events.log.toString(), events.log.contains("recovered"));
        feed(c2, MockAmqp1Peer.amqpFrame(PEER_CHANNEL, new End(new Amqp1Error(Amqp1Error.NOT_FOUND, "bye"))));
        scheduler.fireNext();
        Conn c3 = connector.next();
        bringUp(c3);
        c3.handler.error(new IOException("wire broke"));
        scheduler.fireNext();
        Conn c4 = connector.next();
        assertNotNull(c4);
        assertTrue(events.log.toString(), events.log.size() >= 6);
    }

    @Test
    public void saslMechanismNotOfferedIsPermanent() throws Exception {
        newClient().mechanism("external").connect(null, appHandler(null, null));
        Conn c = connector.next();
        offerMechanisms(c, "ANONYMOUS");
        assertTrue(events.log.toString(), events.log.contains("failed"));
        assertFalse(events.log.toString(), events.log.contains("reconnecting:1"));
    }

    @Test
    public void plainWithoutCredentialsIsPermanent() throws Exception {
        newClient().mechanism("PLAIN").connect(null, appHandler(null, null));
        Conn c = connector.next();
        offerMechanisms(c, "PLAIN");
        assertTrue(events.log.toString(), events.log.contains("failed"));
        assertTrue(events.causes.get(events.causes.size() - 1).getMessage().contains("credentials"));
    }

    @Test
    public void plainCredentialsAreSentAndBadCredentialsAreNotRetried() throws Exception {
        newClient().credentials("u", "p").hostname("host.example").idleTimeOut(5000L)
                .containerId("cid").connect(null, appHandler(null, null));
        Conn c = connector.next();
        offerMechanisms(c, "PLAIN", "ANONYMOUS");
        assertTrue(sentSaslMechanism(c, "PLAIN"));
        feed(c, MockAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.AUTH, null)));
        assertTrue(events.log.toString(), events.log.contains("failed"));
        assertTrue(events.causes.get(0).getMessage().contains("SASL code 1"));
    }

    @Test
    public void transientAuthFailureIsRetried() throws Exception {
        newClient().credentials("u", "p").connect(null, appHandler(null, null));
        Conn c = connector.next();
        offerMechanisms(c, "PLAIN");
        feed(c, MockAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.SYS_TEMP, null)));
        assertTrue(events.log.toString(), events.log.contains("reconnecting:1"));
        scheduler.fireNext();
        Conn c2 = connector.next();
        assertNotNull(c2);
    }

    @Test
    public void externalMechanismIsSelectedWhenRequested() throws Exception {
        newClient().mechanism("EXTERNAL").connect(null, appHandler(null, null));
        Conn c = connector.next();
        offerMechanisms(c, "EXTERNAL", "PLAIN");
        assertTrue(sentSaslMechanism(c, "EXTERNAL"));
    }

    @Test
    public void connectFailuresAreRetriedThenAbandoned() throws Exception {
        connector.failure = new IOException("no route to broker");
        newClient().recoveryPolicy(fastPolicy(2)).connect(null, appHandler(null, null));
        scheduler.fireNext();
        scheduler.fireNext();
        assertTrue(events.log.toString(), events.log.contains("failed"));
        assertTrue(scheduler.tasks.isEmpty());
        assertEquals(3, connector.attempts);
        assertTrue(events.log.toString(), events.log.contains("reconnecting:1"));
        assertTrue(events.log.toString(), events.log.contains("reconnecting:2"));
        assertFalse(events.log.toString(), events.log.contains("reconnecting:3"));
    }

    @Test
    public void mechanismAndLinkConfigurationIsValidated() throws Exception {
        newClient();
        try {
            client.mechanism("CRAM-MD5");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("CRAM-MD5"));
        }
        client.mechanism(null);
        client.mechanism("anonymous");
        try {
            new Amqp1ClientRecovery((String) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("socketPath", expected.getMessage());
        }
        assertNotNull(new Amqp1ClientRecovery("/tmp/none-for-test.sock"));
        assertNotNull(new Amqp1ClientRecovery("localhost", 5672));
        try {
            client.execute(new Runnable() {
                @Override
                public void run() {
                }
            });
            fail("expected IllegalStateException before connect");
        } catch (IllegalStateException expected) {
            assertEquals("not connected yet", expected.getMessage());
        }
        client.connect(null, appHandler(null, null));
        Conn c = connector.next();
        final boolean[] ran = new boolean[1];
        client.execute(new Runnable() {
            @Override
            public void run() {
                ran[0] = true;
            }
        });
        assertTrue(ran[0]);
        bringUp(c);
        assertTrue(firstConnected);
        try {
            recoverable.attachSender(new Attach("r", 0, true), new SenderApp());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            recoverable.attachReceiver(new Attach("s", 0, false), new ReceiverApp());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            recoverable.attachSender("n", "a", null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("handler", expected.getMessage());
        }
        try {
            recoverable.attachReceiver("n", "a", null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("handler", expected.getMessage());
        }
        recoverable.attachSender("dup", "a", new SenderApp());
        try {
            recoverable.attachSender("dup", "b", new SenderApp());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("dup"));
        }
        recoverable.attachReceiver("dup", "b", new ReceiverApp());
    }

    @Test
    public void closeCancelsAPendingRetryAndStopsRecovery() throws Exception {
        newClient().connect(null, appHandler(null, null));
        Conn c = connector.next();
        c.handler.disconnected();
        assertTrue(events.log.toString(), events.log.contains("reconnecting:1"));
        assertEquals(1, scheduler.handles.size());
        TimerHandle pending = scheduler.handles.get(0);
        client.close();
        assertTrue(pending.isCancelled());
        assertEquals(1, connector.attempts);
        scheduler.fireNext();
        assertEquals("a retry firing after close() does not connect", 1, connector.attempts);
        client.connect(null, appHandler(null, null));
        assertEquals("a closed client does not connect", 1, connector.attempts);
    }

    @Test
    public void listenerDefaultsAreNoOps() {
        Amqp1RecoveryListener quiet = new Amqp1RecoveryListener() {
        };
        quiet.onConnectionLost(new IOException("x"));
        quiet.onReconnecting(1, 5L);
        quiet.onRecovered();
        quiet.onRecoveryFailed(new IOException("y"));
    }

    @Test
    public void recoveryWithoutAListenerStillRetriesAndAbandons() throws Exception {
        connector.failure = new IOException("refused");
        client = new Amqp1ClientRecovery(InetAddress.getLoopbackAddress(), 5672);
        client.useConnectorForTesting(connector);
        client.useRetrySchedulerForTesting(scheduler);
        client.recoveryPolicy(fastPolicy(1)).connect(null, appHandler(null, null));
        assertEquals(1, connector.attempts);
        scheduler.fireNext();
        assertEquals(2, connector.attempts);
        assertTrue("abandoned, no further retry", scheduler.tasks.isEmpty());
    }
}
