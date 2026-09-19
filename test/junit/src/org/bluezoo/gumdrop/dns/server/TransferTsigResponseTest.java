/*
 * TransferTsigResponseTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQueryTransport;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.TsigKey;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TransferTsigResponseTest {

    @Test
    public void testHandlerSignsAxfrSequence() throws Exception {
        Path zone = Files.createTempFile("tsig-xfr", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "www IN A 192.0.2.1\n");
        TsigKey key = TsigKey.fromBase64("xfr.", TsigKey.HMAC_SHA256, "c2VjcmV0");
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .tsigKey(key)
                    .build();
            DnsQuestion q = new DnsQuestion("example.com.", DnsType.AXFR,
                    org.bluezoo.gumdrop.dns.DnsClass.IN);
            DnsMessage axfr = new DnsMessage(99, DnsMessage.FLAG_RD,
                    Collections.singletonList(q),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList());
            final DnsMessage signedRequest = DnsTsig.sign(axfr, key);

            final AtomicReference<List<DnsMessage>> seqRef =
                    new AtomicReference<List<DnsMessage>>();
            final CountDownLatch latch = new CountDownLatch(1);
            handler.handleQuery(signedRequest, null, DnsQueryTransport.FRAMED_TCP,
                    new DnsQueryCallback() {
                        @Override
                        public void onResponse(DnsMessage response) {
                            latch.countDown();
                        }

                        @Override
                        public void onResponseSequence(List<DnsMessage> responses) {
                            seqRef.set(responses);
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error) {
                            latch.countDown();
                        }
                    });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            List<DnsMessage> seq = seqRef.get();
            assertNotNull(seq);
            assertFalse(seq.isEmpty());
            assertNotNull(seq.get(0).getTsigRecord());
            assertTrue(DnsTsig.verifyResponseSequence(seq, key, signedRequest));
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
