/*
 * HostsFileTest.java
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

import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HostsFile}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HostsFileTest {

    @Test
    public void testLocalhostResolvable() {
        List<InetAddress> result = HostsFile.lookup("localhost");
        assertNotNull("localhost should be in hosts file", result);
        assertFalse("localhost should have at least one address",
                result.isEmpty());
    }

    @Test
    public void testCaseInsensitive() {
        List<InetAddress> lower = HostsFile.lookup("localhost");
        List<InetAddress> upper = HostsFile.lookup("LOCALHOST");
        assertEquals(lower, upper);
    }

    @Test
    public void testUnknownHostReturnsNull() {
        List<InetAddress> result = HostsFile.lookup(
                "this-host-does-not-exist.invalid");
        assertNull(result);
    }

}
