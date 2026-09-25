/*
 * Amqp1ClientProtocolHandler.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Error;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1Frame;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameHandler;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1FrameParser;
import org.bluezoo.gumdrop.amqp1.codec.Amqp1ProtocolException;
import org.bluezoo.gumdrop.amqp1.codec.Attach;
import org.bluezoo.gumdrop.amqp1.codec.Begin;
import org.bluezoo.gumdrop.amqp1.codec.Close;
import org.bluezoo.gumdrop.amqp1.codec.Detach;
import org.bluezoo.gumdrop.amqp1.codec.Disposition;
import org.bluezoo.gumdrop.amqp1.codec.End;
import org.bluezoo.gumdrop.amqp1.codec.Flow;
import org.bluezoo.gumdrop.amqp1.codec.Open;
import org.bluezoo.gumdrop.amqp1.codec.Performative;
import org.bluezoo.gumdrop.amqp1.codec.PerformativeReader;
import org.bluezoo.gumdrop.amqp1.codec.SaslChallenge;
import org.bluezoo.gumdrop.amqp1.codec.SaslInit;
import org.bluezoo.gumdrop.amqp1.codec.SaslMechanisms;
import org.bluezoo.gumdrop.amqp1.codec.SaslOutcome;
import org.bluezoo.gumdrop.amqp1.codec.SaslResponse;
import org.bluezoo.gumdrop.amqp1.codec.Transfer;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.util.JulWarnings;

/**
 * AMQP 1.0 client protocol handler (issue #501).
 *
 * <p>Drives the connection from the transport up: the SASL security
 * layer, the AMQP protocol header, {@code open}, and {@code begin}/
 * {@code end} for sessions, exposing each step through typed state
 * interfaces so that the compiler rejects out-of-sequence calls:
 *
 * <pre>
 * Amqp1ConnectionReady --(sasl-mechanisms)--&gt; Amqp1SaslHandshake
 *      --(sasl-outcome)--&gt; Amqp1AuthHandler
 *      --(authenticated)--&gt; Amqp1ConnectionOpener
 *      --(open)--&gt; Amqp1OpenHandler
 *      --(peer open)--&gt; Amqp1Connection
 *      --(begin)--&gt; Amqp1SessionHandler / Amqp1Session
 * </pre>
 *
 * <p>Links are attached within sessions ({@link Amqp1Session#attachSender},
 * {@link Amqp1Session#attachReceiver}); credit, transfer and disposition
 * are handled by the session and link classes, which this handler feeds
 * with the performatives it decodes. Message payload is never gathered:
 * a {@code transfer}'s octets are forwarded to the receiving link as they
 * arrive.
 *
 * <p>Bytes arrive through the streaming {@link Amqp1FrameParser}: frame
 * bodies are forwarded as they arrive and only the small performative at
 * the front of each body is gathered (by a {@link PerformativeReader}),
 * so nothing here ever assumes a complete frame is available in one read.
 *
 * <p>This handler sits above the transport, so TLS is applied by the
 * transport factory before {@link #connected} (implicit TLS,
 * {@code amqps://}, port 5671): the first bytes sent are the SASL
 * protocol header inside the TLS session.
 *
 * <p>Idle timeouts are honoured in both directions (core specification
 * 2.4.5): a heartbeat is sent when the peer's {@code idle-time-out} would
 * otherwise expire, and the connection is failed if nothing arrives
 * within our own advertised timeout.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Amqp1ConnectionReady
 */
public final class Amqp1ClientProtocolHandler implements ProtocolHandler, Amqp1FrameHandler {

    private static final Logger LOGGER =
            Logger.getLogger(Amqp1ClientProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.amqp1.client.L10N");

    /** Source of monotonic time, replaceable so idle timeouts can be tested. */
    interface NanoClock {
        long nanoTime();
    }

    private static final NanoClock SYSTEM_CLOCK = new NanoClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    /** Largest performative accepted; bounds the memory used to gather one. */
    private static final int MAX_PERFORMATIVE_SIZE = 1048576;

    private enum State {
        DISCONNECTED,
        /** SASL header sent; waiting for the server's. */
        SASL_HEADER,
        /** Waiting for sasl-mechanisms. */
        SASL_MECHANISMS,
        /** Mechanisms delivered; the application is choosing. */
        SASL_CHOOSING,
        /** sasl-init sent; exchanging challenges until sasl-outcome. */
        SASL_OUTCOME,
        /** Authenticated; AMQP header sent; open not yet complete. */
        AMQP,
        /** Both opens exchanged. */
        OPEN,
        CLOSED
    }

    private final Amqp1ConnectionReady handler;
    private final NanoClock clock;
    private final Amqp1FrameParser parser;
    private final PerformativeReader reader = new PerformativeReader(MAX_PERFORMATIVE_SIZE);

    private Endpoint endpoint;
    private State state = State.DISCONNECTED;

    // Current frame
    private int frameType;
    private int frameChannel;
    private Performative performative;
    private boolean rearmHeaderAfterFrame;
    /** The receiving link the current frame's payload belongs to (a transfer frame). */
    private ReceiverImpl transferReceiver;

    // SASL
    private List<String> offeredMechanisms;
    private SaslClientMechanism saslMechanism;
    private ExecutorService saslExecutor;
    private Amqp1AuthHandler authHandler;

    // Connection negotiation
    private boolean amqpHeaderReceived;
    private Open localOpen;
    private Open peerOpen;
    private Amqp1OpenHandler openHandler;
    private boolean closeSent;
    private int channelMax;
    private long peerMaxFrameSize = Amqp1Frame.MIN_MAX_FRAME_SIZE;

    // Sessions, keyed by our channel number and by the peer's
    private final Map<Integer, SessionImpl> sessionsByLocalChannel =
            new HashMap<Integer, SessionImpl>();
    private final Map<Integer, SessionImpl> sessionsByRemoteChannel =
            new HashMap<Integer, SessionImpl>();

    // Idle timeouts
    private long lastSendNanos;
    private long lastReceiveNanos;
    private TimerHandle keepAliveTimer;
    private TimerHandle idleCheckTimer;

    public Amqp1ClientProtocolHandler(Amqp1ConnectionReady handler) {
        this(handler, SYSTEM_CLOCK);
    }

    Amqp1ClientProtocolHandler(Amqp1ConnectionReady handler, NanoClock clock) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.clock = clock;
        this.parser = new Amqp1FrameParser(this);
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        lastSendNanos = clock.nanoTime();
        lastReceiveNanos = lastSendNanos;
        state = State.SASL_HEADER;
        send(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_SASL));
        handler.onConnected(ep);
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void disconnected() {
        boolean wasClosed = state == State.CLOSED;
        state = State.CLOSED;
        cancelTimers();
        if (!wasClosed) {
            endAllSessions(new Amqp1Error(Amqp1Error.CONNECTION_FORCED, "Connection lost"));
        }
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        handler.onSecurityEstablished(info);
    }

    @Override
    public void error(Exception cause) {
        handler.onError(cause);
    }

    // ── sending ──

    private void send(ByteBuffer data) {
        lastSendNanos = clock.nanoTime();
        endpoint.send(data);
    }

    private void sendPerformative(int type, int channel, Performative p) {
        ByteBuffer body = p.encode();
        send(Amqp1Frame.encode(type, channel, body));
    }

    private void sendAmqp(int channel, Performative p) {
        ByteBuffer body = p.encode();
        if (peerOpen != null && Amqp1Frame.HEADER_SIZE + (long) body.remaining() > peerMaxFrameSize) {
            throw new IllegalStateException("Performative of " + body.remaining()
                    + " octets exceeds the peer's max-frame-size " + peerMaxFrameSize);
        }
        send(Amqp1Frame.encode(Amqp1Frame.TYPE_AMQP, channel, body));
    }

    // ── Amqp1FrameHandler ──

    @Override
    public void protocolHeader(int protocolId, int major, int minor, int revision) {
        if (state == State.CLOSED) {
            return;
        }
        if (major != 1 || minor != 0 || revision != 0) {
            fail(Amqp1Error.NOT_IMPLEMENTED, "Peer offered AMQP protocol version "
                    + major + "." + minor + "." + revision);
            return;
        }
        if (state == State.SASL_HEADER) {
            if (protocolId != Amqp1Frame.PROTOCOL_ID_SASL) {
                fail(Amqp1Error.NOT_IMPLEMENTED,
                        "Peer did not offer the SASL security layer (protocol id " + protocolId + ")");
                return;
            }
            state = State.SASL_MECHANISMS;
        } else if (state == State.AMQP) {
            if (protocolId != Amqp1Frame.PROTOCOL_ID_AMQP) {
                fail(Amqp1Error.NOT_IMPLEMENTED,
                        "Expected the AMQP protocol header, got protocol id " + protocolId);
                return;
            }
            amqpHeaderReceived = true;
        } else {
            fail(Amqp1Error.ILLEGAL_STATE, "Unexpected protocol header in state " + state);
        }
    }

    @Override
    public void startFrame(int type, int channel, int bodyLength) {
        if (state == State.CLOSED) {
            return;
        }
        lastReceiveNanos = clock.nanoTime();
        frameType = type;
        frameChannel = channel;
        performative = null;
        transferReceiver = null;
        reader.reset();
    }

    @Override
    public void frameBody(ByteBuffer chunk) {
        if (state == State.CLOSED) {
            return;
        }
        if (performative == null) {
            try {
                performative = reader.receive(chunk);
            } catch (Amqp1ProtocolException e) {
                fail(Amqp1Error.DECODE_ERROR, e.getMessage());
                return;
            }
            if (performative == null) {
                return; // more octets needed
            }
            dispatch(performative);
            if (state == State.CLOSED) {
                return;
            }
        }
        if (chunk.hasRemaining()) {
            if (transferReceiver == null) {
                // Only a transfer carries a payload
                fail(Amqp1Error.DECODE_ERROR, "Unexpected payload after "
                        + performative.getClass().getSimpleName());
                return;
            }
            transferReceiver.payload(chunk);
        }
    }

    @Override
    public void endFrame() {
        if (state != State.CLOSED && performative == null) {
            // The frame ended before the performative it started was complete
            fail(Amqp1Error.DECODE_ERROR, "Frame ended inside a performative");
            return;
        }
        if (transferReceiver != null) {
            ReceiverImpl receiver = transferReceiver;
            transferReceiver = null;
            receiver.endTransferFrame();
        }
        if (rearmHeaderAfterFrame) {
            rearmHeaderAfterFrame = false;
            parser.expectProtocolHeader();
        }
    }

    @Override
    public void heartbeat(int channel) {
        lastReceiveNanos = clock.nanoTime();
    }

    @Override
    public void frameError(String message) {
        fail(Amqp1Error.FRAMING_ERROR, message);
    }

    // ── dispatch ──

    private void dispatch(Performative p) {
        if (frameType == Amqp1Frame.TYPE_SASL) {
            if (p instanceof SaslMechanisms) {
                handleSaslMechanisms((SaslMechanisms) p);
            } else if (p instanceof SaslChallenge) {
                handleSaslChallenge((SaslChallenge) p);
            } else if (p instanceof SaslOutcome) {
                handleSaslOutcome((SaslOutcome) p);
            } else {
                fail(Amqp1Error.ILLEGAL_STATE, "Unexpected "
                        + p.getClass().getSimpleName() + " from the server");
            }
            return;
        }
        if (state != State.AMQP && state != State.OPEN) {
            fail(Amqp1Error.ILLEGAL_STATE, "AMQP frame in state " + state);
            return;
        }
        if (p instanceof Open) {
            handleOpen((Open) p);
        } else if (p instanceof Begin) {
            handleBegin(frameChannel, (Begin) p);
        } else if (p instanceof End) {
            handleEnd(frameChannel, (End) p);
        } else if (p instanceof Close) {
            handleClose((Close) p);
        } else if (p instanceof Attach) {
            SessionImpl session = requireSession();
            if (session != null) {
                session.handleAttach((Attach) p);
            }
        } else if (p instanceof Flow) {
            SessionImpl session = requireSession();
            if (session != null) {
                session.handleFlow((Flow) p);
            }
        } else if (p instanceof Transfer) {
            SessionImpl session = requireSession();
            if (session != null) {
                transferReceiver = session.handleTransfer((Transfer) p);
            }
        } else if (p instanceof Disposition) {
            SessionImpl session = requireSession();
            if (session != null) {
                session.handleDisposition((Disposition) p);
            }
        } else if (p instanceof Detach) {
            SessionImpl session = requireSession();
            if (session != null) {
                session.handleDetach((Detach) p);
            }
        } else {
            fail(Amqp1Error.ILLEGAL_STATE, "Unexpected "
                    + p.getClass().getSimpleName() + " in an AMQP frame");
        }
    }

    // ── SASL ──

    private void handleSaslMechanisms(SaslMechanisms m) {
        if (state != State.SASL_MECHANISMS) {
            fail(Amqp1Error.ILLEGAL_STATE, "sasl-mechanisms in state " + state);
            return;
        }
        offeredMechanisms = m.getMechanisms();
        state = State.SASL_CHOOSING;
        handler.handleSaslMechanisms(offeredMechanisms, new Amqp1SaslHandshake() {
            @Override
            public void authenticate(String username, String password, Amqp1AuthHandler h) {
                startSasl(SaslUtils.createClient("PLAIN", username, password, null), h, null);
            }

            @Override
            public void authenticateAnonymous(String trace, Amqp1AuthHandler h) {
                startSasl(new Amqp1AnonymousMechanism(trace), h, null);
            }

            @Override
            public void authenticate(SaslClientMechanism mechanism, Amqp1AuthHandler h) {
                startSasl(mechanism, h, null);
            }

            @Override
            public void authenticate(SaslClientMechanism mechanism, Amqp1AuthHandler h,
                    ExecutorService executor) {
                startSasl(mechanism, h, executor);
            }
        });
    }

    private void startSasl(final SaslClientMechanism mechanism, Amqp1AuthHandler h,
            ExecutorService executor) {
        if (state != State.SASL_CHOOSING) {
            throw new IllegalStateException("SASL mechanism already chosen");
        }
        if (mechanism == null || h == null) {
            throw new NullPointerException(mechanism == null ? "mechanism" : "handler");
        }
        String name = mechanism.getMechanismName();
        if (!offeredMechanisms.contains(name)) {
            failLocal(new IOException(java.text.MessageFormat.format(
                    L10N.getString("err.sasl_mechanism_not_offered"), name, offeredMechanisms)));
            return;
        }
        saslMechanism = mechanism;
        saslExecutor = executor;
        authHandler = h;
        state = State.SASL_OUTCOME;
        if (mechanism.hasInitialResponse()) {
            evaluateChallenge(new byte[0], new ChallengeCallback() {
                @Override
                public void onResponse(byte[] response) {
                    sendPerformative(Amqp1Frame.TYPE_SASL, 0,
                            new SaslInit(mechanism.getMechanismName(), response));
                }
            });
        } else {
            sendPerformative(Amqp1Frame.TYPE_SASL, 0, new SaslInit(name, null));
        }
    }

    private void handleSaslChallenge(SaslChallenge c) {
        if (state != State.SASL_OUTCOME || saslMechanism == null) {
            fail(Amqp1Error.ILLEGAL_STATE, "sasl-challenge in state " + state);
            return;
        }
        evaluateChallenge(c.getData(), new ChallengeCallback() {
            @Override
            public void onResponse(byte[] response) {
                sendPerformative(Amqp1Frame.TYPE_SASL, 0, new SaslResponse(response));
            }
        });
    }

    private void handleSaslOutcome(SaslOutcome outcome) {
        if (state != State.SASL_OUTCOME) {
            fail(Amqp1Error.ILLEGAL_STATE, "sasl-outcome in state " + state);
            return;
        }
        Amqp1AuthHandler h = authHandler;
        saslMechanism = null;
        saslExecutor = null;
        authHandler = null;
        if (!outcome.isSuccess()) {
            state = State.CLOSED;
            cancelTimers();
            h.handleAuthenticationFailed(outcome.getCode(), outcome.getAdditionalData());
            endpoint.close();
            return;
        }
        // The AMQP protocol header follows the SASL layer, from both sides
        state = State.AMQP;
        rearmHeaderAfterFrame = true;
        send(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_AMQP));
        h.handleAuthenticated(new Amqp1ConnectionOpener() {
            @Override
            public void open(String containerId, String hostname, Amqp1OpenHandler oh) {
                Open o = new Open(containerId);
                o.setHostname(hostname);
                open(o, oh);
            }

            @Override
            public void open(Open o, Amqp1OpenHandler oh) {
                sendOpen(o, oh);
            }
        });
    }

    /** Result callback for a (possibly offloaded) challenge evaluation. */
    private abstract class ChallengeCallback {
        abstract void onResponse(byte[] response);
    }

    /**
     * Evaluates a SASL challenge, offloading to the executor when one was
     * supplied (required for GSSAPI, whose first evaluation may block on
     * KDC contact) and dispatching the result back onto the connection's
     * event loop either way.
     */
    private void evaluateChallenge(final byte[] challenge, final ChallengeCallback callback) {
        final SaslClientMechanism mechanism = saslMechanism;
        if (saslExecutor == null) {
            try {
                callback.onResponse(mechanism.evaluateChallenge(challenge));
            } catch (IOException e) {
                failLocal(e);
            }
            return;
        }
        saslExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte[] response = mechanism.evaluateChallenge(challenge);
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (state == State.SASL_OUTCOME) {
                                callback.onResponse(response);
                            }
                        }
                    });
                } catch (final IOException e) {
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (state == State.SASL_OUTCOME) {
                                failLocal(e);
                            }
                        }
                    });
                }
            }
        });
    }

    // ── open / close ──

    private void sendOpen(Open open, Amqp1OpenHandler oh) {
        if (state != State.AMQP || localOpen != null) {
            throw new IllegalStateException("open already sent");
        }
        if (oh == null) {
            throw new NullPointerException("handler");
        }
        long frameMax = open.getMaxFrameSize();
        if (frameMax == Open.DEFAULT_MAX_FRAME_SIZE) {
            open.setMaxFrameSize(Amqp1ConnectionOpener.DEFAULT_MAX_FRAME_SIZE);
            frameMax = Amqp1ConnectionOpener.DEFAULT_MAX_FRAME_SIZE;
        }
        if (frameMax < Amqp1Frame.MIN_MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("max-frame-size must be at least "
                    + Amqp1Frame.MIN_MAX_FRAME_SIZE);
        }
        localOpen = open;
        openHandler = oh;
        // Once the peer has our open it may use frames up to our limit
        parser.setMaxFrameSize((int) Math.min(frameMax, Integer.MAX_VALUE));
        sendPerformative(Amqp1Frame.TYPE_AMQP, 0, open);
        scheduleIdleCheck();
        maybeCompleteOpen();
    }

    private void handleOpen(Open open) {
        if (state != State.AMQP || peerOpen != null) {
            fail(Amqp1Error.ILLEGAL_STATE, "open in state " + state);
            return;
        }
        if (frameChannel != 0) {
            fail(Amqp1Error.ILLEGAL_STATE, "open on channel " + frameChannel);
            return;
        }
        peerOpen = open;
        peerMaxFrameSize = Math.max(open.getMaxFrameSize(), Amqp1Frame.MIN_MAX_FRAME_SIZE);
        scheduleKeepAlive();
        maybeCompleteOpen();
    }

    /** Fires the application callback once both opens have been exchanged. */
    private void maybeCompleteOpen() {
        if (localOpen == null || peerOpen == null || state != State.AMQP) {
            return;
        }
        channelMax = Math.min(localOpen.getChannelMax(), peerOpen.getChannelMax());
        state = State.OPEN;
        Amqp1OpenHandler oh = openHandler;
        openHandler = null;
        oh.handleOpen(peerOpen, connection);
    }

    private void handleClose(Close close) {
        if (frameChannel != 0) {
            fail(Amqp1Error.ILLEGAL_STATE, "close on channel " + frameChannel);
            return;
        }
        Amqp1Error error = close.getError();
        if (!closeSent) {
            closeSent = true;
            sendPerformative(Amqp1Frame.TYPE_AMQP, 0, new Close());
        }
        State previous = state;
        state = State.CLOSED;
        cancelTimers();
        endAllSessions(error);
        endpoint.close();
        if (previous != State.CLOSED) {
            handler.onConnectionClosed(error);
        }
    }

    private void closeConnection(Amqp1Error error) {
        if (state != State.OPEN || closeSent) {
            throw new IllegalStateException("connection is not open");
        }
        closeSent = true;
        sendAmqp(0, new Close(error));
    }

    // ── sessions ──

    private void beginSession(Begin begin, Amqp1SessionHandler sh) {
        if (state != State.OPEN || closeSent) {
            throw new IllegalStateException("connection is not open");
        }
        if (sh == null) {
            throw new NullPointerException("handler");
        }
        if (begin.getRemoteChannel() != null) {
            throw new IllegalArgumentException("remote-channel must be unset when initiating a session");
        }
        int channel = -1;
        for (int c = 0; c <= channelMax; c++) {
            if (!sessionsByLocalChannel.containsKey(Integer.valueOf(c))) {
                channel = c;
                break;
            }
        }
        if (channel < 0) {
            throw new IllegalStateException("channel-max " + channelMax + " reached");
        }
        SessionImpl session = new SessionImpl(this, channel, begin, sh);
        sessionsByLocalChannel.put(Integer.valueOf(channel), session);
        sendAmqp(channel, begin);
    }

    private void handleBegin(int channel, Begin begin) {
        Integer remote = begin.getRemoteChannel();
        if (remote == null) {
            fail(Amqp1Error.NOT_IMPLEMENTED,
                    "Peer-initiated sessions are not supported (begin on channel " + channel + ")");
            return;
        }
        SessionImpl session = sessionsByLocalChannel.get(remote);
        if (session == null || session.remoteChannel >= 0) {
            fail(Amqp1Error.ILLEGAL_STATE, "begin answering no pending session (remote-channel "
                    + remote + ")");
            return;
        }
        if (sessionsByRemoteChannel.containsKey(Integer.valueOf(channel))) {
            fail(Amqp1Error.ILLEGAL_STATE, "begin on channel " + channel + " already in use");
            return;
        }
        sessionsByRemoteChannel.put(Integer.valueOf(channel), session);
        session.onBegin(begin, channel);
    }

    private void handleEnd(int channel, End end) {
        SessionImpl session = sessionsByRemoteChannel.get(Integer.valueOf(channel));
        if (session == null) {
            fail(Amqp1Error.ILLEGAL_STATE, "end on unmapped channel " + channel);
            return;
        }
        sessionsByLocalChannel.remove(Integer.valueOf(session.localChannel));
        sessionsByRemoteChannel.remove(Integer.valueOf(channel));
        session.onEnded(end.getError());
    }

    /** Finds the session a frame on the current channel belongs to, failing the connection if none. */
    private SessionImpl requireSession() {
        SessionImpl session = sessionsByRemoteChannel.get(Integer.valueOf(frameChannel));
        if (session == null) {
            fail(Amqp1Error.ILLEGAL_STATE, "frame on unmapped channel " + frameChannel);
        }
        return session;
    }

    private void endAllSessions(Amqp1Error error) {
        List<SessionImpl> all = new ArrayList<SessionImpl>(sessionsByLocalChannel.values());
        sessionsByLocalChannel.clear();
        sessionsByRemoteChannel.clear();
        for (SessionImpl s : all) {
            if (!s.isEnded()) {
                s.onEnded(error);
            }
        }
    }

    // ── services for sessions and links ──

    /** Sends a performative on an AMQP channel, checking it fits the peer's frame size. */
    void sendPerformative(int channel, Performative p) {
        sendAmqp(channel, p);
    }

    /** Sends an already-encoded frame. */
    void sendFrame(ByteBuffer frame) {
        send(frame);
    }

    void failConnection(String condition, String message) {
        fail(condition, message);
    }

    boolean isConnectionOpen() {
        return state == State.OPEN && !closeSent;
    }

    long peerMaxFrameSize() {
        return peerMaxFrameSize;
    }

    private final Amqp1Connection connection = new Amqp1Connection() {
        @Override
        public Open getPeerOpen() {
            return peerOpen;
        }

        @Override
        public long getPeerMaxFrameSize() {
            return peerMaxFrameSize;
        }

        @Override
        public int getChannelMax() {
            return channelMax;
        }

        @Override
        public void beginSession(Amqp1SessionHandler sh) {
            beginSession(new Begin(0, DEFAULT_WINDOW, DEFAULT_WINDOW), sh);
        }

        @Override
        public void beginSession(Begin begin, Amqp1SessionHandler sh) {
            Amqp1ClientProtocolHandler.this.beginSession(begin, sh);
        }

        @Override
        public void close(Amqp1Error error) {
            closeConnection(error);
        }
    };

    // ── idle timeouts (core specification 2.4.5) ──

    /**
     * Keeps the peer's idle timeout from expiring: an empty frame is
     * sent whenever half of it has passed without sending anything else.
     */
    private void scheduleKeepAlive() {
        final long peerTimeout = peerOpen.getIdleTimeOut();
        if (peerTimeout <= 0) {
            return;
        }
        final long interval = Math.max(1L, peerTimeout / 2);
        keepAliveTimer = endpoint.scheduleTimer(interval, new Runnable() {
            @Override
            public void run() {
                if (state == State.CLOSED || closeSent) {
                    return;
                }
                long idleMs = (clock.nanoTime() - lastSendNanos) / 1000000L;
                if (idleMs >= interval) {
                    send(Amqp1Frame.encodeHeartbeat(0));
                }
                scheduleKeepAlive();
            }
        });
    }

    /**
     * Fails the connection if nothing has been received within the idle
     * timeout we advertised. The peer is expected to send at half that
     * interval, so checking at half the interval catches a stall promptly.
     */
    private void scheduleIdleCheck() {
        final long timeout = localOpen.getIdleTimeOut();
        if (timeout <= 0) {
            return;
        }
        final long interval = Math.max(1L, timeout / 2);
        idleCheckTimer = endpoint.scheduleTimer(interval, new Runnable() {
            @Override
            public void run() {
                if (state == State.CLOSED || closeSent) {
                    return;
                }
                long idleMs = (clock.nanoTime() - lastReceiveNanos) / 1000000L;
                if (idleMs > timeout) {
                    fail(Amqp1Error.RESOURCE_LIMIT_EXCEEDED,
                            "Idle timeout of " + timeout + "ms expired");
                } else {
                    scheduleIdleCheck();
                }
            }
        });
    }

    private void cancelTimers() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }
        if (idleCheckTimer != null) {
            idleCheckTimer.cancel();
            idleCheckTimer = null;
        }
    }

    // ── failure ──

    /**
     * A violation by the peer, or a timeout: close the connection with
     * {@code error} if it is open enough for a {@code close} to be
     * meaningful, tear down and report to the application.
     */
    private void fail(String condition, String message) {
        if (state == State.CLOSED) {
            return;
        }
        Amqp1Error error = new Amqp1Error(condition, message);
        boolean canClose = localOpen != null && !closeSent;
        state = State.CLOSED;
        cancelTimers();
        if (canClose) {
            closeSent = true;
            sendPerformative(Amqp1Frame.TYPE_AMQP, 0, new Close(error));
        }
        endpoint.close();
        endAllSessions(error);
        Amqp1ProtocolException e = new Amqp1ProtocolException(message);
        JulWarnings.warn(LOGGER, L10N.getString("warn.protocol_error"), e);
        handler.onError(e);
    }

    /** A local failure (for example, SASL evaluation), with no peer violation. */
    private void failLocal(IOException e) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        cancelTimers();
        endpoint.close();
        endAllSessions(new Amqp1Error(Amqp1Error.INTERNAL_ERROR, e.getMessage()));
        JulWarnings.warn(LOGGER, L10N.getString("warn.protocol_error"), e);
        handler.onError(e);
    }
}
