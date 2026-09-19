/*
 * UpstreamRelayHandlerDnssecTest.java
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

package org.bluezoo.gumdrop.dns;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * DNSSEC validation and RFC 8198 proof cache wiring on the forwarder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UpstreamRelayHandlerDnssecTest {

    private Gumdrop gumdrop;

    @Before
    public void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create()
                .workerThreads(1)
                .drainTimeoutMs(0));
    }

    @After
    public void shutdownGumdrop() {
        if (gumdrop != null) {
            gumdrop.shutdown();
        }
    }

    @Test
    public void proofCacheCreatedWhenDnssecEnabled() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("127.0.0.1")
                .useSystemResolvers(false)
                .dnssecEnabled(true)
                .build();
        handler.start(gumdrop);
        try {
            assertNotNull(handler.getNsecProofCache());
        } finally {
            handler.stop();
        }
    }

    @Test
    public void proofCacheAbsentWhenDnssecDisabled() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("127.0.0.1")
                .useSystemResolvers(false)
                .dnssecEnabled(false)
                .build();
        handler.start(gumdrop);
        try {
            assertNull(handler.getNsecProofCache());
        } finally {
            handler.stop();
        }
    }
}
