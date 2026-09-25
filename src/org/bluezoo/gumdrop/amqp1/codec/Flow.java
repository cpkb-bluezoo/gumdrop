/*
 * Flow.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code flow} performative (core specification 2.7.4): updates the
 * session's transfer windows and, when it carries a handle, the credit
 * of one link.
 *
 * <p>Transfer numbers and counts are 32-bit serial numbers that wrap; use
 * {@link #serialAdd} and {@link #serialDiff} to compute with them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Flow extends Performative {

    private Long nextIncomingId;
    private long incomingWindow;
    private long nextOutgoingId;
    private long outgoingWindow;
    private Long handle;
    private Long deliveryCount;
    private Long linkCredit;
    private Long available;
    private boolean drain;
    private boolean echo;
    private Map<Object, Object> properties = new LinkedHashMap<Object, Object>();

    /**
     * Creates a session-level flow.
     *
     * @param nextIncomingId the next transfer-id expected, or {@code null}
     *      before the peer's {@code begin} has been seen
     * @param incomingWindow the incoming window
     * @param nextOutgoingId the next transfer-id this side will send
     * @param outgoingWindow the outgoing window
     */
    public Flow(Long nextIncomingId, long incomingWindow, long nextOutgoingId,
            long outgoingWindow) {
        this.nextIncomingId = nextIncomingId;
        this.incomingWindow = incomingWindow;
        this.nextOutgoingId = nextOutgoingId;
        this.outgoingWindow = outgoingWindow;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_FLOW;
    }

    /** The next transfer-id the sender of this flow expects to receive, or {@code null}. */
    public Long getNextIncomingId() {
        return nextIncomingId;
    }

    public long getIncomingWindow() {
        return incomingWindow;
    }

    public long getNextOutgoingId() {
        return nextOutgoingId;
    }

    public long getOutgoingWindow() {
        return outgoingWindow;
    }

    /** The link this flow concerns, or {@code null} for a session-level flow. */
    public Long getHandle() {
        return handle;
    }

    public Long getDeliveryCount() {
        return deliveryCount;
    }

    public Long getLinkCredit() {
        return linkCredit;
    }

    public Long getAvailable() {
        return available;
    }

    public boolean isDrain() {
        return drain;
    }

    public void setDrain(boolean drain) {
        this.drain = drain;
    }

    /** Whether the receiver of this flow must reply with its own. */
    public boolean isEcho() {
        return echo;
    }

    public void setEcho(boolean echo) {
        this.echo = echo;
    }

    public Map<Object, Object> getProperties() {
        return properties;
    }

    /**
     * Adds the link fields, making this a link-level flow.
     *
     * @param handle the link handle
     * @param deliveryCount the sender's delivery-count
     * @param linkCredit the link credit
     */
    public void setLink(long handle, long deliveryCount, long linkCredit) {
        this.handle = Long.valueOf(handle);
        this.deliveryCount = Long.valueOf(deliveryCount);
        this.linkCredit = Long.valueOf(linkCredit);
    }

    public void setAvailable(Long available) {
        this.available = available;
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addUint(nextIncomingId)
                .addUint(Long.valueOf(incomingWindow))
                .addUint(Long.valueOf(nextOutgoingId))
                .addUint(Long.valueOf(outgoingWindow))
                .addUint(handle)
                .addUint(deliveryCount)
                .addUint(linkCredit)
                .addUint(available)
                .addBoolean(drain ? Boolean.TRUE : null)
                .addBoolean(echo ? Boolean.TRUE : null)
                .addMap(properties)
                .writeTo(out, DESCRIPTOR_FLOW);
    }

    static Flow fromFields(List<Object> f) throws Amqp1ProtocolException {
        long in = Fields.required(Fields.unsigned(f, 1, "incoming-window"), "flow",
                "incoming-window").longValue();
        long next = Fields.required(Fields.unsigned(f, 2, "next-outgoing-id"), "flow",
                "next-outgoing-id").longValue();
        long out = Fields.required(Fields.unsigned(f, 3, "outgoing-window"), "flow",
                "outgoing-window").longValue();
        Flow flow = new Flow(Fields.unsigned(f, 0, "next-incoming-id"), in, next, out);
        flow.handle = Fields.unsigned(f, 4, "handle");
        flow.deliveryCount = Fields.unsigned(f, 5, "delivery-count");
        flow.linkCredit = Fields.unsigned(f, 6, "link-credit");
        flow.available = Fields.unsigned(f, 7, "available");
        Boolean drain = Fields.bool(f, 8, "drain");
        flow.drain = drain != null && drain.booleanValue();
        Boolean echo = Fields.bool(f, 9, "echo");
        flow.echo = echo != null && echo.booleanValue();
        flow.properties = Fields.map(f, 10, "properties");
        return flow;
    }

    /** Adds {@code n} to a 32-bit serial number, wrapping at 2^32. */
    public static long serialAdd(long serial, long n) {
        return (serial + n) & 0xFFFFFFFFL;
    }

    /**
     * Returns {@code a - b} for two 32-bit serial numbers, as a signed
     * distance in the range -2^31 to 2^31 - 1 (RFC 1982 arithmetic).
     */
    public static long serialDiff(long a, long b) {
        return (int) ((a - b) & 0xFFFFFFFFL);
    }
}
