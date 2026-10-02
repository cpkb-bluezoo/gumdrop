/*
 * AltSvcParsingEdgeTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Boundary and malformed-input cases for the Alt-Svc parsing in
 * {@link AltSvcListener} (RFC 7838 section 3).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AltSvcParsingEdgeTest {

    @Test
    public void portBoundaries() {
        assertNull(AltSvcListener.parseAltSvcH3("h3=\":0\""));
        assertNull(AltSvcListener.parseAltSvcH3("h3=\":65536\""));
        assertNull(AltSvcListener.parseAltSvcH3("h3=\":\""));
        assertEquals(65535, AltSvcListener.parseAltSvcH3("h3=\":65535\"").port);
        assertEquals(1, AltSvcListener.parseAltSvcH3("h3=\":1\"").port);
    }

    @Test
    public void unterminatedOrColonlessEntriesAreRejected() {
        assertNull(AltSvcListener.parseAltSvcH3("h3=\":443"));
        assertNull(AltSvcListener.parseAltSvcH3("h3=\"host\""));
        assertNull(AltSvcListener.parseAltSvcH3("h3=\""));
        assertNull(AltSvcListener.parseAltSvcH3("h3="));
        assertNull(AltSvcListener.parseAltSvcH3("h3"));
        assertNull(AltSvcListener.parseAltSvcH3("clear"));
        assertNull(AltSvcListener.parseAltSvcH3("   "));
    }

    @Test
    public void whitespaceAndTabsBeforeEntriesAreSkipped() {
        AltSvcListener.H3Entry e = AltSvcListener.parseAltSvcH3("  \t h3=\":8443\" \t; \t ma=30");
        assertNotNull(e);
        assertEquals(8443, e.port);
        assertEquals(30L, e.maxAgeSeconds);
    }

    @Test
    public void ipv6AlternativeHostLengthExcludesThePort() {
        String value = "h3=\"[2001:db8::1]:4433\"";
        AltSvcListener.H3Entry e = AltSvcListener.parseAltSvcH3(value);
        assertNotNull(e);
        assertEquals(4433, e.port);
        assertEquals("[2001:db8::1]", AltSvcListener.extractAltSvcHost(value, e.hostLength));
    }

    @Test
    public void maxAgeVariants() {
        assertEquals(0L, AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=0").maxAgeSeconds);
        assertEquals("ma without digits keeps the default", AltSvcListener.DEFAULT_MAX_AGE_SECONDS,
                AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=").maxAgeSeconds);
        assertEquals(AltSvcListener.DEFAULT_MAX_AGE_SECONDS,
                AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=x").maxAgeSeconds);
        assertEquals("only the first entry's parameters count", 5L,
                AltSvcListener.parseAltSvcH3("h3=\":443\"; ma=5, h3=\":444\"; ma=9").maxAgeSeconds);
        assertEquals(7L, AltSvcListener.parseAltSvcH3("h3=\":443\"; persist=1; foo=bar; ma=7").maxAgeSeconds);
        assertEquals(AltSvcListener.DEFAULT_MAX_AGE_SECONDS,
                AltSvcListener.parseAltSvcH3("h3=\":443\" garbage").maxAgeSeconds);
        assertEquals(AltSvcListener.DEFAULT_MAX_AGE_SECONDS,
                AltSvcListener.parseAltSvcH3("h3=\":443\";").maxAgeSeconds);
    }

    @Test
    public void entriesBeforeTheH3EntryAreSkipped() {
        AltSvcListener.H3Entry e = AltSvcListener.parseAltSvcH3(
                "h2=\"alt.example:443\", h3-29=\":443\", h3=\":4443\"; ma=11");
        assertNotNull(e);
        assertEquals(4443, e.port);
        assertEquals(11L, e.maxAgeSeconds);
        assertNull(AltSvcListener.parseAltSvcH3("h2=\":443\", quic=\":443\""));
        assertNull(AltSvcListener.parseAltSvcH3("h2=\":443\""));
        assertNull(AltSvcListener.parseAltSvcH3("h2=\":443\","));
    }

    @Test
    public void extractHostFindsTheH3EntryAmongOthers() {
        String value = "h2=\"x.example:443\", h3=\"alt.example:8443\"";
        AltSvcListener.H3Entry e = AltSvcListener.parseAltSvcH3(value);
        assertNotNull(e);
        assertEquals("alt.example", AltSvcListener.extractAltSvcHost(value, e.hostLength));
        assertNull(AltSvcListener.extractAltSvcHost("h2=\":443\"", 0));
        assertNull(AltSvcListener.extractAltSvcHost("", 0));
        assertEquals("", AltSvcListener.extractAltSvcHost("  h3=\":443\"", 0));
    }
}
