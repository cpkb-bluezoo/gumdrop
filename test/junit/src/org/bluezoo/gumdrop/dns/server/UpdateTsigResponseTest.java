/*
 * UpdateTsigResponseTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsTsig;
import org.bluezoo.gumdrop.dns.TsigKey;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class UpdateTsigResponseTest {

    @Test
    public void testHandlerSignsUpdateResponse() throws Exception {
        Path zone = Files.createTempFile("tsig-upd", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "ns1 IN A 127.0.0.1\n");
        TsigKey key = TsigKey.fromBase64("upd.", TsigKey.HMAC_SHA256, "c2VjcmV0");
        try {
            AuthoritativeZoneHandler handler = AuthoritativeZoneHandler.builder()
                    .zone(ZoneFile.load(zone))
                    .tsigKey(key)
                    .build();
            MutableZone zoneMem = handler.getZone();
            DnsMessage update = DnsMessage.createDynamicUpdate(31,
                    Collections.singletonList(zoneMem.getSoaRecord()),
                    Collections.<DnsResourceRecord>emptyList(),
                    Collections.singletonList(DnsResourceRecord.a("tsig.example.com.",
                            300, InetAddress.getByName("192.0.2.77"))));
            final DnsMessage signedRequest = DnsTsig.sign(update, key);

            final AtomicReference<DnsMessage> responseRef = new AtomicReference<DnsMessage>();
            final CountDownLatch latch = new CountDownLatch(1);
            handler.handleNonQueryOpcode(signedRequest, null, new DnsQueryCallback() {
                @Override
                public void onResponse(DnsMessage response) {
                    responseRef.set(response);
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            DnsMessage response = responseRef.get();
            assertNotNull(response);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertNotNull(response.getTsigRecord());
            assertTrue(DnsTsig.verifyResponse(response, key, signedRequest));
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
