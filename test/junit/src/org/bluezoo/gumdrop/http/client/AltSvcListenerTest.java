/*
 * AltSvcListenerTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AltSvcListener}'s Alt-Svc header parsing (RFC 7838).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AltSvcListenerTest {

    @Test
    public void testSameOriginNoMaxAge() {
        AltSvcListener.H3Entry entry = AltSvcListener.parseAltSvcH3("h3=\":443\"");
        assertNotNull(entry);
        assertEquals(0, entry.hostLength);
        assertEquals(443, entry.port);
        assertEquals(AltSvcListener.DEFAULT_MAX_AGE_SECONDS, entry.maxAgeSeconds);
    }

    @Test
    public void testExplicitHost() {
        AltSvcListener.H3Entry entry =
                AltSvcListener.parseAltSvcH3("h3=\"alt.example.com:8443\"");
        assertNotNull(entry);
        assertEquals(8443, entry.port);
        assertEquals("alt.example.com",
                AltSvcListener.extractAltSvcHost("h3=\"alt.example.com:8443\"", entry.hostLength));
    }

    @Test
    public void testMaxAgeParsed() {
        AltSvcListener.H3Entry entry =
                AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=2592000");
        assertNotNull(entry);
        assertEquals(443, entry.port);
        assertEquals(2592000L, entry.maxAgeSeconds);
    }

    @Test
    public void testMaxAgeWithOtherEntriesFollowing() {
        AltSvcListener.H3Entry entry =
                AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=60, h3-29=\":443\"; ma=60");
        assertNotNull(entry);
        assertEquals(60L, entry.maxAgeSeconds);
    }

    @Test
    public void testUnknownParamIgnored() {
        AltSvcListener.H3Entry entry =
                AltSvcListener.parseAltSvcH3("h3=\":443\"; persist=1; ma=100");
        assertNotNull(entry);
        assertEquals(100L, entry.maxAgeSeconds);
    }

    @Test
    public void testH3NotFirstEntry() {
        AltSvcListener.H3Entry entry =
                AltSvcListener.parseAltSvcH3("h2=\":443\", h3=\":8443\"; ma=120");
        assertNotNull(entry);
        assertEquals(8443, entry.port);
        assertEquals(120L, entry.maxAgeSeconds);
    }

    @Test
    public void testNoH3Entry() {
        assertNull(AltSvcListener.parseAltSvcH3("h2=\":443\""));
    }

    @Test
    public void testMalformedMissingColon() {
        assertNull(AltSvcListener.parseAltSvcH3("h3=\"443\""));
    }

    @Test
    public void testMalformedNonNumericPort() {
        assertNull(AltSvcListener.parseAltSvcH3("h3=\":abc\""));
    }

    @Test
    public void testEmptyValue() {
        assertNull(AltSvcListener.parseAltSvcH3(""));
    }
}
