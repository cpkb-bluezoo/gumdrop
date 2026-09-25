/*
 * SenderImpl.java
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

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageHeader;
import org.bluezoo.gumdrop.amqp1.codec.MessageProperties;

/**
 * The sending end of a link.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SenderImpl extends LinkImpl implements Amqp1Sender {

    final Amqp1SenderHandler handler;
    private OutgoingDeliveryImpl current;

    SenderImpl(SessionImpl session, Attach attach, Amqp1SenderHandler handler) {
        super(session, attach);
        this.handler = handler;
        this.deliveryCount = attach.getInitialDeliveryCount().longValue();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Attach getPeerAttach() {
        return peerAttach;
    }

    @Override
    public long getLinkCredit() {
        return linkCredit;
    }

    @Override
    public OutgoingDeliveryImpl startDelivery(byte[] deliveryTag, MessageHeader header,
            MessageProperties properties, Map<Object, Object> applicationProperties,
            boolean settled) {
        requireAttached();
        if (deliveryTag == null || deliveryTag.length < 1 || deliveryTag.length > 32) {
            throw new IllegalArgumentException("delivery tag must be 1 to 32 octets");
        }
        int mode = attach.getSndSettleMode();
        if (mode == Attach.SND_UNSETTLED && settled) {
            throw new IllegalArgumentException("link sends unsettled deliveries only");
        }
        if (mode == Attach.SND_SETTLED && !settled) {
            throw new IllegalArgumentException("link sends settled deliveries only");
        }
        if (current != null) {
            throw new IllegalStateException("a delivery is already in progress on this link");
        }
        if (linkCredit <= 0) {
            throw new IllegalStateException("no link credit");
        }
        linkCredit--;
        deliveryCount = Flow.serialAdd(deliveryCount, 1);
        current = new OutgoingDeliveryImpl(this, deliveryTag.clone(), settled, header,
                properties, applicationProperties);
        return current;
    }

    @Override
    public OutgoingDeliveryImpl send(byte[] deliveryTag, MessageProperties properties,
            Map<Object, Object> applicationProperties, ByteBuffer body) {
        boolean settled = attach.getSndSettleMode() == Attach.SND_SETTLED;
        OutgoingDeliveryImpl d = startDelivery(deliveryTag, null, properties,
                applicationProperties, settled);
        d.write(body);
        d.finish();
        return d;
    }

    @Override
    public void detach(Amqp1Error error, boolean close) {
        detachLink(error, close);
    }

    /** The delivery finished, was aborted or the link ended. */
    void deliveryDone(OutgoingDeliveryImpl d) {
        if (current == d) {
            current = null;
        }
    }

    @Override
    boolean isRefused(Attach peer) {
        return peer.getTarget() == null;
    }

    @Override
    void notifyAttached(Attach peer) {
        handler.handleAttached(this, peer);
    }

    @Override
    void notifyDetached(Amqp1Error error, boolean closed) {
        current = null;
        handler.handleDetached(error, closed);
    }

    @Override
    void onFlow(Flow f) {
        if (f.getLinkCredit() != null) {
            long peerCount = f.getDeliveryCount() == null ? deliveryCount
                    : f.getDeliveryCount().longValue();
            // credit = the receiver's count + its credit - our count (serial arithmetic)
            long credit = Flow.serialDiff(
                    Flow.serialAdd(peerCount, f.getLinkCredit().longValue()), deliveryCount);
            linkCredit = Math.max(0L, credit);
        }
        if (f.isDrain()) {
            // Use up the remaining credit and confirm to the receiver
            deliveryCount = Flow.serialAdd(deliveryCount, linkCredit);
            linkCredit = 0;
            session.sendLinkFlow(this, true, false);
        } else if (f.isEcho()) {
            session.sendLinkFlow(this, false, false);
        }
        if (linkCredit > 0 && status == Status.ATTACHED) {
            handler.handleCredit(this);
        }
    }
}
