/*
 * PerformativeCodecTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Tests for the performative classes and {@link PerformativeCodec}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PerformativeCodecTest {

    private static Performative roundTrip(Performative p) throws Exception {
        ByteBuffer buf = p.encode();
        Performative decoded = PerformativeCodec.decode(buf);
        assertFalse("decode must consume the whole performative", buf.hasRemaining());
        return decoded;
    }

    private static Performative decode(String hex) throws Exception {
        return PerformativeCodec.decode(ByteBuffer.wrap(Amqp1TypesTest.hex(hex)));
    }

    private static String encoded(Performative p) {
        ByteBuffer b = p.encode();
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        return Amqp1TypesTest.toHex(bytes);
    }

    @Test
    public void testMinimalOpenWireFormat() throws Exception {
        assertEquals("005310" + "c0" + "04" + "01" + "a10161", encoded(new Open("a")));
    }

    @Test
    public void testOpenDecodedFromFixture() throws Exception {
        Open o = (Open) decode("005310" + "c0" + "04" + "01" + "a10161");
        assertEquals("a", o.getContainerId());
        assertNull(o.getHostname());
        assertEquals(Open.DEFAULT_MAX_FRAME_SIZE, o.getMaxFrameSize());
        assertEquals(Open.DEFAULT_CHANNEL_MAX, o.getChannelMax());
        assertEquals(0L, o.getIdleTimeOut());
        assertTrue(o.getOfferedCapabilities().isEmpty());
        assertTrue(o.getProperties().isEmpty());
    }

    @Test
    public void testFullOpenRoundTrip() throws Exception {
        Open o = new Open("container-1");
        o.setHostname("broker.example.org");
        o.setMaxFrameSize(65536);
        o.setChannelMax(255);
        o.setIdleTimeOut(30000);
        o.getOutgoingLocales().add("en-GB");
        o.getIncomingLocales().add("en-GB");
        o.getIncomingLocales().add("fr");
        o.getOfferedCapabilities().add("ANONYMOUS-RELAY");
        o.getDesiredCapabilities().add("DELAYED_DELIVERY");
        o.getProperties().put(new Amqp1Symbol("product"), "gumdrop");

        Open d = (Open) roundTrip(o);
        assertEquals("container-1", d.getContainerId());
        assertEquals("broker.example.org", d.getHostname());
        assertEquals(65536L, d.getMaxFrameSize());
        assertEquals(255, d.getChannelMax());
        assertEquals(30000L, d.getIdleTimeOut());
        assertEquals(Arrays.asList("en-GB"), d.getOutgoingLocales());
        assertEquals(Arrays.asList("en-GB", "fr"), d.getIncomingLocales());
        assertEquals(Arrays.asList("ANONYMOUS-RELAY"), d.getOfferedCapabilities());
        assertEquals(Arrays.asList("DELAYED_DELIVERY"), d.getDesiredCapabilities());
        assertEquals("gumdrop", d.getProperties().get(new Amqp1Symbol("product")));
    }

    @Test
    public void testOpenAcceptsSingleSymbolForMultipleField() throws Exception {
        // offered-capabilities as a bare symbol rather than an array
        // fields 1-6 null, then offered-capabilities (field 7) as one symbol
        Open o = (Open) decode("005310" + "c0" + "0d" + "08"
                + "a10161" + "404040404040" + "a30158");
        assertEquals(Arrays.asList("X"), o.getOfferedCapabilities());
    }

    @Test
    public void testOpenWithoutContainerIdRejected() {
        try {
            decode("005310" + "45");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            assertTrue(expected.getMessage().contains("container-id"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testOpenEncodeWithoutContainerIdRejected() {
        try {
            new Open(null).encode();
            fail("expected rejection");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void testOpenFieldOfWrongTypeRejected() {
        // container-id encoded as a uint
        try {
            decode("005310" + "c0" + "03" + "01" + "5201");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            assertTrue(expected.getMessage().contains("container-id"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testBeginRoundTrip() throws Exception {
        Begin b = new Begin(1, 2048, 4096);
        b.setRemoteChannel(Integer.valueOf(3));
        b.setHandleMax(1023);
        b.getDesiredCapabilities().add("x-cap");
        Begin d = (Begin) roundTrip(b);
        assertEquals(Integer.valueOf(3), d.getRemoteChannel());
        assertEquals(1L, d.getNextOutgoingId());
        assertEquals(2048L, d.getIncomingWindow());
        assertEquals(4096L, d.getOutgoingWindow());
        assertEquals(1023L, d.getHandleMax());
        assertEquals(Arrays.asList("x-cap"), d.getDesiredCapabilities());
    }

    @Test
    public void testBeginInitiatorHasNoRemoteChannel() throws Exception {
        Begin d = (Begin) roundTrip(new Begin(0, 100, 100));
        assertNull(d.getRemoteChannel());
        assertEquals(Begin.DEFAULT_HANDLE_MAX, d.getHandleMax());
    }

    @Test
    public void testBeginWireFormat() throws Exception {
        // remote-channel null, next-outgoing-id 0, incoming 100, outgoing 100
        assertEquals("005311" + "c0" + "07" + "04" + "40" + "43" + "5264" + "5264",
                encoded(new Begin(0, 100, 100)));
    }

    @Test
    public void testBeginMissingWindowsRejected() {
        try {
            decode("005311" + "c0" + "03" + "02" + "40" + "43");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            assertTrue(expected.getMessage().contains("incoming-window"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testEndAndCloseWithoutError() throws Exception {
        assertEquals("005317" + "45", encoded(new End()));
        assertEquals("005318" + "45", encoded(new Close()));
        assertNull(((End) decode("005317" + "45")).getError());
        assertNull(((Close) roundTrip(new Close())).getError());
    }

    @Test
    public void testCloseWithError() throws Exception {
        Amqp1Error err = new Amqp1Error(Amqp1Error.NOT_FOUND, "no such queue");
        err.getInfo().put(new Amqp1Symbol("node"), "q1");
        Close d = (Close) roundTrip(new Close(err));
        assertEquals("amqp:not-found", d.getError().getCondition());
        assertEquals("no such queue", d.getError().getDescription());
        assertEquals("q1", d.getError().getInfo().get(new Amqp1Symbol("node")));
    }

    @Test
    public void testEndWithErrorWireFormat() throws Exception {
        String expected = "005317" + "c0" + "0b" + "01"
                + "00531d" + "c0" + "07" + "01" + "a30" + "4" + "616263" + "64";
        // condition "abcd": a3 04 61626364
        expected = "005317" + "c0" + "0d" + "01"
                + "00531d" + "c0" + "07" + "01" + "a304" + "61626364";
        assertEquals(expected, encoded(new End(new Amqp1Error("abcd"))));
    }

    @Test
    public void testErrorWithoutConditionRejected() {
        try {
            decode("005318" + "c0" + "05" + "01" + "00531d" + "45");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            assertTrue(expected.getMessage().contains("condition"));
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testErrorFieldMustBeDescribedError() {
        try {
            decode("005318" + "c0" + "03" + "01" + "a10161");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            // ok
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testSaslMechanismsRoundTrip() throws Exception {
        SaslMechanisms m = (SaslMechanisms) roundTrip(
                new SaslMechanisms(Arrays.asList("PLAIN", "ANONYMOUS", "EXTERNAL")));
        assertEquals(Arrays.asList("PLAIN", "ANONYMOUS", "EXTERNAL"), m.getMechanisms());
    }

    @Test
    public void testSaslMechanismsAcceptsSingleSymbol() throws Exception {
        SaslMechanisms m = (SaslMechanisms) decode("005340" + "c0" + "08" + "01"
                + "a3" + "05" + "504c41494e");
        assertEquals(Arrays.asList("PLAIN"), m.getMechanisms());
    }

    @Test
    public void testSaslMechanismsRequiresAtLeastOne() {
        try {
            decode("005340" + "45");
            fail("expected rejection");
        } catch (Amqp1ProtocolException expected) {
            // ok
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testSaslInitPlain() throws Exception {
        byte[] response = new byte[] {0, 'u', 0, 'p'};
        SaslInit init = new SaslInit("PLAIN", response);
        init.setHostname("broker");
        SaslInit d = (SaslInit) roundTrip(init);
        assertEquals("PLAIN", d.getMechanism());
        assertArrayEquals(response, d.getInitialResponse());
        assertEquals("broker", d.getHostname());
    }

    @Test
    public void testSaslInitWithoutInitialResponse() throws Exception {
        SaslInit d = (SaslInit) roundTrip(new SaslInit("ANONYMOUS", null));
        assertNull(d.getInitialResponse());
        assertNull(d.getHostname());
        assertEquals("005341" + "c0" + "0c" + "01" + "a3" + "09" + "414e4f4e594d4f5553",
                encoded(new SaslInit("ANONYMOUS", null)));
    }

    @Test
    public void testSaslChallengeAndResponse() throws Exception {
        byte[] data = new byte[] {1, 2, 3};
        assertArrayEquals(data, ((SaslChallenge) roundTrip(new SaslChallenge(data))).getData());
        assertArrayEquals(data, ((SaslResponse) roundTrip(new SaslResponse(data))).getData());
        // empty data is a value, not an absent field
        assertEquals(0, ((SaslResponse) roundTrip(new SaslResponse(new byte[0]))).getData().length);
    }

    @Test
    public void testSaslOutcome() throws Exception {
        SaslOutcome ok = (SaslOutcome) roundTrip(new SaslOutcome(SaslOutcome.OK, null));
        assertTrue(ok.isSuccess());
        assertNull(ok.getAdditionalData());
        // code 0 must still be present: it is the mandatory first field
        assertEquals("005344" + "c0" + "03" + "01" + "5000",
                encoded(new SaslOutcome(SaslOutcome.OK, null)));

        SaslOutcome bad = (SaslOutcome) roundTrip(
                new SaslOutcome(SaslOutcome.AUTH, new byte[] {9}));
        assertFalse(bad.isSuccess());
        assertEquals(SaslOutcome.AUTH, bad.getCode());
        assertArrayEquals(new byte[] {9}, bad.getAdditionalData());
    }

    @Test
    public void testDecodeLeavesPositionAfterPerformative() throws Exception {
        // a performative followed by payload bytes, as a transfer would be
        ByteBuffer buf = ByteBuffer.wrap(Amqp1TypesTest.hex("005317" + "45" + "dead"));
        assertTrue(PerformativeCodec.decode(buf) instanceof End);
        assertEquals(2, buf.remaining());
    }

    @Test
    public void testUnsupportedDescriptorRejected() {
        assertUnsupported("005399" + "45");
        // a descriptor whose low byte collides with a supported one
        assertUnsupported("00" + "800000000100000010" + "45");
        // symbolic descriptors are not supported
        assertUnsupported("00" + "a3016f" + "45");
    }

    @Test
    public void testBodyThatIsNotAPerformativeRejected() {
        assertUnsupported("a10161");
        assertUnsupported("005310" + "a10161");
    }

    private static void assertUnsupported(String hex) {
        try {
            decode(hex);
            fail("expected rejection of " + hex);
        } catch (Amqp1ProtocolException expected) {
            // ok
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test
    public void testPerformativesReportDescriptors() {
        List<Performative> all = Arrays.<Performative>asList(new Open("c"),
                new Begin(0, 1, 1), new End(), new Close(),
                new SaslMechanisms(Arrays.asList("PLAIN")), new SaslInit("PLAIN", null),
                new SaslChallenge(new byte[0]), new SaslResponse(new byte[0]),
                new SaslOutcome(0, null));
        long[] expected = {0x10, 0x11, 0x17, 0x18, 0x40, 0x41, 0x42, 0x43, 0x44};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], all.get(i).getDescriptor());
        }
    }
}
