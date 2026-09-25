/*
 * Amqp1SenderTest.java
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.amqp1.client.FakeAmqp1Peer.Out;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Disposition;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageHandler;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageParser;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for sending links: attach, credit, streamed transfer, session
 * windows, settlement and detach, against the fake broker.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1SenderTest {

    private ClientHarness h;
    private ClientHarness.SessionRecorder session;
    private SenderRecorder sender;

    @Before
    public void setUp() {
        h = new ClientHarness();
        session = h.openSession();
    }

    // ── helpers ──

    /** Attaches a sender and has the broker accept it. */
    private SenderRecorder attach(String name, Attach template) {
        SenderRecorder rec = new SenderRecorder();
        if (template == null) {
            session.session.attachSender(name, "queue://orders", rec);
        } else {
            session.session.attachSender(template, rec);
        }
        List<Attach> sent = h.sent(Attach.class);
        Attach ours = sent.get(sent.size() - 1);
        h.feed(ClientHarness.brokerFrame(ClientHarness.brokerAttach(ours, ours.getHandle())));
        return rec;
    }

    private void grantCredit(SenderRecorder rec, long handle, long deliveryCount, long credit) {
        grantCredit(rec, handle, deliveryCount, credit, 2048);
    }

    /** A link flow that also states the broker's session window (in frames). */
    private void grantCredit(SenderRecorder rec, long handle, long deliveryCount, long credit,
            long brokerWindow) {
        Flow f = new Flow(Long.valueOf(0), brokerWindow, 0, 2048);
        f.setLink(handle, deliveryCount, credit);
        h.feed(ClientHarness.brokerFrame(f));
    }

    private SenderRecorder readySender() {
        sender = attach("s1", null);
        grantCredit(sender, 0, 0, 10);
        return sender;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Parses the message octets of a transfer into a recorder. */
    private static MessageRecorder parse(byte[] payload) {
        MessageRecorder r = new MessageRecorder();
        MessageParser p = new MessageParser(r);
        p.receive(ByteBuffer.wrap(payload));
        p.endMessage();
        assertNull(r.error, r.error);
        return r;
    }

    private static byte[] payloadOf(List<Out> transfers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Out o : transfers) {
            out.write(o.payload, 0, o.payload.length);
        }
        return out.toByteArray();
    }

    // ── attach ──

    @Test
    public void testAttachSenderSendsAttach() {
        SenderRecorder rec = new SenderRecorder();
        session.session.attachSender("orders-out", "queue://orders", rec);
        Out out = h.last();
        assertEquals("amqp/0 Attach", out.toString());
        Attach a = (Attach) out.performative;
        assertEquals("orders-out", a.getName());
        assertEquals(0L, a.getHandle());
        assertFalse(a.isReceiver());
        assertEquals("queue://orders", a.getTarget().getAddress());
        assertNotNull("a sender advertises an (empty) source", a.getSource());
        assertEquals(Long.valueOf(0), a.getInitialDeliveryCount());
    }

    @Test
    public void testPeerAttachDeliversHandlerCallback() {
        SenderRecorder rec = attach("s1", null);
        assertNotNull(rec.sender);
        assertEquals("queue://orders", rec.peerAttach.getTarget().getAddress());
        assertEquals("s1", rec.sender.getName());
        assertSame(rec.peerAttach, rec.sender.getPeerAttach());
        assertEquals(0L, rec.sender.getLinkCredit());
    }

    @Test
    public void testHandlesAreDistinctAndReused() {
        SenderRecorder a = attach("a", null);
        SenderRecorder b = attach("b", null);
        List<Attach> sent = h.sent(Attach.class);
        assertEquals(0L, sent.get(0).getHandle());
        assertEquals(1L, sent.get(1).getHandle());
        a.sender.detach(null, true);
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        attach("c", null);
        assertEquals("the freed handle is reused", 0L, h.sent(Attach.class).get(2).getHandle());
        assertNotNull(b.sender);
    }

    @Test(expected = IllegalStateException.class)
    public void testDuplicateLinkNameRejected() {
        attach("dup", null);
        session.session.attachSender("dup", "queue://other", new SenderRecorder());
    }

    @Test
    public void testSameNameAllowedForOppositeDirection() {
        attach("both", null);
        session.session.attachReceiver("both", "queue://x", new ReceiverProbe());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachSenderWithReceiverAttachRejected() {
        session.session.attachSender(new Attach("x", 0, true), new SenderRecorder());
    }

    @Test
    public void testAttachWithCustomSettleModeAndTarget() {
        Attach a = new Attach("custom", 0, false);
        a.setSndSettleMode(Attach.SND_UNSETTLED);
        a.setRcvSettleMode(Attach.RCV_SECOND);
        a.setMaxMessageSize(1024);
        org.bluezoo.gumdrop.amqp1.codec.Target t = new org.bluezoo.gumdrop.amqp1.codec.Target("topic://t");
        t.setDurable(2);
        a.setTarget(t);
        session.session.attachSender(a, new SenderRecorder());
        Attach sent = (Attach) h.last().performative;
        assertEquals(Attach.SND_UNSETTLED, sent.getSndSettleMode());
        assertEquals(Attach.RCV_SECOND, sent.getRcvSettleMode());
        assertEquals(1024L, sent.getMaxMessageSize());
        assertEquals(2L, sent.getTarget().getDurable());
    }

    @Test
    public void testRefusedAttachReportsDetachNotAttached() {
        SenderRecorder rec = new SenderRecorder();
        session.session.attachSender("refused", "queue://nope", rec);
        Attach ours = (Attach) h.last().performative;
        Attach refusal = new Attach("refused", 0, true); // null target: refused
        h.feed(ClientHarness.brokerFrame(refusal));
        assertNull("no handleAttached for a refused link", rec.sender);
        h.feed(ClientHarness.brokerFrame(new Detach(0, true,
                new Amqp1Error(Amqp1Error.NOT_FOUND, "no such queue"))));
        assertTrue(rec.detached);
        assertEquals(Amqp1Error.NOT_FOUND, rec.detachError.getCondition());
        assertEquals("amqp/0 Detach", h.last().toString());
        assertEquals(ours.getHandle(), ((Detach) h.last().performative).getHandle());
    }

    @Test
    public void testPeerInitiatedLinkIsRefused() {
        Attach unsolicited = new Attach("push", 3, false);
        unsolicited.setTarget(new org.bluezoo.gumdrop.amqp1.codec.Target("x"));
        unsolicited.setInitialDeliveryCount(Long.valueOf(0));
        h.feed(ClientHarness.brokerFrame(unsolicited));
        assertNotNull(h.ready.error);
        assertEquals(Amqp1Error.NOT_IMPLEMENTED,
                ((Close) h.last().performative).getError().getCondition());
    }

    // ── credit ──

    @Test
    public void testCreditFromFlow() {
        SenderRecorder rec = attach("s1", null);
        grantCredit(rec, 0, 0, 10);
        assertEquals(10L, rec.sender.getLinkCredit());
        assertEquals(1, rec.creditEvents);
    }

    @Test
    public void testCreditIsRelativeToDeliveryCount() {
        SenderRecorder rec = readySender();
        rec.sender.send(bytes("t1"), null, null, ByteBuffer.wrap(bytes("x")));
        rec.sender.send(bytes("t2"), null, null, ByteBuffer.wrap(bytes("x")));
        assertEquals(8L, rec.sender.getLinkCredit());
        // the broker's flow: it has seen 1 delivery and grants 5 more; we have sent 2
        grantCredit(rec, 0, 1, 5);
        assertEquals("1 + 5 - 2", 4L, rec.sender.getLinkCredit());
    }

    @Test
    public void testCreditCannotGoNegative() {
        SenderRecorder rec = readySender();
        rec.sender.send(bytes("t1"), null, null, ByteBuffer.wrap(bytes("x")));
        grantCredit(rec, 0, 0, 0);
        assertEquals(0L, rec.sender.getLinkCredit());
    }

    @Test(expected = IllegalStateException.class)
    public void testSendWithoutCreditRejected() {
        SenderRecorder rec = attach("s1", null);
        rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
    }

    @Test
    public void testDrainConsumesCreditAndConfirms() {
        SenderRecorder rec = readySender();
        Flow drain = new Flow(Long.valueOf(0), 2048, 0, 2048);
        drain.setLink(0, 0, 10);
        drain.setDrain(true);
        h.feed(ClientHarness.brokerFrame(drain));
        assertEquals(0L, rec.sender.getLinkCredit());
        Flow reply = h.sent(Flow.class).get(h.sent(Flow.class).size() - 1);
        assertTrue(reply.isDrain());
        assertEquals(Long.valueOf(10), reply.getDeliveryCount());
        assertEquals(Long.valueOf(0), reply.getLinkCredit());
    }

    @Test
    public void testEchoedFlowIsAnswered() {
        SenderRecorder rec = readySender();
        int before = h.sent(Flow.class).size();
        Flow echo = new Flow(Long.valueOf(0), 2048, 0, 2048);
        echo.setLink(0, 0, 10);
        echo.setEcho(true);
        h.feed(ClientHarness.brokerFrame(echo));
        assertEquals(before + 1, h.sent(Flow.class).size());
        assertEquals(Long.valueOf(0), h.sent(Flow.class).get(before).getHandle());
        assertNotNull(rec);
    }

    @Test
    public void testFlowForUnattachedHandleFailsConnection() {
        Flow f = new Flow(Long.valueOf(0), 10, 0, 10);
        f.setLink(9, 0, 1);
        h.feed(ClientHarness.brokerFrame(f));
        assertNotNull(h.ready.error);
        assertEquals(Amqp1Error.UNATTACHED_HANDLE,
                ((Close) h.last().performative).getError().getCondition());
    }

    // ── sending ──

    @Test
    public void testSendSmallMessage() {
        SenderRecorder rec = readySender();
        MessageProperties props = new MessageProperties();
        props.setSubject("hello");
        Map<Object, Object> appProps = new LinkedHashMap<Object, Object>();
        appProps.put("colour", "red");
        Amqp1OutgoingDelivery d = rec.sender.send(bytes("tag-1"), props, appProps,
                ByteBuffer.wrap(bytes("payload")));

        List<Out> transfers = h.sentOuts(Transfer.class);
        assertEquals(1, transfers.size());
        Transfer t = (Transfer) transfers.get(0).performative;
        assertEquals(0L, t.getHandle());
        assertEquals(Long.valueOf(0), t.getDeliveryId());
        assertArrayEquals(bytes("tag-1"), t.getDeliveryTag());
        assertEquals("unsettled by default", Boolean.FALSE, t.getSettled());
        assertFalse(t.isMore());
        assertEquals(Long.valueOf(0), d.getDeliveryId());
        assertFalse(d.isSettled());
        MessageRecorder m = parse(transfers.get(0).payload);
        assertEquals("hello", m.properties.getSubject());
        assertEquals("red", m.applicationProperties.get("colour"));
        assertArrayEquals(bytes("payload"), m.data.toByteArray());
        assertEquals(9L, rec.sender.getLinkCredit());
    }

    @Test
    public void testDeliveryIdsIncrease() {
        SenderRecorder rec = readySender();
        rec.sender.send(bytes("a"), null, null, ByteBuffer.wrap(bytes("1")));
        rec.sender.send(bytes("b"), null, null, ByteBuffer.wrap(bytes("2")));
        List<Out> transfers = h.sentOuts(Transfer.class);
        assertEquals(Long.valueOf(0), ((Transfer) transfers.get(0).performative).getDeliveryId());
        assertEquals(Long.valueOf(1), ((Transfer) transfers.get(1).performative).getDeliveryId());
    }

    @Test
    public void testDeliveryIdsAreSessionWideAcrossLinks() {
        SenderRecorder a = readySender();
        SenderRecorder b = attach("s2", null);
        grantCredit(b, 1, 0, 5);
        a.sender.send(bytes("a"), null, null, ByteBuffer.wrap(bytes("1")));
        b.sender.send(bytes("b"), null, null, ByteBuffer.wrap(bytes("2")));
        a.sender.send(bytes("c"), null, null, ByteBuffer.wrap(bytes("3")));
        List<Out> transfers = h.sentOuts(Transfer.class);
        assertEquals(Long.valueOf(0), ((Transfer) transfers.get(0).performative).getDeliveryId());
        assertEquals(Long.valueOf(1), ((Transfer) transfers.get(1).performative).getDeliveryId());
        assertEquals(1L, ((Transfer) transfers.get(1).performative).getHandle());
        assertEquals(Long.valueOf(2), ((Transfer) transfers.get(2).performative).getDeliveryId());
    }

    @Test
    public void testHeaderIsSent() {
        SenderRecorder rec = readySender();
        MessageHeader header = new MessageHeader();
        header.setDurable(true);
        header.setPriority(9);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("h"), header, null, null, false);
        d.write(ByteBuffer.wrap(bytes("b")));
        d.finish();
        MessageRecorder m = parse(h.sentOuts(Transfer.class).get(0).payload);
        assertTrue(m.header.isDurable());
        assertEquals(9, m.header.getPriority());
    }

    @Test
    public void testSecondDeliveryWaitsForFirstToFinish() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("a"), null, null, null, false);
        try {
            rec.sender.startDelivery(bytes("b"), null, null, null, false);
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("in progress"));
        }
        d.finish();
        rec.sender.startDelivery(bytes("b"), null, null, null, false).finish();
    }

    @Test
    public void testDeliveryTagValidated() {
        SenderRecorder rec = readySender();
        for (byte[] bad : new byte[][] {null, new byte[0], new byte[33]}) {
            try {
                rec.sender.startDelivery(bad, null, null, null, false);
                fail("expected rejection");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testWriteAfterFinishRejected() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("a"), null, null, null, false);
        d.finish();
        d.write(ByteBuffer.wrap(bytes("late")));
    }

    // ── streaming ──

    /** A session whose broker only accepts frames of 1024 octets. */
    private SenderRecorder smallFrameSender(long brokerIncomingWindow) {
        h = new ClientHarness();
        Open theirs = new Open("broker");
        theirs.setMaxFrameSize(1024);
        h.open(new Open("client-1"), theirs);
        session = h.beginSession(new Begin(0, 2048, 2048),
                new Begin(0, brokerIncomingWindow, 2048));
        sender = attach("s1", null);
        grantCredit(sender, 0, 0, 10, brokerIncomingWindow);
        return sender;
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7);
        }
        return b;
    }

    @Test
    public void testLargeMessageIsSplitAcrossFrames() {
        SenderRecorder rec = smallFrameSender(2048);
        byte[] body = pattern(5000);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("big"), null, null, null, false);
        for (int off = 0; off < body.length; off += 700) {
            int n = Math.min(700, body.length - off);
            assertTrue(d.write(ByteBuffer.wrap(body, off, n)));
        }
        d.finish();

        List<Out> transfers = h.sentOuts(Transfer.class);
        assertTrue("several frames: " + transfers.size(), transfers.size() >= 5);
        for (int i = 0; i < transfers.size(); i++) {
            Out o = transfers.get(i);
            Transfer t = (Transfer) o.performative;
            int frameSize = 8 + t.encode().remaining() + o.payload.length;
            assertTrue("frame " + i + " is " + frameSize, frameSize <= 1024);
            boolean last = i == transfers.size() - 1;
            assertEquals("more on frame " + i, !last, t.isMore());
            if (i == 0) {
                assertEquals(Long.valueOf(0), t.getDeliveryId());
                assertArrayEquals(bytes("big"), t.getDeliveryTag());
            } else {
                assertNull("continuations omit the delivery-id", t.getDeliveryId());
                assertNull(t.getDeliveryTag());
            }
        }
        MessageRecorder m = parse(payloadOf(transfers));
        assertArrayEquals(body, m.data.toByteArray());
        assertEquals("one data section per write", 8, m.dataSections);
    }

    @Test
    public void testSmallWritesAreCoalescedIntoFullFrames() {
        SenderRecorder rec = smallFrameSender(2048);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("c"), null, null, null, false);
        for (int i = 0; i < 100; i++) {
            d.write(ByteBuffer.wrap(bytes("0123456789")));
        }
        assertTrue("nothing sent until a frame fills or the delivery finishes",
                h.sentOuts(Transfer.class).size() <= 2);
        d.finish();
        assertEquals(1000, parse(payloadOf(h.sentOuts(Transfer.class))).data.size());
    }

    @Test
    public void testFrameSizeExactMultipleStillEndsWithFinalFrame() {
        SenderRecorder rec = smallFrameSender(2048);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("e"), null, null, null, false);
        d.write(ByteBuffer.wrap(pattern(2000)));
        d.finish();
        List<Out> transfers = h.sentOuts(Transfer.class);
        assertFalse(((Transfer) transfers.get(transfers.size() - 1).performative).isMore());
        assertEquals(2000, parse(payloadOf(transfers)).data.size());
    }

    @Test
    public void testWriteAcceptsDirectBuffers() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("d"), null, null, null, false);
        ByteBuffer direct = ByteBuffer.allocateDirect(20000);
        direct.put(pattern(20000));
        direct.flip();
        d.write(direct);
        assertFalse(direct.hasRemaining());
        d.finish();
        assertEquals(20000, parse(payloadOf(h.sentOuts(Transfer.class))).data.size());
    }

    @Test
    public void testSessionWindowLimitsTransfersUntilFlow() {
        SenderRecorder rec = smallFrameSender(2); // the broker can take 2 frames
        byte[] body = pattern(5000);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("w"), null, null, null, false);
        boolean allAccepted = true;
        for (int off = 0; off < body.length; off += 700) {
            allAccepted &= d.write(ByteBuffer.wrap(body, off, Math.min(700, body.length - off)));
        }
        d.finish();
        assertFalse("write reports the exhausted window", allAccepted);
        assertEquals("only the window's worth was sent", 2, h.sentOuts(Transfer.class).size());
        assertEquals(0, rec.writableEvents);

        // the broker has taken 2 frames and opens its window to 10 more
        h.feed(ClientHarness.brokerFrame(new Flow(Long.valueOf(2), 10, 0, 2048)));
        assertTrue(h.sentOuts(Transfer.class).size() > 2);
        assertEquals("handleWritable once the queue drained", 1, rec.writableEvents);
        assertArrayEquals(body, parse(payloadOf(h.sentOuts(Transfer.class))).data.toByteArray());
    }

    @Test
    public void testWindowArithmeticUsesNextIncomingId() {
        SenderRecorder rec = smallFrameSender(1);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("x"), null, null, null, false);
        d.write(ByteBuffer.wrap(pattern(3000)));
        d.finish();
        int sentBefore = h.sentOuts(Transfer.class).size();
        assertEquals(1, sentBefore);
        // next-incoming-id 1 + window 1 - our next-outgoing-id 1 = 1 more frame
        h.feed(ClientHarness.brokerFrame(new Flow(Long.valueOf(1), 1, 0, 2048)));
        assertEquals(sentBefore + 1, h.sentOuts(Transfer.class).size());
    }

    @Test
    public void testAbortSendsAbortedTransfer() {
        SenderRecorder rec = smallFrameSender(2048);
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("ab"), null, null, null, false);
        d.write(ByteBuffer.wrap(pattern(3000)));
        d.abort();
        List<Out> transfers = h.sentOuts(Transfer.class);
        Transfer last = (Transfer) transfers.get(transfers.size() - 1).performative;
        assertTrue(last.isAborted());
        assertFalse(last.isMore());
        // the link is free for another delivery
        rec.sender.startDelivery(bytes("next"), null, null, null, false).finish();
    }

    @Test
    public void testAbortBeforeAnythingSentSendsNothing() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.startDelivery(bytes("ab"), null, null, null, false);
        d.abort();
        assertEquals(0, h.sentOuts(Transfer.class).size());
    }

    // ── settlement ──

    @Test
    public void testAcceptedOutcomeSettledByReceiver() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
        Disposition disp = new Disposition(true, 0, null);
        disp.setSettled(true);
        disp.setState(DeliveryState.accepted());
        h.feed(ClientHarness.brokerFrame(disp));
        assertEquals(1, rec.outcomes.size());
        assertSame(d, rec.outcomes.get(0).delivery);
        assertEquals(DeliveryState.Type.ACCEPTED, rec.outcomes.get(0).state.getType());
        assertTrue(rec.outcomes.get(0).settled);
        assertTrue(d.isSettled());
    }

    @Test
    public void testRejectedOutcome() {
        SenderRecorder rec = readySender();
        rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
        Disposition disp = new Disposition(true, 0, null);
        disp.setSettled(true);
        disp.setState(DeliveryState.rejected(new Amqp1Error(Amqp1Error.NOT_ALLOWED, "no")));
        h.feed(ClientHarness.brokerFrame(disp));
        assertEquals(DeliveryState.Type.REJECTED, rec.outcomes.get(0).state.getType());
        assertEquals(Amqp1Error.NOT_ALLOWED, rec.outcomes.get(0).state.getError().getCondition());
    }

    @Test
    public void testDispositionRangeCoversSeveralDeliveries() {
        SenderRecorder rec = readySender();
        for (int i = 0; i < 4; i++) {
            rec.sender.send(bytes("t" + i), null, null, ByteBuffer.wrap(bytes("x")));
        }
        Disposition disp = new Disposition(true, 1, Long.valueOf(3));
        disp.setSettled(true);
        disp.setState(DeliveryState.accepted());
        h.feed(ClientHarness.brokerFrame(disp));
        assertEquals(3, rec.outcomes.size());
    }

    @Test
    public void testUnsettledOutcomeThenClientSettles() {
        SenderRecorder rec = readySender();
        Amqp1OutgoingDelivery d = rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
        Disposition disp = new Disposition(true, 0, null);
        disp.setState(DeliveryState.accepted()); // not settled: receiver settle mode second
        h.feed(ClientHarness.brokerFrame(disp));
        assertFalse(rec.outcomes.get(0).settled);
        assertFalse(d.isSettled());
        d.settle();
        Disposition sent = (Disposition) h.last().performative;
        assertFalse("we are the sender", sent.isReceiver());
        assertEquals(0L, sent.getFirst());
        assertTrue(sent.isSettled());
        assertTrue(d.isSettled());
        try {
            d.settle();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testOutcomeForUnknownDeliveryIsIgnored() {
        SenderRecorder rec = readySender();
        Disposition disp = new Disposition(true, 50, null);
        disp.setSettled(true);
        h.feed(ClientHarness.brokerFrame(disp));
        assertTrue(rec.outcomes.isEmpty());
        assertNull(h.ready.error);
    }

    @Test
    public void testSettledSenderSendsPresettled() {
        Attach a = new Attach("fire-and-forget", 0, false);
        a.setSndSettleMode(Attach.SND_SETTLED);
        a.setTarget(new org.bluezoo.gumdrop.amqp1.codec.Target("queue://q"));
        SenderRecorder rec = attach(null, a);
        grantCredit(rec, 0, 0, 5);
        Amqp1OutgoingDelivery d = rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
        Transfer t = (Transfer) h.sentOuts(Transfer.class).get(0).performative;
        assertEquals(Boolean.TRUE, t.getSettled());
        assertTrue(d.isSettled());
        try {
            d.settle();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testSettleModeConflictsRejected() {
        Attach unsettledOnly = new Attach("u", 0, false);
        unsettledOnly.setSndSettleMode(Attach.SND_UNSETTLED);
        unsettledOnly.setTarget(new org.bluezoo.gumdrop.amqp1.codec.Target("queue://u"));
        SenderRecorder u = attach(null, unsettledOnly);
        grantCredit(u, 0, 0, 5);
        try {
            u.sender.startDelivery(bytes("t"), null, null, null, true);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        Attach settledOnly = new Attach("s", 0, false);
        settledOnly.setSndSettleMode(Attach.SND_SETTLED);
        settledOnly.setTarget(new org.bluezoo.gumdrop.amqp1.codec.Target("queue://s"));
        SenderRecorder s = attach(null, settledOnly);
        grantCredit(s, 1, 0, 5);
        try {
            s.sender.startDelivery(bytes("t"), null, null, null, false);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void testMixedModeAllowsBoth() {
        SenderRecorder rec = readySender();
        rec.sender.startDelivery(bytes("a"), null, null, null, true).finish();
        rec.sender.startDelivery(bytes("b"), null, null, null, false).finish();
        List<Out> transfers = h.sentOuts(Transfer.class);
        assertEquals(Boolean.TRUE, ((Transfer) transfers.get(0).performative).getSettled());
        assertEquals(Boolean.FALSE, ((Transfer) transfers.get(1).performative).getSettled());
    }

    // ── detach and end ──

    @Test
    public void testDetachAwaitsPeerDetach() {
        SenderRecorder rec = readySender();
        rec.sender.detach(null, true);
        Detach sent = (Detach) h.last().performative;
        assertEquals(0L, sent.getHandle());
        assertTrue(sent.isClosed());
        assertFalse(rec.detached);
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        assertTrue(rec.detached);
        assertTrue(rec.detachClosed);
        assertEquals("only one detach sent", 1, h.sent(Detach.class).size());
    }

    @Test
    public void testPeerDetachIsAnswered() {
        SenderRecorder rec = readySender();
        h.feed(ClientHarness.brokerFrame(new Detach(0, true,
                new Amqp1Error(Amqp1Error.DETACH_FORCED, "gone"))));
        assertTrue(rec.detached);
        assertEquals(Amqp1Error.DETACH_FORCED, rec.detachError.getCondition());
        assertEquals("amqp/0 Detach", h.last().toString());
        assertTrue(((Detach) h.last().performative).isClosed());
    }

    @Test
    public void testDetachedLinkRejectsFurtherUse() {
        SenderRecorder rec = readySender();
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        try {
            rec.sender.send(bytes("t"), null, null, ByteBuffer.wrap(bytes("x")));
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
        try {
            rec.sender.detach(null, true);
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testSessionEndDetachesLinksWithoutClosingThem() {
        SenderRecorder rec = readySender();
        h.feed(ClientHarness.brokerFrame(new End(new Amqp1Error(Amqp1Error.NOT_ALLOWED, "bye"))));
        assertTrue(rec.detached);
        assertEquals(Amqp1Error.NOT_ALLOWED, rec.detachError.getCondition());
        assertFalse("a session ending detaches, it does not close, the link", rec.detachClosed);
        assertTrue(session.ended);
    }

    @Test
    public void testConnectionCloseDetachesLinksWithoutClosingThem() {
        SenderRecorder rec = readySender();
        h.feed(FakeAmqp1Peer.amqpFrame(0, new Close()));
        assertTrue(rec.detached);
        assertFalse(rec.detachClosed);
    }

    @Test
    public void testConnectionLossDetachesLinksWithoutClosingThem() {
        SenderRecorder rec = readySender();
        h.handler.disconnected();
        assertTrue(rec.detached);
        assertFalse(rec.detachClosed);
    }

    @Test
    public void testBrokerClosingALinkIsAClose() {
        SenderRecorder rec = readySender();
        h.feed(ClientHarness.brokerFrame(new Detach(0, true, null)));
        assertTrue(rec.detachClosed);
    }

    // ── recorders ──

    private static final class Outcome {
        final Amqp1OutgoingDelivery delivery;
        final DeliveryState state;
        final boolean settled;

        Outcome(Amqp1OutgoingDelivery delivery, DeliveryState state, boolean settled) {
            this.delivery = delivery;
            this.state = state;
            this.settled = settled;
        }
    }

    private static final class SenderRecorder implements Amqp1SenderHandler {
        Amqp1Sender sender;
        Attach peerAttach;
        int creditEvents;
        int writableEvents;
        boolean detached;
        boolean detachClosed;
        Amqp1Error detachError;
        final List<Outcome> outcomes = new ArrayList<Outcome>();

        @Override
        public void handleAttached(Amqp1Sender s, Attach peer) {
            sender = s;
            peerAttach = peer;
        }

        @Override
        public void handleCredit(Amqp1Sender s) {
            creditEvents++;
        }

        @Override
        public void handleWritable(Amqp1OutgoingDelivery delivery) {
            writableEvents++;
        }

        @Override
        public void handleOutcome(Amqp1OutgoingDelivery delivery, DeliveryState state,
                boolean settled) {
            outcomes.add(new Outcome(delivery, state, settled));
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
            detached = true;
            detachError = error;
            detachClosed = closed;
        }
    }

    /** A receiver handler that does nothing, for tests that only need one attached. */
    private static final class ReceiverProbe implements Amqp1ReceiverHandler {
        @Override
        public void handleAttached(Amqp1Receiver receiver, Attach peerAttach) {
        }

        @Override
        public void startDelivery(Amqp1IncomingDelivery delivery) {
        }

        @Override
        public void handleAborted(Amqp1IncomingDelivery delivery) {
        }

        @Override
        public void handleDetached(Amqp1Error error, boolean closed) {
        }

        @Override
        public void header(MessageHeader header) {
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> a) {
        }

        @Override
        public void messageAnnotations(Map<Object, Object> a) {
        }

        @Override
        public void properties(MessageProperties p) {
        }

        @Override
        public void applicationProperties(Map<Object, Object> p) {
        }

        @Override
        public void startData(long length) {
        }

        @Override
        public void dataChunk(ByteBuffer chunk) {
        }

        @Override
        public void endData() {
        }

        @Override
        public void amqpSequence(List<Object> row) {
        }

        @Override
        public void amqpValue(Object value) {
        }

        @Override
        public void footer(Map<Object, Object> f) {
        }

        @Override
        public void endMessage() {
        }

        @Override
        public void messageError(String message) {
        }
    }

    /** Collects a parsed message. */
    private static final class MessageRecorder implements MessageHandler {
        MessageHeader header;
        MessageProperties properties;
        Map<Object, Object> applicationProperties;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int dataSections;
        String error;

        @Override
        public void header(MessageHeader h) {
            header = h;
        }

        @Override
        public void deliveryAnnotations(Map<Object, Object> a) {
        }

        @Override
        public void messageAnnotations(Map<Object, Object> a) {
        }

        @Override
        public void properties(MessageProperties p) {
            properties = p;
        }

        @Override
        public void applicationProperties(Map<Object, Object> p) {
            applicationProperties = p;
        }

        @Override
        public void startData(long length) {
            dataSections++;
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
        }

        @Override
        public void amqpValue(Object value) {
        }

        @Override
        public void footer(Map<Object, Object> f) {
        }

        @Override
        public void endMessage() {
        }

        @Override
        public void messageError(String message) {
            error = message;
        }
    }
}
