/*
 * EchHttpsDiscoveryTest.java
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

package org.bluezoo.gumdrop.tls;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link EchHttpsDiscovery}.
 */
public class EchHttpsDiscoveryTest {

    private static final byte[] PK_RM = hex(
            "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");

    @Test
    public void selectsCompatibleConfigFromList() throws Exception {
        EchConfig ech = EchConfig.createV13(7, PK_RM, "public.example", 32);
        byte[] list = EchConfig.encodeList(new EchConfig[] { ech });
        EchConfig selected = EchHttpsDiscovery.selectClientConfig(list);
        assertNotNull(selected);
        assertEquals(7, selected.getConfigId());
        assertEquals("public.example", selected.getPublicName());
    }

    @Test
    public void nullListReturnsNull() {
        assertNull(EchHttpsDiscovery.selectClientConfig(null));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
