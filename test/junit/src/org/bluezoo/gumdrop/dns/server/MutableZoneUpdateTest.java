/*
 * MutableZoneUpdateTest.java
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

import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link MutableZone} and {@link DynamicUpdateProcessor}
 * (RFC 2136 prerequisites and updates).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MutableZoneUpdateTest {

    private Path file;
    private MutableZone zone;

    @Before
    public void setUp() throws Exception {
        file = Files.createTempFile("mz", ".zone");
        Files.writeString(file, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 5 7200 3600 1209600 60\n"
                + "@ IN NS ns1.example.com.\n"
                + "@ IN NS ns2.other.net.\n"
                + "@ IN MX 10 mail.example.com.\n"
                + "ns1 IN A 192.0.2.1\n"
                + "ns1 IN AAAA 2001:db8::1\n"
                + "mail IN A 192.0.2.2\n"
                + "www IN CNAME ns1.example.com.\n"
                + "*.wild IN A 192.0.2.3\n");
        zone = ZoneFile.load(file).asMutable();
    }

    @After
    public void tearDown() throws Exception {
        Files.deleteIfExists(file);
    }

    private static DnsResourceRecord raw(String name, DnsType type, int rawType,
                                         int rawClass, int ttl, byte[] rdata) {
        DnsClass cls = null;
        if (rawClass == 1) {
            cls = DnsClass.IN;
        }
        return new DnsResourceRecord(name, type, rawType, cls, rawClass, ttl, rdata);
    }

    private int update(List<DnsResourceRecord> zoneSection,
                       List<DnsResourceRecord> prereqs,
                       List<DnsResourceRecord> changes) {
        DnsMessage m = DnsMessage.createDynamicUpdate(1, zoneSection, prereqs, changes);
        return DynamicUpdateProcessor.apply(zone, m);
    }

    private List<DnsResourceRecord> zoneSec() {
        return Collections.singletonList(zone.getSoaRecord());
    }

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    private static List<DnsResourceRecord> one(DnsResourceRecord rr) {
        return Collections.singletonList(rr);
    }

    @Test
    public void testZoneSectionMustHaveOneEntry() {
        assertEquals(DnsMessage.RCODE_FORMERR, update(none(), none(), none()));
        List<DnsResourceRecord> two = new ArrayList<DnsResourceRecord>();
        two.add(zone.getSoaRecord());
        two.add(zone.getSoaRecord());
        assertEquals(DnsMessage.RCODE_FORMERR, update(two, none(), none()));
    }

    @Test
    public void testWrongZoneIsNotZone() {
        DnsResourceRecord other = DnsResourceRecord.soa("other.org.", 60, "a.", "b.", 1, 1, 1, 1, 1);
        assertEquals(DnsMessage.RCODE_NOTZONE, update(one(other), none(), none()));
    }

    @Test
    public void testPrerequisiteNameNotInUseSatisfiedAndViolated() {
        DnsResourceRecord ok = raw("absent.example.com.", DnsType.ANY, 255, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), one(ok), none()));
        DnsResourceRecord bad = raw("www.example.com.", DnsType.ANY, 255, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_YXDOMAIN, update(zoneSec(), one(bad), none()));
    }

    @Test
    public void testPrerequisiteNameInUse() {
        DnsResourceRecord ok = raw("www.example.com.", DnsType.ANY, 255, 1, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), one(ok), none()));
        DnsResourceRecord bad = raw("absent.example.com.", DnsType.ANY, 255, 1, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, update(zoneSec(), one(bad), none()));
    }

    @Test
    public void testPrerequisiteRrsetDoesNotExist() {
        DnsResourceRecord ok = raw("www.example.com.", DnsType.A, 1, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), one(ok), none()));
        DnsResourceRecord bad = raw("ns1.example.com.", DnsType.A, 1, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_YXRRSET, update(zoneSec(), one(bad), none()));
    }

    @Test
    public void testPrerequisiteRrsetExists() throws Exception {
        InetAddress a = InetAddress.getByName("192.0.2.1");
        DnsResourceRecord ok = DnsResourceRecord.a("ns1.example.com.", 0, a);
        DnsResourceRecord okVal = raw("ns1.example.com.", DnsType.A, 1, 1, 5, a.getAddress());
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), one(okVal), none()));
        DnsResourceRecord bad = raw("www.example.com.", DnsType.A, 1, 1, 5, a.getAddress());
        assertEquals(DnsMessage.RCODE_NXRRSET, update(zoneSec(), one(bad), none()));
        assertNotNull(ok);
    }

    @Test
    public void testPrerequisiteOutsideZone() throws Exception {
        DnsResourceRecord out = DnsResourceRecord.a("x.other.org.", 5,
                InetAddress.getByName("192.0.2.1"));
        assertEquals(DnsMessage.RCODE_NOTZONE, update(zoneSec(), one(out), none()));
    }

    @Test
    public void testUpdateOutsideZone() throws Exception {
        DnsResourceRecord out = DnsResourceRecord.a("x.other.org.", 5,
                InetAddress.getByName("192.0.2.1"));
        assertEquals(DnsMessage.RCODE_NOTZONE, update(zoneSec(), none(), one(out)));
    }

    @Test
    public void testUpdateDeleteRrsetAndName() throws Exception {
        DnsResourceRecord delA = raw("ns1.example.com.", DnsType.A, 1, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(delA)));
        assertFalse(zone.rrsetExists("ns1.example.com.", DnsType.A));
        assertTrue(zone.rrsetExists("ns1.example.com.", DnsType.AAAA));
        assertEquals(6, zone.getSerial());
        DnsResourceRecord delName = raw("ns1.example.com.", DnsType.ANY, 255, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(delName)));
        assertFalse(zone.nameExists("ns1.example.com."));
        assertEquals(7, zone.getSerial());
    }

    @Test
    public void testUpdateDeleteOfMissingNameLeavesSerial() {
        DnsResourceRecord del = raw("nothing.example.com.", DnsType.ANY, 255, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(del)));
        DnsResourceRecord delT = raw("nothing.example.com.", DnsType.A, 1, 255, 0, new byte[0]);
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(delT)));
        assertEquals(5, zone.getSerial());
    }

    @Test
    public void testUpdateCannotDeleteOriginOrSoa() {
        DnsResourceRecord delOrigin = raw("example.com.", DnsType.ANY, 255, 255, 0, new byte[0]);
        try {
            update(zoneSec(), none(), one(delOrigin));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("cannot delete origin", expected.getMessage());
        }
        DnsResourceRecord delSoa = raw("example.com.", DnsType.SOA, 6, 255, 0, new byte[0]);
        try {
            update(zoneSec(), none(), one(delSoa));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("cannot delete SOA", expected.getMessage());
        }
    }

    @Test
    public void testUpdateAddSoaRefused() {
        DnsResourceRecord soa = DnsResourceRecord.soa("example.com.", 60, "a.", "b.", 9, 1, 1, 1, 1);
        assertEquals(DnsMessage.RCODE_REFUSED, update(zoneSec(), none(), one(soa)));
    }

    @Test
    public void testUpdateUnknownRawTypeIsAdded() {
        DnsResourceRecord unknown = raw("new.example.com.", null, 65280, 1, 30, new byte[] {1, 2});
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(unknown)));
        assertTrue(zone.nameExists("new.example.com."));
    }

    @Test
    public void testJournalRecordsDynamicUpdate() throws Exception {
        DnsResourceRecord add = DnsResourceRecord.a("n.example.com.", 30,
                InetAddress.getByName("192.0.2.77"));
        assertEquals(DnsMessage.RCODE_NOERROR, update(zoneSec(), none(), one(add)));
        assertFalse(zone.getJournal().isEmpty());
        assertEquals(6, zone.getSerial());
        assertEquals(60, zone.authoritySoa().getTTL());
        // commit of an empty or null batch is a no-op
        zone.commitDynamicUpdate(null);
        zone.commitDynamicUpdate(new ZoneChangeBatch());
        assertEquals(6, zone.getSerial());
    }

    @Test
    public void testLookupVariants() {
        assertEquals(ZoneLookupResult.STATUS_ANSWER,
                zone.lookup("ns1.example.com.", DnsType.A).getStatus());
        assertEquals(ZoneLookupResult.STATUS_NODATA,
                zone.lookup("ns1.example.com.", DnsType.MX).getStatus());
        ZoneLookupResult cname = zone.lookup("www.example.com.", DnsType.A);
        assertEquals(ZoneLookupResult.STATUS_ANSWER, cname.getStatus());
        assertEquals(DnsType.CNAME, cname.getAnswers().get(0).getType());
        assertEquals(ZoneLookupResult.STATUS_ANSWER,
                zone.lookup("www.example.com.", DnsType.CNAME).getStatus());
        assertEquals(ZoneLookupResult.STATUS_NODATA,
                zone.lookup("ns1.example.com.", DnsType.CNAME).getStatus());
        ZoneLookupResult any = zone.lookup("ns1.example.com.", DnsType.ANY);
        assertEquals(2, any.getAnswers().size());
        ZoneLookupResult wild = zone.lookup("foo.wild.example.com.", DnsType.A);
        assertTrue(wild.isFromWildcard());
        assertEquals(ZoneLookupResult.STATUS_NXDOMAIN,
                zone.lookup("foo.nowild.example.com.", DnsType.A).getStatus());
        assertEquals(ZoneLookupResult.STATUS_NXDOMAIN,
                zone.lookup("nothing.example.com.", DnsType.A).getStatus());
        assertEquals(ZoneLookupResult.STATUS_NXDOMAIN, zone.lookup("com.", DnsType.A).getStatus());
    }

    @Test
    public void testGlueAndSecondaryAddresses() {
        List<DnsResourceRecord> glue = zone.glueFor(zone.getNsRecords());
        assertEquals(2, glue.size());
        List<InetSocketAddress> addrs = zone.inZoneSecondaryAddresses();
        assertEquals(2, addrs.size());
        assertEquals(53, addrs.get(0).getPort());
        assertTrue(zone.glueFor(null).isEmpty());
        assertTrue(zone.glueFor(Collections.<DnsResourceRecord>emptyList()).isEmpty());
        List<DnsResourceRecord> mx = new ArrayList<DnsResourceRecord>();
        mx.add(DnsResourceRecord.mx("example.com.", 60, 10, "mail.example.com."));
        mx.add(DnsResourceRecord.mx("example.com.", 60, 10, "mx.elsewhere.org."));
        mx.add(DnsResourceRecord.mx("example.com.", 60, 10, "gone.example.com."));
        assertEquals(1, zone.glueFor(mx).size());
        List<DnsResourceRecord> notNs = new ArrayList<DnsResourceRecord>();
        notNs.add(zone.getSoaRecord());
        assertTrue(zone.glueFor(notNs).isEmpty());
    }

    @Test
    public void testAccessors() {
        assertEquals("example.com.", zone.getOrigin());
        assertEquals(300, zone.getDefaultTtl());
        assertEquals(60, zone.getMinimumTtl());
        assertTrue(zone.isWithinZone("A.EXAMPLE.COM"));
        assertTrue(zone.isWithinZone("example.com"));
        assertFalse(zone.isWithinZone("notexample.com."));
        assertTrue(zone.ownerNames().contains("www.example.com."));
        assertTrue(zone.recordsAt("nothing.example.com.").isEmpty());
        assertEquals(1, zone.recordsAt("www.example.com.").size());
        assertFalse(zone.allRecords().isEmpty());
        assertFalse(zone.rrsetExists("nothing.example.com.", DnsType.A));
        assertEquals(5, zone.getSoaData().serial);
    }

    @Test
    public void testAddRecordOutsideZoneRejected() throws Exception {
        try {
            zone.addRecord(DnsResourceRecord.a("x.other.org.", 5, InetAddress.getByName("192.0.2.1")));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("owner outside zone", expected.getMessage());
        }
    }

    @Test
    public void testFromAxfrAndReplace() throws Exception {
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>();
        records.add(DnsResourceRecord.soa("example.com.", 120, "ns.example.com.", "h.example.com.",
                42, 10, 20, 30, 40));
        records.add(DnsResourceRecord.a("a.example.com.", 120, InetAddress.getByName("192.0.2.5")));
        MutableZone z = MutableZone.fromAxfr("example.com.", records);
        assertEquals(42, z.getSerial());
        assertEquals(40, z.getMinimumTtl());
        assertTrue(z.nameExists("a.example.com."));

        List<DnsResourceRecord> next = new ArrayList<DnsResourceRecord>();
        next.add(DnsResourceRecord.soa("example.com.", 120, "ns.example.com.", "h.example.com.",
                43, 10, 20, 30, 50));
        z.replaceFromAxfr(next);
        assertEquals(43, z.getSerial());
        assertEquals(50, z.getMinimumTtl());
        assertFalse(z.nameExists("a.example.com."));

        List<DnsResourceRecord> noSoa = new ArrayList<DnsResourceRecord>();
        noSoa.add(DnsResourceRecord.a("a.example.com.", 120, InetAddress.getByName("192.0.2.5")));
        try {
            MutableZone.fromAxfr("example.com.", noSoa);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no SOA"));
        }
    }
}
