/*
 * Transfer.java
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

import java.util.List;

/**
 * The {@code transfer} performative (core specification 2.7.5): carries
 * (part of) a message on a link. The message bytes follow the
 * performative in the frame body and are not part of this class; a
 * message may span several transfers ({@link #isMore()}).
 *
 * <p>Only the first transfer of a delivery carries the delivery-id,
 * delivery-tag and message-format; on continuations those are omitted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Transfer extends Performative {

    private long handle;
    private Long deliveryId;
    private byte[] deliveryTag;
    private Long messageFormat;
    private Boolean settled;
    private boolean more;
    private Integer rcvSettleMode;
    private DeliveryState state;
    private boolean resume;
    private boolean aborted;
    private boolean batchable;

    /** @param handle the handle of the link the transfer is on */
    public Transfer(long handle) {
        this.handle = handle;
    }

    @Override
    public long getDescriptor() {
        return DESCRIPTOR_TRANSFER;
    }

    public long getHandle() {
        return handle;
    }

    /** The delivery-id: set on the first transfer of a delivery, otherwise {@code null}. */
    public Long getDeliveryId() {
        return deliveryId;
    }

    public byte[] getDeliveryTag() {
        return deliveryTag;
    }

    /** The message format; 0 (the standard AMQP message format) if unset. */
    public long getMessageFormat() {
        return messageFormat == null ? 0L : messageFormat.longValue();
    }

    /**
     * Whether the sender settled the delivery with this transfer, or
     * {@code null} if unspecified (unsettled; on a continuation the
     * value of the first transfer applies).
     */
    public Boolean getSettled() {
        return settled;
    }

    /** True if more transfers of this delivery follow. */
    public boolean isMore() {
        return more;
    }

    public void setMore(boolean more) {
        this.more = more;
    }

    public Integer getRcvSettleMode() {
        return rcvSettleMode;
    }

    public void setRcvSettleMode(Integer rcvSettleMode) {
        this.rcvSettleMode = rcvSettleMode;
    }

    public DeliveryState getState() {
        return state;
    }

    public void setState(DeliveryState state) {
        this.state = state;
    }

    public boolean isResume() {
        return resume;
    }

    /** True if the sender abandons this delivery; any bytes already sent are discarded. */
    public boolean isAborted() {
        return aborted;
    }

    public void setAborted(boolean aborted) {
        this.aborted = aborted;
    }

    public boolean isBatchable() {
        return batchable;
    }

    /**
     * Sets the fields of a delivery's first transfer.
     *
     * @param deliveryId the session-unique delivery-id
     * @param deliveryTag the link-unique delivery-tag (at most 32 octets)
     * @param settled whether the delivery is sent already settled
     */
    public void setFirst(long deliveryId, byte[] deliveryTag, boolean settled) {
        if (deliveryTag == null || deliveryTag.length > 32) {
            throw new IllegalArgumentException("delivery-tag must be 1 to 32 octets");
        }
        this.deliveryId = Long.valueOf(deliveryId);
        this.deliveryTag = deliveryTag;
        this.settled = Boolean.valueOf(settled);
    }

    @Override
    public void write(Amqp1Encoder out) {
        new Amqp1ListBuilder()
                .addUint(Long.valueOf(handle))
                .addUint(deliveryId)
                .addBinary(deliveryTag)
                .addUint(messageFormat)
                .addBoolean(settled)
                .addBoolean(more ? Boolean.TRUE : null)
                .addUbyte(rcvSettleMode)
                .addObjectEncoded(state)
                .addBoolean(resume ? Boolean.TRUE : null)
                .addBoolean(aborted ? Boolean.TRUE : null)
                .addBoolean(batchable ? Boolean.TRUE : null)
                .writeTo(out, DESCRIPTOR_TRANSFER);
    }

    static Transfer fromFields(List<Object> f) throws Amqp1ProtocolException {
        long handle = Fields.required(Fields.unsigned(f, 0, "handle"), "transfer", "handle")
                .longValue();
        Transfer t = new Transfer(handle);
        t.deliveryId = Fields.unsigned(f, 1, "delivery-id");
        t.deliveryTag = Fields.binary(f, 2, "delivery-tag");
        t.messageFormat = Fields.unsigned(f, 3, "message-format");
        t.settled = Fields.bool(f, 4, "settled");
        Boolean more = Fields.bool(f, 5, "more");
        t.more = more != null && more.booleanValue();
        Long rcv = Fields.unsigned(f, 6, "rcv-settle-mode");
        t.rcvSettleMode = rcv == null ? null : Integer.valueOf(rcv.intValue());
        t.state = DeliveryState.fromDescribed(Fields.get(f, 7));
        Boolean resume = Fields.bool(f, 8, "resume");
        t.resume = resume != null && resume.booleanValue();
        Boolean aborted = Fields.bool(f, 9, "aborted");
        t.aborted = aborted != null && aborted.booleanValue();
        Boolean batchable = Fields.bool(f, 10, "batchable");
        t.batchable = batchable != null && batchable.booleanValue();
        return t;
    }
}
