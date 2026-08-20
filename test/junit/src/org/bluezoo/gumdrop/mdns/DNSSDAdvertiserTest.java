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

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSSDAdvertiser}, exercised against fake
 * {@link Service}/{@link Listener} implementations rather than a real
 * {@code Gumdrop} instance -- the point of {@link DNSSDAdvertiser}
 * taking its service list as a plain parameter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSSDAdvertiserTest {

    private static final Set<String> NO_EXCLUSIONS = Collections.<String>emptySet();

    private static List<DNSResourceRecord> build(List<Service> services) {
        return DNSSDAdvertiser.buildRecords(services, "gumdrop", 4500, NO_EXCLUSIONS);
    }

    private static List<DNSResourceRecord> ofType(List<DNSResourceRecord> records, DNSType type) {
        List<DNSResourceRecord> result = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord rr : records) {
            if (rr.getType() == type) {
                result.add(rr);
            }
        }
        return result;
    }

    @Test
    public void testAdvertisesKnownServiceType() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));

        List<DNSResourceRecord> ptrs = ofType(records, DNSType.PTR);
        List<DNSResourceRecord> srvs = ofType(records, DNSType.SRV);
        List<DNSResourceRecord> txts = ofType(records, DNSType.TXT);

        // One PTR enumerating the service type, one SRV, one TXT, plus
        // the section 9 meta-query PTR -- two PTRs total.
        assertEquals(2, ptrs.size());
        assertEquals(1, srvs.size());
        assertEquals(1, txts.size());

        DNSResourceRecord serviceTypePtr = findByName(ptrs, "_http._tcp.local");
        assertNotNull(serviceTypePtr);
        assertEquals("gumdrop._http._tcp.local", serviceTypePtr.getTargetName());

        DNSResourceRecord metaPtr = findByName(ptrs, DNSSDAdvertiser.DNS_SD_META_QUERY_NAME);
        assertNotNull(metaPtr);
        assertEquals("_http._tcp.local", metaPtr.getTargetName());

        DNSResourceRecord srv = srvs.get(0);
        assertEquals("gumdrop._http._tcp.local", srv.getName());
        assertEquals(8080, srv.getSRVPort());
        assertEquals("gumdrop.local", srv.getSRVTarget());
    }

    @Test
    public void testUnknownDescriptionIsSkippedNotErrored() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("health", 9090)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));
        assertTrue(records.isEmpty());
    }

    @Test
    public void testExcludedDescriptionIsSkipped() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        Set<String> excluded = new HashSet<String>(Arrays.asList("http"));

        List<DNSResourceRecord> records = DNSSDAdvertiser.buildRecords(
                Collections.singletonList(service), "gumdrop", 4500, excluded);

        assertTrue(records.isEmpty());
    }

    @Test
    public void testNonPositivePortIsSkipped() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", -1)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));
        assertTrue(records.isEmpty());
    }

    @Test
    public void testPtrRecordsAreSharedNotCacheFlushed() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));

        for (DNSResourceRecord rr : ofType(records, DNSType.PTR)) {
            assertFalse("PTR records must never carry cache-flush", rr.isCacheFlush());
        }
    }

    @Test
    public void testSrvAndTxtRecordsAreCacheFlushed() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));

        for (DNSResourceRecord rr : ofType(records, DNSType.SRV)) {
            assertTrue(rr.isCacheFlush());
        }
        for (DNSResourceRecord rr : ofType(records, DNSType.TXT)) {
            assertTrue(rr.isCacheFlush());
        }
    }

    @Test
    public void testTwoServiceTypesEachGetTheirOwnMetaPtr() {
        Service service = new FakeService(Arrays.<Listener>asList(
                new FakeListener("http", 8080),
                new FakeListener("imap", 143)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));

        List<DNSResourceRecord> metaPtrs = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord rr : ofType(records, DNSType.PTR)) {
            if (rr.getName().equals(DNSSDAdvertiser.DNS_SD_META_QUERY_NAME)) {
                metaPtrs.add(rr);
            }
        }
        assertEquals(2, metaPtrs.size());
    }

    @Test
    public void testTxtRecordHasSingleEmptyStringNotZeroLength() {
        Service service = new FakeService(
                Arrays.<Listener>asList(new FakeListener("http", 8080)));
        List<DNSResourceRecord> records = build(Collections.singletonList(service));

        DNSResourceRecord txt = ofType(records, DNSType.TXT).get(0);
        // RFC 6763 section 6.1: RDATA must not be zero-length.
        assertTrue(txt.getRData().length > 0);
    }

    private static DNSResourceRecord findByName(List<DNSResourceRecord> records, String name) {
        for (DNSResourceRecord rr : records) {
            if (rr.getName().equals(name)) {
                return rr;
            }
        }
        return null;
    }

    private static final class FakeService implements Service {
        private final List<Listener> listeners;

        FakeService(List<Listener> listeners) {
            this.listeners = listeners;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List getListeners() {
            return listeners;
        }

        @Override public void start() { }
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
