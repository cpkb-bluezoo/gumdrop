/*
 * DnssecChainValidatorOffloadTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.DnssecStatus;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.*;
import static org.junit.Assert.*;

/**
 * Tests that {@link DnssecChainValidator} runs signature and DS digest
 * verification on the crypto pool rather than on the resolver's selector
 * loop, and fails safe when the pool refuses the work.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnssecChainValidatorOffloadTest {

    private TestGumdrop.QueuedExecutor pool;
    private CannedResolver resolver;
    private DnssecTrustAnchor anchors;
    private DnssecChainValidator validator;
    private DnssecStatus status;
    private int calls;

    private final DnssecValidationCallback callback = new DnssecValidationCallback() {
        @Override
        public void onValidated(DnssecStatus s, DnsMessage response) {
            status = s;
            calls++;
        }
    };

    @Before
    public void setUp() {
        pool = new TestGumdrop.QueuedExecutor();
        useExecutor(pool);
    }

    private void useExecutor(Executor offload) {
        Gumdrop gumdrop = TestGumdrop.create(offload);
        SelectorLoop loop = gumdrop.nextWorkerLoop();
        resolver = new CannedResolver();
        resolver.selectorLoop(loop);
        anchors = new DnssecTrustAnchor();
        anchors.clear();
        validator = new DnssecChainValidator(resolver, anchors);
    }

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    private static List<DnsResourceRecord> a(String name) throws Exception {
        return list(DnsResourceRecord.a(name, 300, InetAddress.getByName("192.0.2.1")));
    }

    private static List<DnsResourceRecord> concat(List<DnsResourceRecord> x,
                                                  DnsResourceRecord... more) {
        List<DnsResourceRecord> out = new ArrayList<DnsResourceRecord>(x);
        for (int i = 0; i < more.length; i++) {
            out.add(more[i]);
        }
        return out;
    }

    @Test
    public void signatureIsVerifiedOnThePoolNotTheCallingThread() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 256);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, zsk, "example.com.");
        validator.validate(message(concat(rrset, sig, zsk.dnskey), none()), callback);

        assertNull("verification must wait for the pool", status);
        assertEquals(1, pool.pendingCount());
        pool.runAll();
        assertEquals(DnssecStatus.SECURE, status);
        assertEquals(1, calls);
    }

    @Test
    public void badSignatureIsStillBogusWhenOffloaded() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 256);
        TestKey other = ecdsaP256("example.com.", 256);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, other, "example.com.");
        validator.validate(message(concat(rrset, sig, zsk.dnskey), none()), callback);
        pool.runAll();
        assertEquals(DnssecStatus.BOGUS, status);
    }

    @Test
    public void dsDigestAndParentSignatureAreVerifiedOnThePool() throws Exception {
        TestKey ksk = ecdsaP256("example.com.", 257);
        TestKey parent = ecdsaP256("com.", 257);
        anchors.addDNSKEYAnchor("com.", parent.dnskey);
        DnsResourceRecord ds = ds(ksk, 2);
        DnsResourceRecord dsSig = signCurrent(list(ds), parent, "com.");
        resolver.put("example.com.", DnsType.DS, message(list(ds, dsSig), none()));
        resolver.put("com.", DnsType.DNSKEY, message(list(parent.dnskey), none()));
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        validator.validate(message(concat(rrset, sig, ksk.dnskey), none()), callback);

        assertNull(status);
        // signature, DS digest, parent signature: three separate pool tasks
        assertEquals(3, pool.runAll());
        assertEquals(DnssecStatus.SECURE, status);
    }

    @Test
    public void saturatedPoolIsIndeterminateNotBogus() throws Exception {
        useExecutor(new Executor() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("full");
            }
        });
        TestKey zsk = ecdsaP256("example.com.", 256);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        List<DnsResourceRecord> rrset = a("www.example.com.");
        DnsResourceRecord sig = signCurrent(rrset, zsk, "example.com.");
        validator.validate(message(concat(rrset, sig, zsk.dnskey), none()), callback);
        assertEquals(DnssecStatus.INDETERMINATE, status);
        assertEquals(1, calls);
    }

    @Test
    public void nsec3AboveTheIterationLimitIsInsecureWithoutAnyVerification() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        DnsResourceRecord n3 = nsec3("AAAA.example.com.", 1,
                DnssecValidator.MAX_NSEC3_ITERATIONS + 1, new byte[0],
                new byte[20], new int[] {1});
        DnsResourceRecord sig = signCurrent(list(n3), zsk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY, message(list(zsk.dnskey), none()));
        validator.validate(message(none(), list(n3, sig)), callback);
        assertEquals(DnssecStatus.INSECURE, status);
        assertEquals(0, pool.pendingCount());
    }

    @Test
    public void nsec3AtTheIterationLimitIsStillValidated() throws Exception {
        TestKey zsk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        DnsResourceRecord n3 = nsec3("AAAA.example.com.", 1,
                DnssecValidator.MAX_NSEC3_ITERATIONS, new byte[0],
                new byte[20], new int[] {1});
        DnsResourceRecord sig = signCurrent(list(n3), zsk, "example.com.");
        resolver.put("example.com.", DnsType.DNSKEY, message(list(zsk.dnskey), none()));
        validator.validate(message(none(), list(n3, sig)), callback);
        pool.runAll();
        assertEquals(DnssecStatus.SECURE, status);
    }
}
