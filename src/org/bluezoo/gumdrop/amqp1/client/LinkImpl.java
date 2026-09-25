/*
 * LinkImpl.java
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

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Flow;

/**
 * State shared by the two ends of a link: handles, the attach/detach
 * life cycle, and the delivery-count and link-credit variables
 * (core specification 2.6.7).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class LinkImpl {

    enum Status { ATTACH_SENT, ATTACHED, DETACH_SENT, DETACHED }

    final SessionImpl session;
    final Attach attach;
    final String name;
    long localHandle;
    long remoteHandle = -1;
    Attach peerAttach;
    Status status = Status.ATTACH_SENT;
    long deliveryCount;
    long linkCredit;

    LinkImpl(SessionImpl session, Attach attach) {
        this.session = session;
        this.attach = attach;
        this.name = attach.getName();
    }

    /** Link names are unique per direction within a session. */
    final String key() {
        return (attach.isReceiver() ? "r:" : "s:") + name;
    }

    final boolean isDetached() {
        return status == Status.DETACHED;
    }

    /** Whether the peer's attach refuses the link (a null terminus follows with a detach). */
    abstract boolean isRefused(Attach peer);

    abstract void notifyAttached(Attach peer);

    abstract void notifyDetached(Amqp1Error error, boolean closed);

    abstract void onFlow(Flow f);

    void onPeerAttach(Attach peer) {
        remoteHandle = peer.getHandle();
        peerAttach = peer;
        status = Status.ATTACHED;
        session.mapRemoteHandle(this);
        if (!isRefused(peer)) {
            notifyAttached(peer);
        }
    }

    void onPeerDetach(Detach d) {
        boolean weDetachedFirst = status == Status.DETACH_SENT;
        status = Status.DETACHED;
        session.removeLink(this);
        if (!weDetachedFirst) {
            // The peer detached first, so answer it
            session.owner.sendPerformative(session.localChannel,
                    new Detach(localHandle, d.isClosed(), null));
        }
        notifyDetached(d.getError(), d.isClosed());
    }

    /** The session ended (or the connection went away) under the link. */
    void onSessionEnded(Amqp1Error error) {
        if (status == Status.DETACHED) {
            return;
        }
        status = Status.DETACHED;
        // Not closed: the link endpoint was detached, not destroyed, so it
        // can be attached again on a new session (core specification 2.6.5)
        notifyDetached(error, false);
    }

    final void detachLink(Amqp1Error error, boolean close) {
        if (status != Status.ATTACHED) {
            throw new IllegalStateException("link is not attached");
        }
        status = Status.DETACH_SENT;
        session.owner.sendPerformative(session.localChannel,
                new Detach(localHandle, close, error));
    }

    final void requireAttached() {
        if (status != Status.ATTACHED) {
            throw new IllegalStateException("link is not attached");
        }
    }
}
