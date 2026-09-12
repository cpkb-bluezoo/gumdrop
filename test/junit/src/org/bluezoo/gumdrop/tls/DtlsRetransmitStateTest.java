/*
 * DtlsRetransmitStateTest.java
 * Copyright (C) 2026 Chris Burdess
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
