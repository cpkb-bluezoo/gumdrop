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
import java.util.Comparator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespType;
import org.bluezoo.gumdrop.redis.codec.RespEncoder;
import org.bluezoo.gumdrop.redis.codec.RespException;
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
    private final ReplyRouter router = new ReplyRouter();

    // Reads Pub/Sub deliveries and pushes, which share the shape of replies.
    private final PubSubReader reader = new PubSubReader(new PubSubReader.Host() {
        @Override
        public MessageHandler messageHandler() {
            return messageHandler;
        }

        @Override
        public PushHandler pushHandler() {
            return pushHandler;
        }

        @Override
        public int maxField() {
            return maxField;
        }

        @Override
        public ReplyStream stream() {
            return pubStream;
        }

        @Override
        public RespEventHandler becomeReply() {
            router.divert();
            return router;
        }

        @Override
        public void acknowledged(String kind, String name, int count) {
            subscriptionAcknowledged(kind, name, count);
        }
    });

    // The handle on a delivery being read.
    private final ReplyStream pubStream = new ReplyStream() {
        @Override
        public void resume() {
            Endpoint ep = endpoint;
            if (ep != null) {
                ep.execute(new Runnable() {
                    @Override
                    public void run() {
                        resumeReading();
                    }
                });
            }
        }

        @Override
        public void cancel() {
            reader.requestCancel();
            resume();
        }
    };

    // The longest identifier (key, field name, cursor) or scalar read whole.
    private int maxField = DEFAULT_MAX_FIELD;

    // Flow control. While a handler has paused its reply, reading is
    // stopped and what has already arrived is held here (at most one
    // receive buffer's worth) until it resumes; null when not paused.
    private ByteBuffer held;
    private boolean receiving;
    private boolean resumeRequested;

    // The command whose reply is being read: it has left the queue, but
    // the server is still sending its answer.
    private PendingCommand inFlight;
    private final RespEncoder encoder;

    private Endpoint endpoint;

    // Writes the requests in order, a block at a time for one with a
    // streamed argument.
    private RequestWriter writer;
    // the last streamed command queued: commands sent behind it start
    // their deadlines when it has been written, not when it was queued
    private RequestWriter.Job lastStreamed;

    // Pending command callbacks (FIFO)
    private final Deque<PendingCommand> pendingCommands;

    // The pending commands that have a deadline and have not yet timed out,
    // earliest first (ties in the order sent). Deadlines are not in queue
    // order, as a command can name its own timeout and a blocking command
    // waits longer, so the queue itself cannot say which is due next. A
    // command leaves this set when it is answered or when it times out.
    private final TreeSet<PendingCommand> deadlines = new TreeSet<PendingCommand>(
            new Comparator<PendingCommand>() {
                @Override
                public int compare(PendingCommand a, PendingCommand b) {
                    long d = a.deadlineNanos - b.deadlineNanos;
                    if (d != 0) {
                        return d < 0 ? -1 : 1;
                    }
                    return a.sequence < b.sequence ? -1 : a.sequence == b.sequence ? 0 : 1;
                }
            });
    private long nextSequence;
    // Test seam: how many pending commands expire() has examined in all.
    private long expireExamined;

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

    /** The longest identifier or scalar delivered whole, unless configured. */
    static final int DEFAULT_MAX_FIELD = 64 * 1024;

    /** The longest header or simple-string line read, unless configured. */
    static final int DEFAULT_MAX_LINE = RespDecoder.DEFAULT_MAX_LINE;

    /** The most bytes of one reply that are discarded, unless configured. */
    static final long DEFAULT_MAX_SKIP = RespDecoder.DEFAULT_MAX_SKIP;

    /**
     * The smallest line limit that can be set: a header line is a type
     * byte, a 64-bit length and a CRLF, and an error line must be able to
     * say what went wrong.
     */
    static final int MIN_MAX_LINE = 64;


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

    // Health check. When something is waiting on the server and nothing has
    // been heard from it for healthIntervalMs, PING; when nothing is heard
    // for another interval, the connection is dead. 0: no check.
    private long healthIntervalMs;
    private org.bluezoo.gumdrop.TimerHandle healthTimer;
    // when the server was last heard from, or when something started to wait
    private long quietSinceNanos;
    private boolean pingOutstanding;
    private boolean disconnectedCalled;

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
        this.writer = new RequestWriter(ep, writerOwner);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("debug.connected"));
        }
        handler.handleReady(this);
    }

    // RESP spec — streaming decode of RESP2 wire format
    @Override
    public void receive(ByteBuffer buf) {
        quietSinceNanos = ticker.nanos();
        if (held != null) {
            // reading is paused, but what was already on its way arrives:
            // keep it, bounded by what one read delivers
            hold(buf);
            return;
        }
        feed(buf);
    }

    // Decodes the bytes. If a handler pauses its reply the rest is held,
    // reading is stopped and the clocks that would run against the server
    // are suspended until the handler resumes.
    private void feed(ByteBuffer buf) {
        boolean paused = false;
        receiving = true;
        try {
            do {
                resumeRequested = false;
                paused = decoder.receive(buf, router);
            } while (paused && resumeRequested);
        } catch (RespException e) {
            events().warn("err.protocol_error").thrown(e).emit();
            handler.onError(e);
            close();
            return;
        } catch (RuntimeException e) {
            // A handler or the Pub/Sub code threw, or a well-formed value
            // was of a shape that was not expected: treat it as a protocol
            // error rather than let it escape into the selector loop.
            RespException malformed = new RespException("Malformed server reply", e);
            events().warn("err.protocol_error").thrown(malformed).emit();
            handler.onError(malformed);
            close();
            return;
        } finally {
            receiving = false;
        }
        if (paused && !closed) {
            pauseReading(buf);
        }
    }

    private void pauseReading(ByteBuffer rest) {
        held = ByteBuffer.allocate(rest.remaining());
        held.put(rest);
        held.flip();
        suspendInFlight();
        endpoint.pauseRead();
    }

    private void hold(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return;
        }
        ByteBuffer joined = ByteBuffer.allocate(held.remaining() + buf.remaining());
        joined.put(held);
        joined.put(buf);
        joined.flip();
        held = joined;
    }

    // Stops the clocks that run against the server while the consumer, not
    // the server, is what the reply waits for.
    private void suspendInFlight() {
        PendingCommand p = inFlight;
        if (p != null && p.deadlineNanos != 0) {
            deadlines.remove(p);
            p.suspended = true;
        }
    }

    // The consumer is ready for more: the clocks start again from now, what
    // was held is decoded, and if the handler does not pause again, reading
    // resumes. Runs on the connection's event loop.
    private void resumeReading() {
        if (receiving) {
            // asked for from inside the event that paused: the decoder
            // carries on instead of stopping
            resumeRequested = true;
            return;
        }
        if (held == null || closed) {
            return;
        }
        long now = ticker.nanos();
        quietSinceNanos = now;
        PendingCommand p = inFlight;
        if (p != null && p.suspended) {
            p.suspended = false;
            p.lastActivityNanos = now;
            p.deadlineNanos = now + p.timeoutMs * 1000000L;
            if (p.deadlineNanos == 0) {
                p.deadlineNanos = 1;
            }
            deadlines.add(p);
            armFor(p.deadlineNanos);
        }
        ByteBuffer pending = held;
        held = null;
        feed(pending);
        if (held == null && !closed) {
            endpoint.resumeRead();
            armHealth();
        }
    }

    @Override
    public void disconnected() {
        closed = true;
        cancelTimer();
        if (writer != null) {
            writer.abort();
        }
        failInFlight();
        releasePending();
        if (!disconnectedCalled) {
            disconnectedCalled = true;
            handler.onDisconnected();
        }
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
        if (pendingCommands.isEmpty()) {
            return;
        }
        if (listener == null) {
            // nobody else will tell them
            failPending(L10N.getString("err.not_connected"));
            return;
        }
        List<SentCommand> unanswered = new ArrayList<SentCommand>(pendingCommands.size());
        for (PendingCommand pending : pendingCommands) {
            if (pending.sent != null && !pending.timedOut && !pending.internal) {
                unanswered.add(pending.sent);
            }
        }
        pendingCommands.clear();
        deadlines.clear();
        if (!unanswered.isEmpty()) {
            listener.connectionLost(unanswered);
        }
    }

    // Tells the handlers of the commands still waiting that they failed, and
    // forgets the commands. Those already told of a timeout are not told again.
    private void failPending(String error) {
        List<PendingCommand> waiting = new ArrayList<PendingCommand>(pendingCommands);
        pendingCommands.clear();
        deadlines.clear();
        for (PendingCommand pending : waiting) {
            if (!pending.timedOut && !pending.internal && pending.callback != null) {
                dispatchError(pending.callback, error);
            }
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

    /**
     * Sets the health check interval: while commands are waiting for a reply
     * (or the connection is subscribed) and nothing has been heard from the
     * server for this long, a {@code PING} is sent, and if nothing is heard
     * for another interval the connection is closed and what was waiting is
     * failed. A connection whose oldest waiting command is one that blocks
     * on the server on purpose is not checked.
     *
     * @param millis the interval; 0 for no check (the default)
     */
    void setHealthCheckMillis(long millis) {
        this.healthIntervalMs = Math.max(0, millis);
    }

    /** Test seam: how many pending commands expire() has examined in all. */
    long expireExamined() {
        return expireExamined;
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
        if (command.getStreamed() != null) {
            transmitStreamed(command.getCallback(), redirectListener != null ? command : null,
                    command.getTimeoutMs(), command.getCommand(), command.getBinaryArgs(),
                    command.getStreamedIndex(), command.getStreamed());
            return;
        }
        transmit(command.getCallback(), redirectListener != null ? command : null,
                command.getCommand(), command.getTextArgs(), command.getBinaryArgs(),
                command.getTimeoutMs());
    }

    /**
     * Sets the longest identifier (a key, a field name, a cursor) or scalar
     * that is read whole. A longer one fails the reply it is in, and the
     * connection is not affected.
     *
     * @param max the limit in bytes, at least 1
     */
    void setMaxField(int max) {
        this.maxField = Math.max(1, max);
    }

    /**
     * Sets the longest header or simple-string line (an error line, a
     * length, a status) that is read. A longer one is a protocol error and
     * closes the connection.
     *
     * @param max the limit in bytes, at least {@link #MIN_MAX_LINE}
     */
    void setMaxLine(int max) {
        decoder.setMaxLine(max);
    }

    /**
     * Sets the most bytes of one reply that are read only to be discarded:
     * what a handler skipped, the reply to a command that timed out, and
     * attributes. Past it the connection is closed.
     *
     * @param max the limit in bytes, at least 1
     */
    void setMaxSkip(long max) {
        decoder.setMaxSkip(max);
    }

    /** Test seam: attaches an endpoint without the connect handshake. */
    void attachForTest(Endpoint ep) {
        this.endpoint = ep;
        this.writer = new RequestWriter(ep, writerOwner);
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
        held = null;
        if (writer != null) {
            writer.abort();
        }
        failInFlight();
        releasePending();
        pendingCommands.clear();
        deadlines.clear();
    }

    // A reply that was being streamed when the connection went is not
    // repeated, as the handler has already been given part of it.
    private void failInFlight() {
        PendingCommand p = inFlight;
        if (p == null) {
            return;
        }
        inFlight = null;
        deadlines.remove(p);
        if (!p.timedOut && !p.internal) {
            String error = L10N.getString("err.connection_lost_mid_reply");
            ReplyAdapter adapter = router.adapter;
            if (adapter != null) {
                adapter.abort(error);
            } else if (p.callback != null) {
                dispatchError(p.callback, error);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response processing (RESP spec — "RESP protocol description")
    // ─────────────────────────────────────────────────────────────────────────

    // A subscription was confirmed or ended: the counts are the server's,
    // and the handler is told.
    private void subscriptionAcknowledged(String kind, String name, int count) {
        MessageHandler h = messageHandler;
        if (h == null) {
            return;
        }
        if ("subscribe".equals(kind)) {
            ordinaryCount = count;
            settle("subscribe");
            h.handleSubscribed(name, count);
        } else if ("psubscribe".equals(kind)) {
            ordinaryCount = count;
            settle("psubscribe");
            h.handlePatternSubscribed(name, count);
        } else if ("unsubscribe".equals(kind)) {
            settle("unsubscribe");
            ordinaryCount = count;
            leaveModeIfIdle();
            h.handleUnsubscribed(name, count);
        } else if ("punsubscribe".equals(kind)) {
            settle("punsubscribe");
            ordinaryCount = count;
            leaveModeIfIdle();
            h.handlePatternUnsubscribed(name, count);
        } else if ("ssubscribe".equals(kind)) {
            shardCount = count;
            settle("ssubscribe");
            h.handleShardSubscribed(name, count);
        } else if ("sunsubscribe".equals(kind)) {
            settle("sunsubscribe");
            shardCount = count;
            leaveModeIfIdle();
            h.handleShardUnsubscribed(name, count);
        }
    }

    // The connection leaves Pub/Sub mode once nothing is subscribed, of
    // either kind.
    private void leaveModeIfIdle() {
        if (ordinaryCount == 0 && shardCount == 0) {
            pubSubMode = false;
            messageHandler = null;
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
        CommandInfo info = sent != null ? sent.info() : CommandInfo.of(command);
        PendingCommand pending = pending(callback, sent, info, argCount);
        // control commands and subscriptions are few and have no handler
        // to tell, so only commands with a handler are refused
        if (maxPending > 0 && callback != null && pending.subscriptionKind == null
                && pendingCommands.size() >= maxPending) {
            dispatchError(callback, MessageFormat.format(
                    L10N.getString("err.too_many_pending"), Integer.valueOf(maxPending)));
            return;
        }
        long blocking = CommandTimeouts.blockingMillis(info, text, binary);
        pending.blocking = blocking >= 0;
        pending.deadlineNanos = deadlineFor(pending, blocking, timeoutMs);
        if (lastStreamed != null && pending.deadlineNanos != 0) {
            // queued behind a streamed command: the clock starts when the
            // server can have seen it
            pending.deadlineNanos = 0;
            pending.deferredBlocking = blocking;
            pending.deferredTimeoutMs = timeoutMs;
            lastStreamed.after.add(pending);
        }
        ByteBuffer buf;
        if (binary != null) {
            buf = encoder.encodeCommand(command, binary);
        } else if (text != null) {
            buf = encoder.encodeCommand(command, text);
        } else {
            buf = encoder.encodeCommand(command);
        }
        if (pendingCommands.isEmpty()) {
            // from now on the server owes us an answer
            quietSinceNanos = ticker.nanos();
        }
        pending.sequence = nextSequence++;
        pendingCommands.add(pending);
        writer.send(buf);
        if (pending.deadlineNanos != 0) {
            deadlines.add(pending);
            armFor(pending.deadlineNanos);
        }
        armHealth();
    }

    // ── requests with a streamed argument ──

    private final RequestWriter.Owner writerOwner = new RequestWriter.Owner() {
        @Override
        public void streamBroken(RequestWriter.Job job, String error) {
            PendingCommand p = (PendingCommand) job.owner;
            // the command has failed in its own way; what is behind it
            // fails with the connection
            p.timedOut = true;
            deadlines.remove(p);
            if (p.callback != null) {
                dispatchError(p.callback, MessageFormat.format(
                        L10N.getString("err.argument_failed"), error));
            }
            events().warn("warn.argument_failed").attr("error", error).emit();
            close();
            disconnected();
        }

        @Override
        public void streamWritten(RequestWriter.Job job) {
            if (lastStreamed == job) {
                lastStreamed = null;
            }
            quietSinceNanos = ticker.nanos();
            startDeadline((PendingCommand) job.owner);
            for (Object o : job.after) {
                startDeadline((PendingCommand) o);
            }
            armHealth();
        }
    };

    // The server can now have all of the command: its clock starts.
    private void startDeadline(PendingCommand p) {
        if (p.timedOut || closed || p.deadlineNanos != 0) {
            return;
        }
        p.deadlineNanos = deadlineFor(p, p.deferredBlocking, p.deferredTimeoutMs);
        if (p.deadlineNanos != 0) {
            deadlines.add(p);
            armFor(p.deadlineNanos);
        }
    }

    @Override
    void sendStreamed(Object callback, long timeoutMs, String command, byte[][] args,
                      int index, ArgumentSource source) {
        transmitStreamed(callback, null, timeoutMs, command, args, index, source);
    }

    private void transmitStreamed(Object callback, SentCommand sent, long timeoutMs,
                                  String command, byte[][] args, int index,
                                  ArgumentSource source) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        CommandInfo info = sent != null ? sent.info() : CommandInfo.of(command);
        PendingCommand pending = pending(callback, sent, info, args.length);
        if (maxPending > 0 && callback != null && pending.subscriptionKind == null
                && pendingCommands.size() >= maxPending) {
            dispatchError(callback, MessageFormat.format(
                    L10N.getString("err.too_many_pending"), Integer.valueOf(maxPending)));
            return;
        }
        long blocking = CommandTimeouts.blockingMillis(info, null, args);
        pending.blocking = blocking >= 0;
        // opened now, so that a source that cannot be sent again fails
        // here, before anything is queued or written
        RequestWriter.Job[] holder = new RequestWriter.Job[1];
        ArgumentStream stream = source.open(writer.sinkFor(holder));
        if (stream == null) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_repeatable"));
            }
            return;
        }
        long length = source.length();
        RequestWriter.Job job = new RequestWriter.Job(
                encoder.encodeStreamedHead(command, args, index, length),
                encoder.encodeStreamedTail(args, index), stream, length, pending);
        holder[0] = job;
        // the clock starts when the whole command has been written
        pending.deferredBlocking = blocking;
        pending.deferredTimeoutMs = timeoutMs;
        if (lastStreamed != null) {
            lastStreamed.after.add(pending);
        }
        lastStreamed = job;
        if (pendingCommands.isEmpty()) {
            quietSinceNanos = ticker.nanos();
        }
        pending.sequence = nextSequence++;
        pendingCommands.add(pending);
        writer.send(job);
    }

    // ── health check ──

    // Whether the server owes us something we should hear about: a reply to
    // a command (unless the oldest waiting command blocks on purpose), or a
    // subscription's traffic.
    private boolean watching() {
        if (writer != null && writer.isBusy()) {
            // it is the client that is sending
            return false;
        }
        if (held != null) {
            // paused: it is the consumer that is being waited for
            return false;
        }
        if (inFlight != null || pingOutstanding || pubSubMode) {
            return true;
        }
        PendingCommand head = pendingCommands.peek();
        return head != null && !head.blocking && !head.internal;
    }

    private void armHealth() {
        if (healthIntervalMs <= 0 || closed || endpoint == null || healthTimer != null
                || !watching()) {
            return;
        }
        long dueNanos = quietSinceNanos + healthIntervalMs * 1000000L;
        scheduleHealth(Math.max(1, (dueNanos - ticker.nanos() + 999999L) / 1000000L));
    }

    private void scheduleHealth(long delayMs) {
        healthTimer = endpoint.scheduleTimer(delayMs, new Runnable() {
            @Override
            public void run() {
                checkHealth();
            }
        });
    }

    private void checkHealth() {
        healthTimer = null;
        if (closed) {
            return;
        }
        if (!watching()) {
            // re-armed by the next command or subscription
            return;
        }
        long quietMs = (ticker.nanos() - quietSinceNanos) / 1000000L;
        if (quietMs < healthIntervalMs) {
            // heard from the server since: look again when the interval is up
            scheduleHealth(Math.max(1, healthIntervalMs - quietMs));
            return;
        }
        if (pingOutstanding) {
            healthFailed();
            return;
        }
        PendingCommand ping = pending(null, null, CommandInfo.of("PING"), 0);
        ping.internal = true;
        pendingCommands.add(ping);
        pingOutstanding = true;
        writer.send(encoder.encodeCommand("PING"));
        scheduleHealth(healthIntervalMs);
    }

    // The server has not answered a PING within an interval: it is not
    // going to. The connection is closed and what was waiting is failed, so
    // that callers can fail over and the owner can reconnect.
    private void healthFailed() {
        RedirectListener listener = redirectListener;
        if (listener != null) {
            listener.healthCheckFailed();
        }
        close();
        disconnected();
    }

    // ── timeouts ──

    // The moment a command's reply is overdue, or 0 for never.
    private long deadlineFor(PendingCommand pending, long blocking, long timeoutMs) {
        if (pending.subscriptionKind != null || pending.callback == null) {
            return 0;
        }
        long ms;
        if (timeoutMs >= 0) {
            ms = timeoutMs;
        } else if (defaultTimeoutMs == 0) {
            return 0;
        } else {
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
        if (healthTimer != null) {
            healthTimer.cancel();
            healthTimer = null;
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
        while (!deadlines.isEmpty()) {
            PendingCommand first = deadlines.first();
            expireExamined++;
            if (first.deadlineNanos - now <= 0) {
                deadlines.pollFirst();
                if (first.streaming) {
                    // a reply being read: it is the silence that counts,
                    // not the time it takes
                    long idleDue = first.lastActivityNanos + first.timeoutMs * 1000000L;
                    if (idleDue - now > 0) {
                        first.deadlineNanos = idleDue;
                        deadlines.add(first);
                        continue;
                    }
                }
                first.timedOut = true;
                overdue.add(first);
            } else {
                armFor(first.deadlineNanos);
                break;
            }
        }
        // handlers may send more commands, so only now
        for (PendingCommand pending : overdue) {
            String error = MessageFormat.format(
                    L10N.getString("err.command_timeout"), Long.valueOf(pending.timeoutMs));
            if (pending == inFlight && router.adapter != null) {
                // part of its reply has been delivered: the handler is
                // completed through its adapter, and the rest discarded
                router.adapter.abort(error);
            } else {
                dispatchError(pending.callback, error);
            }
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
        armHealth();
    }

    @Override
    public void ssubscribe(MessageHandler h, String... channels) {
        this.messageHandler = h;
        this.pubSubMode = true;
        // until the server's acknowledgement gives the real count, count it
        // as one so an ordinary unsubscribe to zero does not end the mode
        this.shardCount = Math.max(shardCount, 1);
        sendCommand(null, "SSUBSCRIBE", channels);
        armHealth();
    }

    @Override
    public void psubscribe(MessageHandler h, String... patterns) {
        this.messageHandler = h;
        this.pubSubMode = true;
        sendCommand(null, "PSUBSCRIBE", patterns);
        armHealth();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Routes the events of each reply to the command it answers: to the
     * adapter for the command's handler, which passes them on as they come.
     * A reply nobody wants (to a command that timed out, or to the health
     * check) is discarded as it arrives, without being assembled.
     */
    private final class ReplyRouter implements RespEventHandler {

        // who the reply in progress is for
        private static final int DISCARD = 0;
        private static final int COMMAND = 1;
        // Pub/Sub deliveries share the shape of replies, so a reply on a
        // subscribed connection is told apart only once it is complete
        private static final int UNCLAIMED = 2;

        private int mode;
        private PendingCommand target;
        // null for a command with no handler
        ReplyAdapter adapter;
        // nothing of the reply has been reported yet
        private boolean first;
        // a RESP3 blob error being read: an error line too long for one,
        // collected up to the maximum field length
        private boolean inBlobError;
        private byte[] blob = new byte[128];
        private int blobLength;

        @Override
        public void beginReply(boolean push) {
            target = null;
            adapter = null;
            mode = UNCLAIMED;
            if (!push && !pubSubMode) {
                PendingCommand head = pendingCommands.poll();
                if (head != null) {
                    claim(head);
                    return;
                }
            }
            reader.beginReply(push);
        }

        // The deliveries turned out to be the answer to the command at the
        // head of the queue, or to nothing.
        void divert() {
            PendingCommand head = pendingCommands.poll();
            if (head != null) {
                claim(head);
                return;
            }
            events().warn("warn.response_no_pending").attr("response", "").emit();
            target = null;
            adapter = null;
            mode = DISCARD;
        }

        // The reply that follows is the answer to this command.
        void claim(PendingCommand head) {
            first = true;
            target = null;
            adapter = null;
            if (head.internal) {
                // the answer to our own PING
                pingOutstanding = false;
                deadlines.remove(head);
                mode = DISCARD;
            } else if (head.timedOut) {
                // its handler has been told it timed out
                mode = DISCARD;
            } else {
                target = head;
                mode = COMMAND;
                head.streaming = true;
                head.lastActivityNanos = ticker.nanos();
                inFlight = head;
                adapter = ReplyAdapter.forHandler(head.callback, resultSession, maxField);
                if (adapter != null) {
                    adapter.setStream(new Stream(adapter));
                }
            }
        }

        @Override
        public void endReply(boolean skipped) {
            if (mode == DISCARD) {
                return;
            }
            if (mode == COMMAND) {
                PendingCommand done = target;
                if (inFlight == done) {
                    inFlight = null;
                }
                deadlines.remove(done);
                done.streaming = false;
                ReplyAdapter a = adapter;
                adapter = null;
                target = null;
                if (a != null) {
                    a.endReply(skipped);
                }
                armHealth();
                return;
            }
            reader.endReply(skipped);
        }

        // The reply is being read: it is activity for the deadline, and
        // nothing more is given to a handler that has been told it timed out.
        private boolean live() {
            if (target.timedOut) {
                return false;
            }
            if (target.deadlineNanos != 0) {
                target.lastActivityNanos = ticker.nanos();
            }
            return true;
        }

        // The events other than an error line, which are the reply to the
        // command if they come first.
        private ReplyFlow toCommand() {
            first = false;
            return live() && adapter != null ? null : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow nullValue() {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.nullValue();
            }
            return mode == UNCLAIMED ? reader.nullValue() : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow integer(long value) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.integer(value);
            }
            return mode == UNCLAIMED ? reader.integer(value) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.doubleValue(value);
            }
            return mode == UNCLAIMED ? reader.doubleValue(value) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.booleanValue(value);
            }
            return mode == UNCLAIMED ? reader.booleanValue(value) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.bigNumber(digits);
            }
            return mode == UNCLAIMED ? reader.bigNumber(digits) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow simpleString(String value) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.simpleString(value);
            }
            return mode == UNCLAIMED ? reader.simpleString(value) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow error(String message) {
            if (mode == COMMAND) {
                boolean whole = first;
                first = false;
                if (!live()) {
                    return ReplyFlow.SKIP;
                }
                if (whole) {
                    // the reply is this error: a listener may be able to
                    // act on it (a cluster redirect), whether or not the
                    // command has a handler
                    RedirectListener listener = redirectListener;
                    if (listener != null && target.sent != null
                            && listener.handleError(target.sent, message)) {
                        if (adapter != null) {
                            adapter.abandon();
                        }
                        return ReplyFlow.SKIP;
                    }
                }
                return adapter != null ? adapter.error(message) : ReplyFlow.SKIP;
            }
            return mode == UNCLAIMED ? reader.error(message) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.beginAggregate(kind, length);
            }
            return mode == UNCLAIMED ? reader.beginAggregate(kind, length) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow endAggregate() {
            if (mode == COMMAND) {
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.endAggregate();
            }
            return mode == UNCLAIMED ? reader.endAggregate() : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow beginString(RespType kind, long length, String format) {
            if (mode == COMMAND) {
                if (kind == RespType.BLOB_ERROR) {
                    inBlobError = true;
                    blobLength = 0;
                    return ReplyFlow.CONTINUE;
                }
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.beginString(kind, length, format);
            }
            return mode == UNCLAIMED ? reader.beginString(kind, length, format) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            if (mode == COMMAND) {
                if (inBlobError) {
                    // an error message, so what the limit does not hold is dropped
                    int n = Math.min(chunk.remaining(), maxField - blobLength);
                    if (n > 0) {
                        if (blobLength + n > blob.length) {
                            blob = java.util.Arrays.copyOf(blob, Math.max(blobLength + n, blob.length * 2));
                        }
                        chunk.get(blob, blobLength, n);
                        blobLength += n;
                    }
                    return ReplyFlow.CONTINUE;
                }
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.stringChunk(chunk);
            }
            return mode == UNCLAIMED ? reader.stringChunk(chunk) : ReplyFlow.SKIP;
        }

        @Override
        public ReplyFlow endString() {
            if (mode == COMMAND) {
                if (inBlobError) {
                    inBlobError = false;
                    return error(new String(blob, 0, blobLength, java.nio.charset.StandardCharsets.UTF_8));
                }
                ReplyFlow f = toCommand();
                return f != null ? f : adapter.endString();
            }
            return mode == UNCLAIMED ? reader.endString() : ReplyFlow.SKIP;
        }
    }

    /** The handle a handler has on its reply. */
    private final class Stream implements ReplyStream {
        private final ReplyAdapter adapter;

        Stream(ReplyAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public void resume() {
            Endpoint ep = endpoint;
            if (ep == null) {
                return;
            }
            ep.execute(new Runnable() {
                @Override
                public void run() {
                    resumeReading();
                }
            });
        }

        @Override
        public void cancel() {
            adapter.requestCancel();
            resume();
        }
    }

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
        // order sent, to break ties between equal deadlines
        long sequence;
        long timeoutMs;
        boolean timedOut;
        // waits on the server on purpose (a blocking pop and the like)
        boolean blocking;
        // sent by the connection itself (the health check's PING)
        boolean internal;
        // its reply is being read; the deadline then slides with the
        // silence, from when anything last arrived
        boolean streaming;
        long lastActivityNanos;
        // the consumer has paused the reply: no deadline runs
        boolean suspended;
        // queued behind a streamed command: how to work out its deadline
        // once that has been written
        long deferredBlocking;
        long deferredTimeoutMs;

        PendingCommand(Object callback, SentCommand sent, String subscriptionKind,
                       int confirmationsDue) {
            this.callback = callback;
            this.sent = sent;
            this.subscriptionKind = subscriptionKind;
            this.confirmationsDue = confirmationsDue;
        }
    }

    private PendingCommand pending(Object callback, SentCommand sent, CommandInfo info, int argCount) {
        String kind = info.subscriptionKind;
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
                deadlines.remove(head);
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
