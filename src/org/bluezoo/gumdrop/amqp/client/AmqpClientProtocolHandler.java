/*
 * AmqpClientProtocolHandler.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.bluezoo.gumdrop.amqp.AmqpFrame;
import org.bluezoo.gumdrop.amqp.AmqpFrameHandler;
import org.bluezoo.gumdrop.amqp.AmqpFrameParser;
import org.bluezoo.gumdrop.amqp.AmqpMethod;
import org.bluezoo.gumdrop.amqp.AmqpProtocolException;
import org.bluezoo.gumdrop.amqp.BasicMethods;
import org.bluezoo.gumdrop.amqp.BasicProperties;
import org.bluezoo.gumdrop.amqp.ChannelMethods;
import org.bluezoo.gumdrop.amqp.ConfirmMethods;
import org.bluezoo.gumdrop.amqp.ConnectionMethods;
import org.bluezoo.gumdrop.amqp.ExchangeMethods;
import org.bluezoo.gumdrop.amqp.FieldTable;
import org.bluezoo.gumdrop.amqp.QueueMethods;
import org.bluezoo.gumdrop.amqp.TxMethods;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.amqp.client.ChannelClosedListener;
import org.bluezoo.gumdrop.amqp.client.ClientChannel;
import org.bluezoo.gumdrop.amqp.client.ClientConnection;
import org.bluezoo.gumdrop.amqp.client.ClientHandshake;
import org.bluezoo.gumdrop.amqp.client.ClientTuned;
import org.bluezoo.gumdrop.amqp.client.ConfirmListener;
import org.bluezoo.gumdrop.amqp.client.ConnectionReady;
import org.bluezoo.gumdrop.amqp.client.DeliveryHandler;
import org.bluezoo.gumdrop.amqp.client.FlowListener;
import org.bluezoo.gumdrop.amqp.client.PublishBody;
import org.bluezoo.gumdrop.amqp.client.CancelHandler;
import org.bluezoo.gumdrop.amqp.client.ChannelCloseHandler;
import org.bluezoo.gumdrop.amqp.client.ChannelOpenHandler;
import org.bluezoo.gumdrop.amqp.client.CloseHandler;
import org.bluezoo.gumdrop.amqp.client.ConfirmSelectHandler;
import org.bluezoo.gumdrop.amqp.client.ConsumeHandler;
import org.bluezoo.gumdrop.amqp.client.ExchangeDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.FlowHandler;
import org.bluezoo.gumdrop.amqp.client.OpenHandler;
import org.bluezoo.gumdrop.amqp.client.QueueBindHandler;
import org.bluezoo.gumdrop.amqp.client.QueueDeclareHandler;
import org.bluezoo.gumdrop.amqp.client.TuneHandler;
import org.bluezoo.gumdrop.amqp.client.TxCommitHandler;
import org.bluezoo.gumdrop.amqp.client.TxRollbackHandler;
import org.bluezoo.gumdrop.amqp.client.TxSelectHandler;
import org.bluezoo.gumdrop.auth.SaslClientMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;

/**
 * AMQP 0-9-1 client protocol handler (issue #154).
 *
 * <p>Implements a type-safe state machine ({@code ConnectionReady} →
 * {@code ClientHandshake} → {@code TuneHandler} → {@code ClientTuned}
 * → {@code OpenHandler} → {@code ClientConnection} →
 * {@code ChannelOpenHandler} → {@code ClientChannel}), mirroring
 * {@link org.bluezoo.gumdrop.smtp.client.SmtpClientProtocolHandler}.
 *
 * <p>Frame parsing uses the streaming, push-based {@link AmqpFrameParser}:
 * no method here ever assumes a complete frame, let alone a complete
 * message, is available in one read. Message bodies — both outbound
 * ({@link PublishBody}) and inbound ({@link DeliveryHandler}) — are
 * handled as a sequence of chunks, never accumulated into one buffer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectionReady
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification</a>
 */
public final class AmqpClientProtocolHandler implements ProtocolHandler, AmqpFrameHandler {

    private static final Logger LOGGER =
            Logger.getLogger(AmqpClientProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.amqp.client.L10N");

    /** AMQP 0-9-1 protocol header, sent immediately on connect. */
    private static final byte[] PROTOCOL_HEADER =
            { 'A', 'M', 'Q', 'P', 0, 0, 9, 1 };

    private enum State {
        DISCONNECTED, AWAITING_START, AWAITING_SECURE_OR_TUNE, AWAITING_OPEN_OK, OPEN, CLOSED
    }

    /** Result callback for a (possibly offloaded) {@link SaslClientMechanism#evaluateChallenge} call. */
    private interface ChallengeCallback {
        void onResponse(byte[] response);
        void onFailure(IOException e);
    }

    private final ConnectionReady handler;
    private final AmqpFrameParser parser;

    private Endpoint endpoint;
    private State state = State.DISCONNECTED;

    private int negotiatedChannelMax;
    private long negotiatedFrameMax = AmqpFrameParser.DEFAULT_MAX_FRAME_SIZE;
    private int negotiatedHeartbeat;

    /** Pending connection-level callback (TuneHandler / OpenHandler / CloseHandler). */
    private Object pendingConnectionCallback;

    /**
     * The in-progress SASL exchange, non-null between {@code start-ok} and
     * the terminating {@code tune} (or a fatal {@code close}). Needed
     * because a multi-step mechanism (e.g. GSSAPI) receives further
     * {@code connection.secure} challenges after {@code start-ok} and
     * before {@code tune} — see issue #188.
     */
    private SaslClientMechanism pendingSaslClient;
    /** Worker executor for {@link #pendingSaslClient}, or null to evaluate challenges inline. */
    private ExecutorService pendingSaslExecutor;

    private final Map<Integer, ChannelImpl> channels = new HashMap<Integer, ChannelImpl>();
    /**
     * Pending per-channel RPC callbacks: ChannelOpenHandler,
     * ChannelCloseHandler, ExchangeDeclareHandler,
     * QueueDeclareHandler, QueueBindHandler,
     * ConsumeHandler, CancelHandler, ConfirmSelectHandler,
     * ServerTx*Handler, or FlowHandler, depending on which request
     * is outstanding.
     *
     * <p>A FIFO queue per channel, not a single slot: AMQP explicitly
     * permits a client to pipeline several requests on a channel without
     * waiting for each reply first, and guarantees replies come back in
     * the order the requests were sent — a single slot silently drops a
     * pending callback the moment a second request is issued before the
     * first's reply arrives (this bit the topology-replay path in {@code
     * RecoverableChannelImpl}, which fires several recorded declare/bind/
     * consume calls back-to-back on reconnect).
     */
    private final Map<Integer, ArrayDeque<Object>> pendingChannelCallbacks =
            new HashMap<Integer, ArrayDeque<Object>>();

    private void pushPendingChannelCallback(int channelId, Object callback) {
        ArrayDeque<Object> queue = pendingChannelCallbacks.get(channelId);
        if (queue == null) {
            queue = new ArrayDeque<Object>();
            pendingChannelCallbacks.put(channelId, queue);
        }
        queue.addLast(callback);
    }

    /** Returns the oldest still-pending callback for the channel, or null if none. */
    private Object popPendingChannelCallback(int channelId) {
        ArrayDeque<Object> queue = pendingChannelCallbacks.get(channelId);
        if (queue == null) {
            return null;
        }
        Object callback = queue.pollFirst();
        if (queue.isEmpty()) {
            pendingChannelCallbacks.remove(channelId);
        }
        return callback;
    }

    private boolean hasPendingChannelCallback(int channelId) {
        ArrayDeque<Object> queue = pendingChannelCallbacks.get(channelId);
        return queue != null && !queue.isEmpty();
    }

    public AmqpClientProtocolHandler(ConnectionReady handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.parser = new AmqpFrameParser(this);
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        state = State.AWAITING_START;
        endpoint.send(ByteBuffer.wrap(PROTOCOL_HEADER));
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void disconnected() {
        state = State.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        // No protocol-level action needed; AMQPS negotiates TLS before the
        // protocol header is sent (implicit TLS, like AMQP over TLS on 5671).
    }

    @Override
    public void error(Exception cause) {
        handler.onError(cause);
    }

    // ── AmqpFrameHandler ──

    @Override
    public void methodFrame(int channel, ByteBuffer payload) {
        int classId = payload.getShort() & 0xFFFF;
        int methodId = payload.getShort() & 0xFFFF;
        try {
            if (channel == 0) {
                dispatchConnectionMethod(classId, methodId, payload);
            } else {
                dispatchChannelMethod(channel, classId, methodId, payload);
            }
        } catch (AmqpProtocolException e) {
            protocolError(e);
        } catch (RuntimeException e) {
            // Malformed argument encoding surfaces as an unchecked buffer
            // exception from the *Methods decoders; treat identically to a
            // declared protocol violation rather than propagating a raw
            // BufferUnderflowException to the caller.
            protocolError(new AmqpProtocolException("Malformed method arguments", e));
        }
    }

    @Override
    public void headerFrame(int channel, ByteBuffer payload) {
        ChannelImpl ch = channels.get(channel);
        if (ch == null) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.header_frame_unknown_channel"), channel);
            return;
        }
        try {
            ch.handleHeaderFrame(payload);
        } catch (AmqpProtocolException e) {
            protocolError(e);
        }
    }

    @Override
    public void bodyFrame(int channel, ByteBuffer payload) {
        ChannelImpl ch = channels.get(channel);
        if (ch == null) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.body_frame_unknown_channel"), channel);
            return;
        }
        try {
            ch.handleBodyFrame(payload);
        } catch (AmqpProtocolException e) {
            protocolError(e);
        }
    }

    @Override
    public void heartbeatFrame() {
        if (endpoint != null) {
            endpoint.send(AmqpFrame.encodeHeartbeat());
        }
    }

    @Override
    public void frameError(String message) {
        protocolError(new AmqpProtocolException(message));
    }

    private void protocolError(AmqpProtocolException e) {
        LOGGER.log(Level.WARNING, L10N.getString("warn.protocol_error"), e);
        handler.onError(e);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /** SASL exchange failed (mechanism evaluation threw, or offloaded evaluation failed). */
    private void failSasl(IOException e) {
        LOGGER.log(Level.WARNING, L10N.getString("warn.protocol_error"), e);
        pendingSaslClient = null;
        pendingSaslExecutor = null;
        handler.onError(e);
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── connection-level method dispatch ──

    private void dispatchConnectionMethod(int classId, int methodId, ByteBuffer payload)
            throws AmqpProtocolException {
        if (classId != AmqpMethod.CLASS_CONNECTION) {
            throw new AmqpProtocolException(
                    "Unexpected class " + classId + " on channel 0");
        }
        switch (methodId) {
            case AmqpMethod.CONNECTION_START:
                handleStart(payload);
                break;
            case AmqpMethod.CONNECTION_SECURE:
                handleSecure(payload);
                break;
            case AmqpMethod.CONNECTION_TUNE:
                handleTune(payload);
                break;
            case AmqpMethod.CONNECTION_OPEN_OK:
                handleOpenOk(payload);
                break;
            case AmqpMethod.CONNECTION_CLOSE:
                handleClose(payload);
                break;
            case AmqpMethod.CONNECTION_CLOSE_OK:
                handleCloseOk();
                break;
            default:
                throw new AmqpProtocolException(
                        "Unexpected connection method " + methodId + " in state " + state);
        }
    }

    private void handleStart(ByteBuffer payload) throws AmqpProtocolException {
        if (state != State.AWAITING_START) {
            throw new AmqpProtocolException("connection.start in state " + state);
        }
        ConnectionMethods.Start start = ConnectionMethods.decodeStart(payload);
        handler.onConnected(endpoint);
        handler.handleStart(start.serverProperties, start.mechanisms, start.locales,
                new ClientHandshake() {
                    @Override
                    public void startOk(String username, String password, TuneHandler tuneHandler) {
                        sendStartOk(SaslUtils.createClient("PLAIN", username, password, null),
                                tuneHandler, null);
                    }

                    @Override
                    public void startOk(SaslClientMechanism saslClient, TuneHandler tuneHandler) {
                        sendStartOk(saslClient, tuneHandler, null);
                    }

                    @Override
                    public void startOk(SaslClientMechanism saslClient, TuneHandler tuneHandler,
                            ExecutorService executor) {
                        sendStartOk(saslClient, tuneHandler, executor);
                    }
                });
    }

    private void sendStartOk(final SaslClientMechanism saslClient, final TuneHandler tuneHandler,
            final ExecutorService executor) {
        pendingSaslClient = saslClient;
        pendingSaslExecutor = executor;
        pendingConnectionCallback = tuneHandler;
        if (saslClient.hasInitialResponse()) {
            evaluateChallenge(new byte[0], new ChallengeCallback() {
                @Override
                public void onResponse(byte[] response) {
                    sendStartOkFrame(response);
                }

                @Override
                public void onFailure(IOException e) {
                    failSasl(e);
                }
            });
        } else {
            sendStartOkFrame(new byte[0]);
        }
    }

    private void sendStartOkFrame(byte[] response) {
        FieldTable clientProperties = new FieldTable()
                .put("product", "gumdrop")
                .put("platform", "Java");
        ByteBuffer args = ConnectionMethods.encodeStartOk(
                clientProperties, pendingSaslClient.getMechanismName(), response, "en_US");
        endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args));
        state = State.AWAITING_SECURE_OR_TUNE;
    }

    /**
     * {@code connection.secure} — the broker wants another round of the
     * SASL exchange before it will send {@code tune} (issue #188; needed
     * by multi-step mechanisms such as GSSAPI).
     */
    private void handleSecure(ByteBuffer payload) throws AmqpProtocolException {
        if (state != State.AWAITING_SECURE_OR_TUNE || pendingSaslClient == null) {
            throw new AmqpProtocolException("connection.secure in state " + state);
        }
        byte[] challenge = ConnectionMethods.decodeSecure(payload);
        evaluateChallenge(challenge, new ChallengeCallback() {
            @Override
            public void onResponse(byte[] response) {
                ByteBuffer args = ConnectionMethods.encodeSecureOk(response);
                endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, args));
            }

            @Override
            public void onFailure(IOException e) {
                failSasl(e);
            }
        });
    }

    /**
     * Evaluates a SASL challenge against {@link #pendingSaslClient},
     * offloading to {@link #pendingSaslExecutor} when one is configured
     * (required for GSSAPI, whose first evaluation may block on KDC
     * contact) and dispatching the result back onto the connection's
     * event loop either way.
     */
    private void evaluateChallenge(final byte[] challenge, final ChallengeCallback callback) {
        final SaslClientMechanism client = pendingSaslClient;
        if (pendingSaslExecutor != null) {
            pendingSaslExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        final byte[] response = client.evaluateChallenge(challenge);
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onResponse(response);
                            }
                        });
                    } catch (final IOException e) {
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onFailure(e);
                            }
                        });
                    }
                }
            });
        } else {
            try {
                callback.onResponse(client.evaluateChallenge(challenge));
            } catch (IOException e) {
                callback.onFailure(e);
            }
        }
    }

    private void handleTune(ByteBuffer payload) throws AmqpProtocolException {
        if (state != State.AWAITING_SECURE_OR_TUNE || !(pendingConnectionCallback instanceof TuneHandler)) {
            throw new AmqpProtocolException("connection.tune in state " + state);
        }
        TuneHandler tuneHandler = (TuneHandler) pendingConnectionCallback;
        pendingConnectionCallback = null;
        pendingSaslClient = null;
        pendingSaslExecutor = null;

        ConnectionMethods.Tune tune = ConnectionMethods.decodeTune(payload);
        negotiatedChannelMax = tune.channelMax;
        negotiatedFrameMax = (tune.frameMax > 0) ? tune.frameMax : AmqpFrameParser.DEFAULT_MAX_FRAME_SIZE;
        negotiatedHeartbeat = tune.heartbeat;
        parser.setMaxFrameSize((int) Math.min(negotiatedFrameMax, Integer.MAX_VALUE));

        // tune-ok must be sent before open regardless of what the
        // application wants to do next, so send it here rather than
        // waiting for a callback the application controls.
        ByteBuffer tuneOk = ConnectionMethods.encodeTuneOk(
                negotiatedChannelMax, negotiatedFrameMax, negotiatedHeartbeat);
        endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, tuneOk));

        tuneHandler.handleTune(negotiatedChannelMax, negotiatedFrameMax, negotiatedHeartbeat,
                new ClientTuned() {
                    @Override
                    public void open(String virtualHost, OpenHandler openHandler) {
                        sendOpen(virtualHost, openHandler);
                    }
                });
    }

    private void sendOpen(String virtualHost, OpenHandler openHandler) {
        endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, ConnectionMethods.encodeOpen(virtualHost)));
        state = State.AWAITING_OPEN_OK;
        pendingConnectionCallback = openHandler;
    }

    private void handleOpenOk(ByteBuffer payload) throws AmqpProtocolException {
        if (state != State.AWAITING_OPEN_OK || !(pendingConnectionCallback instanceof OpenHandler)) {
            throw new AmqpProtocolException("connection.open-ok in state " + state);
        }
        OpenHandler openHandler = (OpenHandler) pendingConnectionCallback;
        pendingConnectionCallback = null;
        ConnectionMethods.decodeOpenOk(payload);
        state = State.OPEN;
        openHandler.handleOpenOk(connectionView);
    }

    private void handleClose(ByteBuffer payload) throws AmqpProtocolException {
        ConnectionMethods.CloseReason reason = ConnectionMethods.decodeClose(payload);
        endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0, ConnectionMethods.encodeCloseOk()));
        state = State.CLOSED;
        handler.onConnectionClosed(reason.replyCode, reason.replyText);
    }

    private void handleCloseOk() throws AmqpProtocolException {
        if (!(pendingConnectionCallback instanceof CloseHandler)) {
            throw new AmqpProtocolException("Unsolicited connection.close-ok");
        }
        CloseHandler closeHandler = (CloseHandler) pendingConnectionCallback;
        pendingConnectionCallback = null;
        state = State.CLOSED;
        closeHandler.handleCloseOk();
    }

    // ── channel-level method dispatch ──

    private void dispatchChannelMethod(int channel, int classId, int methodId, ByteBuffer payload)
            throws AmqpProtocolException {
        if (classId == AmqpMethod.CLASS_CHANNEL) {
            switch (methodId) {
                case AmqpMethod.CHANNEL_OPEN_OK:
                    handleChannelOpenOk(channel, payload);
                    return;
                case AmqpMethod.CHANNEL_CLOSE:
                    handleChannelClose(channel, payload);
                    return;
                case AmqpMethod.CHANNEL_CLOSE_OK:
                    handleChannelCloseOk(channel);
                    return;
                case AmqpMethod.CHANNEL_FLOW:
                    requireChannel(channel).handleFlow(payload);
                    return;
                case AmqpMethod.CHANNEL_FLOW_OK:
                    requireChannel(channel).handleFlowOk(payload);
                    return;
                default:
                    throw new AmqpProtocolException(
                            "Unexpected channel method " + methodId + " on channel " + channel);
            }
        }

        ChannelImpl ch = requireChannel(channel);

        if (classId == AmqpMethod.CLASS_EXCHANGE && methodId == AmqpMethod.EXCHANGE_DECLARE_OK) {
            ch.handleExchangeDeclareOk(payload);
        } else if (classId == AmqpMethod.CLASS_QUEUE && methodId == AmqpMethod.QUEUE_DECLARE_OK) {
            ch.handleQueueDeclareOk(payload);
        } else if (classId == AmqpMethod.CLASS_QUEUE && methodId == AmqpMethod.QUEUE_BIND_OK) {
            ch.handleQueueBindOk(payload);
        } else if (classId == AmqpMethod.CLASS_BASIC && methodId == AmqpMethod.BASIC_CONSUME_OK) {
            ch.handleConsumeOk(payload);
        } else if (classId == AmqpMethod.CLASS_BASIC && methodId == AmqpMethod.BASIC_CANCEL_OK) {
            ch.handleCancelOk(payload);
        } else if (classId == AmqpMethod.CLASS_BASIC && methodId == AmqpMethod.BASIC_DELIVER) {
            ch.handleDeliver(payload);
        } else if (classId == AmqpMethod.CLASS_TX && methodId == AmqpMethod.TX_SELECT_OK) {
            ch.handleTxSelectOk();
        } else if (classId == AmqpMethod.CLASS_TX && methodId == AmqpMethod.TX_COMMIT_OK) {
            ch.handleTxCommitOk();
        } else if (classId == AmqpMethod.CLASS_TX && methodId == AmqpMethod.TX_ROLLBACK_OK) {
            ch.handleTxRollbackOk();
        } else if (classId == AmqpMethod.CLASS_CONFIRM && methodId == AmqpMethod.CONFIRM_SELECT_OK) {
            ch.handleConfirmSelectOk();
        } else if (classId == AmqpMethod.CLASS_BASIC && methodId == AmqpMethod.BASIC_ACK) {
            // A client never receives basic.ack for anything other than a
            // publisher confirm — basic.ack sent *by* the client acks a
            // consumed delivery, but that's outbound, not something this
            // dispatch (incoming frames only) ever sees.
            ch.handleConfirmAck(payload);
        } else if (classId == AmqpMethod.CLASS_BASIC && methodId == AmqpMethod.BASIC_NACK) {
            ch.handleConfirmNack(payload);
        } else {
            throw new AmqpProtocolException(
                    "Unhandled class " + classId + " method " + methodId + " on channel " + channel);
        }
    }

    private ChannelImpl requireChannel(int channel) throws AmqpProtocolException {
        ChannelImpl ch = channels.get(channel);
        if (ch == null) {
            throw new AmqpProtocolException("Method on unknown/unopened channel " + channel);
        }
        return ch;
    }

    private void handleChannelOpenOk(int channel, ByteBuffer payload) throws AmqpProtocolException {
        Object pending = popPendingChannelCallback(channel);
        if (!(pending instanceof ChannelOpenHandler)) {
            throw new AmqpProtocolException("Unsolicited channel.open-ok on channel " + channel);
        }
        ChannelMethods.decodeOpenOk(payload);
        ChannelImpl ch = new ChannelImpl(channel);
        channels.put(channel, ch);
        ((ChannelOpenHandler) pending).handleChannelOpenOk(ch);
    }

    private void handleChannelClose(int channel, ByteBuffer payload) throws AmqpProtocolException {
        ConnectionMethods.CloseReason reason = ChannelMethods.decodeClose(payload);
        endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channel, ChannelMethods.encodeCloseOk()));
        ChannelImpl ch = channels.remove(channel);
        // The channel is gone; anything else still queued for it (e.g. a
        // declare pipelined just before the broker closed the channel)
        // will never get a reply now — drop the whole queue, not just one entry.
        pendingChannelCallbacks.remove(channel);
        if (ch != null) {
            ch.notifyClosed(reason.replyCode, reason.replyText);
        }
    }

    private void handleChannelCloseOk(int channel) throws AmqpProtocolException {
        Object pending = popPendingChannelCallback(channel);
        if (!(pending instanceof ChannelCloseHandler)) {
            throw new AmqpProtocolException("Unsolicited channel.close-ok on channel " + channel);
        }
        channels.remove(channel);
        // channel.close-ok is the last reply this channel will ever get;
        // drop anything else still queued rather than leaving it to throw
        // "unsolicited" later against a channel that no longer exists.
        pendingChannelCallbacks.remove(channel);
        ((ChannelCloseHandler) pending).handleChannelCloseOk();
    }

    // ── ClientConnection implementation ──

    private final ClientConnection connectionView = new ClientConnection() {
        @Override
        public void channelOpen(int channelId, ChannelOpenHandler openHandler) {
            if (channels.containsKey(channelId) || hasPendingChannelCallback(channelId)) {
                throw new IllegalStateException("Channel " + channelId + " is already in use");
            }
            pushPendingChannelCallback(channelId, openHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, ChannelMethods.encodeOpen()));
        }

        @Override
        public void close(int replyCode, String replyText, CloseHandler closeHandler) {
            pendingConnectionCallback = closeHandler;
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, 0,
                    ConnectionMethods.encodeClose(replyCode, replyText)));
        }
    };

    // ── ClientChannel implementation ──

    /**
     * In-progress inbound delivery on a channel: set when {@code
     * basic.deliver} arrives, populated further as the content-header
     * and content-body frames that follow it are parsed. AMQP does not
     * interleave a new message's content frames with an in-progress
     * one's on the same channel, so one slot per channel is sufficient.
     */
    private static final class DeliveryContext {
        final String consumerTag;
        final long deliveryTag;
        final DeliveryHandler target;
        long remaining = -1; // -1 until the header frame sets the real body size

        DeliveryContext(String consumerTag, long deliveryTag, DeliveryHandler target) {
            this.consumerTag = consumerTag;
            this.deliveryTag = deliveryTag;
            this.target = target;
        }
    }

    private final class ChannelImpl implements ClientChannel {
        private final int channelId;
        private ChannelClosedListener closeListener;
        private FlowListener flowListener;
        private ConfirmListener confirmListener;
        private boolean confirmsEnabled;
        private long publishSeqNo;
        private final Map<String, DeliveryHandler> consumers = new HashMap<String, DeliveryHandler>();
        private DeliveryContext currentDelivery;

        ChannelImpl(int channelId) {
            this.channelId = channelId;
        }

        @Override
        public int getChannelId() {
            return channelId;
        }

        @Override
        public void setCloseListener(ChannelClosedListener listener) {
            this.closeListener = listener;
        }

        @Override
        public void close(int replyCode, String replyText, ChannelCloseHandler closeHandler) {
            pushPendingChannelCallback(channelId, closeHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId,
                    ChannelMethods.encodeClose(replyCode, replyText)));
        }

        void notifyClosed(int replyCode, String replyText) {
            if (closeListener != null) {
                closeListener.onChannelClosed(replyCode, replyText);
            }
        }

        @Override
        public void exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete,
                FieldTable arguments, ExchangeDeclareHandler declareHandler) {
            pushPendingChannelCallback(channelId, declareHandler);
            ByteBuffer args = ExchangeMethods.encodeDeclare(
                    exchange, type, false, durable, autoDelete, false, false, arguments);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, args));
        }

        void handleExchangeDeclareOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof ExchangeDeclareHandler)) {
                throw new AmqpProtocolException("Unsolicited exchange.declare-ok on channel " + channelId);
            }
            ExchangeMethods.decodeDeclareOk(payload);
            ((ExchangeDeclareHandler) pending).handleExchangeDeclareOk();
        }

        @Override
        public void queueDeclare(String queue, boolean durable, boolean exclusive, boolean autoDelete,
                FieldTable arguments, QueueDeclareHandler declareHandler) {
            pushPendingChannelCallback(channelId, declareHandler);
            ByteBuffer args = QueueMethods.encodeDeclare(
                    queue, false, durable, exclusive, autoDelete, false, arguments);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, args));
        }

        void handleQueueDeclareOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof QueueDeclareHandler)) {
                throw new AmqpProtocolException("Unsolicited queue.declare-ok on channel " + channelId);
            }
            QueueMethods.DeclareOk result = QueueMethods.decodeDeclareOk(payload);
            ((QueueDeclareHandler) pending)
                    .handleQueueDeclareOk(result.queue, result.messageCount, result.consumerCount);
        }

        @Override
        public void queueBind(String queue, String exchange, String routingKey, FieldTable arguments,
                QueueBindHandler bindHandler) {
            pushPendingChannelCallback(channelId, bindHandler);
            ByteBuffer args = QueueMethods.encodeBind(queue, exchange, routingKey, false, arguments);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, args));
        }

        void handleQueueBindOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof QueueBindHandler)) {
                throw new AmqpProtocolException("Unsolicited queue.bind-ok on channel " + channelId);
            }
            QueueMethods.decodeBindOk(payload);
            ((QueueBindHandler) pending).handleQueueBindOk();
        }

        @Override
        public PublishBody basicPublish(String exchange, String routingKey, boolean mandatory,
                BasicProperties properties, long bodySize) {
            ByteBuffer methodArgs = BasicMethods.encodePublish(exchange, routingKey, mandatory, false);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, methodArgs));

            BasicProperties props = (properties != null) ? properties : new BasicProperties();
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_HEADER, channelId, props.encode(bodySize)));

            long seqNo = confirmsEnabled ? ++publishSeqNo : 0;
            return new PublishBodyImpl(channelId, bodySize, seqNo);
        }

        // ── publisher confirms ──

        @Override
        public void confirmSelect(ConfirmSelectHandler confirmHandler) {
            pushPendingChannelCallback(channelId, confirmHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, ConfirmMethods.encodeSelect(false)));
        }

        void handleConfirmSelectOk() throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof ConfirmSelectHandler)) {
                throw new AmqpProtocolException("Unsolicited confirm.select-ok on channel " + channelId);
            }
            // AMQP 0-9-1 (RabbitMQ extension): sequence numbering starts
            // fresh at 1 from this point, regardless of any publishes
            // already made on this channel before confirms were enabled.
            confirmsEnabled = true;
            publishSeqNo = 0;
            ((ConfirmSelectHandler) pending).handleConfirmSelectOk();
        }

        @Override
        public void setConfirmListener(ConfirmListener listener) {
            this.confirmListener = listener;
        }

        void handleConfirmAck(ByteBuffer payload) {
            BasicMethods.Ack ack = BasicMethods.decodeAck(payload);
            if (confirmListener != null) {
                confirmListener.onAck(ack.deliveryTag, ack.multiple);
            }
        }

        void handleConfirmNack(ByteBuffer payload) {
            BasicMethods.Nack nack = BasicMethods.decodeNack(payload);
            if (confirmListener != null) {
                confirmListener.onNack(nack.deliveryTag, nack.multiple);
            }
        }

        @Override
        public void basicConsume(String queue, String consumerTag, boolean noAck, boolean exclusive,
                FieldTable arguments, DeliveryHandler deliveryHandler, ConsumeHandler consumeHandler) {
            pushPendingChannelCallback(channelId, new PendingConsume(consumerTag, deliveryHandler, consumeHandler));
            ByteBuffer args = BasicMethods.encodeConsume(
                    queue, consumerTag, false, noAck, exclusive, false, arguments);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, args));
        }

        void handleConsumeOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof PendingConsume)) {
                throw new AmqpProtocolException("Unsolicited basic.consume-ok on channel " + channelId);
            }
            PendingConsume p = (PendingConsume) pending;
            String consumerTag = BasicMethods.decodeConsumeOk(payload);
            consumers.put(consumerTag, p.deliveryHandler);
            p.consumeHandler.handleConsumeOk(consumerTag);
        }

        @Override
        public void basicCancel(String consumerTag, CancelHandler cancelHandler) {
            pushPendingChannelCallback(channelId, cancelHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId,
                    BasicMethods.encodeCancel(consumerTag, false)));
        }

        void handleCancelOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof CancelHandler)) {
                throw new AmqpProtocolException("Unsolicited basic.cancel-ok on channel " + channelId);
            }
            String consumerTag = BasicMethods.decodeCancelOk(payload);
            consumers.remove(consumerTag);
            ((CancelHandler) pending).handleCancelOk(consumerTag);
        }

        @Override
        public void basicAck(long deliveryTag, boolean multiple) {
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId,
                    BasicMethods.encodeAck(deliveryTag, multiple)));
        }

        @Override
        public void basicNack(long deliveryTag, boolean multiple, boolean requeue) {
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId,
                    BasicMethods.encodeNack(deliveryTag, multiple, requeue)));
        }

        @Override
        public void basicReject(long deliveryTag, boolean requeue) {
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId,
                    BasicMethods.encodeReject(deliveryTag, requeue)));
        }

        // ── transactions ──

        @Override
        public void txSelect(TxSelectHandler txHandler) {
            pushPendingChannelCallback(channelId, txHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, TxMethods.encodeSelect()));
        }

        void handleTxSelectOk() throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof TxSelectHandler)) {
                throw new AmqpProtocolException("Unsolicited tx.select-ok on channel " + channelId);
            }
            ((TxSelectHandler) pending).handleTxSelectOk();
        }

        @Override
        public void txCommit(TxCommitHandler txHandler) {
            pushPendingChannelCallback(channelId, txHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, TxMethods.encodeCommit()));
        }

        void handleTxCommitOk() throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof TxCommitHandler)) {
                throw new AmqpProtocolException("Unsolicited tx.commit-ok on channel " + channelId);
            }
            ((TxCommitHandler) pending).handleTxCommitOk();
        }

        @Override
        public void txRollback(TxRollbackHandler txHandler) {
            pushPendingChannelCallback(channelId, txHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, TxMethods.encodeRollback()));
        }

        void handleTxRollbackOk() throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof TxRollbackHandler)) {
                throw new AmqpProtocolException("Unsolicited tx.rollback-ok on channel " + channelId);
            }
            ((TxRollbackHandler) pending).handleTxRollbackOk();
        }

        // ── flow control ──

        @Override
        public void setFlowListener(FlowListener listener) {
            this.flowListener = listener;
        }

        @Override
        public void flow(boolean active, FlowHandler flowHandler) {
            pushPendingChannelCallback(channelId, flowHandler);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, ChannelMethods.encodeFlow(active)));
        }

        /** Broker-initiated channel.flow: always ack, then notify the listener. */
        void handleFlow(ByteBuffer payload) {
            boolean active = ChannelMethods.decodeFlow(payload);
            endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_METHOD, channelId, ChannelMethods.encodeFlowOk(active)));
            if (flowListener != null) {
                flowListener.onFlow(active);
            }
        }

        /** Reply to our own client-initiated channel.flow. */
        void handleFlowOk(ByteBuffer payload) throws AmqpProtocolException {
            Object pending = popPendingChannelCallback(channelId);
            if (!(pending instanceof FlowHandler)) {
                throw new AmqpProtocolException("Unsolicited channel.flow-ok on channel " + channelId);
            }
            boolean active = ChannelMethods.decodeFlowOk(payload);
            ((FlowHandler) pending).handleFlowOk(active);
        }

        void handleDeliver(ByteBuffer payload) throws AmqpProtocolException {
            if (currentDelivery != null) {
                throw new AmqpProtocolException(
                        "basic.deliver received while a previous delivery is still in progress"
                                + " on channel " + channelId);
            }
            BasicMethods.Deliver deliver = BasicMethods.decodeDeliver(payload);
            DeliveryHandler target = consumers.get(deliver.consumerTag);
            if (target == null) {
                throw new AmqpProtocolException(
                        "basic.deliver for unknown consumer tag " + deliver.consumerTag);
            }
            currentDelivery = new DeliveryContext(deliver.consumerTag, deliver.deliveryTag, target);
            target.onDeliveryStart(deliver.consumerTag, deliver.deliveryTag, deliver.redelivered,
                    deliver.exchange, deliver.routingKey);
        }

        void handleHeaderFrame(ByteBuffer payload) throws AmqpProtocolException {
            if (currentDelivery == null) {
                throw new AmqpProtocolException(
                        "content-header frame with no preceding basic.deliver on channel " + channelId);
            }
            BasicProperties.Header header = BasicProperties.decode(payload);
            currentDelivery.remaining = header.getBodySize();
            currentDelivery.target.onDeliveryProperties(header.getProperties(), header.getBodySize());
            if (currentDelivery.remaining == 0) {
                completeCurrentDelivery();
            }
        }

        void handleBodyFrame(ByteBuffer payload) throws AmqpProtocolException {
            if (currentDelivery == null || currentDelivery.remaining < 0) {
                throw new AmqpProtocolException(
                        "content-body frame with no preceding content-header on channel " + channelId);
            }
            int chunkSize = payload.remaining();
            if (chunkSize > currentDelivery.remaining) {
                throw new AmqpProtocolException(
                        "content-body frame overruns declared body size on channel " + channelId);
            }
            currentDelivery.target.onDeliveryBodyChunk(payload);
            currentDelivery.remaining -= chunkSize;
            if (currentDelivery.remaining == 0) {
                completeCurrentDelivery();
            }
        }

        private void completeCurrentDelivery() {
            DeliveryContext delivery = currentDelivery;
            currentDelivery = null;
            delivery.target.onDeliveryComplete();
        }
    }

    private static final class PendingConsume {
        final String requestedTag;
        final DeliveryHandler deliveryHandler;
        final ConsumeHandler consumeHandler;

        PendingConsume(String requestedTag, DeliveryHandler deliveryHandler, ConsumeHandler consumeHandler) {
            this.requestedTag = requestedTag;
            this.deliveryHandler = deliveryHandler;
            this.consumeHandler = consumeHandler;
        }
    }

    private final class PublishBodyImpl implements PublishBody {
        private final int channelId;
        private final long declaredSize;
        private final long sequenceNumber;
        private long written;
        private boolean completed;

        PublishBodyImpl(int channelId, long declaredSize, long sequenceNumber) {
            this.channelId = channelId;
            this.declaredSize = declaredSize;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public long getSequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public void writeBody(ByteBuffer chunk) {
            if (completed) {
                throw new IllegalStateException("PublishBody already completed");
            }
            int size = chunk.remaining();
            if (written + size > declaredSize) {
                throw new IllegalStateException(
                        "PublishBody write exceeds declared bodySize " + declaredSize);
            }
            written += size;
            if (size > 0) {
                endpoint.send(AmqpFrame.encode(AmqpFrame.TYPE_BODY, channelId, chunk));
            }
        }

        @Override
        public void onWriteReady(Runnable callback) {
            endpoint.onWriteReady(callback);
        }

        @Override
        public void complete() {
            if (completed) {
                throw new IllegalStateException("PublishBody already completed");
            }
            if (written != declaredSize) {
                throw new IllegalStateException(
                        "PublishBody completed after writing " + written
                                + " bytes, but declared bodySize was " + declaredSize);
            }
            completed = true;
        }
    }
}
