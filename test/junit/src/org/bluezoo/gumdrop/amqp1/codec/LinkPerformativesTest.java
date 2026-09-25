/*
 * LinkPerformativesTest.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for the link performatives ({@link Attach}, {@link Detach},
 * {@link Flow}, {@link Transfer}, {@link Disposition}) and the types they
 * carry ({@link Source}, {@link Target}, {@link DeliveryState}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LinkPerformativesTest {

    private static Performative roundTrip(Performative p) throws Exception {
        ByteBuffer buf = p.encode();
        Performative decoded = PerformativeCodec.decode(buf);
        assertFalse("decode must consume the whole performative", buf.hasRemaining());
        return decoded;
    }

    private static String encoded(Performative p) {
        ByteBuffer b = p.encode();
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        return Amqp1TypesTest.toHex(bytes);
    }

    private static Performative decode(String hex) throws Exception {
        return PerformativeCodec.decode(ByteBuffer.wrap(Amqp1TypesTest.hex(hex)));
    }

    // ── attach ──

    @Test
    public void testSenderAttachRoundTrip() throws Exception {
        Attach a = new Attach("link-1", 3, false);
        a.setSndSettleMode(Attach.SND_UNSETTLED);
        a.setRcvSettleMode(Attach.RCV_SECOND);
        a.setSource(new Source());
        a.setTarget(new Target("queue://orders"));
        a.setInitialDeliveryCount(Long.valueOf(0));
        a.setMaxMessageSize(1048576);
        a.getDesiredCapabilities().add("shared");
        a.getProperties().put(new Amqp1Symbol("p"), "v");

        Attach d = (Attach) roundTrip(a);
        assertEquals("link-1", d.getName());
        assertEquals(3L, d.getHandle());
        assertFalse(d.isReceiver());
        assertEquals(Attach.SND_UNSETTLED, d.getSndSettleMode());
        assertEquals(Attach.RCV_SECOND, d.getRcvSettleMode());
        assertNotNull(d.getSource());
        assertNull(d.getSource().getAddress());
        assertEquals("queue://orders", d.getTarget().getAddress());
        assertEquals(Long.valueOf(0), d.getInitialDeliveryCount());
        assertEquals(1048576L, d.getMaxMessageSize());
        assertEquals(Arrays.asList("shared"), d.getDesiredCapabilities());
        assertEquals("v", d.getProperties().get(new Amqp1Symbol("p")));
    }

    @Test
    public void testReceiverAttachDefaults() throws Exception {
        Attach a = new Attach("rx", 0, true);
        a.setSource(new Source("topic://news"));
        a.setTarget(new Target());
        Attach d = (Attach) roundTrip(a);
        assertTrue(d.isReceiver());
        assertEquals(Attach.SND_MIXED, d.getSndSettleMode());
        assertEquals(Attach.RCV_FIRST, d.getRcvSettleMode());
        assertNull(d.getInitialDeliveryCount());
        assertEquals(0L, d.getMaxMessageSize());
        assertEquals("topic://news", d.getSource().getAddress());
    }

    @Test
    public void testAttachWithNullTermini() throws Exception {
        // a broker refusing a link replies with null source and target
        Attach d = (Attach) roundTrip(new Attach("x", 1, true));
        assertNull(d.getSource());
        assertNull(d.getTarget());
    }

    @Test
    public void testAttachWireFormat() throws Exception {
        // name "a", handle 0, role sender(false); trailing fields omitted
        assertEquals("005312" + "c0" + "06" + "03" + "a10161" + "43" + "42",
                encoded(new Attach("a", 0, false)));
    }

    @Test
    public void testAttachCopyStartsFreshButKeepsTheLinkDescription() throws Exception {
        Attach original = new Attach("orders-in", 7, true);
        original.setSndSettleMode(Attach.SND_UNSETTLED);
        original.setRcvSettleMode(Attach.RCV_SECOND);
        original.setSource(new Source("queue://orders"));
        original.setTarget(new Target());
        original.setMaxMessageSize(4096);
        original.setInitialDeliveryCount(Long.valueOf(99));
        original.getDesiredCapabilities().add("shared");
        original.getProperties().put(new Amqp1Symbol("p"), "v");

        Attach copy = original.copy();
        assertEquals("orders-in", copy.getName());
        assertTrue(copy.isReceiver());
        assertEquals(0L, copy.getHandle());
        assertNull("delivery-count is not carried over", copy.getInitialDeliveryCount());
        assertEquals(Attach.SND_UNSETTLED, copy.getSndSettleMode());
        assertEquals(Attach.RCV_SECOND, copy.getRcvSettleMode());
        assertEquals("queue://orders", copy.getSource().getAddress());
        assertEquals(4096L, copy.getMaxMessageSize());
        assertEquals(Arrays.asList("shared"), copy.getDesiredCapabilities());
        assertEquals("v", copy.getProperties().get(new Amqp1Symbol("p")));

        // the copy is independent: changing it (as the client does) leaves the original alone
        copy.setHandle(3);
        copy.getDesiredCapabilities().add("other");
        assertEquals(7L, original.getHandle());
        assertEquals(Arrays.asList("shared"), original.getDesiredCapabilities());
    }

    @Test
    public void testAttachDefaultsSurviveACopy() {
        Attach copy = new Attach("x", 1, false).copy();
        assertEquals(Attach.SND_MIXED, copy.getSndSettleMode());
        assertEquals(Attach.RCV_FIRST, copy.getRcvSettleMode());
    }

    @Test
    public void testAttachMissingMandatoryFieldsRejected() {
        assertRejected("005312" + "45");
        assertRejected("005312" + "c0" + "05" + "02" + "a10161" + "43");
    }

    @Test
    public void testAttachWithUnsupportedTargetRejected() {
        // a coordinator (0x30) in the target position
        assertRejected("005312" + "c0" + "13" + "07" + "a10161" + "43" + "42" + "40" + "40"
                + "40" + "005330" + "45");
    }

    @Test
    public void testSourceFullRoundTrip() throws Exception {
        Source s = new Source("queue://q");
        s.setDurable(Terminus.DURABLE_UNSETTLED_STATE);
        s.setExpiryPolicy(Terminus.EXPIRY_NEVER);
        s.setTimeout(3600);
        s.setDynamic(true);
        s.setDistributionMode(Source.DISTRIBUTION_COPY);
        s.getFilter().put(new Amqp1Symbol("selector"),
                new Amqp1Described(new Amqp1Symbol("apache.org:selector-filter:string"),
                        "colour = 'red'"));
        s.setDefaultOutcome(DeliveryState.released());
        s.getOutcomes().add("amqp:accepted:list");
        s.getCapabilities().add("queue");
        Attach a = new Attach("l", 0, true);
        a.setSource(s);

        Source d = ((Attach) roundTrip(a)).getSource();
        assertEquals("queue://q", d.getAddress());
        assertEquals(Terminus.DURABLE_UNSETTLED_STATE, d.getDurable());
        assertEquals(Terminus.EXPIRY_NEVER, d.getExpiryPolicy());
        assertEquals(3600L, d.getTimeout());
        assertTrue(d.isDynamic());
        assertEquals(Source.DISTRIBUTION_COPY, d.getDistributionMode());
        assertEquals("colour = 'red'", ((Amqp1Described) d.getFilter()
                .get(new Amqp1Symbol("selector"))).getValue());
        assertEquals(DeliveryState.Type.RELEASED, d.getDefaultOutcome().getType());
        assertEquals(Arrays.asList("amqp:accepted:list"), d.getOutcomes());
        assertEquals(Arrays.asList("queue"), d.getCapabilities());
    }

    @Test
    public void testTargetFullRoundTrip() throws Exception {
        Target t = new Target("topic://t");
        t.setDurable(Terminus.DURABLE_CONFIGURATION);
        t.setDynamic(true);
        t.getCapabilities().add("topic");
        Attach a = new Attach("l", 0, false);
        a.setTarget(t);
        Target d = ((Attach) roundTrip(a)).getTarget();
        assertEquals("topic://t", d.getAddress());
        assertEquals(Terminus.DURABLE_CONFIGURATION, d.getDurable());
        assertTrue(d.isDynamic());
        assertEquals(Arrays.asList("topic"), d.getCapabilities());
    }

    // ── detach ──

    @Test
    public void testDetachRoundTrip() throws Exception {
        Detach d = (Detach) roundTrip(new Detach(5, true,
                new Amqp1Error(Amqp1Error.NOT_FOUND, "gone")));
        assertEquals(5L, d.getHandle());
        assertTrue(d.isClosed());
        assertEquals("amqp:not-found", d.getError().getCondition());
    }

    @Test
    public void testDetachDefaults() throws Exception {
        Detach d = (Detach) roundTrip(new Detach(0, false, null));
        assertFalse(d.isClosed());
        assertNull(d.getError());
        assertEquals("005316" + "c0" + "02" + "01" + "43", encoded(new Detach(0, false, null)));
    }

    // ── flow ──

    @Test
    public void testSessionFlowRoundTrip() throws Exception {
        Flow f = (Flow) roundTrip(new Flow(Long.valueOf(7), 2048, 9, 4096));
        assertEquals(Long.valueOf(7), f.getNextIncomingId());
        assertEquals(2048L, f.getIncomingWindow());
        assertEquals(9L, f.getNextOutgoingId());
        assertEquals(4096L, f.getOutgoingWindow());
        assertNull(f.getHandle());
        assertFalse(f.isDrain());
    }

    @Test
    public void testLinkFlowRoundTrip() throws Exception {
        Flow f = new Flow(Long.valueOf(0), 100, 0, 100);
        f.setLink(2, 10, 50);
        f.setDrain(true);
        f.setEcho(true);
        f.setAvailable(Long.valueOf(3));
        Flow d = (Flow) roundTrip(f);
        assertEquals(Long.valueOf(2), d.getHandle());
        assertEquals(Long.valueOf(10), d.getDeliveryCount());
        assertEquals(Long.valueOf(50), d.getLinkCredit());
        assertEquals(Long.valueOf(3), d.getAvailable());
        assertTrue(d.isDrain());
        assertTrue(d.isEcho());
    }

    @Test
    public void testFlowBeforePeerBeginHasNoNextIncomingId() throws Exception {
        Flow d = (Flow) roundTrip(new Flow(null, 10, 0, 10));
        assertNull(d.getNextIncomingId());
    }

    @Test
    public void testFlowMissingWindowsRejected() {
        assertRejected("005313" + "c0" + "03" + "02" + "40" + "43");
    }

    @Test
    public void testSerialArithmeticWraps() {
        assertEquals(1L, Flow.serialAdd(0xFFFFFFFFL, 2));
        assertEquals(0L, Flow.serialAdd(0xFFFFFFFFL, 1));
        assertEquals(5L, Flow.serialDiff(10, 5));
        assertEquals(-5L, Flow.serialDiff(5, 10));
        // across the wrap: 2 is 3 ahead of 0xFFFFFFFF
        assertEquals(3L, Flow.serialDiff(2, 0xFFFFFFFFL));
        assertEquals(-3L, Flow.serialDiff(0xFFFFFFFFL, 2));
    }

    // ── transfer ──

    @Test
    public void testFirstTransferRoundTrip() throws Exception {
        Transfer t = new Transfer(4);
        t.setFirst(12, new byte[] {1, 2, 3}, false);
        t.setMore(true);
        Transfer d = (Transfer) roundTrip(t);
        assertEquals(4L, d.getHandle());
        assertEquals(Long.valueOf(12), d.getDeliveryId());
        assertArrayEquals(new byte[] {1, 2, 3}, d.getDeliveryTag());
        assertEquals(Boolean.FALSE, d.getSettled());
        assertTrue(d.isMore());
        assertEquals(0L, d.getMessageFormat());
        assertFalse(d.isAborted());
    }

    @Test
    public void testContinuationTransferOmitsFirstOnlyFields() throws Exception {
        Transfer t = new Transfer(4);
        t.setMore(true);
        // handle, then nulls up to "more" (field 5)
        String hex = encoded(t);
        assertEquals("005314" + "c0" + "08" + "06" + "5204" + "40" + "40" + "40" + "40" + "41", hex);
        Transfer d = (Transfer) decode(hex);
        assertNull(d.getDeliveryId());
        assertNull(d.getDeliveryTag());
        assertNull(d.getSettled());
        assertTrue(d.isMore());
    }

    @Test
    public void testTransferAbortedAndSettled() throws Exception {
        Transfer t = new Transfer(0);
        t.setFirst(0, new byte[] {9}, true);
        t.setAborted(true);
        Transfer d = (Transfer) roundTrip(t);
        assertEquals(Boolean.TRUE, d.getSettled());
        assertTrue(d.isAborted());
    }

    @Test
    public void testTransferWithStateAndRcvSettleMode() throws Exception {
        Transfer t = new Transfer(0);
        t.setFirst(1, new byte[] {1}, false);
        t.setRcvSettleMode(Integer.valueOf(Attach.RCV_SECOND));
        t.setState(DeliveryState.accepted());
        Transfer d = (Transfer) roundTrip(t);
        assertEquals(Integer.valueOf(Attach.RCV_SECOND), d.getRcvSettleMode());
        assertEquals(DeliveryState.Type.ACCEPTED, d.getState().getType());
    }

    @Test
    public void testDeliveryTagLimits() {
        Transfer t = new Transfer(0);
        t.setFirst(0, new byte[32], false);
        try {
            t.setFirst(0, new byte[33], false);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            t.setFirst(0, null, false);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void testTransferMissingHandleRejected() {
        assertRejected("005314" + "45");
    }

    @Test
    public void testTransferPayloadFollowsPerformative() throws Exception {
        Transfer t = new Transfer(0);
        t.setFirst(0, new byte[] {1}, false);
        ByteBuffer frame = Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, 2, t.encode(),
                ByteBuffer.wrap(new byte[] {7, 8, 9}));
        // header: size, doff, type, channel
        assertEquals(2, frame.getShort(6));
        ByteBuffer body = frame.duplicate();
        body.position(Amqp1Frame.HEADER_SIZE);
        assertTrue(PerformativeCodec.decode(body) instanceof Transfer);
        assertEquals(3, body.remaining());
        assertEquals(7, body.get());
    }

    // ── disposition ──

    @Test
    public void testDispositionRoundTrip() throws Exception {
        Disposition p = new Disposition(true, 3, Long.valueOf(9));
        p.setSettled(true);
        p.setState(DeliveryState.rejected(new Amqp1Error(Amqp1Error.NOT_ALLOWED, "bad")));
        Disposition d = (Disposition) roundTrip(p);
        assertTrue(d.isReceiver());
        assertEquals(3L, d.getFirst());
        assertEquals(9L, d.getLast());
        assertTrue(d.isSettled());
        assertEquals(DeliveryState.Type.REJECTED, d.getState().getType());
        assertEquals("amqp:not-allowed", d.getState().getError().getCondition());
    }

    @Test
    public void testDispositionLastDefaultsToFirst() throws Exception {
        Disposition d = (Disposition) roundTrip(new Disposition(false, 5, null));
        assertEquals(5L, d.getLast());
        assertFalse(d.isReceiver());
        assertFalse(d.isSettled());
        assertNull(d.getState());
    }

    @Test
    public void testDispositionWireFormat() throws Exception {
        Disposition p = new Disposition(true, 0, null);
        p.setSettled(true);
        p.setState(DeliveryState.accepted());
        assertEquals("005315" + "c0" + "09" + "05" + "41" + "43" + "40" + "41" + "005324" + "45",
                encoded(p));
    }

    // ── delivery states ──

    @Test
    public void testAcceptedAndReleased() throws Exception {
        assertEquals(DeliveryState.Type.ACCEPTED, stateOf(DeliveryState.accepted()).getType());
        assertEquals(DeliveryState.Type.RELEASED, stateOf(DeliveryState.released()).getType());
        assertTrue(DeliveryState.accepted().isTerminal());
    }

    @Test
    public void testRejectedWithoutError() throws Exception {
        DeliveryState s = stateOf(DeliveryState.rejected(null));
        assertEquals(DeliveryState.Type.REJECTED, s.getType());
        assertNull(s.getError());
    }

    @Test
    public void testModified() throws Exception {
        Map<Object, Object> annotations = new LinkedHashMap<Object, Object>();
        annotations.put(new Amqp1Symbol("x-retry"), Long.valueOf(2));
        DeliveryState s = stateOf(DeliveryState.modified(true, true, annotations));
        assertEquals(DeliveryState.Type.MODIFIED, s.getType());
        assertTrue(s.isDeliveryFailed());
        assertTrue(s.isUndeliverableHere());
        assertEquals(Long.valueOf(2), s.getMessageAnnotations().get(new Amqp1Symbol("x-retry")));
        DeliveryState plain = stateOf(DeliveryState.modified(false, false, null));
        assertFalse(plain.isDeliveryFailed());
        assertTrue(plain.getMessageAnnotations().isEmpty());
    }

    @Test
    public void testReceivedIsNotTerminal() throws Exception {
        DeliveryState s = stateOf(DeliveryState.received(2, 100));
        assertEquals(DeliveryState.Type.RECEIVED, s.getType());
        assertFalse(s.isTerminal());
        assertEquals(2L, s.getSectionNumber());
        assertEquals(100L, s.getSectionOffset());
    }

    @Test
    public void testUnknownDeliveryStateRejected() {
        // a disposition whose state has an unknown descriptor (0x99)
        assertRejected("005315" + "c0" + "09" + "05" + "41" + "43" + "40" + "40" + "005399" + "45");
    }

    private static DeliveryState stateOf(DeliveryState state) throws Exception {
        Disposition p = new Disposition(true, 0, null);
        p.setState(state);
        return ((Disposition) roundTrip(p)).getState();
    }

    private static void assertRejected(String hex) {
        try {
            decode(hex);
            fail("expected rejection of " + hex);
        } catch (Amqp1ProtocolException expected) {
            // ok
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
