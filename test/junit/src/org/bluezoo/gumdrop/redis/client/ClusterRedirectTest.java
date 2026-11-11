/*
 * ClusterRedirectTest.java
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

package org.bluezoo.gumdrop.redis.client;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link ClusterRedirect#parse} on the text of {@code MOVED} and
 * {@code ASK} error replies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterRedirectTest {

    @Test
    public void movedNamesTheNewOwner() {
        ClusterRedirect r = ClusterRedirect.parse("MOVED 3999 127.0.0.1:6381");
        assertNotNull(r);
        assertFalse(r.isAsk());
        assertEquals(3999, r.getSlot());
        assertEquals("127.0.0.1", r.getHost());
        assertEquals(6381, r.getPort());
    }

    @Test
    public void askIsRecognised() {
        ClusterRedirect r = ClusterRedirect.parse("ASK 12182 10.0.0.1:7000");
        assertTrue(r.isAsk());
        assertEquals(12182, r.getSlot());
        assertEquals("10.0.0.1", r.getHost());
        assertEquals(7000, r.getPort());
    }

    @Test
    public void aHostnameIsAccepted() {
        ClusterRedirect r = ClusterRedirect.parse("MOVED 1 redis-b.example.com:6379");
        assertEquals("redis-b.example.com", r.getHost());
    }

    @Test
    public void aBlankHostIsNull() {
        ClusterRedirect r = ClusterRedirect.parse("MOVED 3999 :6381");
        assertNull(r.getHost());
        assertEquals(6381, r.getPort());
    }

    @Test
    public void anIpv6HostIsTakenUpToTheLastColon() {
        ClusterRedirect r = ClusterRedirect.parse("MOVED 3999 ::1:6381");
        assertEquals("::1", r.getHost());
        assertEquals(6381, r.getPort());
    }

    @Test
    public void aBracketedIpv6HostIsUnbracketed() {
        ClusterRedirect r = ClusterRedirect.parse("ASK 5 [fe80::2]:7000");
        assertEquals("fe80::2", r.getHost());
        assertEquals(7000, r.getPort());
    }

    @Test
    public void slotBoundsAreAccepted() {
        assertEquals(0, ClusterRedirect.parse("MOVED 0 h:1").getSlot());
        assertEquals(16383, ClusterRedirect.parse("MOVED 16383 h:1").getSlot());
    }

    @Test
    public void extraSpacesAreTolerated() {
        ClusterRedirect r = ClusterRedirect.parse("MOVED   3999   h:6381 ");
        assertEquals(3999, r.getSlot());
        assertEquals("h", r.getHost());
    }

    @Test
    public void otherErrorsAreNotRedirects() {
        assertNull(ClusterRedirect.parse("ERR unknown command"));
        assertNull(ClusterRedirect.parse("CLUSTERDOWN The cluster is down"));
        assertNull(ClusterRedirect.parse("TRYAGAIN Multiple keys request during rehashing"));
        assertNull(ClusterRedirect.parse("CROSSSLOT Keys in request don't hash to the same slot"));
        assertNull(ClusterRedirect.parse(""));
        assertNull(ClusterRedirect.parse(null));
    }

    @Test
    public void theKindIsCaseSensitive() {
        assertNull(ClusterRedirect.parse("moved 3999 h:1"));
    }

    @Test
    public void malformedRedirectsAreRejected() {
        assertNull(ClusterRedirect.parse("MOVED"));
        assertNull(ClusterRedirect.parse("MOVED 3999"));
        assertNull(ClusterRedirect.parse("MOVED x h:1"));
        assertNull(ClusterRedirect.parse("MOVED 3999 h"));
        assertNull(ClusterRedirect.parse("MOVED 3999 h:x"));
        assertNull(ClusterRedirect.parse("MOVED 3999 h:0"));
        assertNull(ClusterRedirect.parse("MOVED 3999 h:70000"));
        assertNull(ClusterRedirect.parse("MOVED 16384 h:1"));
        assertNull(ClusterRedirect.parse("MOVED -1 h:1"));
        assertNull(ClusterRedirect.parse("MOVED 3999 h:1 extra"));
    }

    @Test
    public void aKindWithNoSeparatorIsNotRedirect() {
        assertNull(ClusterRedirect.parse("MOVEDX 3999 h:1"));
    }
}
