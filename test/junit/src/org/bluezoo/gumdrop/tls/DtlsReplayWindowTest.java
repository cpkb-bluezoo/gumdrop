/*
 * DtlsReplayWindowTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class DtlsReplayWindowTest {

    @Test
    public void acceptsInOrderAndRejectsDuplicates() {
        DtlsReplayWindow window = new DtlsReplayWindow();
        assertTrue(window.mayAccept(10L));
        window.recordAccepted(10L);
        assertFalse(window.mayAccept(10L));
        assertTrue(window.mayAccept(11L));
    }

    @Test
    public void forgedRecordDoesNotBurnSlotUntilAccepted() {
        DtlsReplayWindow window = new DtlsReplayWindow();
        assertTrue(window.mayAccept(5L));
        // AEAD verification failed -- must not advance the window.
        assertTrue(window.mayAccept(5L));
        window.recordAccepted(5L);
        assertFalse(window.mayAccept(5L));
    }

    @Test
    public void highestAcceptedTracksLastRecordedSequence() {
        DtlsReplayWindow window = new DtlsReplayWindow();
        assertEquals(-1L, window.highestAccepted());
        window.recordAccepted(10L);
        assertEquals(10L, window.highestAccepted());
        window.recordAccepted(15L);
        assertEquals(15L, window.highestAccepted());
    }

}
