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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.codec.RESPDecoder;
import org.bluezoo.gumdrop.redis.codec.RESPEncoder;
import org.bluezoo.gumdrop.redis.codec.RESPException;
import org.bluezoo.gumdrop.redis.codec.RESPValue;

/**
 * Event-driven, NIO-based Redis client endpoint handler.
 *
 * <p>Implements {@link ProtocolHandler} and {@link RedisSession}, storing an
 * {@link Endpoint} field set in {@link #connected(Endpoint)} and delegating
 * all I/O to the endpoint.
 *
 * <p>This handler processes the Redis protocol using RESP encoding/decoding
 * and integrates with Gumdrop's asynchronous I/O framework.
 *
 * <p>Commands are queued and matched with responses in order. Multiple
 * commands can be outstanding simultaneously (pipelining).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientProtocolHandler implements ProtocolHandler, RedisSession {

    private static final Logger LOGGER = Logger.getLogger(RedisClientProtocolHandler.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.client.L10N");
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private final RedisConnectionReady handler;
    private final RESPDecoder decoder;
    private final RESPEncoder encoder;

    private Endpoint endpoint;

    // Pending command callbacks (FIFO)
    private final Deque<PendingCommand> pendingCommands;

    // Pub/Sub state
    private boolean pubSubMode = false;
    private MessageHandler messageHandler = null;

    // Connection state
    private boolean closed = false;

    /**
     * Creates a Redis client endpoint handler.
     *
     * @param handler the client handler for callbacks
     */
    public RedisClientProtocolHandler(RedisConnectionReady handler) {
        this.handler = handler;
        this.decoder = new RESPDecoder();
        this.encoder = new RESPEncoder();
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

    @Override
    public void receive(ByteBuffer buf) {
        try {
            decoder.receive(buf);
            RESPValue response;
            while ((response = decoder.next()) != null) {
                processResponse(response);
            }
        } catch (RESPException e) {
            LOGGER.log(Level.WARNING, L10N.getString("err.protocol_error"), e);
            handler.onError(e);
            close();
        }
    }

    @Override
    public void disconnected() {
        closed = true;
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
                LOGGER.finer("Cipher suite: " + cipher);
            }
        }
    }

    @Override
    public void error(Exception cause) {
        handler.onError(cause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession - Connection
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
        pendingCommands.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response processing
    // ─────────────────────────────────────────────────────────────────────────

    private void processResponse(RESPValue response) {
        // Check for Pub/Sub messages
        if (pubSubMode && response.isArray()) {
            List<RESPValue> array = response.asArray();
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
            if (LOGGER.isLoggable(Level.WARNING)) {
                String msg = MessageFormat.format(L10N.getString("warn.response_no_pending"), response);
                LOGGER.warning(msg);
            }
            return;
        }
        dispatchResponse(pending, response);
    }

    private boolean handlePubSubMessage(String type, List<RESPValue> array) {
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
            messageHandler.handleSubscribed(channel, count);
            return true;
        }
        if ("psubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            messageHandler.handlePatternSubscribed(pattern, count);
            return true;
        }
        if ("unsubscribe".equals(type) && array.size() >= 3) {
            String channel = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            messageHandler.handleUnsubscribed(channel, count);
            if (count == 0) {
                pubSubMode = false;
                messageHandler = null;
            }
            return true;
        }
        if ("punsubscribe".equals(type) && array.size() >= 3) {
            String pattern = array.get(1).asString();
            int count = (int) array.get(2).asLong();
            messageHandler.handlePatternUnsubscribed(pattern, count);
            if (count == 0) {
                pubSubMode = false;
                messageHandler = null;
            }
            return true;
        }

        return false;
    }

    private void dispatchResponse(PendingCommand pending, RESPValue response) {
        Object callback = pending.callback;
        if (callback == null) {
            return;
        }

        // Handle errors for all handler types
        if (response.isError()) {
            String error = response.getErrorMessage();
            dispatchError(callback, error);
            return;
        }

        // Dispatch based on handler type
        if (callback instanceof StringResultHandler) {
            StringResultHandler h = (StringResultHandler) callback;
            String result = response.asString();
            h.handleResult(result, this);
        } else if (callback instanceof BulkResultHandler) {
            BulkResultHandler h = (BulkResultHandler) callback;
            if (response.isNull()) {
                h.handleNull(this);
            } else {
                byte[] result = response.asBytes();
                h.handleResult(result, this);
            }
        } else if (callback instanceof IntegerResultHandler) {
            IntegerResultHandler h = (IntegerResultHandler) callback;
            long result = response.asLong();
            h.handleResult(result, this);
        } else if (callback instanceof BooleanResultHandler) {
            BooleanResultHandler h = (BooleanResultHandler) callback;
            boolean result = response.asLong() != 0;
            h.handleResult(result, this);
        } else if (callback instanceof ArrayResultHandler) {
            ArrayResultHandler h = (ArrayResultHandler) callback;
            if (response.isNull()) {
                h.handleNull(this);
            } else {
                List<RESPValue> result = response.asArray();
                h.handleResult(result, this);
            }
        }
    }

    private void dispatchError(Object callback, String error) {
        if (callback instanceof StringResultHandler) {
            ((StringResultHandler) callback).handleError(error, this);
        } else if (callback instanceof BulkResultHandler) {
            ((BulkResultHandler) callback).handleError(error, this);
        } else if (callback instanceof IntegerResultHandler) {
            ((IntegerResultHandler) callback).handleError(error, this);
        } else if (callback instanceof BooleanResultHandler) {
            ((BooleanResultHandler) callback).handleError(error, this);
        } else if (callback instanceof ArrayResultHandler) {
            ((ArrayResultHandler) callback).handleError(error, this);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command sending
    // ─────────────────────────────────────────────────────────────────────────

    private void sendCommand(Object callback, String command) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command);
        endpoint.send(buf);
    }

    private void sendCommand(Object callback, String command, String[] args) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        endpoint.send(buf);
    }

    private void sendCommand(Object callback, String command, byte[][] args) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        endpoint.send(buf);
    }

    private void sendCommandMixed(Object callback, String command, Object... args) {
        if (endpoint == null || closed) {
            if (callback != null) {
                dispatchError(callback, L10N.getString("err.not_connected"));
            }
            return;
        }
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encode(command, args);
        endpoint.send(buf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Connection
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void auth(String password, StringResultHandler h) {
        sendCommand(h, "AUTH", new String[] { password });
    }

    @Override
    public void auth(String username, String password, StringResultHandler h) {
        sendCommand(h, "AUTH", new String[] { username, password });
    }

    @Override
    public void ping(StringResultHandler h) {
        sendCommand(h, "PING");
    }

    @Override
    public void ping(String message, BulkResultHandler h) {
        sendCommand(h, "PING", new String[] { message });
    }

    @Override
    public void select(int index, StringResultHandler h) {
        sendCommand(h, "SELECT", new String[] { String.valueOf(index) });
    }

    @Override
    public void echo(String message, BulkResultHandler h) {
        sendCommand(h, "ECHO", new String[] { message });
    }

    @Override
    public void quit() {
        sendCommand(null, "QUIT");
        close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Strings
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void get(String key, BulkResultHandler h) {
        sendCommand(h, "GET", new String[] { key });
    }

    @Override
    public void set(String key, String value, StringResultHandler h) {
        sendCommand(h, "SET", new String[] { key, value });
    }

    @Override
    public void set(String key, byte[] value, StringResultHandler h) {
        sendCommand(h, "SET", new byte[][] { key.getBytes(UTF_8), value });
    }

    @Override
    public void setex(String key, int seconds, String value, StringResultHandler h) {
        sendCommand(h, "SETEX", new String[] { key, String.valueOf(seconds), value });
    }

    @Override
    public void psetex(String key, long milliseconds, String value, StringResultHandler h) {
        sendCommand(h, "PSETEX", new String[] { key, String.valueOf(milliseconds), value });
    }

    @Override
    public void setnx(String key, String value, BooleanResultHandler h) {
        sendCommand(h, "SETNX", new String[] { key, value });
    }

    @Override
    public void getset(String key, String value, BulkResultHandler h) {
        sendCommand(h, "GETSET", new String[] { key, value });
    }

    @Override
    public void mget(ArrayResultHandler h, String... keys) {
        sendCommand(h, "MGET", keys);
    }

    @Override
    public void mset(StringResultHandler h, String... keysAndValues) {
        sendCommand(h, "MSET", keysAndValues);
    }

    @Override
    public void incr(String key, IntegerResultHandler h) {
        sendCommand(h, "INCR", new String[] { key });
    }

    @Override
    public void incrby(String key, long increment, IntegerResultHandler h) {
        sendCommand(h, "INCRBY", new String[] { key, String.valueOf(increment) });
    }

    @Override
    public void incrbyfloat(String key, double increment, BulkResultHandler h) {
        sendCommand(h, "INCRBYFLOAT", new String[] { key, String.valueOf(increment) });
    }

    @Override
    public void decr(String key, IntegerResultHandler h) {
        sendCommand(h, "DECR", new String[] { key });
    }

    @Override
    public void decrby(String key, long decrement, IntegerResultHandler h) {
        sendCommand(h, "DECRBY", new String[] { key, String.valueOf(decrement) });
    }

    @Override
    public void append(String key, String value, IntegerResultHandler h) {
        sendCommand(h, "APPEND", new String[] { key, value });
    }

    @Override
    public void strlen(String key, IntegerResultHandler h) {
        sendCommand(h, "STRLEN", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Keys
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void del(IntegerResultHandler h, String... keys) {
        sendCommand(h, "DEL", keys);
    }

    @Override
    public void exists(String key, BooleanResultHandler h) {
        sendCommand(h, "EXISTS", new String[] { key });
    }

    @Override
    public void exists(IntegerResultHandler h, String... keys) {
        sendCommand(h, "EXISTS", keys);
    }

    @Override
    public void expire(String key, int seconds, BooleanResultHandler h) {
        sendCommand(h, "EXPIRE", new String[] { key, String.valueOf(seconds) });
    }

    @Override
    public void pexpire(String key, long milliseconds, BooleanResultHandler h) {
        sendCommand(h, "PEXPIRE", new String[] { key, String.valueOf(milliseconds) });
    }

    @Override
    public void expireat(String key, long timestamp, BooleanResultHandler h) {
        sendCommand(h, "EXPIREAT", new String[] { key, String.valueOf(timestamp) });
    }

    @Override
    public void ttl(String key, IntegerResultHandler h) {
        sendCommand(h, "TTL", new String[] { key });
    }

    @Override
    public void pttl(String key, IntegerResultHandler h) {
        sendCommand(h, "PTTL", new String[] { key });
    }

    @Override
    public void persist(String key, BooleanResultHandler h) {
        sendCommand(h, "PERSIST", new String[] { key });
    }

    @Override
    public void keys(String pattern, ArrayResultHandler h) {
        sendCommand(h, "KEYS", new String[] { pattern });
    }

    @Override
    public void rename(String oldKey, String newKey, StringResultHandler h) {
        sendCommand(h, "RENAME", new String[] { oldKey, newKey });
    }

    @Override
    public void renamenx(String oldKey, String newKey, BooleanResultHandler h) {
        sendCommand(h, "RENAMENX", new String[] { oldKey, newKey });
    }

    @Override
    public void type(String key, StringResultHandler h) {
        sendCommand(h, "TYPE", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Hashes
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void hget(String key, String field, BulkResultHandler h) {
        sendCommand(h, "HGET", new String[] { key, field });
    }

    @Override
    public void hset(String key, String field, String value, IntegerResultHandler h) {
        sendCommand(h, "HSET", new String[] { key, field, value });
    }

    @Override
    public void hset(String key, String field, byte[] value, IntegerResultHandler h) {
        sendCommand(h, "HSET", new byte[][] { key.getBytes(UTF_8), field.getBytes(UTF_8), value });
    }

    @Override
    public void hsetnx(String key, String field, String value, BooleanResultHandler h) {
        sendCommand(h, "HSETNX", new String[] { key, field, value });
    }

    @Override
    public void hmget(String key, ArrayResultHandler h, String... fields) {
        String[] args = new String[fields.length + 1];
        args[0] = key;
        for (int i = 0; i < fields.length; i++) {
            args[i + 1] = fields[i];
        }
        sendCommand(h, "HMGET", args);
    }

    @Override
    public void hmset(String key, StringResultHandler h, String... fieldsAndValues) {
        String[] args = new String[fieldsAndValues.length + 1];
        args[0] = key;
        for (int i = 0; i < fieldsAndValues.length; i++) {
            args[i + 1] = fieldsAndValues[i];
        }
        sendCommand(h, "HMSET", args);
    }

    @Override
    public void hgetall(String key, ArrayResultHandler h) {
        sendCommand(h, "HGETALL", new String[] { key });
    }

    @Override
    public void hkeys(String key, ArrayResultHandler h) {
        sendCommand(h, "HKEYS", new String[] { key });
    }

    @Override
    public void hvals(String key, ArrayResultHandler h) {
        sendCommand(h, "HVALS", new String[] { key });
    }

    @Override
    public void hdel(String key, IntegerResultHandler h, String... fields) {
        String[] args = new String[fields.length + 1];
        args[0] = key;
        for (int i = 0; i < fields.length; i++) {
            args[i + 1] = fields[i];
        }
        sendCommand(h, "HDEL", args);
    }

    @Override
    public void hexists(String key, String field, BooleanResultHandler h) {
        sendCommand(h, "HEXISTS", new String[] { key, field });
    }

    @Override
    public void hlen(String key, IntegerResultHandler h) {
        sendCommand(h, "HLEN", new String[] { key });
    }

    @Override
    public void hincrby(String key, String field, long increment, IntegerResultHandler h) {
        sendCommand(h, "HINCRBY", new String[] { key, field, String.valueOf(increment) });
    }

    @Override
    public void hincrbyfloat(String key, String field, double increment, BulkResultHandler h) {
        sendCommand(h, "HINCRBYFLOAT", new String[] { key, field, String.valueOf(increment) });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Lists
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void lpush(String key, IntegerResultHandler h, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        for (int i = 0; i < values.length; i++) {
            args[i + 1] = values[i];
        }
        sendCommand(h, "LPUSH", args);
    }

    @Override
    public void rpush(String key, IntegerResultHandler h, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        for (int i = 0; i < values.length; i++) {
            args[i + 1] = values[i];
        }
        sendCommand(h, "RPUSH", args);
    }

    @Override
    public void lpop(String key, BulkResultHandler h) {
        sendCommand(h, "LPOP", new String[] { key });
    }

    @Override
    public void rpop(String key, BulkResultHandler h) {
        sendCommand(h, "RPOP", new String[] { key });
    }

    @Override
    public void lrange(String key, int start, int stop, ArrayResultHandler h) {
        sendCommand(h, "LRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void llen(String key, IntegerResultHandler h) {
        sendCommand(h, "LLEN", new String[] { key });
    }

    @Override
    public void lindex(String key, int index, BulkResultHandler h) {
        sendCommand(h, "LINDEX", new String[] { key, String.valueOf(index) });
    }

    @Override
    public void lset(String key, int index, String value, StringResultHandler h) {
        sendCommand(h, "LSET", new String[] { key, String.valueOf(index), value });
    }

    @Override
    public void ltrim(String key, int start, int stop, StringResultHandler h) {
        sendCommand(h, "LTRIM", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void lrem(String key, int count, String value, IntegerResultHandler h) {
        sendCommand(h, "LREM", new String[] { key, String.valueOf(count), value });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Sets
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void sadd(String key, IntegerResultHandler h, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(h, "SADD", args);
    }

    @Override
    public void srem(String key, IntegerResultHandler h, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(h, "SREM", args);
    }

    @Override
    public void smembers(String key, ArrayResultHandler h) {
        sendCommand(h, "SMEMBERS", new String[] { key });
    }

    @Override
    public void sismember(String key, String member, BooleanResultHandler h) {
        sendCommand(h, "SISMEMBER", new String[] { key, member });
    }

    @Override
    public void scard(String key, IntegerResultHandler h) {
        sendCommand(h, "SCARD", new String[] { key });
    }

    @Override
    public void spop(String key, BulkResultHandler h) {
        sendCommand(h, "SPOP", new String[] { key });
    }

    @Override
    public void srandmember(String key, BulkResultHandler h) {
        sendCommand(h, "SRANDMEMBER", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Sorted Sets
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void zadd(String key, double score, String member, IntegerResultHandler h) {
        sendCommand(h, "ZADD", new String[] { key, String.valueOf(score), member });
    }

    @Override
    public void zscore(String key, String member, BulkResultHandler h) {
        sendCommand(h, "ZSCORE", new String[] { key, member });
    }

    @Override
    public void zrange(String key, int start, int stop, ArrayResultHandler h) {
        sendCommand(h, "ZRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void zrangeWithScores(String key, int start, int stop, ArrayResultHandler h) {
        sendCommand(h, "ZRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop), "WITHSCORES" });
    }

    @Override
    public void zrevrange(String key, int start, int stop, ArrayResultHandler h) {
        sendCommand(h, "ZREVRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void zrank(String key, String member, IntegerResultHandler h) {
        sendCommand(h, "ZRANK", new String[] { key, member });
    }

    @Override
    public void zrem(String key, IntegerResultHandler h, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(h, "ZREM", args);
    }

    @Override
    public void zcard(String key, IntegerResultHandler h) {
        sendCommand(h, "ZCARD", new String[] { key });
    }

    @Override
    public void zincrby(String key, double increment, String member, BulkResultHandler h) {
        sendCommand(h, "ZINCRBY", new String[] { key, String.valueOf(increment), member });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Pub/Sub
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void subscribe(MessageHandler h, String... channels) {
        this.messageHandler = h;
        this.pubSubMode = true;
        sendCommand(null, "SUBSCRIBE", channels);
    }

    @Override
    public void psubscribe(MessageHandler h, String... patterns) {
        this.messageHandler = h;
        this.pubSubMode = true;
        sendCommand(null, "PSUBSCRIBE", patterns);
    }

    @Override
    public void unsubscribe(String... channels) {
        if (channels.length == 0) {
            sendCommand(null, "UNSUBSCRIBE");
        } else {
            sendCommand(null, "UNSUBSCRIBE", channels);
        }
    }

    @Override
    public void punsubscribe(String... patterns) {
        if (patterns.length == 0) {
            sendCommand(null, "PUNSUBSCRIBE");
        } else {
            sendCommand(null, "PUNSUBSCRIBE", patterns);
        }
    }

    @Override
    public void publish(String channel, String message, IntegerResultHandler h) {
        sendCommand(h, "PUBLISH", new String[] { channel, message });
    }

    @Override
    public void publish(String channel, byte[] message, IntegerResultHandler h) {
        sendCommand(h, "PUBLISH", new byte[][] { channel.getBytes(UTF_8), message });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Transactions
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void multi(StringResultHandler h) {
        sendCommand(h, "MULTI");
    }

    @Override
    public void exec(ArrayResultHandler h) {
        sendCommand(h, "EXEC");
    }

    @Override
    public void discard(StringResultHandler h) {
        sendCommand(h, "DISCARD");
    }

    @Override
    public void watch(StringResultHandler h, String... keys) {
        sendCommand(h, "WATCH", keys);
    }

    @Override
    public void unwatch(StringResultHandler h) {
        sendCommand(h, "UNWATCH");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Scripting
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void eval(String script, int numKeys, String[] keys, String[] args, ArrayResultHandler h) {
        String[] allArgs = new String[2 + keys.length + args.length];
        allArgs[0] = script;
        allArgs[1] = String.valueOf(numKeys);
        for (int i = 0; i < keys.length; i++) {
            allArgs[i + 2] = keys[i];
        }
        for (int i = 0; i < args.length; i++) {
            allArgs[i + 2 + keys.length] = args[i];
        }
        sendCommand(h, "EVAL", allArgs);
    }

    @Override
    public void evalsha(String sha1, int numKeys, String[] keys, String[] args, ArrayResultHandler h) {
        String[] allArgs = new String[2 + keys.length + args.length];
        allArgs[0] = sha1;
        allArgs[1] = String.valueOf(numKeys);
        for (int i = 0; i < keys.length; i++) {
            allArgs[i + 2] = keys[i];
        }
        for (int i = 0; i < args.length; i++) {
            allArgs[i + 2 + keys.length] = args[i];
        }
        sendCommand(h, "EVALSHA", allArgs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Server
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void info(BulkResultHandler h) {
        sendCommand(h, "INFO");
    }

    @Override
    public void info(String section, BulkResultHandler h) {
        sendCommand(h, "INFO", new String[] { section });
    }

    @Override
    public void dbsize(IntegerResultHandler h) {
        sendCommand(h, "DBSIZE");
    }

    @Override
    public void flushdb(StringResultHandler h) {
        sendCommand(h, "FLUSHDB");
    }

    @Override
    public void flushall(StringResultHandler h) {
        sendCommand(h, "FLUSHALL");
    }

    @Override
    public void time(ArrayResultHandler h) {
        sendCommand(h, "TIME");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Generic
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void command(ArrayResultHandler h, String command, String... args) {
        sendCommand(h, command, args);
    }

    @Override
    public void command(ArrayResultHandler h, String command, byte[]... args) {
        sendCommand(h, command, args);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tracks a pending command waiting for a response.
     */
    private static class PendingCommand {
        final Object callback;

        PendingCommand(Object callback) {
            this.callback = callback;
        }
    }

}
