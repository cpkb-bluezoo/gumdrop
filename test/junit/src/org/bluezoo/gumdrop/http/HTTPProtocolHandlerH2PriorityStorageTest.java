/*
 * HTTPProtocolHandlerH2PriorityStorageTest.java
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #299: {@code HTTPProtocolHandler}'s
 * {@code h2Priority} map moved from {@code Map<Integer, PriorityParams>} to
 * {@link org.bluezoo.gumdrop.util.IntObjectHashMap} to avoid a boxed-key
 * lookup on every RFC 9218 priority read. These exercise the storage and
 * retrieval behaviour through it directly -- {@code applyRfc9218Priority}
 * (via a {@code Priority} header, {@code fromUpdate=false}) and
 * {@code priorityUpdateFrameReceived} (via a {@code PRIORITY_UPDATE} frame,
 * {@code fromUpdate=true}), both public entry points -- rather than only
 * relying on the underlying map's own unit tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPProtocolHandlerH2PriorityStorageTest {

    @Test
    public void testUnsetStreamReturnsDefaultPriority() {
        HTTPProtocolHandler connection = new HTTPProtocolHandler(new HTTPListener());
        assertEquals(PriorityParams.DEFAULT, connection.h2PriorityOf(1));
    }

    @Test
    public void testPriorityHeaderIsStoredAndRetrievable() {
        HTTPProtocolHandler connection = new HTTPProtocolHandler(new HTTPListener());
        Headers headers = new Headers();
        headers.add(new Header(PriorityParams.PRIORITY_HEADER, "u=1"));

        connection.applyRfc9218Priority(1, headers);

        PriorityParams stored = connection.h2PriorityOf(1);
        assertEquals(1, stored.getUrgency());
    }

    @Test
    public void testDifferentStreamsTrackIndependentPriorities() {
        HTTPProtocolHandler connection = new HTTPProtocolHandler(new HTTPListener());
        Headers urgent = new Headers();
        urgent.add(new Header(PriorityParams.PRIORITY_HEADER, "u=0"));
        Headers background = new Headers();
        background.add(new Header(PriorityParams.PRIORITY_HEADER, "u=7"));

        connection.applyRfc9218Priority(1, urgent);
        connection.applyRfc9218Priority(3, background);

        assertEquals(0, connection.h2PriorityOf(1).getUrgency());
        assertEquals(7, connection.h2PriorityOf(3).getUrgency());
        assertEquals(PriorityParams.DEFAULT, connection.h2PriorityOf(5));
    }

    @Test
    public void testHeaderPriorityDoesNotOverrideAnAlreadySetPriority() {
        // RFC 9218 section 4.1: an incoming Priority header (fromUpdate =
        // false) sets the stream's initial priority only; it must not
        // override a value already recorded for that stream.
        HTTPProtocolHandler connection = new HTTPProtocolHandler(new HTTPListener());
        Headers first = new Headers();
        first.add(new Header(PriorityParams.PRIORITY_HEADER, "u=2"));
        Headers second = new Headers();
        second.add(new Header(PriorityParams.PRIORITY_HEADER, "u=6"));

        connection.applyRfc9218Priority(1, first);
        connection.applyRfc9218Priority(1, second);

        assertEquals("a second header-derived priority must not override the first",
                2, connection.h2PriorityOf(1).getUrgency());
    }

    @Test
    public void testPriorityUpdateFrameOverridesAnExistingPriority() {
        // RFC 9218 section 7.1: a PRIORITY_UPDATE frame (fromUpdate =
        // true) always takes effect, unlike a Priority header.
        HTTPProtocolHandler connection = new HTTPProtocolHandler(new HTTPListener());
        Headers initial = new Headers();
        initial.add(new Header(PriorityParams.PRIORITY_HEADER, "u=2"));
        connection.applyRfc9218Priority(1, initial);
        assertEquals(2, connection.h2PriorityOf(1).getUrgency());

        connection.priorityUpdateFrameReceived(1, "u=5");

        assertEquals("a PRIORITY_UPDATE frame must override the prior priority",
                5, connection.h2PriorityOf(1).getUrgency());
    }
}
