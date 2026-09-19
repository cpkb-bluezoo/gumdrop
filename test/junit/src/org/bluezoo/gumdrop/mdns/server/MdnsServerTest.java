/*
 * MdnsServerTest.java
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

package org.bluezoo.gumdrop.mdns.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.mdns.MdnsListener;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives the mDNS probing, announcement, conflict and query-response state
 * machine of {@link MdnsServer} through a recording listener whose timers
 * are fired explicitly, so no multicast socket is involved.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MdnsServerTest {

    private static final class Timer implements TimerHandle {
        final long delayMs;
        final Runnable task;
        boolean cancelled;

        Timer(long delayMs, Runnable task) {
            this.delayMs = delayMs;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class FakeListener extends MdnsListener {
        final List<DnsMessage> group = new ArrayList<DnsMessage>();
        final List<DnsMessage> unicast = new ArrayList<DnsMessage>();
        final List<InetSocketAddress> unicastTargets =
                new ArrayList<InetSocketAddress>();
        final List<Timer> timers = new ArrayList<Timer>();
        boolean bound = true;
        boolean failStart;
        boolean failStop;
        int starts;
        int stops;

        @Override
        public void sendToGroup(ByteBuffer data) {
            group.add(parse(data));
        }

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            unicast.add(parse(data));
            unicastTargets.add(destination);
        }

        @Override
        public TimerHandleWrapper scheduleTimer(long delayMs, Runnable cb) {
            Timer t = new Timer(delayMs, cb);
            timers.add(t);
            return new TimerHandleWrapper(t);
        }

        @Override
        public boolean isBound() {
            return bound;
        }

        @Override
        public void start(Gumdrop gumdrop) {
            starts++;
            if (failStart) {
                throw new IllegalStateException("cannot bind");
            }
        }

        @Override
        public void stop() {
            stops++;
            if (failStop) {
                throw new IllegalStateException("cannot stop");
            }
        }

        Timer lastLiveTimer() {
            for (int i = timers.size() - 1; i >= 0; i--) {
                if (!timers.get(i).cancelled) {
                    return timers.get(i);
                }
            }
            return null;
        }

        /** Runs the newest live timer; returns false if there is none. */
        boolean fire() {
            Timer t = lastLiveTimer();
            if (t == null) {
                return false;
            }
            t.cancelled = true;
            t.task.run();
            return true;
        }
    }

    private static DnsMessage parse(ByteBuffer data) {
        try {
            return DnsMessage.parse(data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final InetSocketAddress PEER =
            new InetSocketAddress("192.168.1.9", 5353);
    private static final byte[] OUR_ADDR = {(byte) 192, (byte) 168, 1, 5};

    private MdnsServer server;
    private FakeListener listener;

    @Before
    public void setUp() throws Exception {
        server = new MdnsServer();
        server.setHostname("box.example.org");
        server.setAdvertiseServices(false);
        listener = new FakeListener();
        server.addListener(listener);
    }

    private void probeAndAnnounce() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        while (!server.isAnnounced()) {
            assertTrue("timer expected", listener.fire());
        }
    }

    private static ByteBuffer queryFor(String name, DnsType type,
            boolean unicast, List<DnsResourceRecord> known) {
        return new DnsMessage(0, 0,
                Collections.singletonList(
                        new DnsQuestion(name, type, DnsClass.IN, unicast)),
                known,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()).serialize();
    }

    private static DnsResourceRecord aRecord(String name, byte[] addr,
            int ttl) {
        return new DnsResourceRecord(name, DnsType.A, DnsClass.IN, ttl, addr);
    }

    private static ByteBuffer responseWith(DnsResourceRecord... answers) {
        return new DnsMessage(0, DnsMessage.FLAG_QR | DnsMessage.FLAG_AA,
                Collections.<DnsQuestion>emptyList(),
                Arrays.asList(answers),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()).serialize();
    }

    private static ByteBuffer probeFrom(String name, byte[] addr) {
        return new DnsMessage(0, 0,
                Collections.singletonList(
                        new DnsQuestion(name, DnsType.ANY, DnsClass.IN)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.singletonList(aRecord(name, addr, 120)),
                Collections.<DnsResourceRecord>emptyList()).serialize();
    }

    // ── composition / configuration ──

    @Test
    public void composerBuildsConfiguredServer() {
        MdnsListener l = new MdnsListener();
        MdnsServer s = MdnsServer.compose().listener(l).hostname("h")
                .advertiseServices(false).excludedServices("a b").server();
        assertEquals(1, s.getListeners().size());
        assertSame(l, s.getListeners().get(0));
    }

    @Test
    public void composerRequiresListener() {
        try {
            MdnsServer.compose().server();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // no listener configured
        }
        try {
            MdnsServer.compose().listener(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // null listener
        }
    }

    @Test
    public void setListenersKeepsOnlyMdnsListeners() {
        MdnsServer s = new MdnsServer();
        List<Object> items = new ArrayList<Object>();
        items.add(new MdnsListener());
        items.add("not a listener");
        s.setListeners(items);
        assertEquals(1, s.getListeners().size());
    }

    @Test
    public void excludedServicesAcceptsNullAndTokens() {
        server.setExcludedServices("web ftp");
        server.setExcludedServices(null);
    }

    @Test
    public void listenersViewIsUnmodifiable() {
        try {
            server.getListeners().clear();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // read-only view
        }
    }

    // ── lifecycle ──

    @Test
    public void startWithoutListenersDoesNothing() {
        MdnsServer s = new MdnsServer();
        s.setHostname("h");
        s.start(null);
        assertFalse(s.isAnnounced());
        assertNull(s.getCurrentName());
    }

    @Test
    public void startWithUnboundListenerDoesNotProbe() {
        listener.bound = false;
        server.start(null);
        assertEquals(1, listener.starts);
        assertTrue(listener.timers.isEmpty());
        assertSame(server, listener.getServer());
    }

    @Test
    public void listenerStartFailureIsTolerated() {
        listener.failStart = true;
        server.start(null);
        assertTrue(listener.timers.isEmpty());
    }

    @Test
    public void stopStopsListenersAndResetsState() throws Exception {
        probeAndAnnounce();
        listener.failStop = true;
        server.stop();
        assertEquals(1, listener.stops);
        assertFalse(server.isAnnounced());
    }

    // ── probing and announcing ──

    @Test
    public void probesThreeTimesThenAnnouncesTwice() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        assertEquals("box.local", server.getCurrentName());
        assertFalse(server.isAnnounced());
        assertTrue(listener.lastLiveTimer().delayMs <= 250);

        for (int i = 0; i < 3; i++) {
            assertTrue(listener.fire());
        }
        assertEquals(3, listener.group.size());
        DnsMessage probe = listener.group.get(0);
        assertTrue(probe.isQuery());
        assertEquals(DnsType.ANY, probe.getQuestions().get(0).getType());
        assertEquals("box.local", probe.getQuestions().get(0).getName());
        assertEquals(1, probe.getAuthorities().size());
        assertTrue(Arrays.equals(OUR_ADDR,
                probe.getAuthorities().get(0).getRData()));

        assertTrue(listener.fire());
        assertTrue(server.isAnnounced());
        assertEquals(4, listener.group.size());
        DnsMessage announce = listener.group.get(3);
        assertTrue(announce.isResponse());
        assertTrue(announce.isAuthoritative());
        assertEquals(1, announce.getAnswers().size());
        assertEquals(120, announce.getAnswers().get(0).getTTL());

        assertTrue(listener.fire());
        assertEquals(5, listener.group.size());
        assertNull(listener.lastLiveTimer());
    }

    @Test
    public void probeConflictRenamesHost() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        listener.fire();
        server.handleDatagram(listener, responseWith(
                aRecord("box.local", new byte[] {10, 0, 0, 1}, 120)), PEER);
        assertEquals("box-2.local", server.getCurrentName());
        assertFalse(server.isAnnounced());
    }

    @Test
    public void responseWithOwnAddressIsNotAConflict() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        server.handleDatagram(listener, responseWith(
                aRecord("box.local", OUR_ADDR, 120)), PEER);
        assertEquals("box.local", server.getCurrentName());
    }

    @Test
    public void unrelatedResponseDuringProbingIsCached() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        server.handleDatagram(listener, responseWith(
                aRecord("other.local", new byte[] {10, 0, 0, 2}, 120)), PEER);
        assertEquals("box.local", server.getCurrentName());
        assertEquals(1, server.lookup("other.local", DnsType.A).size());
    }

    @Test
    public void responsesAreCachedWhenAnnounced() throws Exception {
        probeAndAnnounce();
        server.handleDatagram(listener, responseWith(
                aRecord("printer.local", new byte[] {10, 0, 0, 3}, 120)),
                PEER);
        assertEquals(1, server.lookup("printer.local", DnsType.A).size());
    }

    @Test
    public void simultaneousProbeLoserBacksOff() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        // their address sorts after ours, so we lose the tie-break
        server.handleDatagram(listener,
                probeFrom("box.local", new byte[] {(byte) 192, (byte) 168, 1,
                        (byte) 200}), PEER);
        assertEquals(1000, listener.lastLiveTimer().delayMs);
        listener.fire();
        assertEquals("box.local", server.getCurrentName());
        // restarted probing begins with the random initial delay
        assertTrue(listener.lastLiveTimer().delayMs <= 250);
    }

    @Test
    public void simultaneousProbeWinnerKeepsGoing() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        long before = listener.lastLiveTimer().delayMs;
        server.handleDatagram(listener,
                probeFrom("box.local", new byte[] {10, 0, 0, 1}), PEER);
        assertEquals(before, listener.lastLiveTimer().delayMs);
    }

    @Test
    public void probeForOtherNameIgnored() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        long before = listener.lastLiveTimer().delayMs;
        server.handleDatagram(listener,
                probeFrom("other.local", new byte[] {(byte) 250, 0, 0, 1}),
                PEER);
        assertEquals(before, listener.lastLiveTimer().delayMs);
    }

    @Test
    public void probingWithNoAddressesNeverConflicts() {
        server.startProbingForTesting(Collections.<InetAddress>emptyList());
        server.handleDatagram(listener,
                probeFrom("box.local", new byte[] {1, 1, 1, 1}), PEER);
        assertEquals("box.local", server.getCurrentName());
    }

    // ── answering queries ──

    @Test
    public void queryForOurNameAnsweredViaMulticastAfterDelay()
            throws Exception {
        probeAndAnnounce();
        listener.group.clear();
        server.handleDatagram(listener, queryFor("BOX.local", DnsType.A,
                false, Collections.<DnsResourceRecord>emptyList()), PEER);
        assertTrue(listener.group.isEmpty());
        Timer t = listener.lastLiveTimer();
        assertTrue(t.delayMs >= 20 && t.delayMs < 120);
        t.task.run();
        assertEquals(1, listener.group.size());
        assertEquals(1, listener.group.get(0).getAnswers().size());
        assertTrue(listener.unicast.isEmpty());
    }

    @Test
    public void unicastQuestionAnsweredDirectly() throws Exception {
        probeAndAnnounce();
        server.handleDatagram(listener, queryFor("box.local", DnsType.ANY,
                true, Collections.<DnsResourceRecord>emptyList()), PEER);
        assertEquals(1, listener.unicast.size());
        assertEquals(PEER, listener.unicastTargets.get(0));
    }

    @Test
    public void knownAnswerSuppressesResponse() throws Exception {
        probeAndAnnounce();
        listener.group.clear();
        Timer before = listener.lastLiveTimer();
        server.handleDatagram(listener, queryFor("box.local", DnsType.A,
                false, Collections.singletonList(
                        aRecord("box.local", OUR_ADDR, 100))), PEER);
        assertSame(before, listener.lastLiveTimer());
        assertTrue(listener.unicast.isEmpty());
    }

    @Test
    public void staleKnownAnswerDoesNotSuppress() throws Exception {
        probeAndAnnounce();
        server.handleDatagram(listener, queryFor("box.local", DnsType.A,
                true, Collections.singletonList(
                        aRecord("box.local", OUR_ADDR, 10))), PEER);
        assertEquals(1, listener.unicast.size());
    }

    @Test
    public void knownAnswerWithDifferentDataDoesNotSuppress()
            throws Exception {
        probeAndAnnounce();
        server.handleDatagram(listener, queryFor("box.local", DnsType.A,
                true, Collections.singletonList(aRecord("box.local",
                        new byte[] {9, 9, 9, 9}, 120))), PEER);
        assertEquals(1, listener.unicast.size());
    }

    @Test
    public void queryForOtherNameOrTypeNotAnswered() throws Exception {
        probeAndAnnounce();
        server.handleDatagram(listener, queryFor("other.local", DnsType.A,
                true, Collections.<DnsResourceRecord>emptyList()), PEER);
        server.handleDatagram(listener, queryFor("box.local", DnsType.AAAA,
                true, Collections.<DnsResourceRecord>emptyList()), PEER);
        assertTrue(listener.unicast.isEmpty());
    }

    @Test
    public void queryWhenIdleIgnored() {
        server.handleDatagram(listener, queryFor("box.local", DnsType.A,
                true, Collections.<DnsResourceRecord>emptyList()), PEER);
        assertTrue(listener.unicast.isEmpty());
    }

    @Test
    public void malformedDatagramIsIgnored() throws Exception {
        probeAndAnnounce();
        int sent = listener.group.size();
        server.handleDatagram(listener,
                ByteBuffer.wrap(new byte[] {1, 2, 3}), PEER);
        assertEquals(sent, listener.group.size());
    }

    // ── goodbye and outbound queries ──

    @Test
    public void goodbyeSendsZeroTtlRecordsOnce() throws Exception {
        probeAndAnnounce();
        listener.group.clear();
        server.sendGoodbye(listener);
        assertEquals(1, listener.group.size());
        assertEquals(0, listener.group.get(0).getAnswers().get(0).getTTL());
        assertFalse(server.isAnnounced());
        server.sendGoodbye(listener);
        assertEquals(1, listener.group.size());
    }

    @Test
    public void goodbyeBeforeAnnouncementIsNoOp() {
        server.startProbingForTesting(Collections.<InetAddress>emptyList());
        server.sendGoodbye(listener);
        assertTrue(listener.group.isEmpty());
    }

    @Test
    public void queryIsSentToGroupWithKnownAnswers() throws Exception {
        server.startProbingForTesting(Arrays.asList(
                InetAddress.getByAddress(OUR_ADDR)));
        server.handleDatagram(listener, responseWith(
                aRecord("nas.local", new byte[] {10, 0, 0, 4}, 120)), PEER);
        listener.group.clear();
        server.query("nas.local", DnsType.A);
        assertEquals(1, listener.group.size());
        DnsMessage q = listener.group.get(0);
        assertEquals("nas.local", q.getQuestions().get(0).getName());
        assertEquals(1, q.getAnswers().size());
    }

    @Test
    public void queryWithoutListenersIsNoOp() {
        new MdnsServer().query("x.local", DnsType.A);
    }
}
