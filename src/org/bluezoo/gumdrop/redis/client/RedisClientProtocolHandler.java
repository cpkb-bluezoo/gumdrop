/*
 * RedisClientProtocolHandler.java
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespEncoder;
import org.bluezoo.gumdrop.redis.codec.RespException;
import org.bluezoo.gumdrop.redis.codec.RespValue;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * Redis client protocol handler using RESP (Redis Serialization Protocol).
 *
 * <p>Implements {@link ProtocolHandler} and {@link RedisSession}, storing an
 * {@link Endpoint} field set in {@link #connected(Endpoint)} and delegating
 * all I/O to the endpoint.
 *
 * <p>Commands are encoded as RESP arrays of bulk strings
 * (RESP spec — "Sending commands to a Redis server") and sent via the
 * endpoint. Responses are decoded by {@link RespDecoder} and dispatched
 * to the appropriate callback in FIFO order (RESP spec — "Pipelining").
 *
 * <p>Supported RESP2 response types:
 * <ul>
 *   <li>Simple String ({@code +}) — dispatched to {@link StringResultHandler}</li>
 *   <li>Error ({@code -}) — dispatched to handler's {@code handleError()}</li>
 *   <li>Integer ({@code :}) — dispatched to {@link IntegerResultHandler} / {@link BooleanResultHandler}</li>
 *   <li>Bulk String ({@code $}) — dispatched to {@link BulkResultHandler}</li>
 *   <li>Array ({@code *}) — dispatched to {@link ArrayResultHandler}</li>
 * </ul>
 *
 * <p>RESP3 types (Map, Set, Double, Boolean, Null, Push, Verbatim String,
 * Big Number, Blob Error) are decoded and dispatched. RESP3 Maps received
 * by {@link ArrayResultHandler} are flattened to alternating key/value lists.
 * The HELLO command negotiates the RESP protocol version (RESP3).
 *
 * <p>Pub/Sub push messages are handled out-of-band when in Pub/Sub mode
 * (RESP spec — "Pub/Sub" / "Push type"). In RESP3, Push (&gt;) replaces
 * the array-based Pub/Sub detection with a dedicated type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 */
public class RedisClientProtocolHandler extends AbstractRedisSession implements ProtocolHandler {

    private static final Logger LOGGER = Logger.getLogger(RedisClientProtocolHandler.class.getName());

    private EventLogger events() {
        return (endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig()).getLogger(RedisClientProtocolHandler.class, L10N);
    }
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.client.L10N");

    private final RedisConnectionReady handler;
    private final RespDecoder decoder;
    private final RespEncoder encoder;

    private Endpoint endpoint;

    // Pending command callbacks (FIFO)
    private final Deque<PendingCommand> pendingCommands;

    // Pub/Sub state
    private boolean pubSubMode = false;
    // subscriptions the server last reported: ordinary (channels and
    // patterns together) and shard channels, which it counts separately
    private int ordinaryCount;
    private int shardCount;
    private MessageHandler messageHandler = null;

    // Connection state
    private boolean closed = false;

    // The session handed to result handlers: this connection, unless a
    // cluster session owns it and wants its own passed back.
    private RedisSession resultSession = this;

    // Offered every error reply; while set, sent commands are remembered
    // so that the listener can send them again. Null for a plain connection.
    private RedirectListener redirectListener;

    /**
     * Creates a Redis client endpoint handler.
     *
     * @param handler the client handler for callbacks
     */
    public RedisClientProtocolHandler(RedisConnectionReady handler) {
        this.handler = handler;
        this.decoder = new RespDecoder();
        this.encoder = new RespEncoder();
        this.pendingCommands = new ArrayDeque<PendingCommand>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ProtocolHandler lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("debug.connected"));
        }
        handler.handleReady(this);
    }

    // RESP spec — streaming decode of RESP2 wire format
    @Override
    public void receive(ByteBuffer buf) {
        try {
            decoder.receive(buf);
            RespValue response;
            while ((response = decoder.next()) != null) {
                processResponse(response);
            }
        } catch (RespException e) {
            events().warn("err.protocol_error").thrown(e).emit();
            handler.onError(e);
            close();
        } catch (RuntimeException e) {
            // A well-formed RESP value of the wrong shape for the pending
            // command or Pub/Sub event (a simple string where an integer
            // was due, a short array) is read through accessors that throw
            // unchecked exceptions: treat it as a protocol error rather
            // than let it escape into the selector loop.
            RespException malformed = new RespException("Malformed server reply", e);
            events().warn("err.protocol_error").thrown(malformed).emit();
            handler.onError(malformed);
            close();
        }
    }

    @Override
    public void disconnected() {
        closed = true;
        releasePending();
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            String protocol = info.getProtocol();
            String cipher = info.getCipherSuite();
            String msg = MessageFormat.format(L10N.getString("debug.tls_handshake_complete"),
                protocol != null ? protocol : "unknown");
            LOGGER.fine(msg);
            if (cipher != null && LOGGER.isLoggable(Level.FINER)) {
                LOGGER.finer(MessageFormat.format(
                        L10N.getString("log.cipher_suite"), cipher));
            }
        }
    }

    @Override
    public void error(Exception cause) {
        handler.onError(cause);
    }

    // Hands the commands still awaiting a reply to the listener, which is
    // then responsible for telling their result handlers. Without a listener
    // nothing changes: they are left as they were.
    private void releasePending() {
        RedirectListener listener = redirectListener;
        if (listener == null || pendingCommands.isEmpty()) {
            return;
        }
        List<SentCommand> unanswered = new ArrayList<SentCommand>(pendingCommands.size());
        for (PendingCommand pending : pendingCommands) {
            if (pending.sent != null) {
                unanswered.add(pending.sent);
            }
        }
        pendingCommands.clear();
        if (!unanswered.isEmpty()) {
            listener.connectionLost(unanswered);
        }
    }

    /**
     * Installs, or with null removes, the listener offered every error
     * reply before the command's result handler.
     */
    void setRedirectListener(RedirectListener listener) {
        this.redirectListener = listener;
    }

    /**
     * Sets the session passed to result handlers in place of this
     * connection, so that follow-up commands go through its owner.
     */
    void setResultSession(RedisSession session) {
        this.resultSession = session != null ? session : this;
    }

    /**
     * Sends an already-built command on this connection, remembering that
     * very object (with its redirect count) while a listener is installed.
     */
    void send(SentCommand command) {
        if (endpoint == null || closed) {
            deliverError(command.getCallback(), L10N.getString("err.not_connected"), resultSession);
            return;
        }
        ByteBuffer buf;
        if (command.getBinaryArgs() != null) {
            buf = encoder.encodeCommand(command.getCommand(), command.getBinaryArgs());
        } else if (command.getTextArgs() != null) {
            buf = encoder.encodeCommand(command.getCommand(), command.getTextArgs());
        } else {
            buf = encoder.encodeCommand(command.getCommand());
        }
        pendingCommands.add(new PendingCommand(command.getCallback(),
                redirectListener != null ? command : null));
        endpoint.send(buf);
    }

    /** Test seam: attaches an endpoint without the connect handshake. */
    void attachForTest(Endpoint ep) {
        this.endpoint = ep;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection management
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("debug.closing_connection"));
        }
        if (endpoint != null) {
            endpoint.close();
        }
        decoder.reset();
        releasePending();
        pendingCommands.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response processing (RESP spec — "RESP protocol description")
    // ─────────────────────────────────────────────────────────────────────────

    // RESP spec — dispatch decoded response values.
    // Pub/Sub push messages are intercepted before FIFO callback dispatch.
    // RESP3 Push type (>) is handled as an out-of-band Pub/Sub delivery.
    private void processResponse(RespValue response) {
        // RESP3 Push type — server-initiated out-of-band data
        if (response.isPush()) {
            List<RespValue> pushData = response.asPush();
            if (pushData != null && !pushData.isEmpty() && messageHandler != null) {
                String type = pushData.get(0).asString();
                if (type != null) {
                    handlePubSubMessage(type, pushData);
                }
            }
            return;
        }
        // RESP2 array-based Pub/Sub messages
        if (pubSubMode && response.isArray()) {
            List<RespValue> array = response.asArray();
            if (array != null && !array.isEmpty()) {
                String type = array.get(0).asString();
                if (type != null) {
                    if (handlePubSubMessage(type, array)) {
                        return;
                    }
                }
            }
        }
        
        // Regular command response
        PendingCommand pending = pendingCommands.poll();
        if (pending == null) {
            events().warn("warn.response_no_pending").attr("response", String.valueOf(response)).emit();
            return;
        }
        dispatchResponse(pending, response);
    }

    // RESP spec — "Pub/Sub": push messages are 3-element arrays
    // [type, channel, message] for message/subscribe/unsubscribe,
    // or 4-element arrays [type, pattern, channel, message] for pmessage.
    private boolean handlePubSubMessage(String type, List<RespValue> array) {
        if (messageHandler == null) {
            return false;
        }
        if ("message".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            byte[] message = array.get(2).asBytes();
            messageHandler.handleMessage(channel, message);
            return true;
        }
        if ("pmessage".equals(type) && array.size() >= 4) {
            String pattern = array.get(1).asString();
            String channel = array.get(2).asString();
            byte[] message = array.get(3).asBytes();
            messageHandler.handlePatternMessage(pattern, channel, message);
            return true;
        }
        if ("subscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            ordinaryCount = count;
            messageHandler.handleSubscribed(channel, count);
            return true;
        }
        if ("psubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            ordinaryCount = count;
            messageHandler.handlePatternSubscribed(pattern, count);
            return true;
        }
        if ("unsubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            ordinaryCount = count;
            MessageHandler h = messageHandler;
            leaveModeIfIdle();
            h.handleUnsubscribed(channel, count);
            return true;
        }
        if ("punsubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            ordinaryCount = count;
            MessageHandler h = messageHandler;
            leaveModeIfIdle();
            h.handlePatternUnsubscribed(pattern, count);
            return true;
        }
        if ("smessage".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            byte[] message = array.get(2).asBytes();
            messageHandler.handleShardMessage(channel, message);
            return true;
        }
        if ("ssubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            shardCount = count;
            messageHandler.handleShardSubscribed(channel, count);
            return true;
        }
        if ("sunsubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            shardCount = count;
            MessageHandler h = messageHandler;
            leaveModeIfIdle();
            h.handleShardUnsubscribed(channel, count);
            return true;
        }

        return false;
    }

    // The connection leaves Pub/Sub mode once nothing is subscribed, of
    // either kind.
    private void leaveModeIfIdle() {
        if (ordinaryCount == 0 && shardCount == 0) {
            pubSubMode = false;
            messageHandler = null;
        }
    }

    // Dispatch based on RESP type prefix:
    // RESP2: + Simple String, - Error, : Integer, $ Bulk String, * Array
    // RESP3: % Map, ~ Set, , Double, # Boolean, _ Null, = Verbatim, ( Big Number
    private void dispatchResponse(PendingCommand pending, RespValue response) {
        Object callback = pending.callback;

        // Handle errors for all handler types. A listener sees the error even
        // when the command has no handler (the SUBSCRIBE family has none),
        // since it may be able to act on it, as on MOVED.
        if (response.isError()) {
            String error = response.getErrorMessage();
            RedirectListener listener = redirectListener;
            if (listener != null && pending.sent != null
                    && listener.handleError(pending.sent, error)) {
                return;
            }
            if (callback != null) {
                dispatchError(callback, error);
            }
            return;
        }
        if (callback == null) {
            return;
        }

        // ScanResultHandler — expects 2-element array [cursor, elements]
        if (callback instanceof ScanResultHandler) {
            ScanResultHandler h = (ScanResultHandler) callback;
            if (response.isNull()) {
                h.handleError("null response", resultSession);
            } else {
                List<RespValue> result = response.asArray();
                if (result != null && result.size() >= 2) {
                    String cursor = result.get(0).asString();
                    List<RespValue> elements = result.get(1).asArray();
                    h.handleResult(cursor, elements, resultSession);
                } else {
                    h.handleError("Invalid scan response", resultSession);
                }
            }
            return;
        }

        // Dispatch based on handler type
        if (callback instanceof StringResultHandler) {
            StringResultHandler h = (StringResultHandler) callback;
            String result = response.asString();
            h.handleResult(result, resultSession);
        } else if (callback instanceof BulkResultHandler) {
            BulkResultHandler h = (BulkResultHandler) callback;
            if (response.isNull()) {
                h.handleNull(resultSession);
            } else {
                byte[] result = response.asBytes();
                h.handleResult(result, resultSession);
            }
        } else if (callback instanceof IntegerResultHandler) {
            IntegerResultHandler h = (IntegerResultHandler) callback;
            long result = response.asLong();
            h.handleResult(result, resultSession);
        } else if (callback instanceof BooleanResultHandler) {
            BooleanResultHandler h = (BooleanResultHandler) callback;
            boolean result = response.asLong() != 0;
            h.handleResult(result, resultSession);
        } else if (callback instanceof ArrayResultHandler) {
            ArrayResultHandler h = (ArrayResultHandler) callback;
            if (response.isNull()) {
                h.handleNull(resultSession);
            } else if (response.isArray()) {
                List<RespValue> result = response.asArray();
                h.handleResult(result, resultSession);
            } else if (response.isMap()) {
                // RESP3 Map — flatten to alternating key/value array for compatibility
                Map<RespValue, RespValue> map = response.asMap();
                List<RespValue> flat = new ArrayList<RespValue>(map.size() * 2);
                for (Map.Entry<RespValue, RespValue> entry : map.entrySet()) {
                    flat.add(entry.getKey());
                    flat.add(entry.getValue());
                }
                h.handleResult(flat, resultSession);
            } else {
                // For other RESP3 types received by ArrayResultHandler,
                // wrap in a single-element array
                List<RespValue> wrapped = new ArrayList<RespValue>(1);
                wrapped.add(response);
                h.handleResult(wrapped, resultSession);
            }
        }
    }

    private void dispatchError(Object callback, String error) {
        deliverError(callback, error, resultSession);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command sending (RESP spec — "Sending commands to a Redis server")
    // Commands are encoded as RESP arrays of bulk strings: *N\r\n$len\r\narg\r\n...
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    void sendCommand(Object callback, String command) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback, remember(callback, command, null, null)));
        ByteBuffer buf = encoder.encodeCommand(command);
        endpoint.send(buf);
    }

    @Override
    void sendCommand(Object callback, String command, String[] args) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback, remember(callback, command, args, null)));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        endpoint.send(buf);
    }

    @Override
    void sendCommand(Object callback, String command, byte[][] args) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback, remember(callback, command, null, args)));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        endpoint.send(buf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — commands that change this connection's own state.
    // All other commands are in AbstractRedisSession.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void quit() {
        sendCommand(null, "QUIT");
        close();
    }

    // Redis command reference — RESET (Redis 6.2+)
    // Resets connection state: Pub/Sub, MULTI, WATCH, auth
    @Override
    public void reset(StringResultHandler h) {
        pubSubMode = false;
        messageHandler = null;
        ordinaryCount = 0;
        shardCount = 0;
        sendCommand(h, "RESET");
    }

    @Override
    public void subscribe(MessageHandler h, String... channels) {
        this.messageHandler = h;
        this.pubSubMode = true;
        sendCommand(null, "SUBSCRIBE", channels);
    }

    @Override
    public void ssubscribe(MessageHandler h, String... channels) {
        this.messageHandler = h;
        this.pubSubMode = true;
        // until the server's acknowledgement gives the real count, count it
        // as one so an ordinary unsubscribe to zero does not end the mode
        this.shardCount = Math.max(shardCount, 1);
        sendCommand(null, "SSUBSCRIBE", channels);
    }

    @Override
    public void psubscribe(MessageHandler h, String... patterns) {
        this.messageHandler = h;
        this.pubSubMode = true;
        sendCommand(null, "PSUBSCRIBE", patterns);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tracks a pending command waiting for a response.
     */
    private static class PendingCommand {
        final Object callback;
        // the command as sent; null unless a RedirectListener was installed
        final SentCommand sent;

        PendingCommand(Object callback, SentCommand sent) {
            this.callback = callback;
            this.sent = sent;
        }
    }

    private SentCommand remember(Object callback, String command,
                                 String[] textArgs, byte[][] binaryArgs) {
        if (redirectListener == null) {
            return null;
        }
        return new SentCommand(callback, command, textArgs, binaryArgs);
    }

}
