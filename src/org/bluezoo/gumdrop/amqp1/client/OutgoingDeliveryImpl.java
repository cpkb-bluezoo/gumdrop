/*
 * OutgoingDeliveryImpl.java
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

package org.bluezoo.gumdrop.amqp1.client;

import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Encoder;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;
import org.bluezoo.gumdrop.amqp1.codec.MessageWriter;

/**
 * A message being sent. Octets are packed into frame-sized buffers as
 * they are written and handed to the session for transmission whenever
 * one fills, so at most one frame's worth is held here however large the
 * message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class OutgoingDeliveryImpl implements Amqp1OutgoingDelivery {

    final SenderImpl link;
    final byte[] tag;
    boolean settled;
    Long deliveryId;

    private final int maxPayload;
    private byte[] frame = new byte[256];
    private int frameLength;
    private boolean anyQueued;
    private boolean done;

    OutgoingDeliveryImpl(SenderImpl link, byte[] tag, boolean settled, MessageHeader header,
            MessageProperties properties, Map<Object, Object> applicationProperties) {
        this.link = link;
        this.tag = tag;
        this.settled = settled;
        this.maxPayload = link.session.maxPayload();
        Amqp1Encoder sections = new Amqp1Encoder();
        if (header != null) {
            MessageWriter.writeHeader(sections, header);
        }
        if (properties != null) {
            MessageWriter.writeProperties(sections, properties);
        }
        if (applicationProperties != null && !applicationProperties.isEmpty()) {
            MessageWriter.writeApplicationProperties(sections, applicationProperties);
        }
        byte[] prefix = sections.toByteArray();
        append(prefix, 0, prefix.length);
    }

    @Override
    public byte[] getTag() {
        return tag.clone();
    }

    @Override
    public Long getDeliveryId() {
        return deliveryId;
    }

    @Override
    public boolean isSettled() {
        return settled;
    }

    @Override
    public boolean write(ByteBuffer body) {
        requireOpen();
        int n = body.remaining();
        if (n > 0) {
            ByteBuffer prefix = MessageWriter.dataSectionPrefix(n);
            byte[] p = new byte[prefix.remaining()];
            prefix.get(p);
            append(p, 0, p.length);
            if (body.hasArray()) {
                append(body.array(), body.arrayOffset() + body.position(), n);
                body.position(body.limit());
            } else {
                byte[] copy = new byte[Math.min(n, 8192)];
                while (body.hasRemaining()) {
                    int c = Math.min(copy.length, body.remaining());
                    body.get(copy, 0, c);
                    append(copy, 0, c);
                }
            }
        }
        boolean writable = link.session.isSendQueueEmpty();
        if (!writable) {
            link.session.awaitWritable(this);
        }
        return writable;
    }

    @Override
    public void finish() {
        requireOpen();
        done = true;
        emitFrame(false);
        link.deliveryDone(this);
    }

    @Override
    public void abort() {
        requireOpen();
        done = true;
        frameLength = 0;
        link.deliveryDone(this);
        if (anyQueued) {
            link.session.enqueue(new SessionImpl.PendingTransfer(this, new byte[0], false, true));
        }
    }

    @Override
    public void settle() {
        if (settled) {
            throw new IllegalStateException("delivery is already settled");
        }
        if (deliveryId == null) {
            throw new IllegalStateException("delivery has not been sent yet");
        }
        link.session.settleOutgoing(this);
    }

    private void requireOpen() {
        if (done) {
            throw new IllegalStateException("delivery is finished or aborted");
        }
        link.requireAttached();
    }

    /** Copies octets into the current frame, sending each frame as it fills. */
    private void append(byte[] src, int offset, int length) {
        while (length > 0) {
            if (frameLength == frame.length && frame.length < maxPayload) {
                byte[] bigger = new byte[Math.min(maxPayload, frame.length * 2)];
                System.arraycopy(frame, 0, bigger, 0, frameLength);
                frame = bigger;
            }
            int room = Math.min(frame.length, maxPayload) - frameLength;
            int n = Math.min(room, length);
            System.arraycopy(src, offset, frame, frameLength, n);
            frameLength += n;
            offset += n;
            length -= n;
            if (frameLength == maxPayload) {
                emitFrame(true);
            }
        }
    }

    private void emitFrame(boolean more) {
        byte[] payload = new byte[frameLength];
        System.arraycopy(frame, 0, payload, 0, frameLength);
        frameLength = 0;
        anyQueued = true;
        link.session.enqueue(new SessionImpl.PendingTransfer(this, payload, more, false));
    }
}
