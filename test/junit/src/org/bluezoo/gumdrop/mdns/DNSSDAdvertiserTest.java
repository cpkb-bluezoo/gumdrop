/*
 * DNSSDAdvertiserTest.java
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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DnssdAdvertiser}, exercised against fake
 * {@link Server}/{@link Listener} implementations rather than a real
 * {@code Gumdrop} instance -- the point of {@link DnssdAdvertiser}
 * taking its server list as a plain parameter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSSDAdvertiserTest {

    private static final Set<String> NO_EXCLUSIONS = Collections.<String>emptySet();

    private static List<DnsResourceRecord> build(List<Server> servers) {
        return DnssdAdvertiser.buildRecords(servers, "gumdrop", 4500, NO_EXCLUSIONS);
    }

    private static List<DnsResourceRecord> ofType(List<DnsResourceRecord> records, DnsType type) {
        List<DnsResourceRecord> result = new ArrayList<DnsResourceRecord>();
        for (DnsResourceRecord rr : records) {
            if (rr.getType() == type) {
                result.add(rr);
            }
        }
        return result;
    }

    @Test
    public void testAdvertisesKnownServiceType() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));

        List<DnsResourceRecord> ptrs = ofType(records, DnsType.PTR);
        List<DnsResourceRecord> srvs = ofType(records, DnsType.SRV);
        List<DnsResourceRecord> txts = ofType(records, DnsType.TXT);

        // One PTR enumerating the service type, one SRV, one TXT, plus
        // the section 9 meta-query PTR -- two PTRs total.
        assertEquals(2, ptrs.size());
        assertEquals(1, srvs.size());
        assertEquals(1, txts.size());

        DnsResourceRecord serviceTypePtr = findByName(ptrs, "_http._tcp.local");
        assertNotNull(serviceTypePtr);
        assertEquals("gumdrop._http._tcp.local", serviceTypePtr.getTargetName());

        DnsResourceRecord metaPtr = findByName(ptrs, DnssdAdvertiser.DNS_SD_META_QUERY_NAME);
        assertNotNull(metaPtr);
        assertEquals("_http._tcp.local", metaPtr.getTargetName());

        DnsResourceRecord srv = srvs.get(0);
        assertEquals("gumdrop._http._tcp.local", srv.getName());
        assertEquals(8080, srv.getSRVPort());
        assertEquals("gumdrop.local", srv.getSRVTarget());
    }

    @Test
    public void testUnknownDescriptionIsSkippedNotErrored() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("health", 9090)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));
        assertTrue(records.isEmpty());
    }

    @Test
    public void testExcludedDescriptionIsSkipped() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        Set<String> excluded = new HashSet<String>(Arrays.asList("http"));

        List<DnsResourceRecord> records = DnssdAdvertiser.buildRecords(
                Collections.singletonList(server), "gumdrop", 4500, excluded);

        assertTrue(records.isEmpty());
    }

    @Test
    public void testNonPositivePortIsSkipped() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", -1)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));
        assertTrue(records.isEmpty());
    }

    @Test
    public void testPtrRecordsAreSharedNotCacheFlushed() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));

        for (DnsResourceRecord rr : ofType(records, DnsType.PTR)) {
            assertFalse("PTR records must never carry cache-flush", rr.isCacheFlush());
        }
    }

    @Test
    public void testSrvAndTxtRecordsAreCacheFlushed() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));

        for (DnsResourceRecord rr : ofType(records, DnsType.SRV)) {
            assertTrue(rr.isCacheFlush());
        }
        for (DnsResourceRecord rr : ofType(records, DnsType.TXT)) {
            assertTrue(rr.isCacheFlush());
        }
    }

    @Test
    public void testTwoServiceTypesEachGetTheirOwnMetaPtr() {
        Server server = new FakeServer(Arrays.<Listener>asList(
                new FakeListener("http", 8080),
                new FakeListener("imap", 143)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));

        List<DnsResourceRecord> metaPtrs = new ArrayList<DnsResourceRecord>();
        for (DnsResourceRecord rr : ofType(records, DnsType.PTR)) {
            if (rr.getName().equals(DnssdAdvertiser.DNS_SD_META_QUERY_NAME)) {
                metaPtrs.add(rr);
            }
        }
        assertEquals(2, metaPtrs.size());
    }

    @Test
    public void testTxtRecordHasSingleEmptyStringNotZeroLength() {
        Server server = new FakeServer(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DnsResourceRecord> records = build(Collections.singletonList(server));

        DnsResourceRecord txt = ofType(records, DnsType.TXT).get(0);
        // RFC 6763 section 6.1: RDATA must not be zero-length.
        assertTrue(txt.getRData().length > 0);
    }

    private static DnsResourceRecord findByName(List<DnsResourceRecord> records, String name) {
        for (DnsResourceRecord rr : records) {
            if (rr.getName().equals(name)) {
                return rr;
            }
        }
        return null;
    }

    private static final class FakeServer implements Server {
        private final List<Listener> listeners;

        FakeServer(List<Listener> listeners) {
            this.listeners = listeners;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List getListeners() {
            return listeners;
        }

        @Override public void start(Gumdrop gumdrop) { }
        @Override public void stop() { }
    }

    private static final class FakeListener extends Listener {
        private final String description;
        private final int port;

        FakeListener(String description, int port) {
            this.description = description;
            this.port = port;
        }

        @Override public String getDescription() { return description; }
        @Override public int getPort() { return port; }
    }

}
