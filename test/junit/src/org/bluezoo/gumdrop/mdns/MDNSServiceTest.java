/*
 * MDNSServiceTest.java
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

package org.bluezoo.gumdrop.mdns;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DNSClass;
import org.bluezoo.gumdrop.dns.DNSMessage;
import org.bluezoo.gumdrop.dns.DNSQuestion;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MDNSService}, exercised in-process against a
 * {@link CapturingMDNSListener} that never opens a real socket: {@code
 * sendToGroup}/{@code sendTo} capture bytes instead of transmitting
 * them, and {@code scheduleTimer} captures the pending task so tests
 * can advance the probing/announcing state machine deterministically
 * one step at a time via {@link CapturingMDNSListener#fireTimer()},
 * rather than depending on wall-clock delays.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MDNSServiceTest {

    private static List<InetAddress> ownAddresses;

    @Before
    public void assumeHasIPv4Interface() throws Exception {
        Assume.assumeTrue("No usable IPv4 interface: skipping",
                !findOwnAddresses().isEmpty());
    }

    /**
     * Mirrors {@code MDNSService.gatherOwnAddresses()} exactly (every
     * up, non-loopback, non-point-to-point interface's IPv4 addresses)
     * so tests don't assume a specific address count on the machine
     * they run on.
     */
    private static List<InetAddress> findOwnAddresses() throws Exception {
        if (ownAddresses != null) {
            return ownAddresses;
        }
        List<InetAddress> result = new ArrayList<InetAddress>();
        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
                continue;
            }
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    result.add(addr);
                }
            }
        }
        ownAddresses = result;
        return result;
    }

    private static InetAddress findFirstOwnAddress() throws Exception {
        return findOwnAddresses().get(0);
    }

    /** Fires the listener's pending timer until nothing more is scheduled, or a step cap is hit. */
    private static void settle(MDNSService service, CapturingMDNSListener listener) {
        for (int i = 0; i < 20 && listener.hasPendingTimer(); i++) {
            listener.fireTimer();
        }
    }

    private static DNSMessage parse(ByteBuffer buf) throws Exception {
        return DNSMessage.parse(buf.duplicate());
    }

    @Test
    public void testProbingThenAnnounceHappyPath() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);

        assertTrue(service.isAnnounced());
        assertEquals("testhost.local", service.getCurrentName());

        // 3 probes + 2 announcements
        assertEquals(5, listener.sentToGroup.size());

        DNSMessage lastAnnouncement = parse(listener.sentToGroup.get(4));
        assertTrue(lastAnnouncement.isResponse());
        assertEquals(findOwnAddresses().size(), lastAnnouncement.getAnswers().size());
        DNSResourceRecord rr = lastAnnouncement.getAnswers().get(0);
        assertEquals("testhost.local", rr.getName());
        assertEquals(DNSType.A, rr.getType());
        assertTrue(rr.isCacheFlush());
    }

    @Test
    public void testProbeMessagesCarryProposedRecordInAuthority() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        listener.fireTimer(); // first probe only

        assertEquals(1, listener.sentToGroup.size());
        DNSMessage probe = parse(listener.sentToGroup.get(0));
        assertFalse(probe.isResponse());
        assertEquals(1, probe.getQuestions().size());
        assertEquals(DNSType.ANY, probe.getQuestions().get(0).getType());
        assertEquals(findOwnAddresses().size(), probe.getAuthorities().size());
        assertEquals(DNSType.A, probe.getAuthorities().get(0).getType());
        // Not yet claimed: cache-flush must not be set on a probe.
        assertFalse(probe.getAuthorities().get(0).isCacheFlush());
    }

    @Test
    public void testConflictingResponseDuringProbingRenames() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        listener.fireTimer(); // one probe sent, still probing

        InetAddress someoneElse = InetAddress.getByName("203.0.113.9");
        DNSResourceRecord conflicting =
                DNSResourceRecord.a("testhost.local", 120, someoneElse);
        DNSMessage response = new DNSMessage(0,
                DNSMessage.FLAG_QR | DNSMessage.FLAG_AA,
                Collections.<DNSQuestion>emptyList(),
                Collections.singletonList(conflicting),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        service.handleDatagram(listener, response.serialize(),
                new InetSocketAddress(someoneElse, 5353));

        assertEquals("testhost-2.local", service.getCurrentName());

        settle(service, listener);
        assertTrue(service.isAnnounced());
        assertEquals("testhost-2.local", service.getCurrentName());
    }

    @Test
    public void testResponseWithOwnAddressIsNotAConflict() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        listener.fireTimer(); // one probe sent

        // Our own announcement/probe echoed back (e.g. by a switch loop)
        // must not be treated as a conflict.
        DNSResourceRecord own = DNSResourceRecord.a(
                "testhost.local", 120, findFirstOwnAddress());
        DNSMessage response = new DNSMessage(0,
                DNSMessage.FLAG_QR | DNSMessage.FLAG_AA,
                Collections.<DNSQuestion>emptyList(),
                Collections.singletonList(own),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
        service.handleDatagram(listener, response.serialize(),
                new InetSocketAddress(findFirstOwnAddress(), 5353));

        assertEquals("testhost.local", service.getCurrentName());
    }

    @Test
    public void testSimultaneousProbeConflictLostWaitsAndKeepsName() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        listener.fireTimer(); // one probe sent, still probing

        // A record whose rdata sorts higher than any of ours (all 0xFF)
        // wins the RFC 6762 section 8.2 tie-break.
        InetAddress higher = InetAddress.getByName("255.255.255.255");
        DNSResourceRecord theirProposal =
                DNSResourceRecord.a("testhost.local", 120, higher);
        DNSQuestion probeQuestion =
                new DNSQuestion("testhost.local", DNSType.ANY, DNSClass.IN);
        DNSMessage theirProbe = new DNSMessage(0, 0,
                Collections.singletonList(probeQuestion),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.singletonList(theirProposal),
                Collections.<DNSResourceRecord>emptyList());

        service.handleDatagram(listener, theirProbe.serialize(),
                new InetSocketAddress(higher, 5353));

        // Same name, not renamed -- just deferred.
        assertEquals("testhost.local", service.getCurrentName());
        assertFalse(service.isAnnounced());

        settle(service, listener);
        assertTrue(service.isAnnounced());
        assertEquals("testhost.local", service.getCurrentName());
    }

    @Test
    public void testKnownAnswerSuppression() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        assertTrue(service.isAnnounced());
        int sentBefore = listener.sentToGroup.size();

        DNSQuestion question =
                new DNSQuestion("testhost.local", DNSType.A, DNSClass.IN);
        // Suppression requires the querier to already know *every* one
        // of our current addresses, not just one.
        List<DNSResourceRecord> knownAnswers = new ArrayList<DNSResourceRecord>();
        for (InetAddress addr : findOwnAddresses()) {
            knownAnswers.add(DNSResourceRecord.a("testhost.local", 120, addr));
        }
        DNSMessage query = new DNSMessage(0, 0,
                Collections.singletonList(question),
                knownAnswers,
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        service.handleDatagram(listener, query.serialize(),
                new InetSocketAddress("198.51.100.5", 12345));

        // No response should even be scheduled.
        assertFalse(listener.hasPendingTimer());
        assertEquals(sentBefore, listener.sentToGroup.size());
    }

    @Test
    public void testQueryWithoutKnownAnswerGetsMulticastResponse() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        int sentBefore = listener.sentToGroup.size();

        DNSQuestion question =
                new DNSQuestion("testhost.local", DNSType.A, DNSClass.IN);
        DNSMessage query = new DNSMessage(0, 0,
                Collections.singletonList(question),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        service.handleDatagram(listener, query.serialize(),
                new InetSocketAddress("198.51.100.5", 12345));

        assertTrue(listener.hasPendingTimer());
        listener.fireTimer();

        assertEquals(sentBefore + 1, listener.sentToGroup.size());
        DNSMessage response = parse(listener.sentToGroup.get(sentBefore));
        assertTrue(response.isResponse());
        assertEquals("testhost.local", response.getAnswers().get(0).getName());
    }

    @Test
    public void testQueryWithUnicastResponseBitGetsUnicastReply() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        int sentBefore = listener.sentToGroup.size();

        DNSQuestion question = new DNSQuestion(
                "testhost.local", DNSType.A, DNSClass.IN, true);
        DNSMessage query = new DNSMessage(0, 0,
                Collections.singletonList(question),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        InetSocketAddress source = new InetSocketAddress("198.51.100.5", 12345);
        service.handleDatagram(listener, query.serialize(), source);

        // Unicast reply is immediate, no timer needed, and doesn't touch the group.
        assertFalse(listener.hasPendingTimer());
        assertEquals(sentBefore, listener.sentToGroup.size());
        assertTrue(listener.sentUnicast.containsKey(source));

        DNSMessage response = parse(listener.sentUnicast.get(source));
        assertTrue(response.isResponse());
        assertEquals("testhost.local", response.getAnswers().get(0).getName());
    }

    @Test
    public void testGoodbyeOnStop() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        int sentBefore = listener.sentToGroup.size();

        service.stop();

        assertEquals(sentBefore + 1, listener.sentToGroup.size());
        DNSMessage goodbye = parse(listener.sentToGroup.get(sentBefore));
        assertTrue(goodbye.isResponse());
        DNSResourceRecord rr = goodbye.getAnswers().get(0);
        assertEquals(0, rr.getTTL());
        assertTrue(rr.isCacheFlush());
        assertFalse(service.isAnnounced());
    }

    @Test
    public void testLookupEmptyBeforeAnyQuery() {
        MDNSService service = new MDNSService();
        assertTrue(service.lookup("other.local", DNSType.A).isEmpty());
    }

    @Test
    public void testQuerySendsMulticastQuestion() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        int sentBefore = listener.sentToGroup.size();

        service.query("other.local", DNSType.A);

        assertEquals(sentBefore + 1, listener.sentToGroup.size());
        DNSMessage query = parse(listener.sentToGroup.get(sentBefore));
        assertFalse(query.isResponse());
        assertEquals(1, query.getQuestions().size());
        DNSQuestion question = query.getQuestions().get(0);
        assertEquals("other.local", question.getName());
        assertEquals(DNSType.A, question.getType());
        assertFalse(question.isUnicastResponseRequested());
    }

    @Test
    public void testQueryResponseIsCachedAndLookupable() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);

        InetAddress otherAddr = InetAddress.getByName("203.0.113.42");
        DNSResourceRecord answer = DNSResourceRecord.a("other.local", 120, otherAddr);
        DNSMessage response = new DNSMessage(0,
                DNSMessage.FLAG_QR | DNSMessage.FLAG_AA,
                Collections.<DNSQuestion>emptyList(),
                Collections.singletonList(answer),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());

        assertTrue(service.lookup("other.local", DNSType.A).isEmpty());
        service.handleDatagram(listener, response.serialize(),
                new InetSocketAddress(otherAddr, 5353));

        List<DNSResourceRecord> found = service.lookup("other.local", DNSType.A);
        assertEquals(1, found.size());
        assertArrayEquals(otherAddr.getAddress(), found.get(0).getRData());
    }

    @Test
    public void testStopClearsCache() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);

        InetAddress otherAddr = InetAddress.getByName("203.0.113.42");
        DNSResourceRecord answer = DNSResourceRecord.a("other.local", 120, otherAddr);
        DNSMessage response = new DNSMessage(0,
                DNSMessage.FLAG_QR | DNSMessage.FLAG_AA,
                Collections.<DNSQuestion>emptyList(),
                Collections.singletonList(answer),
                Collections.<DNSResourceRecord>emptyList(),
                Collections.<DNSResourceRecord>emptyList());
        service.handleDatagram(listener, response.serialize(),
                new InetSocketAddress(otherAddr, 5353));
        assertEquals(1, service.lookup("other.local", DNSType.A).size());

        service.stop();

        assertTrue(service.lookup("other.local", DNSType.A).isEmpty());
    }

    @Test
    public void testAdvertiseServicesDisabledOnlyAnnouncesHostRecords() throws Exception {
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");
        service.setAdvertiseServices(false);

        service.start();
        settle(service, listener);
        assertTrue(service.isAnnounced());

        DNSMessage lastAnnouncement = parse(listener.sentToGroup.get(listener.sentToGroup.size() - 1));
        for (DNSResourceRecord rr : lastAnnouncement.getAnswers()) {
            assertEquals(DNSType.A, rr.getType());
        }
        assertEquals(findOwnAddresses().size(), lastAnnouncement.getAnswers().size());
    }

    @Test
    public void testAdvertiseServicesEnabledByDefaultStillAnnouncesHostRecord() throws Exception {
        // Doesn't assert on any particular DNS-SD content -- Gumdrop's
        // service list is shared JVM-wide state this test doesn't own
        // -- just that enabling the (default-on) integration point
        // doesn't break the host record it already had.
        CapturingMDNSListener listener = new CapturingMDNSListener();
        MDNSService service = new MDNSService();
        service.addListener(listener);
        service.setHostname("testhost");

        service.start();
        settle(service, listener);
        assertTrue(service.isAnnounced());

        DNSMessage lastAnnouncement = parse(listener.sentToGroup.get(listener.sentToGroup.size() - 1));
        int hostRecords = 0;
        for (DNSResourceRecord rr : lastAnnouncement.getAnswers()) {
            if (rr.getType() == DNSType.A && "testhost.local".equalsIgnoreCase(rr.getName())) {
                hostRecords++;
            }
        }
        assertEquals(findOwnAddresses().size(), hostRecords);
    }

    /**
     * An {@link MDNSListener} that never opens a real socket: {@code
     * start()}/{@code stop()} are no-ops, sends are captured in memory,
     * and {@code scheduleTimer} captures the pending task instead of
     * running it, so tests advance the state machine one step at a
     * time via {@link #fireTimer()}.
     */
    static class CapturingMDNSListener extends MDNSListener {

        final List<ByteBuffer> sentToGroup = new ArrayList<ByteBuffer>();
        final Map<InetSocketAddress, ByteBuffer> sentUnicast =
                new LinkedHashMap<InetSocketAddress, ByteBuffer>();
        private Runnable pendingTask;

        @Override
        public void start() {
            // No real socket in tests. stop() is left as inherited:
            // it calls service.sendGoodbye(this) and then a no-op
            // endpoint close, since the endpoint field is never set
            // when start() is never really run.
        }

        @Override
        boolean isBound() {
            // start() above never creates a real endpoint; tell
            // MDNSService the (fake) bind succeeded anyway so it
            // proceeds to probing.
            return true;
        }

        @Override
        void sendToGroup(ByteBuffer data) {
            sentToGroup.add(data.duplicate());
        }

        @Override
        void sendTo(ByteBuffer data, InetSocketAddress destination) {
            sentUnicast.put(destination, data.duplicate());
        }

        @Override
        TimerHandleWrapper scheduleTimer(long delayMs, Runnable callback) {
            pendingTask = callback;
            return new TimerHandleWrapper(new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            });
        }

        boolean hasPendingTimer() {
            return pendingTask != null;
        }

        /** Runs the most recently scheduled task, if any, then clears it. */
        void fireTimer() {
            Runnable task = pendingTask;
            pendingTask = null;
            if (task != null) {
                task.run();
            }
        }
    }

}
