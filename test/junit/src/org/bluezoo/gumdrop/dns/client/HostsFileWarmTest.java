/*
 * HostsFileWarmTest.java
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

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Asserts {@link HostsFile#warm()} eagerly populates the in-memory cache so
 * subsequent {@link HostsFile#lookup} calls reuse it (no re-parse).
 */
public class HostsFileWarmTest {

    @After
    public void tearDown() {
        HostsFile.clear();
    }

    @Test
    public void warm_populatesCache_lookupReusesSameMap() throws Exception {
        HostsFile.clear();
        assertNull("cache should be empty after clear", cachedEntries());

        HostsFile.warm();

        Map<?, ?> afterWarm = cachedEntries();
        assertNotNull("warm() must populate the hosts cache", afterWarm);

        List<InetAddress> first = HostsFile.lookup("localhost");
        // localhost is present on essentially every platform hosts file;
        // if absent, warm still succeeded — assert cache identity below.
        List<InetAddress> second = HostsFile.lookup("localhost");
        assertSame("repeated lookup must reuse warm() cache",
                afterWarm, cachedEntries());
        assertEquals(first, second);
        assertSame("lookup must not replace the cached map",
                afterWarm, cachedEntries());
    }

    @Test
    public void clear_thenLookup_reparses() throws Exception {
        HostsFile.warm();
        Map<?, ?> warmed = cachedEntries();
        assertNotNull(warmed);

        HostsFile.clear();
        assertNull(cachedEntries());

        HostsFile.lookup("localhost");
        Map<?, ?> afterLookup = cachedEntries();
        assertNotNull(afterLookup);
        assertNotSame("clear() must force a fresh parse on next access",
                warmed, afterLookup);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> cachedEntries() throws Exception {
        Field f = HostsFile.class.getDeclaredField("entries");
        f.setAccessible(true);
        return (Map<?, ?>) f.get(null);
    }
}
