/*
 * TcpTransportFactoryEchClientTest.java
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


package org.bluezoo.gumdrop;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.EchConfig;
import org.bluezoo.gumdrop.tls.HandshakeConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Client ECH policy on {@link TcpTransportFactory}: requiring ECH, and
 * adopting authenticated {@code retry_configs} for later connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TcpTransportFactoryEchClientTest {

    private static final byte[] PK = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static EchConfig config(int id, String publicName, int[][] suites) {
        return EchConfig.createV13(id, PK, publicName, 32, suites);
    }

    private static final int[][] SECTION_9 = { { 1, 1 } };

    @Test
    public void echRequiredReachesTheClientHandshakeConfig() {
        TcpTransportFactory factory = new TcpTransportFactory();
        assertFalse(factory.buildClientConfig("example.com").isEchRequired());
        factory.setClientEchRequired(true);
        assertTrue(factory.buildClientConfig("example.com").isEchRequired());
    }

    @Test
    public void retryConfigsReplaceTheClientConfigForLaterConnections() {
        TcpTransportFactory factory = new TcpTransportFactory();
        factory.setClientEchConfig(config(1, "public.example", SECTION_9));
        HandshakeConfig client = factory.buildClientConfig("example.com");
        assertNotNull("engine must be told where to report retry_configs", client.getEchRetryConfigsListener());

        client.getEchRetryConfigsListener().retryConfigsReceived(new EchConfig[] {
                config(8, "grease.invalid", SECTION_9),
                config(9, "unusable.example", new int[][] { { 2, 1 } }),
                config(10, "public.example", SECTION_9) });

        assertEquals("first usable, non-GREASE config wins", 10, factory.getClientEchConfig().getConfigId());
        assertEquals(10, factory.buildClientConfig("example.com").getEchConfig().getConfigId());
    }

    @Test
    public void retryConfigsWithNothingUsableLeaveTheConfigAlone() {
        TcpTransportFactory factory = new TcpTransportFactory();
        EchConfig original = config(1, "public.example", SECTION_9);
        factory.setClientEchConfig(original);
        factory.buildClientConfig("example.com").getEchRetryConfigsListener().retryConfigsReceived(
                new EchConfig[] { config(9, "unusable.example", new int[][] { { 2, 1 } }) });
        assertEquals(1, factory.getClientEchConfig().getConfigId());
    }
}
