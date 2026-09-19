/*
 * NotifyTargetsTest.java
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

import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NotifyTargetsTest {

    @Test
    public void testInZoneNsGlueBecomesNotifyTarget() throws Exception {
        Path zone = Files.createTempFile("notify", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns2.example.com.\n"
                + "ns1 IN A 127.0.0.1\n"
                + "ns2 IN A 192.0.2.2\n");
        try {
            MutableZone mutable = ZoneFile.load(zone).asMutable();
            assertEquals(1, mutable.inZoneSecondaryAddresses().size());
            assertEquals(new InetSocketAddress("192.0.2.2", 53),
                    mutable.inZoneSecondaryAddresses().get(0));
        } finally {
            Files.deleteIfExists(zone);
        }
    }
}
