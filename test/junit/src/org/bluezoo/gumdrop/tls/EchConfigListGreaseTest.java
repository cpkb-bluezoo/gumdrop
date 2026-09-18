/*
 * EchConfigListGreaseTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EchConfigListGreaseTest {

    private static final byte[] PK_RM = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");

    @Test
    public void prependsGreaseConfig() throws Exception {
        EchConfig real = EchConfig.createV13(1, PK_RM, "public.example", 32);
        byte[] list = EchConfig.encodeList(new EchConfig[] { real });
        byte[] greased = EchConfigListGrease.withServerGrease(list);
        EchConfig[] parsed = EchConfig.parseList(greased);
        assertEquals(2, parsed.length);
        assertEquals("grease.invalid", parsed[0].getPublicName());
        assertEquals(1, parsed[1].getConfigId());
    }

    @Test
    public void greaseConfigIsIgnoredByDiscovery() throws Exception {
        EchConfig real = EchConfig.createV13(3, PK_RM, "public.example", 32);
        byte[] list = EchConfig.encodeList(new EchConfig[] { real });
        byte[] greased = EchConfigListGrease.withServerGrease(list);
        EchConfig selected = EchHttpsDiscovery.selectClientConfig(greased);
        assertTrue(selected != null && selected.getConfigId() == 3);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
