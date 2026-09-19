/*
 * UpdateTsigResponseTest.java
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

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
