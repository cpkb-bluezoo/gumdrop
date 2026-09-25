/*
 * ReceiverImpl.java
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

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.MessageParser;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;

/**
 * The receiving end of a link. Each delivery's payload is fed straight
 * into a {@link MessageParser} as the transfer frames arrive, so the
 * message sections reach the application's handler incrementally.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ReceiverImpl extends LinkImpl implements Amqp1Receiver {

    final Amqp1ReceiverHandler handler;
    private IncomingDeliveryImpl current;
    private MessageParser parser;
    private boolean finalFrame;

    ReceiverImpl(SessionImpl session, Attach attach, Amqp1ReceiverHandler handler) {
        super(session, attach);
        this.handler = handler;
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
    public void addCredit(long credit) {
        if (credit <= 0) {
            throw new IllegalArgumentException("credit must be positive");
        }
        requireAttached();
        linkCredit += credit;
        session.sendLinkFlow(this, false, false);
    }

    @Override
    public void detach(Amqp1Error error, boolean close) {
        detachLink(error, close);
    }

    @Override
    boolean isRefused(Attach peer) {
        return peer.getSource() == null;
    }

    @Override
    void notifyAttached(Attach peer) {
        handler.handleAttached(this, peer);
    }

    @Override
    void onPeerAttach(Attach peer) {
        // The sender's initial delivery-count is where our count starts
        deliveryCount = peer.getInitialDeliveryCount() == null ? 0L
                : peer.getInitialDeliveryCount().longValue();
        super.onPeerAttach(peer);
    }

    @Override
    void notifyDetached(Amqp1Error error, boolean closed) {
        current = null;
        parser = null;
        handler.handleDetached(error, closed);
    }

    @Override
    void onFlow(Flow f) {
        if (f.isDrain()) {
            // The sender has used up or returned the remaining credit
            deliveryCount = f.getDeliveryCount() == null ? deliveryCount
                    : f.getDeliveryCount().longValue();
            linkCredit = f.getLinkCredit() == null ? 0L : f.getLinkCredit().longValue();
        } else if (f.isEcho()) {
            session.sendLinkFlow(this, false, false);
        }
    }

    /**
     * Handles a transfer performative.
     *
     * @return false if the connection was failed
     */
    boolean onTransfer(Transfer t) {
        finalFrame = false;
        if (current == null) {
            if (t.getDeliveryId() == null || t.getDeliveryTag() == null) {
                session.owner.failConnection(Amqp1Error.DECODE_ERROR,
                        "First transfer of a delivery lacks a delivery-id or delivery-tag");
                return false;
            }
            if (linkCredit <= 0) {
                session.owner.failConnection(Amqp1Error.TRANSFER_LIMIT_EXCEEDED,
                        "Transfer received on link '" + name + "' without credit");
                return false;
            }
            linkCredit--;
            deliveryCount = Flow.serialAdd(deliveryCount, 1);
            boolean settled = t.getSettled() != null && t.getSettled().booleanValue();
            current = new IncomingDeliveryImpl(this, t.getDeliveryId().longValue(),
                    t.getDeliveryTag(), settled);
            parser = new MessageParser(handler);
            handler.startDelivery(current);
            if (t.isAborted()) {
                abortCurrent();
                return true;
            }
        } else {
            if (t.getDeliveryId() != null && t.getDeliveryId().longValue() != current.deliveryId) {
                session.owner.failConnection(Amqp1Error.ILLEGAL_STATE,
                        "Transfer interleaves deliveries on link '" + name + "'");
                return false;
            }
            if (t.isAborted()) {
                abortCurrent();
                return true;
            }
        }
        finalFrame = !t.isMore();
        return true;
    }

    private void abortCurrent() {
        IncomingDeliveryImpl d = current;
        current = null;
        parser = null;
        handler.handleAborted(d);
    }

    /** Forwards message octets of the current transfer frame. */
    void payload(ByteBuffer chunk) {
        if (parser != null) {
            parser.receive(chunk);
        } else {
            chunk.position(chunk.limit()); // an aborted delivery's payload is ignored
        }
    }

    /** The current transfer frame is complete; finish the message if it was the last. */
    void endTransferFrame() {
        if (finalFrame && current != null) {
            MessageParser p = parser;
            current = null;
            parser = null;
            p.endMessage();
        }
        finalFrame = false;
    }
}
