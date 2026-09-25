/*
 * SessionImpl.java
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.DeliveryState;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Disposition;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.Source;
import org.bluezoo.gumdrop.amqp1.codec.Target;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;

/**
 * One session on a connection: its transfer windows, the links attached
 * to it, and the queue of outgoing transfers waiting for window.
 *
 * <p>Session flow control (core specification 2.5.6) counts transfer
 * frames. The peer may send us {@code incomingWindow} more frames, and we
 * may send it {@code remoteIncomingWindow} more; each side's window is
 * advertised in {@code flow} performatives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SessionImpl implements Amqp1Session {

    enum Status { BEGIN_SENT, ACTIVE, END_SENT, ENDED }

    /** Room reserved in each frame for the transfer performative. */
    private static final int TRANSFER_OVERHEAD = 64;
    /** Largest frame we will build regardless of what the peer allows. */
    private static final int MAX_FRAME = 1048576;

    /** An encoded slice of an outgoing delivery waiting for session window. */
    static final class PendingTransfer {
        final OutgoingDeliveryImpl delivery;
        final byte[] payload;
        final boolean more;
        final boolean aborted;

        PendingTransfer(OutgoingDeliveryImpl delivery, byte[] payload, boolean more,
                boolean aborted) {
            this.delivery = delivery;
            this.payload = payload;
            this.more = more;
            this.aborted = aborted;
        }
    }

    final Amqp1ClientProtocolHandler owner;
    final int localChannel;
    final Amqp1SessionHandler handler;
    int remoteChannel = -1;
    Status status = Status.BEGIN_SENT;
    private Begin peerBegin;

    private long nextOutgoingId;
    private long nextIncomingId;
    private long incomingWindow;
    private final long initialIncomingWindow;
    private final long outgoingWindow;
    private long remoteIncomingWindow;
    private long handleMax;
    private long nextDeliveryId;

    private final Map<Long, LinkImpl> linksByLocalHandle = new HashMap<Long, LinkImpl>();
    private final Map<Long, LinkImpl> linksByRemoteHandle = new HashMap<Long, LinkImpl>();
    private final Map<String, LinkImpl> linksByName = new HashMap<String, LinkImpl>();
    private final Map<Long, OutgoingDeliveryImpl> unsettledOutgoing =
            new HashMap<Long, OutgoingDeliveryImpl>();
    private final ArrayDeque<PendingTransfer> pending = new ArrayDeque<PendingTransfer>();
    private final List<OutgoingDeliveryImpl> writableWaiters = new ArrayList<OutgoingDeliveryImpl>();

    SessionImpl(Amqp1ClientProtocolHandler owner, int localChannel, Begin begin,
            Amqp1SessionHandler handler) {
        this.owner = owner;
        this.localChannel = localChannel;
        this.handler = handler;
        this.nextOutgoingId = begin.getNextOutgoingId();
        this.incomingWindow = begin.getIncomingWindow();
        this.initialIncomingWindow = begin.getIncomingWindow();
        this.outgoingWindow = begin.getOutgoingWindow();
        this.handleMax = begin.getHandleMax();
    }

    // ── Amqp1Session ──

    @Override
    public int getLocalChannel() {
        return localChannel;
    }

    @Override
    public Begin getPeerBegin() {
        return peerBegin;
    }

    @Override
    public void attachSender(String linkName, String address, Amqp1SenderHandler h) {
        Attach a = new Attach(linkName, 0, false);
        a.setTarget(new Target(address));
        attachSender(a, h);
    }

    @Override
    public void attachSender(Attach attach, Amqp1SenderHandler h) {
        if (attach.isReceiver()) {
            throw new IllegalArgumentException("attach describes the receiving end");
        }
        if (h == null) {
            throw new NullPointerException("handler");
        }
        requireActive();
        if (attach.getSource() == null) {
            attach.setSource(new Source());
        }
        if (attach.getInitialDeliveryCount() == null) {
            attach.setInitialDeliveryCount(Long.valueOf(0));
        }
        SenderImpl link = new SenderImpl(this, attach, h);
        register(link);
        owner.sendPerformative(localChannel, attach);
    }

    @Override
    public void attachReceiver(String linkName, String address, Amqp1ReceiverHandler h) {
        Attach a = new Attach(linkName, 0, true);
        a.setSource(new Source(address));
        attachReceiver(a, h);
    }

    @Override
    public void attachReceiver(Attach attach, Amqp1ReceiverHandler h) {
        if (!attach.isReceiver()) {
            throw new IllegalArgumentException("attach describes the sending end");
        }
        if (h == null) {
            throw new NullPointerException("handler");
        }
        requireActive();
        if (attach.getTarget() == null) {
            attach.setTarget(new Target());
        }
        ReceiverImpl link = new ReceiverImpl(this, attach, h);
        register(link);
        owner.sendPerformative(localChannel, attach);
    }

    @Override
    public void end(Amqp1Error error) {
        if (status != Status.ACTIVE || !owner.isConnectionOpen()) {
            throw new IllegalStateException("session is not active");
        }
        status = Status.END_SENT;
        owner.sendPerformative(localChannel, new End(error));
    }

    private void requireActive() {
        if (status != Status.ACTIVE || !owner.isConnectionOpen()) {
            throw new IllegalStateException("session is not active");
        }
    }

    private void register(LinkImpl link) {
        String key = link.key();
        if (linksByName.containsKey(key)) {
            throw new IllegalStateException("link name '" + link.name + "' is already in use");
        }
        long handle = -1;
        for (long h = 0; h <= handleMax; h++) {
            if (!linksByLocalHandle.containsKey(Long.valueOf(h))) {
                handle = h;
                break;
            }
        }
        if (handle < 0) {
            throw new IllegalStateException("handle-max " + handleMax + " reached");
        }
        link.localHandle = handle;
        link.attach.setHandle(handle);
        linksByName.put(key, link);
        linksByLocalHandle.put(Long.valueOf(handle), link);
    }

    void mapRemoteHandle(LinkImpl link) {
        linksByRemoteHandle.put(Long.valueOf(link.remoteHandle), link);
    }

    void removeLink(LinkImpl link) {
        linksByName.remove(link.key());
        linksByLocalHandle.remove(Long.valueOf(link.localHandle));
        if (link.remoteHandle >= 0) {
            linksByRemoteHandle.remove(Long.valueOf(link.remoteHandle));
        }
    }

    // ── peer events (called by the connection handler) ──

    void onBegin(Begin peer, int channel) {
        remoteChannel = channel;
        peerBegin = peer;
        nextIncomingId = peer.getNextOutgoingId();
        remoteIncomingWindow = peer.getIncomingWindow();
        handleMax = Math.min(handleMax, peer.getHandleMax());
        status = Status.ACTIVE;
        handler.handleBegun(this, peer);
    }

    /** The peer ended the session, or the connection went away. */
    void onEnded(Amqp1Error error) {
        boolean ourEndWasSent = status == Status.END_SENT;
        status = Status.ENDED;
        List<LinkImpl> links = new ArrayList<LinkImpl>(linksByLocalHandle.values());
        linksByLocalHandle.clear();
        linksByRemoteHandle.clear();
        linksByName.clear();
        pending.clear();
        writableWaiters.clear();
        unsettledOutgoing.clear();
        for (LinkImpl l : links) {
            l.onSessionEnded(error);
        }
        if (!ourEndWasSent && owner.isConnectionOpen()) {
            // A peer-initiated end must be answered
            owner.sendPerformative(localChannel, new End());
        }
        handler.handleEnded(error);
    }

    boolean isEnded() {
        return status == Status.ENDED;
    }

    void handleAttach(Attach peer) {
        String key = (peer.isReceiver() ? "s:" : "r:") + peer.getName();
        LinkImpl link = linksByName.get(key);
        if (link == null) {
            owner.failConnection(Amqp1Error.NOT_IMPLEMENTED,
                    "Peer-initiated links are not supported (attach '" + peer.getName() + "')");
            return;
        }
        if (link.remoteHandle >= 0) {
            owner.failConnection(Amqp1Error.ILLEGAL_STATE,
                    "Second attach for link '" + peer.getName() + "'");
            return;
        }
        if (linksByRemoteHandle.containsKey(Long.valueOf(peer.getHandle()))) {
            owner.failConnection(Amqp1Error.ILLEGAL_STATE,
                    "Handle " + peer.getHandle() + " is already in use");
            return;
        }
        link.onPeerAttach(peer);
    }

    void handleDetach(Detach d) {
        LinkImpl link = linksByRemoteHandle.get(Long.valueOf(d.getHandle()));
        if (link == null) {
            owner.failConnection(Amqp1Error.UNATTACHED_HANDLE,
                    "Detach of unattached handle " + d.getHandle());
            return;
        }
        link.onPeerDetach(d);
    }

    void handleFlow(Flow f) {
        if (f.getNextIncomingId() != null) {
            long v = Flow.serialDiff(
                    Flow.serialAdd(f.getNextIncomingId().longValue(), f.getIncomingWindow()),
                    nextOutgoingId);
            remoteIncomingWindow = Math.max(0L, v);
        } else {
            remoteIncomingWindow = f.getIncomingWindow();
        }
        if (f.getHandle() != null) {
            LinkImpl link = linksByRemoteHandle.get(f.getHandle());
            if (link == null) {
                owner.failConnection(Amqp1Error.UNATTACHED_HANDLE,
                        "Flow for unattached handle " + f.getHandle());
                return;
            }
            link.onFlow(f);
        } else if (f.isEcho()) {
            sendSessionFlow();
        }
        flushPending();
    }

    /**
     * Accounts for an incoming transfer frame and finds its link.
     *
     * @return the receiving link, or {@code null} if the connection was failed
     */
    ReceiverImpl handleTransfer(Transfer t) {
        if (incomingWindow == 0) {
            owner.failConnection(Amqp1Error.WINDOW_VIOLATION,
                    "Transfer received with an exhausted incoming window");
            return null;
        }
        incomingWindow--;
        nextIncomingId = Flow.serialAdd(nextIncomingId, 1);
        LinkImpl link = linksByRemoteHandle.get(Long.valueOf(t.getHandle()));
        if (!(link instanceof ReceiverImpl)) {
            owner.failConnection(Amqp1Error.UNATTACHED_HANDLE,
                    "Transfer on handle " + t.getHandle() + " which is not an attached receiving link");
            return null;
        }
        ReceiverImpl receiver = (ReceiverImpl) link;
        if (!receiver.onTransfer(t)) {
            return null;
        }
        if (incomingWindow <= initialIncomingWindow / 2) {
            incomingWindow = initialIncomingWindow;
            sendSessionFlow();
        }
        return receiver;
    }

    void handleDisposition(Disposition d) {
        if (!d.isReceiver()) {
            return; // the sender settling deliveries we received: nothing to track
        }
        List<OutgoingDeliveryImpl> hit = new ArrayList<OutgoingDeliveryImpl>();
        for (Map.Entry<Long, OutgoingDeliveryImpl> e : unsettledOutgoing.entrySet()) {
            long id = e.getKey().longValue();
            if (Flow.serialDiff(id, d.getFirst()) >= 0 && Flow.serialDiff(d.getLast(), id) >= 0) {
                hit.add(e.getValue());
            }
        }
        for (OutgoingDeliveryImpl delivery : hit) {
            if (d.isSettled()) {
                unsettledOutgoing.remove(delivery.deliveryId);
                delivery.settled = true;
            }
            delivery.link.handler.handleOutcome(delivery, d.getState(), d.isSettled());
        }
    }

    // ── sending ──

    void sendSessionFlow() {
        owner.sendPerformative(localChannel, sessionFlow());
    }

    private Flow sessionFlow() {
        return new Flow(Long.valueOf(nextIncomingId), incomingWindow, nextOutgoingId,
                outgoingWindow);
    }

    void sendLinkFlow(LinkImpl link, boolean drain, boolean echo) {
        Flow f = sessionFlow();
        f.setLink(link.localHandle, link.deliveryCount, link.linkCredit);
        f.setDrain(drain);
        f.setEcho(echo);
        owner.sendPerformative(localChannel, f);
    }

    /** The payload octets that fit in one transfer frame. */
    int maxPayload() {
        long frame = Math.min(owner.peerMaxFrameSize(), (long) MAX_FRAME);
        return (int) frame - Amqp1Frame.HEADER_SIZE - TRANSFER_OVERHEAD;
    }

    void enqueue(PendingTransfer t) {
        pending.addLast(t);
        flushPending();
    }

    boolean isSendQueueEmpty() {
        return pending.isEmpty();
    }

    void awaitWritable(OutgoingDeliveryImpl d) {
        if (!writableWaiters.contains(d)) {
            writableWaiters.add(d);
        }
    }

    /** Sends queued transfers while the peer's incoming window allows. */
    void flushPending() {
        while (!pending.isEmpty() && remoteIncomingWindow > 0 && status != Status.ENDED
                && owner.isConnectionOpen()) {
            emit(pending.pollFirst());
        }
        if (pending.isEmpty() && !writableWaiters.isEmpty()) {
            List<OutgoingDeliveryImpl> waiters = new ArrayList<OutgoingDeliveryImpl>(writableWaiters);
            writableWaiters.clear();
            for (OutgoingDeliveryImpl d : waiters) {
                if (!d.link.isDetached()) {
                    d.link.handler.handleWritable(d);
                }
            }
        }
    }

    private void emit(PendingTransfer p) {
        OutgoingDeliveryImpl d = p.delivery;
        Transfer t = new Transfer(d.link.localHandle);
        if (d.deliveryId == null) {
            // Delivery-ids must run in the order first transfers are sent
            d.deliveryId = Long.valueOf(nextDeliveryId);
            nextDeliveryId = Flow.serialAdd(nextDeliveryId, 1);
            t.setFirst(d.deliveryId.longValue(), d.tag, d.settled);
            if (!d.settled) {
                unsettledOutgoing.put(d.deliveryId, d);
            }
        }
        t.setMore(p.more);
        t.setAborted(p.aborted);
        if (p.aborted) {
            unsettledOutgoing.remove(d.deliveryId);
        }
        owner.sendFrame(Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, localChannel, t.encode(),
                ByteBuffer.wrap(p.payload)));
        remoteIncomingWindow--;
        nextOutgoingId = Flow.serialAdd(nextOutgoingId, 1);
    }

    void settleOutgoing(OutgoingDeliveryImpl d) {
        Disposition disp = new Disposition(false, d.deliveryId.longValue(), null);
        disp.setSettled(true);
        unsettledOutgoing.remove(d.deliveryId);
        d.settled = true;
        owner.sendPerformative(localChannel, disp);
    }

    void sendIncomingDisposition(IncomingDeliveryImpl d,
            DeliveryState state, boolean settled) {
        Disposition disp = new Disposition(true, d.deliveryId, null);
        disp.setSettled(settled);
        disp.setState(state);
        owner.sendPerformative(localChannel, disp);
    }
}
