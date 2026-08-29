/*
 * QuicEngineConnectionIdDemuxTest.java
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

package org.bluezoo.gumdrop.quic;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Regression test for issue #321: {@code QuicEngine} demultiplexed every
 * received datagram to its {@code QuicConnection} by hex-encoding the
 * destination connection ID into a {@code String}, purely to key a
 * {@code Map<String, QuicConnection>} -- an allocation on every packet,
 * on the hot path shared by every connection an engine handles.
 *
 * <p>Exercises the exact production register/unregister connection-ID
 * path (package-private {@code QuicEngine} methods, reachable directly
 * since this test lives in the same package) at a scale where the
 * eliminated per-call {@code String} allocation is the dominant cost.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicEngineConnectionIdDemuxTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test(timeout = 15000)
    public void testConnectionIdRegistrationAvoidsPerCallStringAllocation() throws Exception {
        QuicTransportFactory factory = new QuicTransportFactory();
        QuicEngine engine = new QuicEngine(factory, true);

        int iterations = 3000000;
        List<byte[]> ids = new ArrayList<byte[]>(iterations);
        for (int i = 0; i < iterations; i++) {
            byte[] id = new byte[QuicEngine.CONNECTION_ID_LENGTH];
            RANDOM.nextBytes(id);
            ids.add(id);
        }

        long start = System.nanoTime();
        for (byte[] id : ids) {
            engine.registerConnectionId(id, null);
            engine.unregisterConnectionId(id);
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(iterations + " register/unregister cycles took " + elapsedMs
                + "ms -- expected connection-ID demultiplexing to avoid hex-encoding "
                + "a String per call and stay far below the cost of doing so",
                elapsedMs < 500);
    }
}
