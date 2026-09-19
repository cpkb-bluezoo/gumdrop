/*
 * DtlsRetransmitStateTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsRetransmitStateTest {

    @Test
    public void usesExpectedTimeoutSchedule() {
        assertArrayEquals(new long[] { 1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L },
                DtlsRetransmitState.RETRANSMIT_TIMEOUTS_MS);
    }

    @Test
    public void progressClearsFlight() {
        DtlsRetransmitState state = new DtlsRetransmitState();
        state.onFlightSent(Collections.singletonList(new byte[] { 1 }));
        state.onProgress();
        assertFalse(state.hasFlight());
        assertEquals(-1L, state.currentTimeoutMs());
    }

    @Test
    public void givesUpAfterExhaustion() {
        DtlsRetransmitState state = new DtlsRetransmitState();
        List<byte[]> flight = Collections.singletonList(new byte[] { 42 });
        state.onFlightSent(flight);
        for (int i = 0; i < DtlsRetransmitState.RETRANSMIT_TIMEOUTS_MS.length; i++) {
            assertTrue(state.currentTimeoutMs() >= 0L);
            assertEquals(flight, state.onTimerFired());
        }
        assertNull(state.onTimerFired());
        assertFalse(state.hasFlight());
    }

}
