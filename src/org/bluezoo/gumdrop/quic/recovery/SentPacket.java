/*
 * SentPacket.java
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

package org.bluezoo.gumdrop.quic.recovery;

/**
 * A tracked outstanding (sent, not yet acknowledged or declared lost)
 * packet, RFC 9002 Appendix A.1.1's fields exactly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-A.1.1">RFC 9002 Appendix A.1.1</a>
 */
public final class SentPacket {

    private final long packetNumber;
    private final long timeSentMillis;
    private final boolean ackEliciting;
    private final boolean inFlight;
    private final int sentBytes;

    /**
     * Creates a sent-packet record.
     *
     * @param packetNumber the packet number
     * @param timeSentMillis the time the packet was sent
     * @param ackEliciting true if an acknowledgment is expected for this packet
     * @param inFlight true if this packet counts toward bytes in flight
     * @param sentBytes the number of bytes sent in the packet (QUIC
     *                  framing included, UDP/IP overhead excluded)
     */
    public SentPacket(long packetNumber, long timeSentMillis, boolean ackEliciting, boolean inFlight,
            int sentBytes) {
        this.packetNumber = packetNumber;
        this.timeSentMillis = timeSentMillis;
        this.ackEliciting = ackEliciting;
        this.inFlight = inFlight;
        this.sentBytes = sentBytes;
    }

    /**
     * Returns the packet number.
     *
     * @return the packet number
     */
    public long getPacketNumber() {
        return packetNumber;
    }

    /**
     * Returns the time the packet was sent.
     *
     * @return the send time, in milliseconds
     */
    public long getTimeSentMillis() {
        return timeSentMillis;
    }

    /**
     * Returns whether an acknowledgment is expected for this packet.
     *
     * @return true if ack-eliciting
     */
    public boolean isAckEliciting() {
        return ackEliciting;
    }

    /**
     * Returns whether this packet counts toward bytes in flight.
     *
     * @return true if in flight
     */
    public boolean isInFlight() {
        return inFlight;
    }

    /**
     * Returns the number of bytes sent in the packet.
     *
     * @return the sent bytes
     */
    public int getSentBytes() {
        return sentBytes;
    }
}
