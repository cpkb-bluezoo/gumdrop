/*
 * AltSvcCacheTest.java
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

package org.bluezoo.gumdrop.http.client;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AltSvcCache}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AltSvcCacheTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    @Test
    public void testPutAndGet() {
        AltSvcCache.put("example.com", 443, null, 8443, 3600);

        AltSvcCache.Entry entry = AltSvcCache.get("example.com", 443);
        assertNotNull(entry);
        assertNull(entry.getH3Host());
        assertEquals(8443, entry.getH3Port());
    }

    @Test
    public void testGetMissing() {
        assertNull(AltSvcCache.get("nowhere.example.com", 443));
    }

    @Test
    public void testGetIsCaseInsensitiveOnHost() {
        AltSvcCache.put("Example.COM", 443, null, 443, 3600);
        assertNotNull(AltSvcCache.get("example.com", 443));
        assertNotNull(AltSvcCache.get("EXAMPLE.COM", 443));
    }

    @Test
    public void testDifferentPortIsDifferentEntry() {
        AltSvcCache.put("example.com", 443, null, 443, 3600);
        assertNull(AltSvcCache.get("example.com", 8080));
    }

    @Test
    public void testExpiry() throws Exception {
        AltSvcCache.put("example.com", 443, null, 443, 0);
        // maxAgeSeconds=0 -> expiry is "now", so a subsequent get (even a
        // few ms later) must observe it as expired.
        Thread.sleep(5);
        assertNull(AltSvcCache.get("example.com", 443));
    }

    @Test
    public void testAltHostPreserved() {
        AltSvcCache.put("example.com", 443, "alt.example.com", 8443, 3600);

        AltSvcCache.Entry entry = AltSvcCache.get("example.com", 443);
        assertNotNull(entry);
        assertEquals("alt.example.com", entry.getH3Host());
        assertEquals(8443, entry.getH3Port());
    }

    @Test
    public void testOverwrite() {
        AltSvcCache.put("example.com", 443, null, 443, 3600);
        AltSvcCache.put("example.com", 443, null, 9443, 3600);

        assertEquals(9443, AltSvcCache.get("example.com", 443).getH3Port());
    }
}
