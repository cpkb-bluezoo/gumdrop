/*
 * ResolvConfWarmTest.java
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
import java.util.List;

import static org.junit.Assert.*;

/**
 * Asserts {@link ResolvConf#warm()} eagerly populates the in-memory cache so
 * subsequent {@link ResolvConf#getNameservers} calls reuse it (no re-parse).
 */
public class ResolvConfWarmTest {

    @After
    public void tearDown() {
        ResolvConf.clear();
    }

    @Test
    public void warm_populatesCache_getNameserversReusesSameList() throws Exception {
        ResolvConf.clear();
        assertNull("cache should be empty after clear", cachedNameservers());

        ResolvConf.warm();

        List<?> afterWarm = cachedNameservers();
        assertNotNull("warm() must populate the nameserver cache", afterWarm);

        List<String> first = ResolvConf.getNameservers();
        List<String> second = ResolvConf.getNameservers();
        assertSame("repeated getNameservers() must reuse warm() cache",
                afterWarm, cachedNameservers());
        assertEquals(first, second);
        assertSame("getNameservers() must not replace the cached list",
                afterWarm, cachedNameservers());
    }

    @Test
    public void clear_thenGetNameservers_reparses() throws Exception {
        ResolvConf.warm();
        List<?> warmed = cachedNameservers();
        assertNotNull(warmed);

        ResolvConf.clear();
        assertNull(cachedNameservers());

        ResolvConf.getNameservers();
        List<?> afterLookup = cachedNameservers();
        assertNotNull(afterLookup);
        assertNotSame("clear() must force a fresh parse on next access",
                warmed, afterLookup);
    }

    private static List<?> cachedNameservers() throws Exception {
        Field f = ResolvConf.class.getDeclaredField("nameservers");
        f.setAccessible(true);
        return (List<?>) f.get(null);
    }
}
