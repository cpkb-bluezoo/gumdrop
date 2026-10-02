/*
 * AuthoritativeZoneHandlerOpsTest.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQueryTransport;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the query, transfer, NOTIFY and dynamic update handling of
 * {@link AuthoritativeZoneHandler} that the other handler tests do not reach.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthoritativeZoneHandlerOpsTest {

    private static final String ORIGIN = "example.com.";

    /** Records NOTIFY requests and resolves names from a fixed table. */
    private static final class RecordingClient implements ZoneMasterClient {
        final List<InetSocketAddress> notified = new ArrayList<InetSocketAddress>();
        boolean failResolve;

        @Override
        public void querySoaSerial(SelectorLoop loop, InetSocketAddress master, String origin,
                                   SerialCallback callback) {
            callback.onFailure(new IOException("unused"));
        }

        @Override
        public void transfer(SelectorLoop loop, InetSocketAddress master, String origin,
                             DnsZoneClient.TransferCallback callback) {
        }

        @Override
        public void notify(SelectorLoop loop, InetSocketAddress peer, String origin) {
            notified.add(peer);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (failResolve) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName("192.0.2.11"),
                InetAddress.getByName("192.0.2.12")};
        }
    }

    private Path file;
    private RecordingClient client;

    @Before
    public void setUp() throws Exception {
        file = Files.createTempFile("azh", ".zone");
        Files.writeString(file, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 10 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 192.0.2.1\n"
                + "www IN A 192.0.2.2\n"
                + "alias IN CNAME www.example.com.\n"
                + "ext IN CNAME host.other.net.\n"
                + "loop1 IN CNAME loop2.example.com.\n"
                + "loop2 IN CNAME loop1.example.com.\n"
                + "dangling IN CNAME nowhere.example.com.\n");
        client = new RecordingClient();
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteIfExists(file);
    }

    private AuthoritativeZoneHandler handler() throws Exception {
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(file))
                .notifyFromNsRecords(false)
                .build();
        h.setMasterClient(client);
        return h;
    }

    private static final class Capture implements DnsQueryCallback {
        DnsMessage response;
        List<DnsMessage> sequence;

        @Override
        public void onResponse(DnsMessage message) {
            response = message;
        }

        @Override
        public void onError(String error) {
            fail(error);
        }

        @Override
        public void onResponseSequence(List<DnsMessage> responses) {
            sequence = responses;
        }
    }

    private static DnsMessage ask(AuthoritativeZoneHandler h, String name, DnsType type,
                                  DnsQueryTransport transport) {
        Capture c = new Capture();
        h.handleQuery(DnsMessage.createQuery(1, name, type), null, transport, c);
        return c.response;
    }

    private static DnsMessage ask(AuthoritativeZoneHandler h, String name, DnsType type) {
        return ask(h, name, type, DnsQueryTransport.UDP);
    }

    private static DnsMessage nonQuery(AuthoritativeZoneHandler h, DnsMessage m) {
        Capture c = new Capture();
        boolean handled = h.handleNonQueryOpcode(m, null, c);
        assertTrue(handled);
        return c.response;
    }

    private DnsResourceRecord soaOf(AuthoritativeZoneHandler h) {
        return h.getZone().getSoaRecord();
    }

    @Test
    public void testSoaAndAnyAndNsQueries() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage soa = ask(h, ORIGIN, DnsType.SOA);
        assertEquals(DnsType.SOA, soa.getAnswers().get(0).getType());
        DnsMessage any = ask(h, "www.example.com.", DnsType.ANY);
        assertEquals(1, any.getAnswers().size());
        assertEquals(DnsType.HINFO, any.getAnswers().get(0).getType());
        DnsMessage ns = ask(h, ORIGIN, DnsType.NS);
        assertEquals(1, ns.getAnswers().size());
        assertFalse(ns.getAdditionals().isEmpty());
        DnsMessage apex = ask(h, ORIGIN, DnsType.A);
        assertTrue(apex.getAnswers().isEmpty());
    }

    @Test
    public void testAnyAnswersFullyWhenMinimalPolicyDisabled() throws Exception {
        AuthoritativeZoneHandler h = handler();
        h.setMinimalAnyPolicy(null);
        DnsMessage any = ask(h, "ns1.example.com.", DnsType.ANY);
        assertEquals(DnsType.A, any.getAnswers().get(0).getType());
        h.setMinimalAnyPolicy(MinimalAnyPolicy.ENABLED);
        any = ask(h, "ns1.example.com.", DnsType.ANY);
        assertEquals(DnsType.HINFO, any.getAnswers().get(0).getType());
    }

    @Test
    public void testCnameChains() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage chain = ask(h, "alias.example.com.", DnsType.A);
        assertEquals(2, chain.getAnswers().size());
        assertEquals(DnsType.CNAME, chain.getAnswers().get(0).getType());
        DnsMessage out = ask(h, "ext.example.com.", DnsType.A);
        assertEquals(1, out.getAnswers().size());
        DnsMessage cname = ask(h, "alias.example.com.", DnsType.CNAME);
        assertEquals(1, cname.getAnswers().size());
        DnsMessage dangling = ask(h, "dangling.example.com.", DnsType.A);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, dangling.getRcode());
        DnsMessage loop = ask(h, "loop1.example.com.", DnsType.A);
        assertEquals(DnsMessage.RCODE_SERVFAIL, loop.getRcode());
    }

    @Test
    public void testAxfrOverUdpAndStream() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage udp = ask(h, ORIGIN, DnsType.AXFR);
        // the sample zone is small enough for one datagram
        assertEquals(DnsMessage.RCODE_NOERROR, udp.getRcode());
        assertFalse(udp.getAnswers().isEmpty());
        Capture c = new Capture();
        h.handleQuery(DnsMessage.createQuery(2, ORIGIN, DnsType.AXFR), null,
                DnsQueryTransport.FRAMED_TCP, c);
        assertNotNull(c.sequence);
        assertFalse(c.sequence.isEmpty());
    }

    @Test
    public void testLargeAxfrOverUdpIsTruncated() throws Exception {
        MutableZone zone = ZoneFile.load(file).asMutable();
        for (int i = 0; i < 60; i++) {
            zone.addRecord(DnsResourceRecord.a("host" + i + ".example.com.", 300,
                    InetAddress.getByName("192.0.2." + (i + 10))));
        }
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder().zone(zone).build();
        DnsMessage udp = ask(h, ORIGIN, DnsType.AXFR);
        assertTrue(udp.isTruncated());
        assertTrue(udp.getAnswers().isEmpty());
    }

    @Test
    public void testAxfrForNonApexIsRefused() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage r = ask(h, "www.example.com.", DnsType.AXFR);
        assertEquals(DnsMessage.RCODE_REFUSED, r.getRcode());
    }

    private DnsMessage ixfrQuery(AuthoritativeZoneHandler h, int clientSerial) {
        DnsResourceRecord soa = DnsResourceRecord.soa(ORIGIN, 300, "ns1.example.com.",
                "host.example.com.", clientSerial, 7200, 3600, 1209600, 300);
        DnsQuestion q = new DnsQuestion(ORIGIN, DnsType.IXFR, DnsClass.IN);
        return new DnsMessage(4, DnsMessage.FLAG_RD, Collections.singletonList(q),
                Collections.<DnsResourceRecord>emptyList(), Collections.singletonList(soa),
                Collections.<DnsResourceRecord>emptyList());
    }

    @Test
    public void testIxfrUpToDateAndRefusedAndIncremental() throws Exception {
        AuthoritativeZoneHandler h = handler();
        Capture upToDate = new Capture();
        h.handleQuery(ixfrQuery(h, 10), null, DnsQueryTransport.UDP, upToDate);
        assertEquals(1, upToDate.response.getAnswers().size());

        Capture ahead = new Capture();
        h.handleQuery(ixfrQuery(h, 99), null, DnsQueryTransport.UDP, ahead);
        assertEquals(DnsMessage.RCODE_REFUSED, ahead.response.getRcode());

        // apply an update so the journal can serve an incremental transfer
        DnsResourceRecord add = DnsResourceRecord.a("n.example.com.", 60,
                InetAddress.getByName("192.0.2.99"));
        DnsMessage update = DnsMessage.createDynamicUpdate(5,
                Collections.singletonList(soaOf(h)),
                Collections.<DnsResourceRecord>emptyList(), Collections.singletonList(add));
        assertEquals(DnsMessage.RCODE_NOERROR, nonQuery(h, update).getRcode());
        Capture delta = new Capture();
        h.handleQuery(ixfrQuery(h, 10), null, DnsQueryTransport.UDP, delta);
        assertNotNull(delta.response);
        Capture seq = new Capture();
        h.handleQuery(ixfrQuery(h, 10), null, DnsQueryTransport.FRAMED_TCP, seq);
        assertNotNull(seq.sequence);

        Capture noSoa = new Capture();
        h.handleQuery(DnsMessage.createQuery(6, ORIGIN, DnsType.IXFR), null,
                DnsQueryTransport.UDP, noSoa);
        assertEquals(DnsMessage.RCODE_FORMERR, noSoa.response.getRcode());
    }

    @Test
    public void testNotifyHandling() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage ok = nonQuery(h, DnsMessage.createNotify(1, ORIGIN));
        assertEquals(DnsMessage.RCODE_NOERROR, ok.getRcode());
        DnsMessage notAuth = nonQuery(h, DnsMessage.createNotify(2, "other.org."));
        assertEquals(DnsMessage.RCODE_NOTAUTH, notAuth.getRcode());
        DnsMessage empty = new DnsMessage(3, DnsMessage.OPCODE_NOTIFY << 11,
                Collections.<DnsQuestion>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        assertEquals(DnsMessage.RCODE_FORMERR, nonQuery(h, empty).getRcode());
    }

    @Test
    public void testNonQueryOpcodeNotRecognised() throws Exception {
        AuthoritativeZoneHandler h = handler();
        Capture c = new Capture();
        DnsMessage status = new DnsMessage(1, 2 << 11, Collections.<DnsQuestion>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        assertFalse(h.handleNonQueryOpcode(status, null, c));
        assertNull(c.response);
    }

    @Test
    public void testUpdateErrorCases() throws Exception {
        AuthoritativeZoneHandler h = handler();
        DnsMessage noZone = DnsMessage.createDynamicUpdate(1,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        assertEquals(DnsMessage.RCODE_FORMERR, nonQuery(h, noZone).getRcode());
        DnsResourceRecord otherSoa = DnsResourceRecord.soa("other.org.", 60, "a.", "b.", 1, 1, 1, 1, 1);
        DnsMessage foreign = DnsMessage.createDynamicUpdate(2,
                Collections.singletonList(otherSoa),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        assertEquals(DnsMessage.RCODE_NOTAUTH, nonQuery(h, foreign).getRcode());
    }

    @Test
    public void testUpdateRefusedWhenTsigRequiredButMissing() throws Exception {
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(file))
                .tsigKey(new TsigKey("k.", "hmac-sha256", new byte[32]))
                .requireTsig(true)
                .build();
        DnsMessage update = DnsMessage.createDynamicUpdate(1,
                Collections.singletonList(soaOf(h)),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        assertEquals(DnsMessage.RCODE_REFUSED, nonQuery(h, update).getRcode());
    }

    @Test
    public void testUpdateNotifiesPeersOnce() throws Exception {
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getByName("192.0.2.30"), 5300);
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(file))
                .notifyPeer(peer)
                .notifyPeer(peer)
                .notifyPeer((InetSocketAddress) null)
                .notifyPeer("replicas.example.net", 5301)
                .build();
        h.setMasterClient(client);
        DnsResourceRecord add = DnsResourceRecord.a("n.example.com.", 60,
                InetAddress.getByName("192.0.2.99"));
        DnsMessage update = DnsMessage.createDynamicUpdate(5,
                Collections.singletonList(soaOf(h)),
                Collections.<DnsResourceRecord>emptyList(), Collections.singletonList(add));
        assertEquals(DnsMessage.RCODE_NOERROR, nonQuery(h, update).getRcode());
        // one literal peer plus the two addresses of the named peer; NS glue also
        // contributes ns1 (port 53) because notifyFromNsRecords defaults to true
        assertTrue(client.notified.contains(peer));
        assertTrue(client.notified.contains(new InetSocketAddress(
                InetAddress.getByName("192.0.2.11"), 5301)));
        assertTrue(client.notified.contains(new InetSocketAddress(
                InetAddress.getByName("192.0.2.12"), 5301)));
        assertTrue(client.notified.contains(new InetSocketAddress(
                InetAddress.getByName("192.0.2.1"), 53)));
        int count = 0;
        for (int i = 0; i < client.notified.size(); i++) {
            if (client.notified.get(i).equals(peer)) {
                count++;
            }
        }
        assertEquals(1, count);
    }

    @Test
    public void testUpdateWithUnresolvablePeerStillNotifiesOthers() throws Exception {
        client.failResolve = true;
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getByName("192.0.2.30"), 5300);
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(file))
                .notifyPeer(peer)
                .notifyPeer("gone.example.net", 5301)
                .notifyFromNsRecords(false)
                .build();
        h.setMasterClient(client);
        DnsResourceRecord add = DnsResourceRecord.a("n.example.com.", 60,
                InetAddress.getByName("192.0.2.99"));
        DnsMessage update = DnsMessage.createDynamicUpdate(5,
                Collections.singletonList(soaOf(h)),
                Collections.<DnsResourceRecord>emptyList(), Collections.singletonList(add));
        assertEquals(DnsMessage.RCODE_NOERROR, nonQuery(h, update).getRcode());
        assertEquals(1, client.notified.size());
        assertEquals(peer, client.notified.get(0));
    }

    @Test
    public void testFailedUpdateDoesNotNotify() throws Exception {
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getByName("192.0.2.30"), 5300);
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                .zone(ZoneFile.load(file)).notifyPeer(peer).build();
        h.setMasterClient(client);
        DnsResourceRecord soa = DnsResourceRecord.soa(ORIGIN, 60, "a.", "b.", 1, 1, 1, 1, 1);
        DnsMessage update = DnsMessage.createDynamicUpdate(5,
                Collections.singletonList(soaOf(h)),
                Collections.<DnsResourceRecord>emptyList(), Collections.singletonList(soa));
        assertEquals(DnsMessage.RCODE_REFUSED, nonQuery(h, update).getRcode());
        assertTrue(client.notified.isEmpty());
    }

    @Test
    public void testBuilderValidation() throws Exception {
        try {
            AuthoritativeZoneHandler.builder().build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            AuthoritativeZoneHandler.builder().zone((ZoneFile) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("zone", expected.getMessage());
        }
        try {
            AuthoritativeZoneHandler.builder().zone((MutableZone) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("zone", expected.getMessage());
        }
        try {
            AuthoritativeZoneHandler.builder().zoneFile(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("path", expected.getMessage());
        }
        try {
            AuthoritativeZoneHandler.builder().zoneFile(file, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("accessMode", expected.getMessage());
        }
        try {
            AuthoritativeZoneHandler.builder().notifyPeer(null, 53);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("host", expected.getMessage());
        }
    }

    @Test
    public void testLoadFromFileAndMissingFile() throws Exception {
        AuthoritativeZoneHandler h = AuthoritativeZoneHandler.load(file);
        assertEquals(ORIGIN, h.getZone().getOrigin());
        assertEquals(1, h.getZones().size());
        Path missing = file.resolveSibling("does-not-exist.zone");
        try {
            AuthoritativeZoneHandler.load(missing);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("failed to load zone file"));
        }
    }

    @Test
    public void testMostSpecificZoneWins() throws Exception {
        MutableZone parent = ZoneFile.load(file).asMutable();
        Path childFile = Files.createTempFile("azh-child", ".zone");
        try {
            Files.writeString(childFile, ""
                    + "$ORIGIN sub.example.com.\n"
                    + "$TTL 60\n"
                    + "@ IN SOA ns.sub.example.com. h.sub.example.com. 1 1 1 1 60\n"
                    + "@ IN NS ns.sub.example.com.\n"
                    + "x IN A 192.0.2.200\n");
            MutableZone child = ZoneFile.load(childFile).asMutable();
            AuthoritativeZoneHandler h = AuthoritativeZoneHandler.builder()
                    .zone(parent).zone(child).build();
            assertEquals("sub.example.com.", h.getZones().get(0).getOrigin());
            DnsMessage r = ask(h, "x.sub.example.com.", DnsType.A);
            assertEquals(1, r.getAnswers().size());
        } finally {
            Files.deleteIfExists(childFile);
        }
    }

    @Test
    public void testStopWithoutStart() throws Exception {
        AuthoritativeZoneHandler h = handler();
        h.start(null);
        h.stop();
    }
}
