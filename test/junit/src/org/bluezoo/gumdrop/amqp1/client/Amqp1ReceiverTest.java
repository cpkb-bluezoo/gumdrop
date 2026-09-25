/*
 * Amqp1ReceiverTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Encoder;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Disposition;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.bluezoo.gumdrop.amqp1.codec.MessageWriter;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for receiving links: attach, credit, streamed delivery of
 * messages across transfer frames, disposition and session windows,
 * against the fake broker.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1ReceiverTest {

    private ClientHarness h;
    private ClientHarness.SessionRecorder session;

    @Before
    public void setUp() {
        h = new ClientHarness();
        session = h.openSession();
    }

    // ── helpers ──

    private ReceiverRecorder attach() {
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "queue://orders", rec);
        List<Attach> sent = h.sent(Attach.class);
        Attach ours = sent.get(sent.size() - 1);
        h.feed(ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, ours.getHandle())));
        return rec;
    }

    private ReceiverRecorder readyReceiver(long credit) {
        ReceiverRecorder rec = attach();
        rec.receiver.addCredit(credit);
        return rec;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 13 + 5);
        }
        return b;
    }

    /** A message: a subject, an application property and one data section. */
    private static byte[] message(byte[] body) {
        Amqp1Encoder e = new Amqp1Encoder();
        MessageProperties p = new MessageProperties();
        p.setSubject("hello");
        MessageWriter.writeProperties(e, p);
        java.util.Map<Object, Object> ap = new java.util.LinkedHashMap<Object, Object>();
        ap.put("k", "v");
        MessageWriter.writeApplicationProperties(e, ap);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = e.toByteArray();
        out.write(head, 0, head.length);
        ByteBuffer prefix = MessageWriter.dataSectionPrefix(body.length);
        byte[] pre = new byte[prefix.remaining()];
        prefix.get(pre);
        out.write(pre, 0, pre.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static Transfer first(long id, String tag, boolean more) {
        Transfer t = new Transfer(0);
        t.setFirst(id, bytes(tag), false);
        t.setMore(more);
        return t;
    }

    private static Transfer continuation(boolean more) {
        Transfer t = new Transfer(0);
        t.setMore(more);
        return t;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        return Arrays.copyOfRange(b, from, to);
    }

    // ── attach and credit ──

    @Test
    public void testAttachReceiverSendsAttach() {
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "queue://orders", rec);
        Attach a = (Attach) h.last().performative;
        assertEquals("r1", a.getName());
        assertTrue(a.isReceiver());
        assertEquals("queue://orders", a.getSource().getAddress());
        assertNotNull("a receiver advertises an (empty) target", a.getTarget());
        assertNull(a.getInitialDeliveryCount());
    }

    @Test
    public void testAttachedCallbackAndNoCreditYet() {
        ReceiverRecorder rec = attach();
        assertNotNull(rec.receiver);
        assertEquals("queue://orders", rec.peerAttach.getSource().getAddress());
        assertEquals(0L, rec.receiver.getLinkCredit());
        assertEquals("no flow until credit is granted", 0, h.sent(Flow.class).size());
    }

    @Test
    public void testAddCreditSendsFlow() {
        ReceiverRecorder rec = attach();
        rec.receiver.addCredit(5);
        Flow f = (Flow) h.last().performative;
        assertEquals(Long.valueOf(0), f.getHandle());
        assertEquals(Long.valueOf(5), f.getLinkCredit());
        assertEquals(Long.valueOf(0), f.getDeliveryCount());
        assertEquals("next-incoming-id starts at the broker's next-outgoing-id",
                Long.valueOf(0), f.getNextIncomingId());
        assertEquals(2048L, f.getIncomingWindow());
        assertEquals(5L, rec.receiver.getLinkCredit());
    }

    @Test
    public void testCreditAccumulates() {
        ReceiverRecorder rec = attach();
        rec.receiver.addCredit(5);
        rec.receiver.addCredit(3);
        assertEquals(8L, rec.receiver.getLinkCredit());
        assertEquals(Long.valueOf(8), ((Flow) h.last().performative).getLinkCredit());
    }

    @Test
    public void testDeliveryCountStartsFromSendersInitialCount() {
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "queue://orders", rec);
        Attach ours = (Attach) h.last().performative;
        Attach broker = ClientHarness.brokerAttach(ours, 0);
        broker.setInitialDeliveryCount(Long.valueOf(4000000000L));
        h.feed(ClientHarness.brokerFrame(broker));
        rec.receiver.addCredit(2);
        assertEquals(Long.valueOf(4000000000L), ((Flow) h.last().performative).getDeliveryCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNonPositiveCreditRejected() {
        attach().receiver.addCredit(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachReceiverWithSenderAttachRejected() {
        session.session.attachReceiver(new Attach("x", 0, false), new ReceiverRecorder());
    }

    @Test
    public void testRefusedReceiverAttach() {
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "queue://nope", rec);
        h.feed(ClientHarness.brokerFrame(new Attach("r1", 0, false))); // null source: refused
        assertNull(rec.receiver);
        h.feed(ClientHarness.brokerFrame(new Detach(0, true,
                new Amqp1Error(Amqp1Error.NOT_FOUND, "no such queue"))));
        assertTrue(rec.detached);
        assertEquals(Amqp1Error.NOT_FOUND, rec.detachError.getCondition());
    }

    // ── receiving messages ──

    @Test
    public void testReceiveSingleFrameMessage() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "tag-0", false), message(bytes("payload"))));
        assertEquals(Arrays.asList("start 0", "properties", "ap", "data 7", "end"), rec.events);
        assertEquals("hello", rec.properties.getSubject());
        assertEquals("v", rec.applicationProperties.get("k"));
        assertArrayEquals(bytes("payload"), rec.data.toByteArray());
        assertArrayEquals(bytes("tag-0"), rec.delivery.getTag());
        assertFalse(rec.delivery.isSettled());
        assertEquals("one credit used", 4L, rec.receiver.getLinkCredit());
    }

    @Test
    public void testMessageStreamedAcrossFrames() {
        ReceiverRecorder rec = readyReceiver(5);
        byte[] body = pattern(3000);
        byte[] msg = message(body);
        int a = 500;
        int b = 1500;
        h.feed(ClientHarness.brokerTransfer(first(0, "t", true), slice(msg, 0, a)));
        assertTrue("first frame's body octets were delivered already: " + rec.data.size(),
                rec.data.size() > 0);
        assertFalse("not complete yet", rec.events.contains("end"));
        h.feed(ClientHarness.brokerTransfer(continuation(true), slice(msg, a, b)));
        int soFar = rec.data.size();
        assertTrue(soFar > 1000);
        h.feed(ClientHarness.brokerTransfer(continuation(false), slice(msg, b, msg.length)));
        assertTrue(rec.events.contains("end"));
        assertArrayEquals(body, rec.data.toByteArray());
        assertEquals("a multi-frame delivery costs one credit", 4L, rec.receiver.getLinkCredit());
        assertEquals("one startDelivery", 1, rec.starts);
    }

    @Test
    public void testMultiFrameMessageFedOneByteAtATime() {
        ReceiverRecorder rec = readyReceiver(5);
        byte[] body = pattern(1200);
        byte[] msg = message(body);
        h.feedByteByByte(
                ClientHarness.brokerTransfer(first(0, "t", true), slice(msg, 0, 400)),
                ClientHarness.brokerTransfer(continuation(true), slice(msg, 400, 900)),
                ClientHarness.brokerTransfer(continuation(false), slice(msg, 900, msg.length)));
        assertNull(rec.error);
        assertTrue(rec.events.contains("end"));
        assertArrayEquals(body, rec.data.toByteArray());
    }

    @Test
    public void testMessageSplitAtEveryBoundaryOfTheFrameStream() {
        byte[] body = pattern(300);
        byte[] msg = message(body);
        ByteBuffer frame = ClientHarness.brokerTransfer(first(0, "t", false), msg);
        byte[] wire = new byte[frame.remaining()];
        frame.get(wire);
        for (int split = 1; split < wire.length; split++) {
            setUp();
            ReceiverRecorder rec = readyReceiver(5);
            // The caller owns the buffer: an incomplete frame header is left in
            // it, and compact() preserves it ahead of the next read
            ByteBuffer buf = ByteBuffer.allocate(wire.length);
            buf.put(wire, 0, split);
            buf.flip();
            h.handler.receive(buf);
            buf.compact();
            buf.put(wire, split, wire.length - split);
            buf.flip();
            h.handler.receive(buf);
            assertFalse("split at " + split, buf.hasRemaining());
            assertNull("split at " + split, rec.error);
            assertTrue("split at " + split, rec.events.contains("end"));
            assertArrayEquals("split at " + split, body, rec.data.toByteArray());
        }
    }

    @Test
    public void testHandleAndDataReachTheApplicationInOrderForTwoMessages() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "a", false), message(bytes("one"))));
        h.feed(ClientHarness.brokerTransfer(first(1, "b", false), message(bytes("two"))));
        assertEquals(2, rec.starts);
        assertEquals(2, rec.ends);
        assertEquals("onetwo", new String(rec.data.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(3L, rec.receiver.getLinkCredit());
    }

    @Test
    public void testEmptyMessageBody() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), new byte[0]));
        assertEquals(Arrays.asList("start 0", "end"), rec.events);
    }

    @Test
    public void testHeaderSectionDelivered() {
        ReceiverRecorder rec = readyReceiver(5);
        Amqp1Encoder e = new Amqp1Encoder();
        MessageHeader header = new MessageHeader();
        header.setDeliveryCount(3);
        MessageWriter.writeHeader(e, header);
        MessageWriter.writeAmqpValue(e, "text");
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), e.toByteArray()));
        assertEquals(Arrays.asList("start 0", "header", "value", "end"), rec.events);
        assertEquals(3L, rec.header.getDeliveryCount());
        assertEquals("text", rec.value);
    }

    @Test
    public void testMalformedMessageIsReportedToTheApplicationOnly() {
        ReceiverRecorder rec = readyReceiver(5);
        // properties section before header: out of order
        Amqp1Encoder e = new Amqp1Encoder();
        MessageWriter.writeProperties(e, new MessageProperties());
        MessageWriter.writeHeader(e, new MessageHeader());
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), e.toByteArray()));
        assertNotNull(rec.messageError);
        assertNull("the connection survives a bad message", h.ready.error);
        assertEquals(4L, rec.receiver.getLinkCredit());
    }

    // ── disposition ──

    @Test
    public void testAcceptSendsDispositionAndSettles() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(7, "t", false), message(bytes("x"))));
        rec.delivery.accept();
        Disposition d = (Disposition) h.last().performative;
        assertTrue(d.isReceiver());
        assertEquals(7L, d.getFirst());
        assertTrue(d.isSettled());
        assertEquals(DeliveryState.Type.ACCEPTED, d.getState().getType());
        assertTrue(rec.delivery.isSettled());
    }

    @Test
    public void testReleaseAndReject() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "a", false), message(bytes("x"))));
        rec.delivery.release();
        assertEquals(DeliveryState.Type.RELEASED,
                ((Disposition) h.last().performative).getState().getType());
        h.feed(ClientHarness.brokerTransfer(first(1, "b", false), message(bytes("y"))));
        rec.delivery.reject(new Amqp1Error(Amqp1Error.INVALID_FIELD, "bad"));
        Disposition d = (Disposition) h.last().performative;
        assertEquals(1L, d.getFirst());
        assertEquals(DeliveryState.Type.REJECTED, d.getState().getType());
        assertEquals(Amqp1Error.INVALID_FIELD, d.getState().getError().getCondition());
    }

    @Test
    public void testDisposeWithoutSettlingThenSettle() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), message(bytes("x"))));
        rec.delivery.dispose(DeliveryState.modified(true, false, null), false);
        Disposition first = (Disposition) h.last().performative;
        assertFalse(first.isSettled());
        assertFalse(rec.delivery.isSettled());
        rec.delivery.dispose(DeliveryState.accepted(), true);
        assertTrue(rec.delivery.isSettled());
    }

    @Test
    public void testDisposingASettledDeliveryRejected() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), message(bytes("x"))));
        rec.delivery.accept();
        try {
            rec.delivery.accept();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testPresettledDeliveryNeedsNoDisposition() {
        ReceiverRecorder rec = readyReceiver(5);
        Transfer t = new Transfer(0);
        t.setFirst(0, bytes("t"), true);
        h.feed(ClientHarness.brokerTransfer(t, message(bytes("x"))));
        assertTrue(rec.delivery.isSettled());
        try {
            rec.delivery.accept();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    // ── aborted deliveries ──

    @Test
    public void testAbortedDelivery() {
        ReceiverRecorder rec = readyReceiver(5);
        byte[] msg = message(pattern(2000));
        h.feed(ClientHarness.brokerTransfer(first(0, "t", true), slice(msg, 0, 600)));
        Transfer abort = continuation(false);
        abort.setAborted(true);
        h.feed(ClientHarness.brokerTransfer(abort, new byte[0]));
        assertEquals(1, rec.aborts);
        assertFalse("no endMessage for an aborted delivery", rec.events.contains("end"));
        assertEquals("the aborted delivery still used its credit", 4L, rec.receiver.getLinkCredit());
        // the link carries on
        h.feed(ClientHarness.brokerTransfer(first(1, "t2", false), message(bytes("ok"))));
        assertTrue(rec.events.contains("end"));
    }

    @Test
    public void testDeliveryAbortedOnItsFirstFrame() {
        ReceiverRecorder rec = readyReceiver(5);
        Transfer t = first(0, "t", true);
        t.setAborted(true);
        h.feed(ClientHarness.brokerTransfer(t, new byte[0]));
        assertEquals(1, rec.starts);
        assertEquals(1, rec.aborts);
    }

    // ── errors by the broker ──

    @Test
    public void testTransferWithoutCreditFailsConnection() {
        readyReceiver(1);
        h.feed(ClientHarness.brokerTransfer(first(0, "a", false), message(bytes("x"))));
        h.feed(ClientHarness.brokerTransfer(first(1, "b", false), message(bytes("y"))));
        assertNotNull(h.ready.error);
        assertEquals(Amqp1Error.TRANSFER_LIMIT_EXCEEDED,
                ((Close) h.last().performative).getError().getCondition());
    }

    @Test
    public void testTransferOnUnattachedHandleFailsConnection() {
        Transfer t = new Transfer(9);
        t.setFirst(0, bytes("t"), false);
        h.feed(ClientHarness.brokerTransfer(t, new byte[0]));
        assertNotNull(h.ready.error);
        assertEquals(Amqp1Error.UNATTACHED_HANDLE,
                ((Close) h.last().performative).getError().getCondition());
    }

    @Test
    public void testFirstTransferWithoutIdFailsConnection() {
        readyReceiver(5);
        h.feed(ClientHarness.brokerTransfer(continuation(false), new byte[0]));
        assertNotNull(h.ready.error);
    }

    @Test
    public void testInterleavedDeliveriesFailConnection() {
        readyReceiver(5);
        byte[] msg = message(pattern(500));
        h.feed(ClientHarness.brokerTransfer(first(0, "a", true), slice(msg, 0, 100)));
        h.feed(ClientHarness.brokerTransfer(first(1, "b", true), slice(msg, 0, 100)));
        assertNotNull(h.ready.error);
    }

    @Test
    public void testWindowViolationFailsConnection() {
        h = new ClientHarness();
        h.open();
        session = h.beginSession(new Begin(0, 0, 0), new Begin(0, 2048, 2048)); // no incoming window
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "q", rec);
        Attach ours = (Attach) h.last().performative;
        h.feed(ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, 0)));
        rec.receiver.addCredit(5);
        h.feed(ClientHarness.brokerTransfer(first(0, "t", false), message(bytes("x"))));
        assertNotNull(h.ready.error);
        assertEquals(Amqp1Error.WINDOW_VIOLATION,
                ((Close) h.last().performative).getError().getCondition());
    }

    @Test
    public void testPayloadOnAnAbortedFrameIsIgnored() {
        ReceiverRecorder rec = readyReceiver(5);
        Transfer t = first(0, "t", false);
        t.setAborted(true);
        h.feed(ClientHarness.brokerTransfer(t, bytes("junk that is ignored")));
        assertNull(h.ready.error);
        assertEquals(1, rec.aborts);
    }

    // ── session window ──

    @Test
    public void testIncomingWindowIsReplenished() {
        h = new ClientHarness();
        h.open();
        session = h.beginSession(new Begin(0, 4, 4), new Begin(0, 2048, 2048));
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "q", rec);
        Attach ours = (Attach) h.last().performative;
        h.feed(ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, 0)));
        rec.receiver.addCredit(20);
        int flowsBefore = h.sent(Flow.class).size();
        h.feed(ClientHarness.brokerTransfer(first(0, "a", false), message(bytes("1"))));
        assertEquals("window 4 -> 3: no flow yet", flowsBefore, h.sent(Flow.class).size());
        h.feed(ClientHarness.brokerTransfer(first(1, "b", false), message(bytes("2"))));
        // window 4 -> 2, at the halfway mark: the client tells the broker it may send 4 more
        List<Flow> flows = h.sent(Flow.class);
        assertEquals(flowsBefore + 1, flows.size());
        Flow f = flows.get(flows.size() - 1);
        assertNull("a session-level flow", f.getHandle());
        assertEquals(Long.valueOf(2), f.getNextIncomingId());
        assertEquals(4L, f.getIncomingWindow());
    }

    @Test
    public void testManyMessagesNeverExhaustTheWindow() {
        h = new ClientHarness();
        h.open();
        session = h.beginSession(new Begin(0, 4, 4), new Begin(0, 2048, 2048));
        ReceiverRecorder rec = new ReceiverRecorder();
        session.session.attachReceiver("r1", "q", rec);
        Attach ours = (Attach) h.last().performative;
        h.feed(ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, 0)));
        rec.receiver.addCredit(100);
        for (int i = 0; i < 50; i++) {
            h.feed(ClientHarness.brokerTransfer(first(i, "t" + i, false), message(bytes("m"))));
        }
        assertNull(h.ready.error);
        assertEquals(50, rec.ends);
    }

    // ── flow, detach ──

    @Test
    public void testEchoedFlowIsAnswered() {
        ReceiverRecorder rec = readyReceiver(5);
        int before = h.sent(Flow.class).size();
        Flow echo = new Flow(Long.valueOf(0), 2048, 0, 2048);
        echo.setLink(0, 0, 0);
        echo.setEcho(true);
        h.feed(ClientHarness.brokerFrame(echo));
        assertEquals(before + 1, h.sent(Flow.class).size());
        assertEquals(Long.valueOf(5), h.sent(Flow.class).get(before).getLinkCredit());
        assertNotNull(rec);
    }

    @Test
    public void testDrainCompletionUpdatesCredit() {
        ReceiverRecorder rec = readyReceiver(5);
        Flow drained = new Flow(Long.valueOf(0), 2048, 0, 2048);
        drained.setLink(0, 5, 0);
        drained.setDrain(true);
        h.feed(ClientHarness.brokerFrame(drained));
        assertEquals(0L, rec.receiver.getLinkCredit());
    }

    @Test
    public void testDetachAwaitsPeerDetach() {
        ReceiverRecorder rec = readyReceiver(5);
        rec.receiver.detach(null, false);
        Detach sent = (Detach) h.last().performative;
        assertFalse(sent.isClosed());
        assertFalse(rec.detached);
        h.feed(ClientHarness.brokerFrame(new Detach(0, false, null)));
        assertTrue(rec.detached);
        assertFalse(rec.detachClosed);
    }

    @Test
    public void testPeerDetachMidDeliveryDiscardsIt() {
        ReceiverRecorder rec = readyReceiver(5);
        byte[] msg = message(pattern(1000));
        h.feed(ClientHarness.brokerTransfer(first(0, "t", true), slice(msg, 0, 300)));
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        assertTrue(rec.detached);
        assertFalse(rec.events.contains("end"));
        assertEquals("amqp/0 Detach", h.last().toString());
    }

    @Test
    public void testSessionEndDetachesReceiver() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerFrame(new org.bluezoo.gumdrop.amqp1.codec.End()));
        assertTrue(rec.detached);
        assertFalse("a session ending detaches, it does not close, the link", rec.detachClosed);
        assertTrue(session.ended);
    }

    @Test
    public void testAddCreditAfterDetachRejected() {
        ReceiverRecorder rec = readyReceiver(5);
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        try {
            rec.receiver.addCredit(1);
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    // ── recorder ──

    private static final class ReceiverRecorder implements Amqp1ReceiverHandler {
        Amqp1Receiver receiver;
        Attach peerAttach;
        Amqp1IncomingDelivery delivery;
        final List<String> events = new ArrayList<String>();
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int starts;
        int ends;
        int aborts;
        boolean detached;
        boolean detachClosed;
        Amqp1Error detachError;
        String messageError;
        Exception error;
        MessageHeader header;
        MessageProperties properties;
        Map<Object, Object> applicationProperties;
        Object value;

        @Override
        public void handleAttached(Amqp1Receiver r, Attach peer) {
            receiver = r;
            peerAttach = peer;
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery d) {
            starts++;
            delivery = d;
            events.add("start " + d.getDeliveryId());
        }

        @Override
        public void handleAborted(Amqp1IncomingDelivery d) {
            aborts++;
        }

        @Override
        public void handleDetached(Amqp1Error e, boolean closed) {
            detached = true;
            detachError = e;
            detachClosed = closed;
        }

        @Override
        public void header(MessageHeader hd) {
            events.add("header");
            header = hd;
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> a) {
            events.add("da");
        }

        @Override
        public void messageAnnotations(Map<Object, Object> a) {
            events.add("ma");
        }

        @Override
        public void properties(MessageProperties p) {
            events.add("properties");
            properties = p;
        }

        @Override
        public void applicationProperties(Map<Object, Object> p) {
            events.add("ap");
            applicationProperties = p;
        }

        @Override
        public void startData(long length) {
            events.add("data " + length);
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            data.write(b, 0, b.length);
        }

        @Override
        public void endData() {
        }

        @Override
        public void amqpSequence(List<Object> row) {
            events.add("seq");
        }

        @Override
        public void amqpValue(Object v) {
            events.add("value");
            value = v;
        }

        @Override
        public void footer(Map<Object, Object> f) {
            events.add("footer");
        }

        @Override
        public void endMessage() {
            events.add("end");
            ends++;
        }

        @Override
        public void messageError(String message) {
            messageError = message;
        }
    }
}
