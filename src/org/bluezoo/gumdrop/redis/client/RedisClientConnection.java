/*
 * RedisClientConnection.java
 * Copyright (C) 2025 Chris Burdess
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

import java.io.IOException;
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

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.redis.codec.RESPDecoder;
import org.bluezoo.gumdrop.redis.codec.RESPEncoder;
import org.bluezoo.gumdrop.redis.codec.RESPException;
import org.bluezoo.gumdrop.redis.codec.RESPValue;

/**
 * Event-driven, NIO-based Redis client connection.
 *
 * <p>This connection handles the Redis protocol using RESP encoding/decoding
 * and integrates with Gumdrop's asynchronous I/O framework.
 *
 * <p>Commands are queued and matched with responses in order. Multiple
 * commands can be outstanding simultaneously (pipelining).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientConnection extends Connection implements RedisSession {

    private static final Logger LOGGER = Logger.getLogger(RedisClientConnection.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.redis.client.L10N");
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private final RedisClient client;
    private final RedisConnectionReady handler;
    private final RESPDecoder decoder;
    private final RESPEncoder encoder;

    // Pending command callbacks (FIFO)
    private final Deque<PendingCommand> pendingCommands;

    // Pub/Sub state
    private boolean pubSubMode = false;
    private MessageHandler messageHandler = null;

    // Connection state
    private boolean closed = false;

    /**
     * Creates a Redis client connection.
     *
     * @param client the parent client
     * @param engine the SSL engine, or null for plaintext
     * @param secure whether this is an initially secure connection
     * @param handler the client handler for callbacks
     */
    protected RedisClientConnection(RedisClient client, SSLEngine engine, boolean secure,
                                    RedisConnectionReady handler) {
        super(engine, secure);
        this.client = client;
        this.handler = handler;
        this.decoder = new RESPDecoder();
        this.encoder = new RESPEncoder();
        this.pendingCommands = new ArrayDeque<PendingCommand>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void connected() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Redis connection established");
        }
        handler.onConnected(createConnectionInfo());
        handler.handleReady(this);
    }

    @Override
    public void finishConnectFailed(IOException cause) {
        handler.onError(cause);
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
    protected void disconnected() throws IOException {
        closed = true;
        handler.onDisconnected();
    }

    @Override
    protected void handshakeComplete(String protocol) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS handshake complete: " + protocol);
        }
        handler.onTLSStarted(createTLSInfo());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Closing Redis connection");
        }
        super.close();
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
                LOGGER.warning("Received response with no pending command: " + response);
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
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command);
        send(buf);
    }

    private void sendCommand(Object callback, String command, String[] args) {
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        send(buf);
    }

    private void sendCommand(Object callback, String command, byte[][] args) {
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encodeCommand(command, args);
        send(buf);
    }

    private void sendCommandMixed(Object callback, String command, Object... args) {
        pendingCommands.add(new PendingCommand(callback));
        ByteBuffer buf = encoder.encode(command, args);
        send(buf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Connection
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void auth(String password, StringResultHandler handler) {
        sendCommand(handler, "AUTH", new String[] { password });
    }

    @Override
    public void auth(String username, String password, StringResultHandler handler) {
        sendCommand(handler, "AUTH", new String[] { username, password });
    }

    @Override
    public void ping(StringResultHandler handler) {
        sendCommand(handler, "PING");
    }

    @Override
    public void ping(String message, BulkResultHandler handler) {
        sendCommand(handler, "PING", new String[] { message });
    }

    @Override
    public void select(int index, StringResultHandler handler) {
        sendCommand(handler, "SELECT", new String[] { String.valueOf(index) });
    }

    @Override
    public void echo(String message, BulkResultHandler handler) {
        sendCommand(handler, "ECHO", new String[] { message });
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
    public void get(String key, BulkResultHandler handler) {
        sendCommand(handler, "GET", new String[] { key });
    }

    @Override
    public void set(String key, String value, StringResultHandler handler) {
        sendCommand(handler, "SET", new String[] { key, value });
    }

    @Override
    public void set(String key, byte[] value, StringResultHandler handler) {
        sendCommand(handler, "SET", new byte[][] { key.getBytes(UTF_8), value });
    }

    @Override
    public void setex(String key, int seconds, String value, StringResultHandler handler) {
        sendCommand(handler, "SETEX", new String[] { key, String.valueOf(seconds), value });
    }

    @Override
    public void psetex(String key, long milliseconds, String value, StringResultHandler handler) {
        sendCommand(handler, "PSETEX", new String[] { key, String.valueOf(milliseconds), value });
    }

    @Override
    public void setnx(String key, String value, BooleanResultHandler handler) {
        sendCommand(handler, "SETNX", new String[] { key, value });
    }

    @Override
    public void getset(String key, String value, BulkResultHandler handler) {
        sendCommand(handler, "GETSET", new String[] { key, value });
    }

    @Override
    public void mget(ArrayResultHandler handler, String... keys) {
        sendCommand(handler, "MGET", keys);
    }

    @Override
    public void mset(StringResultHandler handler, String... keysAndValues) {
        sendCommand(handler, "MSET", keysAndValues);
    }

    @Override
    public void incr(String key, IntegerResultHandler handler) {
        sendCommand(handler, "INCR", new String[] { key });
    }

    @Override
    public void incrby(String key, long increment, IntegerResultHandler handler) {
        sendCommand(handler, "INCRBY", new String[] { key, String.valueOf(increment) });
    }

    @Override
    public void incrbyfloat(String key, double increment, BulkResultHandler handler) {
        sendCommand(handler, "INCRBYFLOAT", new String[] { key, String.valueOf(increment) });
    }

    @Override
    public void decr(String key, IntegerResultHandler handler) {
        sendCommand(handler, "DECR", new String[] { key });
    }

    @Override
    public void decrby(String key, long decrement, IntegerResultHandler handler) {
        sendCommand(handler, "DECRBY", new String[] { key, String.valueOf(decrement) });
    }

    @Override
    public void append(String key, String value, IntegerResultHandler handler) {
        sendCommand(handler, "APPEND", new String[] { key, value });
    }

    @Override
    public void strlen(String key, IntegerResultHandler handler) {
        sendCommand(handler, "STRLEN", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Keys
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void del(IntegerResultHandler handler, String... keys) {
        sendCommand(handler, "DEL", keys);
    }

    @Override
    public void exists(String key, BooleanResultHandler handler) {
        sendCommand(handler, "EXISTS", new String[] { key });
    }

    @Override
    public void exists(IntegerResultHandler handler, String... keys) {
        sendCommand(handler, "EXISTS", keys);
    }

    @Override
    public void expire(String key, int seconds, BooleanResultHandler handler) {
        sendCommand(handler, "EXPIRE", new String[] { key, String.valueOf(seconds) });
    }

    @Override
    public void pexpire(String key, long milliseconds, BooleanResultHandler handler) {
        sendCommand(handler, "PEXPIRE", new String[] { key, String.valueOf(milliseconds) });
    }

    @Override
    public void expireat(String key, long timestamp, BooleanResultHandler handler) {
        sendCommand(handler, "EXPIREAT", new String[] { key, String.valueOf(timestamp) });
    }

    @Override
    public void ttl(String key, IntegerResultHandler handler) {
        sendCommand(handler, "TTL", new String[] { key });
    }

    @Override
    public void pttl(String key, IntegerResultHandler handler) {
        sendCommand(handler, "PTTL", new String[] { key });
    }

    @Override
    public void persist(String key, BooleanResultHandler handler) {
        sendCommand(handler, "PERSIST", new String[] { key });
    }

    @Override
    public void keys(String pattern, ArrayResultHandler handler) {
        sendCommand(handler, "KEYS", new String[] { pattern });
    }

    @Override
    public void rename(String oldKey, String newKey, StringResultHandler handler) {
        sendCommand(handler, "RENAME", new String[] { oldKey, newKey });
    }

    @Override
    public void renamenx(String oldKey, String newKey, BooleanResultHandler handler) {
        sendCommand(handler, "RENAMENX", new String[] { oldKey, newKey });
    }

    @Override
    public void type(String key, StringResultHandler handler) {
        sendCommand(handler, "TYPE", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Hashes
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void hget(String key, String field, BulkResultHandler handler) {
        sendCommand(handler, "HGET", new String[] { key, field });
    }

    @Override
    public void hset(String key, String field, String value, IntegerResultHandler handler) {
        sendCommand(handler, "HSET", new String[] { key, field, value });
    }

    @Override
    public void hset(String key, String field, byte[] value, IntegerResultHandler handler) {
        sendCommand(handler, "HSET", new byte[][] { key.getBytes(UTF_8), field.getBytes(UTF_8), value });
    }

    @Override
    public void hsetnx(String key, String field, String value, BooleanResultHandler handler) {
        sendCommand(handler, "HSETNX", new String[] { key, field, value });
    }

    @Override
    public void hmget(String key, ArrayResultHandler handler, String... fields) {
        String[] args = new String[fields.length + 1];
        args[0] = key;
        for (int i = 0; i < fields.length; i++) {
            args[i + 1] = fields[i];
        }
        sendCommand(handler, "HMGET", args);
    }

    @Override
    public void hmset(String key, StringResultHandler handler, String... fieldsAndValues) {
        String[] args = new String[fieldsAndValues.length + 1];
        args[0] = key;
        for (int i = 0; i < fieldsAndValues.length; i++) {
            args[i + 1] = fieldsAndValues[i];
        }
        sendCommand(handler, "HMSET", args);
    }

    @Override
    public void hgetall(String key, ArrayResultHandler handler) {
        sendCommand(handler, "HGETALL", new String[] { key });
    }

    @Override
    public void hkeys(String key, ArrayResultHandler handler) {
        sendCommand(handler, "HKEYS", new String[] { key });
    }

    @Override
    public void hvals(String key, ArrayResultHandler handler) {
        sendCommand(handler, "HVALS", new String[] { key });
    }

    @Override
    public void hdel(String key, IntegerResultHandler handler, String... fields) {
        String[] args = new String[fields.length + 1];
        args[0] = key;
        for (int i = 0; i < fields.length; i++) {
            args[i + 1] = fields[i];
        }
        sendCommand(handler, "HDEL", args);
    }

    @Override
    public void hexists(String key, String field, BooleanResultHandler handler) {
        sendCommand(handler, "HEXISTS", new String[] { key, field });
    }

    @Override
    public void hlen(String key, IntegerResultHandler handler) {
        sendCommand(handler, "HLEN", new String[] { key });
    }

    @Override
    public void hincrby(String key, String field, long increment, IntegerResultHandler handler) {
        sendCommand(handler, "HINCRBY", new String[] { key, field, String.valueOf(increment) });
    }

    @Override
    public void hincrbyfloat(String key, String field, double increment, BulkResultHandler handler) {
        sendCommand(handler, "HINCRBYFLOAT", new String[] { key, field, String.valueOf(increment) });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Lists
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void lpush(String key, IntegerResultHandler handler, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        for (int i = 0; i < values.length; i++) {
            args[i + 1] = values[i];
        }
        sendCommand(handler, "LPUSH", args);
    }

    @Override
    public void rpush(String key, IntegerResultHandler handler, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        for (int i = 0; i < values.length; i++) {
            args[i + 1] = values[i];
        }
        sendCommand(handler, "RPUSH", args);
    }

    @Override
    public void lpop(String key, BulkResultHandler handler) {
        sendCommand(handler, "LPOP", new String[] { key });
    }

    @Override
    public void rpop(String key, BulkResultHandler handler) {
        sendCommand(handler, "RPOP", new String[] { key });
    }

    @Override
    public void lrange(String key, int start, int stop, ArrayResultHandler handler) {
        sendCommand(handler, "LRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void llen(String key, IntegerResultHandler handler) {
        sendCommand(handler, "LLEN", new String[] { key });
    }

    @Override
    public void lindex(String key, int index, BulkResultHandler handler) {
        sendCommand(handler, "LINDEX", new String[] { key, String.valueOf(index) });
    }

    @Override
    public void lset(String key, int index, String value, StringResultHandler handler) {
        sendCommand(handler, "LSET", new String[] { key, String.valueOf(index), value });
    }

    @Override
    public void ltrim(String key, int start, int stop, StringResultHandler handler) {
        sendCommand(handler, "LTRIM", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void lrem(String key, int count, String value, IntegerResultHandler handler) {
        sendCommand(handler, "LREM", new String[] { key, String.valueOf(count), value });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Sets
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void sadd(String key, IntegerResultHandler handler, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(handler, "SADD", args);
    }

    @Override
    public void srem(String key, IntegerResultHandler handler, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(handler, "SREM", args);
    }

    @Override
    public void smembers(String key, ArrayResultHandler handler) {
        sendCommand(handler, "SMEMBERS", new String[] { key });
    }

    @Override
    public void sismember(String key, String member, BooleanResultHandler handler) {
        sendCommand(handler, "SISMEMBER", new String[] { key, member });
    }

    @Override
    public void scard(String key, IntegerResultHandler handler) {
        sendCommand(handler, "SCARD", new String[] { key });
    }

    @Override
    public void spop(String key, BulkResultHandler handler) {
        sendCommand(handler, "SPOP", new String[] { key });
    }

    @Override
    public void srandmember(String key, BulkResultHandler handler) {
        sendCommand(handler, "SRANDMEMBER", new String[] { key });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Sorted Sets
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void zadd(String key, double score, String member, IntegerResultHandler handler) {
        sendCommand(handler, "ZADD", new String[] { key, String.valueOf(score), member });
    }

    @Override
    public void zscore(String key, String member, BulkResultHandler handler) {
        sendCommand(handler, "ZSCORE", new String[] { key, member });
    }

    @Override
    public void zrange(String key, int start, int stop, ArrayResultHandler handler) {
        sendCommand(handler, "ZRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void zrangeWithScores(String key, int start, int stop, ArrayResultHandler handler) {
        sendCommand(handler, "ZRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop), "WITHSCORES" });
    }

    @Override
    public void zrevrange(String key, int start, int stop, ArrayResultHandler handler) {
        sendCommand(handler, "ZREVRANGE", new String[] { key, String.valueOf(start), String.valueOf(stop) });
    }

    @Override
    public void zrank(String key, String member, IntegerResultHandler handler) {
        sendCommand(handler, "ZRANK", new String[] { key, member });
    }

    @Override
    public void zrem(String key, IntegerResultHandler handler, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        for (int i = 0; i < members.length; i++) {
            args[i + 1] = members[i];
        }
        sendCommand(handler, "ZREM", args);
    }

    @Override
    public void zcard(String key, IntegerResultHandler handler) {
        sendCommand(handler, "ZCARD", new String[] { key });
    }

    @Override
    public void zincrby(String key, double increment, String member, BulkResultHandler handler) {
        sendCommand(handler, "ZINCRBY", new String[] { key, String.valueOf(increment), member });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Pub/Sub
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void subscribe(MessageHandler handler, String... channels) {
        this.messageHandler = handler;
        this.pubSubMode = true;
        sendCommand(null, "SUBSCRIBE", channels);
    }

    @Override
    public void psubscribe(MessageHandler handler, String... patterns) {
        this.messageHandler = handler;
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
    public void publish(String channel, String message, IntegerResultHandler handler) {
        sendCommand(handler, "PUBLISH", new String[] { channel, message });
    }

    @Override
    public void publish(String channel, byte[] message, IntegerResultHandler handler) {
        sendCommand(handler, "PUBLISH", new byte[][] { channel.getBytes(UTF_8), message });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Transactions
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void multi(StringResultHandler handler) {
        sendCommand(handler, "MULTI");
    }

    @Override
    public void exec(ArrayResultHandler handler) {
        sendCommand(handler, "EXEC");
    }

    @Override
    public void discard(StringResultHandler handler) {
        sendCommand(handler, "DISCARD");
    }

    @Override
    public void watch(StringResultHandler handler, String... keys) {
        sendCommand(handler, "WATCH", keys);
    }

    @Override
    public void unwatch(StringResultHandler handler) {
        sendCommand(handler, "UNWATCH");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Scripting
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void eval(String script, int numKeys, String[] keys, String[] args, ArrayResultHandler handler) {
        String[] allArgs = new String[2 + keys.length + args.length];
        allArgs[0] = script;
        allArgs[1] = String.valueOf(numKeys);
        for (int i = 0; i < keys.length; i++) {
            allArgs[i + 2] = keys[i];
        }
        for (int i = 0; i < args.length; i++) {
            allArgs[i + 2 + keys.length] = args[i];
        }
        sendCommand(handler, "EVAL", allArgs);
    }

    @Override
    public void evalsha(String sha1, int numKeys, String[] keys, String[] args, ArrayResultHandler handler) {
        String[] allArgs = new String[2 + keys.length + args.length];
        allArgs[0] = sha1;
        allArgs[1] = String.valueOf(numKeys);
        for (int i = 0; i < keys.length; i++) {
            allArgs[i + 2] = keys[i];
        }
        for (int i = 0; i < args.length; i++) {
            allArgs[i + 2 + keys.length] = args[i];
        }
        sendCommand(handler, "EVALSHA", allArgs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Server
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void info(BulkResultHandler handler) {
        sendCommand(handler, "INFO");
    }

    @Override
    public void info(String section, BulkResultHandler handler) {
        sendCommand(handler, "INFO", new String[] { section });
    }

    @Override
    public void dbsize(IntegerResultHandler handler) {
        sendCommand(handler, "DBSIZE");
    }

    @Override
    public void flushdb(StringResultHandler handler) {
        sendCommand(handler, "FLUSHDB");
    }

    @Override
    public void flushall(StringResultHandler handler) {
        sendCommand(handler, "FLUSHALL");
    }

    @Override
    public void time(ArrayResultHandler handler) {
        sendCommand(handler, "TIME");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession implementation - Generic
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void command(ArrayResultHandler handler, String command, String... args) {
        sendCommand(handler, command, args);
    }

    @Override
    public void command(ArrayResultHandler handler, String command, byte[]... args) {
        sendCommand(handler, command, args);
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

