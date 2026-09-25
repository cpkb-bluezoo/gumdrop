/*
 * ClientHarness.java
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.amqp1.client.FakeAmqp1Peer.Out;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.Performative;
import org.bluezoo.gumdrop.amqp1.codec.SaslMechanisms;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;

/**
 * Drives an {@link Amqp1ClientProtocolHandler} against a
 * {@link FakeAmqp1Peer} through the SASL and open handshake and the
 * beginning of a session, so a test can start from an active session.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ClientHarness {

    final FakeAmqp1Peer peer = new FakeAmqp1Peer();
    final ConnectionRecorder ready = new ConnectionRecorder();
    final Amqp1ClientProtocolHandler handler = new Amqp1ClientProtocolHandler(ready);
    Amqp1Connection connection;

    /** The peer channel number the fake broker uses for its side of sessions. */
    static final int PEER_CHANNEL = 5;

    void feed(ByteBuffer... parts) {
        handler.receive(ByteBuffer.wrap(FakeAmqp1Peer.concat(parts)));
    }

    /** Feeds the bytes one at a time, as a slow network would. */
    void feedByteByByte(ByteBuffer... parts) {
        byte[] data = FakeAmqp1Peer.concat(parts);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 16);
        for (int i = 0; i < data.length; i++) {
            buf.put(data[i]);
            buf.flip();
            handler.receive(buf);
            buf.compact();
        }
    }

    /** Completes SASL (ANONYMOUS) and the open exchange. */
    void open(Open ours, Open theirs) {
        handler.connected(peer);
        feed(FakeAmqp1Peer.saslHeader(),
                FakeAmqp1Peer.saslFrame(new SaslMechanisms(Arrays.asList("ANONYMOUS"))));
        ready.handshake.authenticateAnonymous(null, ready.auth);
        feed(FakeAmqp1Peer.saslFrame(new SaslOutcome(SaslOutcome.OK, null)));
        ready.auth.opener.open(ours, ready.openHandler);
        feed(FakeAmqp1Peer.amqpHeader(), FakeAmqp1Peer.amqpFrame(0, theirs));
        connection = ready.openHandler.connection;
    }

    void open() {
        open(new Open("client-1"), new Open("broker"));
    }

    /**
     * Begins a session with our window sizes {@code ours} and the
     * broker's replying {@code begin}.
     *
     * @return the recorder for the session, with {@code session} set
     */
    SessionRecorder beginSession(Begin ours, Begin theirs) {
        SessionRecorder rec = new SessionRecorder();
        connection.beginSession(ours, rec);
        theirs.setRemoteChannel(Integer.valueOf(0));
        feed(FakeAmqp1Peer.amqpFrame(PEER_CHANNEL, theirs));
        return rec;
    }

    /** Opens the connection and a session with generous windows. */
    SessionRecorder openSession() {
        open();
        return beginSession(new Begin(0, 2048, 2048), new Begin(0, 2048, 2048));
    }

    /** A frame from the broker on its session channel. */
    static ByteBuffer brokerFrame(Performative p) {
        return FakeAmqp1Peer.amqpFrame(PEER_CHANNEL, p);
    }

    /** A transfer frame from the broker, with message octets after the performative. */
    static ByteBuffer brokerTransfer(Transfer t, byte[] payload) {
        return Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, PEER_CHANNEL, t.encode(),
                ByteBuffer.wrap(payload));
    }

    List<Out> out() {
        return peer.output();
    }

    Out last() {
        List<Out> o = peer.output();
        return o.get(o.size() - 1);
    }

    /** Everything the client sent that is a performative of the given type, in order. */
    <T extends Performative> List<T> sent(Class<T> type) {
        List<T> result = new ArrayList<T>();
        for (Out o : peer.output()) {
            if (type.isInstance(o.performative)) {
                result.add(type.cast(o.performative));
            }
        }
        return result;
    }

    /** The {@code Out} items whose performative has the given type. */
    List<Out> sentOuts(Class<? extends Performative> type) {
        List<Out> result = new ArrayList<Out>();
        for (Out o : peer.output()) {
            if (type.isInstance(o.performative)) {
                result.add(o);
            }
        }
        return result;
    }

    // ── recorders ──

    static final class ConnectionRecorder implements Amqp1ConnectionReady {
        Exception error;
        boolean closed;
        Amqp1Error closeError;
        Amqp1SaslHandshake handshake;
        final AuthRecorder auth = new AuthRecorder();
        final OpenRecorder openHandler = new OpenRecorder();

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onError(Exception cause) {
            error = cause;
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void handleSaslMechanisms(List<String> mechanisms, Amqp1SaslHandshake h) {
            handshake = h;
        }

        @Override
        public void onConnectionClosed(Amqp1Error e) {
            closed = true;
            closeError = e;
        }
    }

    static final class AuthRecorder implements Amqp1AuthHandler {
        Amqp1ConnectionOpener opener;

        @Override
        public void handleAuthenticated(Amqp1ConnectionOpener o) {
            opener = o;
        }

        @Override
        public void handleAuthenticationFailed(int code, byte[] additionalData) {
        }
    }

    static final class OpenRecorder implements Amqp1OpenHandler {
        Amqp1Connection connection;

        @Override
        public void handleOpen(Open peerOpen, Amqp1Connection c) {
            connection = c;
        }
    }

    static final class SessionRecorder implements Amqp1SessionHandler {
        Amqp1Session session;
        boolean ended;
        Amqp1Error endError;

        @Override
        public void handleBegun(Amqp1Session s, Begin peerBegin) {
            session = s;
        }

        @Override
        public void handleEnded(Amqp1Error error) {
            ended = true;
            endError = error;
        }
    }

    /** Builds the broker's reply {@code attach} for a link the client attached. */
    static Attach brokerAttach(Attach ours, long brokerHandle) {
        Attach a = new Attach(ours.getName(), brokerHandle, !ours.isReceiver());
        a.setSource(ours.getSource());
        a.setTarget(ours.getTarget());
        if (ours.isReceiver()) {
            a.setInitialDeliveryCount(Long.valueOf(0));
        }
        return a;
    }
}
