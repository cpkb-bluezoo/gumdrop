/*
 * Attach.java
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code attach} performative (core specification 2.7.3): attaches a
 * link endpoint to a session. Each side sends one; the reply carries the
 * same link name and the replying side's own handle.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Attach extends Performative {

    /** Sender settle mode: every delivery is sent unsettled. */
    public static final int SND_UNSETTLED = 0;
    /** Sender settle mode: every delivery is sent settled (at-most-once). */
    public static final int SND_SETTLED = 1;
    /** Sender settle mode: the sender may send some settled and some not (the default). */
    public static final int SND_MIXED = 2;

    /** Receiver settle mode: settle as soon as the outcome is known (the default). */
    public static final int RCV_FIRST = 0;
    /** Receiver settle mode: settle only after the sender has settled. */
    public static final int RCV_SECOND = 1;

    private String name;
    private long handle;
    private boolean receiver;
    private Integer sndSettleMode;
    private Integer rcvSettleMode;
    private Source source;
    private Target target;
    private Map<Object, Object> unsettled = new LinkedHashMap<Object, Object>();
    private boolean incompleteUnsettled;
    private Long initialDeliveryCount;
    private Long maxMessageSize;
    private List<String> offeredCapabilities = new ArrayList<String>();
    private List<String> desiredCapabilities = new ArrayList<String>();
    private Map<Object, Object> properties = new LinkedHashMap<Object, Object>();

    /**
     * @param name the link name, unique within the connection for this direction
     * @param handle the handle this side uses for the link
     * @param receiver {@code true} if this side is the receiving end
     */
    public Attach(String name, long handle, boolean receiver) {
        this.name = name;
        this.handle = handle;
        this.receiver = receiver;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_ATTACH;
    }

    public String getName() {
        return name;
    }

    /** The handle the sender of this {@code attach} uses for the link. */
    public long getHandle() {
        return handle;
    }

    /**
     * Sets the handle. The client assigns link handles itself when an
     * {@code Attach} is passed to it, overwriting this value.
     */
    public void setHandle(long handle) {
        this.handle = handle;
    }

    /** True if the sender of this {@code attach} is the receiving end of the link. */
    public boolean isReceiver() {
        return receiver;
    }

    /** One of {@link #SND_UNSETTLED}, {@link #SND_SETTLED}, {@link #SND_MIXED} (the default). */
    public int getSndSettleMode() {
        return sndSettleMode == null ? SND_MIXED : sndSettleMode.intValue();
    }

    public void setSndSettleMode(int mode) {
        this.sndSettleMode = Integer.valueOf(mode);
    }

    /** One of {@link #RCV_FIRST} (the default) or {@link #RCV_SECOND}. */
    public int getRcvSettleMode() {
        return rcvSettleMode == null ? RCV_FIRST : rcvSettleMode.intValue();
    }

    public void setRcvSettleMode(int mode) {
        this.rcvSettleMode = Integer.valueOf(mode);
    }

    /** The source terminus, or {@code null}. */
    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    /** The target terminus, or {@code null}. */
    public Target getTarget() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    /** Unsettled deliveries carried over when resuming a link; never null. */
    public Map<Object, Object> getUnsettled() {
        return unsettled;
    }

    public boolean isIncompleteUnsettled() {
        return incompleteUnsettled;
    }

    public void setIncompleteUnsettled(boolean incompleteUnsettled) {
        this.incompleteUnsettled = incompleteUnsettled;
    }

    /** For a sender: the delivery-count the link starts from; must be set for a sending attach. */
    public Long getInitialDeliveryCount() {
        return initialDeliveryCount;
    }

    public void setInitialDeliveryCount(Long initialDeliveryCount) {
        this.initialDeliveryCount = initialDeliveryCount;
    }

    /** The largest message the sender of this attach will accept, or 0 for no limit. */
    public long getMaxMessageSize() {
        return maxMessageSize == null ? 0L : maxMessageSize.longValue();
    }

    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = Long.valueOf(maxMessageSize);
    }

    public List<String> getOfferedCapabilities() {
        return offeredCapabilities;
    }

    public List<String> getDesiredCapabilities() {
        return desiredCapabilities;
    }

    public Map<Object, Object> getProperties() {
        return properties;
    }

    /**
     * Returns a copy suitable for attaching the same link again, for
     * example after reconnecting. The handle, delivery-count and any
     * unsettled state are not carried over: the copy starts fresh. The
     * source and target are shared, not cloned.
     *
     * @return a new {@code Attach} with the same name, role, settle modes,
     *      termini, capabilities and properties
     */
    public Attach copy() {
        Attach a = new Attach(name, 0, receiver);
        a.sndSettleMode = sndSettleMode;
        a.rcvSettleMode = rcvSettleMode;
        a.source = source;
        a.target = target;
        a.maxMessageSize = maxMessageSize;
        a.offeredCapabilities = new ArrayList<String>(offeredCapabilities);
        a.desiredCapabilities = new ArrayList<String>(desiredCapabilities);
        a.properties = new LinkedHashMap<Object, Object>(properties);
        return a;
    }

    @Override
    public void write(Amqp1Encoder out) {
        if (name == null) {
            throw new IllegalStateException("attach requires a link name");
        }
        Amqp1ListBuilder list = new Amqp1ListBuilder()
                .addString(name)
                .addUint(Long.valueOf(handle))
                .addBoolean(Boolean.valueOf(receiver))
                .addUbyte(sndSettleMode)
                .addUbyte(rcvSettleMode);
        addTerminus(list, source == null ? null : encodeSource());
        addTerminus(list, target == null ? null : encodeTarget());
        list.addMap(unsettled)
                .addBoolean(incompleteUnsettled ? Boolean.TRUE : null)
                .addUint(initialDeliveryCount)
                .addUlong(maxMessageSize)
                .addSymbols(offeredCapabilities)
                .addSymbols(desiredCapabilities)
                .addMap(properties)
                .writeTo(out, DESCRIPTOR_ATTACH);
    }

    private static void addTerminus(Amqp1ListBuilder list, Amqp1Encoder encoded) {
        if (encoded == null) {
            list.addNull();
        } else {
            list.addEncoded(encoded);
        }
    }

    private Amqp1Encoder encodeSource() {
        Amqp1Encoder e = new Amqp1Encoder();
        source.write(e);
        return e;
    }

    private Amqp1Encoder encodeTarget() {
        Amqp1Encoder e = new Amqp1Encoder();
        target.write(e);
        return e;
    }

    static Attach fromFields(List<Object> f) throws Amqp1ProtocolException {
        String name = Fields.required(Fields.string(f, 0, "name"), "attach", "name");
        long handle = Fields.required(Fields.unsigned(f, 1, "handle"), "attach", "handle")
                .longValue();
        Boolean role = Fields.required(Fields.bool(f, 2, "role"), "attach", "role");
        Attach a = new Attach(name, handle, role.booleanValue());
        Long snd = Fields.unsigned(f, 3, "snd-settle-mode");
        a.sndSettleMode = snd == null ? null : Integer.valueOf(snd.intValue());
        Long rcv = Fields.unsigned(f, 4, "rcv-settle-mode");
        a.rcvSettleMode = rcv == null ? null : Integer.valueOf(rcv.intValue());
        a.source = Source.fromDescribed(Fields.get(f, 5));
        a.target = Target.fromDescribed(Fields.get(f, 6));
        a.unsettled = Fields.map(f, 7, "unsettled");
        Boolean incomplete = Fields.bool(f, 8, "incomplete-unsettled");
        a.incompleteUnsettled = incomplete != null && incomplete.booleanValue();
        a.initialDeliveryCount = Fields.unsigned(f, 9, "initial-delivery-count");
        a.maxMessageSize = Fields.unsigned(f, 10, "max-message-size");
        a.offeredCapabilities = Fields.symbols(f, 11, "offered-capabilities");
        a.desiredCapabilities = Fields.symbols(f, 12, "desired-capabilities");
        a.properties = Fields.map(f, 13, "properties");
        return a;
    }
}
