/*
 * DeliveryState.java
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
 * The state or outcome of a delivery (core specification 3.4): what a
 * receiver tells the sender about a message it was given.
 *
 * <p>The terminal outcomes are {@link Type#ACCEPTED},
 * {@link Type#REJECTED}, {@link Type#RELEASED} and {@link Type#MODIFIED};
 * {@link Type#RECEIVED} is a non-terminal state used to resume a
 * partially transferred delivery. Instances are created with the static
 * factories.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DeliveryState {

    /** The kind of state or outcome. */
    public enum Type { RECEIVED, ACCEPTED, REJECTED, RELEASED, MODIFIED }

    private final Type type;
    private Amqp1Error error;
    private boolean deliveryFailed;
    private boolean undeliverableHere;
    private Map<Object, Object> messageAnnotations = new LinkedHashMap<Object, Object>();
    private long sectionNumber;
    private long sectionOffset;

    private DeliveryState(Type type) {
        this.type = type;
    }

    /** The message was successfully processed. */
    public static DeliveryState accepted() {
        return new DeliveryState(Type.ACCEPTED);
    }

    /**
     * The message was invalid and will never be acceptable.
     *
     * @param error why, or {@code null}
     */
    public static DeliveryState rejected(Amqp1Error error) {
        DeliveryState s = new DeliveryState(Type.REJECTED);
        s.error = error;
        return s;
    }

    /** The message was not (and will not be) processed; it may be redelivered. */
    public static DeliveryState released() {
        return new DeliveryState(Type.RELEASED);
    }

    /**
     * The message was not processed, with instructions for redelivery.
     *
     * @param deliveryFailed whether to count this as a failed delivery attempt
     * @param undeliverableHere whether the message must not be redelivered to this receiver
     * @param messageAnnotations annotations to apply on redelivery, or {@code null}
     */
    public static DeliveryState modified(boolean deliveryFailed, boolean undeliverableHere,
            Map<Object, Object> messageAnnotations) {
        DeliveryState s = new DeliveryState(Type.MODIFIED);
        s.deliveryFailed = deliveryFailed;
        s.undeliverableHere = undeliverableHere;
        if (messageAnnotations != null) {
            s.messageAnnotations = messageAnnotations;
        }
        return s;
    }

    /**
     * A partial delivery position, used when resuming.
     *
     * @param sectionNumber the section reached
     * @param sectionOffset the offset within that section
     */
    public static DeliveryState received(long sectionNumber, long sectionOffset) {
        DeliveryState s = new DeliveryState(Type.RECEIVED);
        s.sectionNumber = sectionNumber;
        s.sectionOffset = sectionOffset;
        return s;
    }

    public Type getType() {
        return type;
    }

    /** True for accepted, rejected, released and modified. */
    public boolean isTerminal() {
        return type != Type.RECEIVED;
    }

    /** For {@link Type#REJECTED}: the reason, or {@code null}. */
    public Amqp1Error getError() {
        return error;
    }

    public boolean isDeliveryFailed() {
        return deliveryFailed;
    }

    public boolean isUndeliverableHere() {
        return undeliverableHere;
    }

    public Map<Object, Object> getMessageAnnotations() {
        return messageAnnotations;
    }

    public long getSectionNumber() {
        return sectionNumber;
    }

    public long getSectionOffset() {
        return sectionOffset;
    }

    void write(Amqp1Encoder out) {
        Amqp1ListBuilder list = new Amqp1ListBuilder();
        switch (type) {
            case ACCEPTED:
                list.writeTo(out, Performative.DESCRIPTOR_STATE_ACCEPTED);
                break;
            case RELEASED:
                list.writeTo(out, Performative.DESCRIPTOR_STATE_RELEASED);
                break;
            case REJECTED:
                if (error != null) {
                    Amqp1Encoder e = new Amqp1Encoder();
                    error.write(e);
                    list.addEncoded(e);
                }
                list.writeTo(out, Performative.DESCRIPTOR_STATE_REJECTED);
                break;
            case MODIFIED:
                list.addBoolean(deliveryFailed ? Boolean.TRUE : null)
                        .addBoolean(undeliverableHere ? Boolean.TRUE : null)
                        .addMap(messageAnnotations)
                        .writeTo(out, Performative.DESCRIPTOR_STATE_MODIFIED);
                break;
            default:
                list.addUint(Long.valueOf(sectionNumber))
                        .addUint(Long.valueOf(sectionOffset))
                        .writeTo(out, Performative.DESCRIPTOR_STATE_RECEIVED);
                break;
        }
    }

    /** Reads a delivery state from a decoded field, or returns null if the field is absent. */
    static DeliveryState fromDescribed(Object value) throws Amqp1ProtocolException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Amqp1Described)) {
            throw new Amqp1ProtocolException("delivery state is not a described type");
        }
        Amqp1Described d = (Amqp1Described) value;
        if (!(d.getDescriptor() instanceof Long) || !(d.getValue() instanceof List)) {
            throw new Amqp1ProtocolException("Unsupported delivery state " + d.getDescriptor());
        }
        @SuppressWarnings("unchecked")
        List<Object> f = (List<Object>) d.getValue();
        long descriptor = ((Long) d.getDescriptor()).longValue();
        if (descriptor == Performative.DESCRIPTOR_STATE_ACCEPTED) {
            return accepted();
        } else if (descriptor == Performative.DESCRIPTOR_STATE_RELEASED) {
            return released();
        } else if (descriptor == Performative.DESCRIPTOR_STATE_REJECTED) {
            return rejected(Amqp1Error.fromDescribed(Fields.get(f, 0)));
        } else if (descriptor == Performative.DESCRIPTOR_STATE_MODIFIED) {
            Boolean failed = Fields.bool(f, 0, "delivery-failed");
            Boolean undeliverable = Fields.bool(f, 1, "undeliverable-here");
            return modified(failed != null && failed.booleanValue(),
                    undeliverable != null && undeliverable.booleanValue(),
                    Fields.map(f, 2, "message-annotations"));
        } else if (descriptor == Performative.DESCRIPTOR_STATE_RECEIVED) {
            Long number = Fields.required(Fields.unsigned(f, 0, "section-number"),
                    "received", "section-number");
            Long offset = Fields.required(Fields.unsigned(f, 1, "section-offset"),
                    "received", "section-offset");
            return received(number.longValue(), offset.longValue());
        }
        throw new Amqp1ProtocolException("Unsupported delivery state descriptor 0x"
                + Long.toHexString(descriptor));
    }

    @Override
    public String toString() {
        return type.toString().toLowerCase();
    }
}
