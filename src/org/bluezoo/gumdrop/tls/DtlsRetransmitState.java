/*
 * DtlsRetransmitState.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure RFC 6347 section 4.2.4 flight-retransmission logic -- no timers.
 * Arming wall-clock retransmission is the caller's job ({@code Dtls12Session}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DtlsRetransmitState {

    /** RFC 6347 section 4.2.4.1 -- matches the retired {@code DTLSSession}. */
    public static final long[] RETRANSMIT_TIMEOUTS_MS =
            { 1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L };

    private List<byte[]> flight = Collections.emptyList();
    private int attempt;

    /**
     * Records a newly sent flight and resets the retransmit attempt counter.
     *
     * @param datagrams fully framed on-wire datagram bytes
     */
    public void onFlightSent(List<byte[]> datagrams) {
        flight = new ArrayList<byte[]>(datagrams);
        attempt = 0;
    }

    /**
     * Clears retransmit state on any datagram from the peer (RFC 6347 section 4.2.4).
     */
    public void onProgress() {
        flight = Collections.emptyList();
        attempt = 0;
    }

    /**
     * Returns whether a flight is currently armed for retransmission.
     *
     * @return true if a flight is pending acknowledgement
     */
    public boolean hasFlight() {
        return !flight.isEmpty();
    }

    /**
     * Returns the current flight datagrams.
     *
     * @return the flight, or an empty list
     */
    public List<byte[]> currentFlight() {
        return flight;
    }

    /**
     * Returns the timeout before the next retransmit should fire, or
     * {@code -1} if the schedule is exhausted.
     *
     * @return milliseconds, or -1 to give up
     */
    public long currentTimeoutMs() {
        if (flight.isEmpty() || attempt >= RETRANSMIT_TIMEOUTS_MS.length) {
            return -1L;
        }
        return RETRANSMIT_TIMEOUTS_MS[attempt];
    }

    /**
     * Called when a retransmit timer fires: increments the attempt counter
     * and returns the datagrams to resend.
     *
     * @return datagrams to resend, or null if attempts are exhausted
     */
    public List<byte[]> onTimerFired() {
        if (flight.isEmpty()) {
            return null;
        }
        attempt++;
        if (attempt > RETRANSMIT_TIMEOUTS_MS.length) {
            flight = Collections.emptyList();
            return null;
        }
        return new ArrayList<byte[]>(flight);
    }

    /**
     * Returns whether retransmission attempts are exhausted with a flight
     * still pending.
     *
     * @return true if the schedule is exhausted
     */
    public boolean isExhausted() {
        return hasFlight() && attempt >= RETRANSMIT_TIMEOUTS_MS.length;
    }

}
