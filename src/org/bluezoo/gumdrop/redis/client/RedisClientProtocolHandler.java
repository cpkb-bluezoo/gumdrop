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
import java.util.LinkedHashMap;
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

    // Receives the pushes that are not Pub/Sub deliveries. May be null.
    private PushHandler pushHandler;

    /** The most commands allowed to await a reply on one connection, unless configured. */
    static final int DEFAULT_MAX_PENDING = 65536;

    /** Source of the monotonic time deadlines are measured in; replaced in tests. */
    interface Ticker {
        long nanos();
    }

    private Ticker ticker = new Ticker() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };

    // Applied to commands that name no timeout of their own. 0: none.
    private long defaultTimeoutMs;
    // 0: unlimited
    private int maxPending = DEFAULT_MAX_PENDING;
    // One timer serves every deadline: it is set for the earliest.
    private org.bluezoo.gumdrop.TimerHandle timer;
    private long timerDueNanos;

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
        cancelTimer();
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
            if (pending.sent != null && !pending.timedOut) {
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
    @Override
    public void setPushHandler(PushHandler handler) {
        this.pushHandler = handler;
    }

    /**
     * Sets how long a command may wait for its reply when it names no
     * timeout of its own. On expiry its handler gets a {@code TIMEOUT} error
     * and a reply that comes later is discarded. Commands that wait on the
     * server on purpose (blocking pops, blocking stream reads) are given
     * their own wait on top of it.
     *
     * @param millis the timeout; 0 for none (the default)
     */
    void setCommandTimeoutMillis(long millis) {
        this.defaultTimeoutMs = Math.max(0, millis);
    }

    /**
     * Sets the most commands that may await a reply at once. A command sent
     * beyond that fails at once with an {@code OVERLOAD} error, instead of
     * queueing without limit. Commands with no handler, and Pub/Sub
     * commands, are not counted against it.
     *
     * @param max the cap; 0 for none
     */
    void setMaxPending(int max) {
        this.maxPending = Math.max(0, max);
    }

    /** Test seam: the clock deadlines are measured against. */
    void setTicker(Ticker ticker) {
        this.ticker = ticker;
    }

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
        transmit(command.getCallback(), redirectListener != null ? command : null,
                command.getCommand(), command.getTextArgs(), command.getBinaryArgs(),
                command.getTimeoutMs());
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
        cancelTimer();
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
            if (pushData != null && !pushData.isEmpty()) {
                String type = pushData.get(0).asString();
                if (type != null && !handlePubSubMessage(type, pushData)) {
                    deliverPush(type, pushData);
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
        if (pending.timedOut) {
            // its handler has been told it timed out
            return;
        }
        dispatchResponse(pending, response);
    }

    // A push nothing else claimed goes to the push handler, if there is one.
    private void deliverPush(String kind, List<RespValue> push) {
        PushHandler h = pushHandler;
        if (h == null) {
            return;
        }
        List<Object> payload = new ArrayList<Object>(push.size() - 1);
        for (int i = 1; i < push.size(); i++) {
            payload.add(toJava(push.get(i)));
        }
        h.onPush(kind, payload);
    }

    // Plain Java values for a push payload, as PushHandler documents.
    private static Object toJava(RespValue value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isInteger()) {
            return Long.valueOf(value.asLong());
        } else if (value.isDouble()) {
            return Double.valueOf(value.asDouble());
        } else if (value.isBoolean()) {
            return Boolean.valueOf(value.asBoolean());
        } else if (value.isArray() || value.isSet() || value.isPush()) {
            List<RespValue> items = value.asArray();
            List<Object> out = new ArrayList<Object>(items.size());
            for (RespValue item : items) {
                out.add(toJava(item));
            }
            return out;
        } else if (value.isMap()) {
            Map<Object, Object> out = new LinkedHashMap<Object, Object>();
            for (Map.Entry<RespValue, RespValue> e : value.asMap().entrySet()) {
                out.put(toJava(e.getKey()), toJava(e.getValue()));
            }
            return out;
        }
        return value.asString();
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
            settle("subscribe");
            messageHandler.handleSubscribed(channel, count);
            return true;
        }
        if ("psubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            ordinaryCount = count;
            settle("psubscribe");
            messageHandler.handlePatternSubscribed(pattern, count);
            return true;
        }
        if ("unsubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            settle("unsubscribe");
            ordinaryCount = count;
            MessageHandler h = messageHandler;
            leaveModeIfIdle();
            h.handleUnsubscribed(channel, count);
            return true;
        }
        if ("punsubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            settle("punsubscribe");
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
            settle("ssubscribe");
            messageHandler.handleShardSubscribed(channel, count);
            return true;
        }
        if ("sunsubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            settle("sunsubscribe");
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
        transmit(callback, remember(callback, command, null, null), command, null, null,
                CommandTimeouts.DEFAULT);
    }

    @Override
    void sendCommand(Object callback, String command, String[] args) {
        transmit(callback, remember(callback, command, args, null), command, args, null,
                CommandTimeouts.DEFAULT);
    }

    @Override
    void sendCommand(Object callback, String command, byte[][] args) {
        transmit(callback, remember(callback, command, null, args), command, null, args,
                CommandTimeouts.DEFAULT);
    }

    @Override
    void sendCommand(Object callback, long timeoutMs, String command, String[] args) {
        transmit(callback, remember(callback, command, args, null), command, args, null, timeoutMs);
    }

    @Override
    void sendCommand(Object callback, long timeoutMs, String command, byte[][] args) {
        transmit(callback, remember(callback, command, null, args), command, null, args, timeoutMs);
    }

    // Queues a command as awaiting its reply, and sends it. Fails it at once
    // if the connection is not up, or if too many commands are already
    // awaiting a reply to take another.
    private void transmit(Object callback, SentCommand sent, String command,
                          String[] text, byte[][] binary, long timeoutMs) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        int argCount = text != null ? text.length : binary != null ? binary.length : 0;
        PendingCommand pending = pending(callback, sent, command, argCount);
        // control commands and subscriptions are few and have no handler
        // to tell, so only commands with a handler are refused
        if (maxPending > 0 && callback != null && pending.subscriptionKind == null
                && pendingCommands.size() >= maxPending) {
            dispatchError(callback, MessageFormat.format(
                    L10N.getString("err.too_many_pending"), Integer.valueOf(maxPending)));
            return;
        }
        pending.deadlineNanos = deadlineFor(pending, command, text, binary, timeoutMs);
        ByteBuffer buf;
        if (binary != null) {
            buf = encoder.encodeCommand(command, binary);
        } else if (text != null) {
            buf = encoder.encodeCommand(command, text);
        } else {
            buf = encoder.encodeCommand(command);
        }
        pendingCommands.add(pending);
        endpoint.send(buf);
        if (pending.deadlineNanos != 0) {
            armFor(pending.deadlineNanos);
        }
    }

    // ── timeouts ──

    // The moment a command's reply is overdue, or 0 for never.
    private long deadlineFor(PendingCommand pending, String command, String[] text,
                             byte[][] binary, long timeoutMs) {
        if (pending.subscriptionKind != null || pending.callback == null) {
            return 0;
        }
        long ms;
        if (timeoutMs >= 0) {
            ms = timeoutMs;
        } else if (defaultTimeoutMs == 0) {
            return 0;
        } else {
            long blocking = CommandTimeouts.blockingMillis(command, text, binary);
            if (blocking < 0) {
                ms = defaultTimeoutMs;
            } else if (blocking == 0) {
                return 0;
            } else {
                ms = defaultTimeoutMs + blocking;
            }
        }
        if (ms == 0) {
            return 0;
        }
        pending.timeoutMs = ms;
        long due = ticker.nanos() + ms * 1000000L;
        return due == 0 ? 1 : due;
    }

    // Makes sure the timer will fire no later than the given moment.
    private void armFor(long dueNanos) {
        if (timer != null && timerDueNanos - dueNanos <= 0) {
            return;
        }
        if (timer != null) {
            timer.cancel();
        }
        long delayMs = Math.max(1, (dueNanos - ticker.nanos() + 999999L) / 1000000L);
        timerDueNanos = dueNanos;
        timer = endpoint.scheduleTimer(delayMs, new Runnable() {
            @Override
            public void run() {
                expire();
            }
        });
        if (timer == null) {
            // an endpoint with no timers: nothing will fire
            timerDueNanos = 0;
        }
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    // The timer has fired: fails every command now overdue, then sets the
    // timer for the next deadline. A failed command keeps its place in the
    // queue as the server will still answer it, in order; the answer is
    // discarded when it comes.
    private void expire() {
        timer = null;
        if (closed) {
            return;
        }
        long now = ticker.nanos();
        List<PendingCommand> overdue = new ArrayList<PendingCommand>();
        long next = 0;
        for (PendingCommand pending : pendingCommands) {
            if (pending.timedOut || pending.deadlineNanos == 0) {
                continue;
            }
            if (pending.deadlineNanos - now <= 0) {
                pending.timedOut = true;
                overdue.add(pending);
            } else if (next == 0 || pending.deadlineNanos - next < 0) {
                next = pending.deadlineNanos;
            }
        }
        if (next != 0) {
            armFor(next);
        }
        // handlers may send more commands, so only now
        for (PendingCommand pending : overdue) {
            dispatchError(pending.callback, MessageFormat.format(
                    L10N.getString("err.command_timeout"), Long.valueOf(pending.timeoutMs)));
        }
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
        // For a subscription command: its kind (subscribe, psubscribe, ...)
        // and how many confirmations are still due. The server answers it
        // with one confirmation per channel rather than with a reply, and
        // the command leaves the queue when the last one has arrived.
        final String subscriptionKind;
        int confirmationsDue;
        // When its reply is overdue (a Ticker time); 0 for never. Once it
        // is, the handler has been told and timedOut is set: the reply
        // still due is read and thrown away.
        long deadlineNanos;
        long timeoutMs;
        boolean timedOut;

        PendingCommand(Object callback, SentCommand sent, String subscriptionKind,
                       int confirmationsDue) {
            this.callback = callback;
            this.sent = sent;
            this.subscriptionKind = subscriptionKind;
            this.confirmationsDue = confirmationsDue;
        }
    }

    private static final String[] SUBSCRIPTION_KINDS = {
        "subscribe", "psubscribe", "ssubscribe", "unsubscribe", "punsubscribe", "sunsubscribe"
    };

    // The kind of subscription command, lower case, or null for any other.
    private static String subscriptionKind(String command) {
        for (String kind : SUBSCRIPTION_KINDS) {
            if (kind.equalsIgnoreCase(command)) {
                return kind;
            }
        }
        return null;
    }

    private PendingCommand pending(Object callback, SentCommand sent, String command, int argCount) {
        String kind = subscriptionKind(command);
        return new PendingCommand(callback, sent, kind, kind != null ? Math.max(1, argCount) : 0);
    }

    // One confirmation of a subscription command has arrived: when it was
    // the last due, the command is no longer awaiting a reply. Confirmations
    // the server volunteers (a slot moved away) find some other command at
    // the head of the queue, and are ignored here.
    private void settle(String kind) {
        PendingCommand head = pendingCommands.peek();
        if (head != null && kind.equals(head.subscriptionKind)) {
            if (--head.confirmationsDue <= 0) {
                pendingCommands.poll();
            }
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
