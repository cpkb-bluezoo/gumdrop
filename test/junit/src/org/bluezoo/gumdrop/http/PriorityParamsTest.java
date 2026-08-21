/*
 * PriorityParamsTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9218 {@link PriorityParams} parse / encode / schedule-key tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PriorityParamsTest {

    @Test
    public void testParseDefaults() {
        assertEquals(PriorityParams.DEFAULT, PriorityParams.parse(""));
        assertEquals(PriorityParams.DEFAULT, PriorityParams.parse("   "));
        assertEquals(PriorityParams.DEFAULT, PriorityParams.parse(null));
    }

    @Test
    public void testParseUrgencyAndIncremental() {
        assertEquals(new PriorityParams(0, false), PriorityParams.parse("u=0"));
        assertEquals(new PriorityParams(5, true), PriorityParams.parse("u=5, i"));
        assertEquals(new PriorityParams(1, true), PriorityParams.parse("i=?1, u=1"));
        assertEquals(new PriorityParams(PriorityParams.DEFAULT_URGENCY, false),
                PriorityParams.parse("i=?0"));
    }

    @Test
    public void testIgnoresUnknownAndOutOfRange() {
        assertEquals(new PriorityParams(PriorityParams.DEFAULT_URGENCY, true),
                PriorityParams.parse("u=9, foo=bar, i"));
    }

    @Test
    public void testEncodeRoundTrips() {
        PriorityParams p = new PriorityParams(0, false);
        assertEquals(p, PriorityParams.parse(p.encode()));
        PriorityParams p2 = new PriorityParams(5, true);
        assertEquals(p2, PriorityParams.parse(p2.encode()));
    }

    @Test
    public void testQuicSendPriorityInvertsUrgency() {
        assertTrue(new PriorityParams(0, false).quicSendPriority()
                > new PriorityParams(7, false).quicSendPriority());
    }

    @Test
    public void testFromHeaders() {
        Headers headers = new Headers();
        headers.add("Priority", "u=1, i");
        assertEquals(new PriorityParams(1, true), PriorityParams.fromHeaders(headers));
    }

    @Test
    public void testScheduleOrdersUrgencyThenIncremental() {
        PriorityParams a = new PriorityParams(0, false);
        PriorityParams b = new PriorityParams(1, true);
        PriorityParams c = new PriorityParams(0, true);
        assertTrue(PriorityParams.compareSchedule(c, 8, a, 4) < 0);
        assertTrue(PriorityParams.compareSchedule(a, 4, b, 0) < 0);
    }

    @Test
    public void testNonIncrementalSlotsSerializeSameUrgency() {
        Rfc9218NonIncrementalSlots slots = new Rfc9218NonIncrementalSlots();
        PriorityParams nonInc = new PriorityParams(3, false);
        assertTrue(slots.claim(0, nonInc));
        assertFalse(slots.claim(4, nonInc));
        assertTrue(slots.claim(0, nonInc));
        slots.release(0, nonInc);
        assertTrue(slots.claim(4, nonInc));
    }

    @Test
    public void testIncrementalAlwaysClaims() {
        Rfc9218NonIncrementalSlots slots = new Rfc9218NonIncrementalSlots();
        PriorityParams inc = new PriorityParams(3, true);
        assertTrue(slots.claim(0, inc));
        assertTrue(slots.claim(4, inc));
    }
}
