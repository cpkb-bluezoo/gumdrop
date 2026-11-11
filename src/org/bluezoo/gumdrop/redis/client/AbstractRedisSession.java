/*
 * AbstractRedisSession.java
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * The Redis command surface: every {@link RedisSession} command expressed
 * as a call to one of three {@code sendCommand} methods.
 *
 * <p>The single-node {@link RedisClientProtocolHandler} sends the command
 * down its connection; a cluster session chooses a node from the command's
 * keys first. Commands that change the state of one particular connection
 * ({@code QUIT}, {@code RESET}, {@code SUBSCRIBE}, {@code PSUBSCRIBE}) are
 * left to the subclass.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class AbstractRedisSession implements RedisSession {

    static final Charset UTF_8 = StandardCharsets.UTF_8;

    /**
     * Sends a command with no arguments.
     *
     * @param callback the result handler, or null if no reply is wanted
     * @param command the command name
     */
    abstract void sendCommand(Object callback, String command);

    /**
     * Sends a command with text arguments.
     *
     * @param callback the result handler, or null if no reply is wanted
     * @param command the command name
     * @param args the arguments
     */
    abstract void sendCommand(Object callback, String command, String[] args);

    /**
     * Sends a command with binary-safe arguments.
     *
     * @param callback the result handler, or null if no reply is wanted
     * @param command the command name
     * @param args the arguments
     */
    abstract void sendCommand(Object callback, String command, byte[][] args);

    /**
     * Tells a command's result handler that it failed, whatever kind of
     * handler it is. Does nothing for a null callback.
     *
     * @param callback the result handler
     * @param error the error message
     * @param session the session to pass to the handler
     */
    static void deliverError(Object callback, String error, RedisSession session) {
        if (callback instanceof ScanResultHandler) {
            ((ScanResultHandler) callback).handleError(error, session);
        } else if (callback instanceof StringResultHandler) {
            ((StringResultHandler) callback).handleError(error, session);
        } else if (callback instanceof BulkResultHandler) {
            ((BulkResultHandler) callback).handleError(error, session);
        } else if (callback instanceof IntegerResultHandler) {
            ((IntegerResultHandler) callback).handleError(error, session);
        } else if (callback instanceof BooleanResultHandler) {
            ((BooleanResultHandler) callback).handleError(error, session);
        } else if (callback instanceof ArrayResultHandler) {
            ((ArrayResultHandler) callback).handleError(error, session);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — Connection commands
    // ─────────────────────────────────────────────────────────────────────────

    // AUTH command — legacy password-only (Redis <= 5)
    @Override
    public void auth(String password, StringResultHandler h) {
        sendCommand(h, "AUTH", new String[] { password });
    }

    // AUTH command — ACL username+password (Redis 6+)
    @Override
    public void auth(String username, String password, StringResultHandler h) {
        sendCommand(h, "AUTH", new String[] { username, password });
    }

    // RESP3 — HELLO command (Redis 6+) negotiates protocol version
    @Override
    public void hello(int protover, ArrayResultHandler h) {
        sendCommand(h, "HELLO", new String[] { String.valueOf(protover) });
    }

    // RESP3 — HELLO with AUTH in a single round-trip
    @Override
    public void hello(int protover, String username, String password, ArrayResultHandler h) {
        sendCommand(h, "HELLO", new String[] {
            String.valueOf(protover), "AUTH", username, password
        });
    }

    // Redis command reference — CLIENT SETNAME
    @Override
    public void clientSetName(String name, StringResultHandler h) {
        sendCommand(h, "CLIENT", new String[] { "SETNAME", name });
    }

    // Redis command reference — CLIENT GETNAME
    @Override
    public void clientGetName(BulkResultHandler h) {
        sendCommand(h, "CLIENT", new String[] { "GETNAME" });
    }

    // Redis command reference — CLIENT ID
    @Override
    public void clientId(IntegerResultHandler h) {
        sendCommand(h, "CLIENT", new String[] { "ID" });
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

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — String commands (Redis command group: Strings)
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
    public void set(String key, String value, SetOptions options, BulkResultHandler h) {
        set(key, value.getBytes(UTF_8), options, h);
    }

    @Override
    public void set(String key, byte[] value, SetOptions options, BulkResultHandler h) {
        String[] extra = options.toArguments();
        byte[][] args = new byte[2 + extra.length][];
        args[0] = key.getBytes(UTF_8);
        args[1] = value;
        for (int i = 0; i < extra.length; i++) {
            args[2 + i] = extra[i].getBytes(UTF_8);
        }
        sendCommand(h, "SET", args);
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
    // RedisSession — Key commands (Redis command group: Generic)
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
    // RedisSession — SCAN commands (Redis command reference — SCAN)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void scan(String cursor, ScanResultHandler h) {
        sendCommand(h, "SCAN", new String[] { cursor });
    }

    @Override
    public void scan(String cursor, String match, int count, ScanResultHandler h) {
        sendCommand(h, "SCAN", buildScanArgs(cursor, match, count));
    }

    @Override
    public void hscan(String key, String cursor, ScanResultHandler h) {
        sendCommand(h, "HSCAN", new String[] { key, cursor });
    }

    @Override
    public void hscan(String key, String cursor, String match, int count, ScanResultHandler h) {
        sendCommand(h, "HSCAN", buildKeyScanArgs(key, cursor, match, count));
    }

    @Override
    public void sscan(String key, String cursor, ScanResultHandler h) {
        sendCommand(h, "SSCAN", new String[] { key, cursor });
    }

    @Override
    public void sscan(String key, String cursor, String match, int count, ScanResultHandler h) {
        sendCommand(h, "SSCAN", buildKeyScanArgs(key, cursor, match, count));
    }

    @Override
    public void zscan(String key, String cursor, ScanResultHandler h) {
        sendCommand(h, "ZSCAN", new String[] { key, cursor });
    }

    @Override
    public void zscan(String key, String cursor, String match, int count, ScanResultHandler h) {
        sendCommand(h, "ZSCAN", buildKeyScanArgs(key, cursor, match, count));
    }

    private String[] buildScanArgs(String cursor, String match, int count) {
        ArrayList<String> args = new ArrayList<String>();
        args.add(cursor);
        if (match != null) {
            args.add("MATCH");
            args.add(match);
        }
        if (count > 0) {
            args.add("COUNT");
            args.add(String.valueOf(count));
        }
        return args.toArray(new String[0]);
    }

    private String[] buildKeyScanArgs(String key, String cursor, String match, int count) {
        ArrayList<String> args = new ArrayList<String>();
        args.add(key);
        args.add(cursor);
        if (match != null) {
            args.add("MATCH");
            args.add(match);
        }
        if (count > 0) {
            args.add("COUNT");
            args.add(String.valueOf(count));
        }
        return args.toArray(new String[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — Hash commands (Redis command group: Hash)
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
    // RedisSession — List commands (Redis command group: List)
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

    // Redis command reference — BLPOP
    @Override
    public void blpop(double timeout, ArrayResultHandler h, String... keys) {
        String[] args = new String[keys.length + 1];
        System.arraycopy(keys, 0, args, 0, keys.length);
        args[keys.length] = String.valueOf(timeout);
        sendCommand(h, "BLPOP", args);
    }

    // Redis command reference — BRPOP
    @Override
    public void brpop(double timeout, ArrayResultHandler h, String... keys) {
        String[] args = new String[keys.length + 1];
        System.arraycopy(keys, 0, args, 0, keys.length);
        args[keys.length] = String.valueOf(timeout);
        sendCommand(h, "BRPOP", args);
    }

    // Redis command reference — BLMOVE (Redis 6.2+)
    @Override
    public void blmove(String source, String destination, String whereFrom, String whereTo,
                       double timeout, BulkResultHandler h) {
        sendCommand(h, "BLMOVE", new String[] {
            source, destination, whereFrom, whereTo, String.valueOf(timeout)
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — Set commands (Redis command group: Set)
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
    // RedisSession — Sorted Set commands (Redis command group: Sorted Set)
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
    // RedisSession — Pub/Sub commands (RESP spec — "Pub/Sub")
    // Once subscribed, the connection enters Pub/Sub mode and only
    // SUBSCRIBE, PSUBSCRIBE, UNSUBSCRIBE, PUNSUBSCRIBE, PING, and
    // RESET commands are valid.
    // ─────────────────────────────────────────────────────────────────────────

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
    // RedisSession — Transaction commands (MULTI/EXEC/DISCARD/WATCH)
    // RESP spec — commands inside MULTI are queued (+QUEUED) and executed
    // atomically by EXEC, which returns an array of results.
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
    // RedisSession — Scripting commands (EVAL/EVALSHA)
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
    // RedisSession — Stream commands (Redis command reference — Streams)
    // ─────────────────────────────────────────────────────────────────────────

    // Redis command reference — XADD
    @Override
    public void xadd(String key, String id, BulkResultHandler h, String... fieldsAndValues) {
        String[] args = new String[2 + fieldsAndValues.length];
        args[0] = key;
        args[1] = id;
        System.arraycopy(fieldsAndValues, 0, args, 2, fieldsAndValues.length);
        sendCommand(h, "XADD", args);
    }

    // Redis command reference — XLEN
    @Override
    public void xlen(String key, IntegerResultHandler h) {
        sendCommand(h, "XLEN", new String[] { key });
    }

    // Redis command reference — XRANGE
    @Override
    public void xrange(String key, String start, String end, ArrayResultHandler h) {
        sendCommand(h, "XRANGE", new String[] { key, start, end });
    }

    @Override
    public void xrange(String key, String start, String end, int count, ArrayResultHandler h) {
        sendCommand(h, "XRANGE", new String[] { key, start, end, "COUNT", String.valueOf(count) });
    }

    // Redis command reference — XREVRANGE
    @Override
    public void xrevrange(String key, String end, String start, ArrayResultHandler h) {
        sendCommand(h, "XREVRANGE", new String[] { key, end, start });
    }

    @Override
    public void xrevrange(String key, String end, String start, int count, ArrayResultHandler h) {
        sendCommand(h, "XREVRANGE", new String[] { key, end, start, "COUNT", String.valueOf(count) });
    }

    // Redis command reference — XREAD
    @Override
    public void xread(int count, long blockMillis, ArrayResultHandler h, String... keysAndIds) {
        ArrayList<String> args = new ArrayList<String>();
        if (count > 0) {
            args.add("COUNT");
            args.add(String.valueOf(count));
        }
        if (blockMillis >= 0) {
            args.add("BLOCK");
            args.add(String.valueOf(blockMillis));
        }
        args.add("STREAMS");
        for (String kv : keysAndIds) {
            args.add(kv);
        }
        sendCommand(h, "XREAD", args.toArray(new String[0]));
    }

    // Redis command reference — XTRIM
    @Override
    public void xtrim(String key, long maxLen, IntegerResultHandler h) {
        sendCommand(h, "XTRIM", new String[] { key, "MAXLEN", String.valueOf(maxLen) });
    }

    // Redis command reference — XACK
    @Override
    public void xack(String key, String group, IntegerResultHandler h, String... ids) {
        String[] args = new String[2 + ids.length];
        args[0] = key;
        args[1] = group;
        System.arraycopy(ids, 0, args, 2, ids.length);
        sendCommand(h, "XACK", args);
    }

    // Redis command reference — XGROUP CREATE
    @Override
    public void xgroupCreate(String key, String group, String id, StringResultHandler h) {
        sendCommand(h, "XGROUP", new String[] { "CREATE", key, group, id, "MKSTREAM" });
    }

    // Redis command reference — XGROUP DESTROY
    @Override
    public void xgroupDestroy(String key, String group, IntegerResultHandler h) {
        sendCommand(h, "XGROUP", new String[] { "DESTROY", key, group });
    }

    // Redis command reference — XPENDING
    @Override
    public void xpending(String key, String group, ArrayResultHandler h) {
        sendCommand(h, "XPENDING", new String[] { key, group });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RedisSession — Server commands
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
    // RedisSession — Generic command passthrough
    // Allows sending arbitrary Redis commands not covered by typed methods.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void command(ArrayResultHandler h, String command, String... args) {
        sendCommand(h, command, args);
    }

    @Override
    public void command(ArrayResultHandler h, String command, byte[]... args) {
        sendCommand(h, command, args);
    }
}
