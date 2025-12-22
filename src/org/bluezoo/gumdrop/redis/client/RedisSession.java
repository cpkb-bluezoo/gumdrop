/*
 * RedisSession.java
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

/**
 * Redis session interface for sending commands.
 *
 * <p>This interface is provided to handlers and provides methods for
 * all common Redis operations. Commands are sent asynchronously and
 * results are delivered via callbacks.
 *
 * <p>Multiple commands can be issued without waiting for responses
 * (pipelining). Redis processes commands in order and responses
 * arrive in order.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface RedisSession {

    // ─────────────────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Authenticates with a password (Redis 5 and earlier).
     *
     * @param password the password
     * @param handler the result handler
     */
    void auth(String password, StringResultHandler handler);

    /**
     * Authenticates with username and password (Redis 6+ ACL).
     *
     * @param username the username
     * @param password the password
     * @param handler the result handler
     */
    void auth(String username, String password, StringResultHandler handler);

    /**
     * Sends a PING command.
     *
     * @param handler receives "PONG"
     */
    void ping(StringResultHandler handler);

    /**
     * Sends a PING command with a message.
     *
     * @param message the message to echo
     * @param handler receives the message back
     */
    void ping(String message, BulkResultHandler handler);

    /**
     * Selects a database by index.
     *
     * @param index the database index (0-15 typically)
     * @param handler the result handler
     */
    void select(int index, StringResultHandler handler);

    /**
     * Echoes a message.
     *
     * @param message the message to echo
     * @param handler receives the message back
     */
    void echo(String message, BulkResultHandler handler);

    /**
     * Closes the connection gracefully.
     */
    void quit();

    /**
     * Closes the connection immediately.
     */
    void close();

    // ─────────────────────────────────────────────────────────────────────────
    // Strings
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gets the value of a key.
     *
     * @param key the key
     * @param handler receives the value or null
     */
    void get(String key, BulkResultHandler handler);

    /**
     * Sets a key to a string value.
     *
     * @param key the key
     * @param value the value
     * @param handler receives "OK"
     */
    void set(String key, String value, StringResultHandler handler);

    /**
     * Sets a key to a binary value.
     *
     * @param key the key
     * @param value the value bytes
     * @param handler receives "OK"
     */
    void set(String key, byte[] value, StringResultHandler handler);

    /**
     * Sets a key with expiration in seconds.
     *
     * @param key the key
     * @param seconds the expiration time in seconds
     * @param value the value
     * @param handler receives "OK"
     */
    void setex(String key, int seconds, String value, StringResultHandler handler);

    /**
     * Sets a key with expiration in milliseconds.
     *
     * @param key the key
     * @param milliseconds the expiration time in milliseconds
     * @param value the value
     * @param handler receives "OK"
     */
    void psetex(String key, long milliseconds, String value, StringResultHandler handler);

    /**
     * Sets a key only if it doesn't exist.
     *
     * @param key the key
     * @param value the value
     * @param handler receives true if set, false if key existed
     */
    void setnx(String key, String value, BooleanResultHandler handler);

    /**
     * Gets the old value and sets a new value.
     *
     * @param key the key
     * @param value the new value
     * @param handler receives the old value or null
     */
    void getset(String key, String value, BulkResultHandler handler);

    /**
     * Gets multiple keys.
     *
     * @param keys the keys
     * @param handler receives array of values (nulls for missing keys)
     */
    void mget(ArrayResultHandler handler, String... keys);

    /**
     * Sets multiple keys.
     *
     * @param handler receives "OK"
     * @param keysAndValues alternating keys and values
     */
    void mset(StringResultHandler handler, String... keysAndValues);

    /**
     * Increments an integer value.
     *
     * @param key the key
     * @param handler receives the new value
     */
    void incr(String key, IntegerResultHandler handler);

    /**
     * Increments by a specific amount.
     *
     * @param key the key
     * @param increment the amount to add
     * @param handler receives the new value
     */
    void incrby(String key, long increment, IntegerResultHandler handler);

    /**
     * Increments a float value.
     *
     * @param key the key
     * @param increment the amount to add
     * @param handler receives the new value as a string
     */
    void incrbyfloat(String key, double increment, BulkResultHandler handler);

    /**
     * Decrements an integer value.
     *
     * @param key the key
     * @param handler receives the new value
     */
    void decr(String key, IntegerResultHandler handler);

    /**
     * Decrements by a specific amount.
     *
     * @param key the key
     * @param decrement the amount to subtract
     * @param handler receives the new value
     */
    void decrby(String key, long decrement, IntegerResultHandler handler);

    /**
     * Appends a value to a string.
     *
     * @param key the key
     * @param value the value to append
     * @param handler receives the new string length
     */
    void append(String key, String value, IntegerResultHandler handler);

    /**
     * Gets the length of a string value.
     *
     * @param key the key
     * @param handler receives the length
     */
    void strlen(String key, IntegerResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Keys
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deletes one or more keys.
     *
     * @param handler receives the count of keys deleted
     * @param keys the keys to delete
     */
    void del(IntegerResultHandler handler, String... keys);

    /**
     * Checks if a key exists.
     *
     * @param key the key
     * @param handler receives true if exists
     */
    void exists(String key, BooleanResultHandler handler);

    /**
     * Checks how many of the specified keys exist.
     *
     * @param handler receives the count of existing keys
     * @param keys the keys to check
     */
    void exists(IntegerResultHandler handler, String... keys);

    /**
     * Sets an expiration time in seconds.
     *
     * @param key the key
     * @param seconds the expiration time
     * @param handler receives true if set, false if key doesn't exist
     */
    void expire(String key, int seconds, BooleanResultHandler handler);

    /**
     * Sets an expiration time in milliseconds.
     *
     * @param key the key
     * @param milliseconds the expiration time
     * @param handler receives true if set, false if key doesn't exist
     */
    void pexpire(String key, long milliseconds, BooleanResultHandler handler);

    /**
     * Sets an expiration time as a Unix timestamp (seconds).
     *
     * @param key the key
     * @param timestamp the Unix timestamp
     * @param handler receives true if set, false if key doesn't exist
     */
    void expireat(String key, long timestamp, BooleanResultHandler handler);

    /**
     * Gets the remaining time to live in seconds.
     *
     * @param key the key
     * @param handler receives TTL, -1 if no expiry, -2 if key doesn't exist
     */
    void ttl(String key, IntegerResultHandler handler);

    /**
     * Gets the remaining time to live in milliseconds.
     *
     * @param key the key
     * @param handler receives TTL, -1 if no expiry, -2 if key doesn't exist
     */
    void pttl(String key, IntegerResultHandler handler);

    /**
     * Removes the expiration from a key.
     *
     * @param key the key
     * @param handler receives true if removed, false if no expiry or key doesn't exist
     */
    void persist(String key, BooleanResultHandler handler);

    /**
     * Finds all keys matching a pattern.
     *
     * @param pattern the glob-style pattern
     * @param handler receives the matching keys
     */
    void keys(String pattern, ArrayResultHandler handler);

    /**
     * Renames a key.
     *
     * @param oldKey the current key name
     * @param newKey the new key name
     * @param handler receives "OK"
     */
    void rename(String oldKey, String newKey, StringResultHandler handler);

    /**
     * Renames a key only if the new key doesn't exist.
     *
     * @param oldKey the current key name
     * @param newKey the new key name
     * @param handler receives true if renamed, false if new key existed
     */
    void renamenx(String oldKey, String newKey, BooleanResultHandler handler);

    /**
     * Returns the type of a key.
     *
     * @param key the key
     * @param handler receives the type (string, list, set, zset, hash, stream)
     */
    void type(String key, StringResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Hashes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gets a hash field value.
     *
     * @param key the key
     * @param field the field name
     * @param handler receives the value or null
     */
    void hget(String key, String field, BulkResultHandler handler);

    /**
     * Sets a hash field.
     *
     * @param key the key
     * @param field the field name
     * @param value the value
     * @param handler receives count of fields added (0 if updated, 1 if new)
     */
    void hset(String key, String field, String value, IntegerResultHandler handler);

    /**
     * Sets a hash field with binary value.
     *
     * @param key the key
     * @param field the field name
     * @param value the value bytes
     * @param handler receives count of fields added
     */
    void hset(String key, String field, byte[] value, IntegerResultHandler handler);

    /**
     * Sets a hash field only if it doesn't exist.
     *
     * @param key the key
     * @param field the field name
     * @param value the value
     * @param handler receives true if set, false if field existed
     */
    void hsetnx(String key, String field, String value, BooleanResultHandler handler);

    /**
     * Gets multiple hash fields.
     *
     * @param key the key
     * @param handler receives array of values
     * @param fields the field names
     */
    void hmget(String key, ArrayResultHandler handler, String... fields);

    /**
     * Sets multiple hash fields.
     *
     * @param key the key
     * @param handler receives "OK"
     * @param fieldsAndValues alternating field names and values
     */
    void hmset(String key, StringResultHandler handler, String... fieldsAndValues);

    /**
     * Gets all fields and values in a hash.
     *
     * @param key the key
     * @param handler receives alternating field/value array
     */
    void hgetall(String key, ArrayResultHandler handler);

    /**
     * Gets all field names in a hash.
     *
     * @param key the key
     * @param handler receives array of field names
     */
    void hkeys(String key, ArrayResultHandler handler);

    /**
     * Gets all values in a hash.
     *
     * @param key the key
     * @param handler receives array of values
     */
    void hvals(String key, ArrayResultHandler handler);

    /**
     * Deletes hash fields.
     *
     * @param key the key
     * @param handler receives count of fields deleted
     * @param fields the field names
     */
    void hdel(String key, IntegerResultHandler handler, String... fields);

    /**
     * Checks if a hash field exists.
     *
     * @param key the key
     * @param field the field name
     * @param handler receives true if exists
     */
    void hexists(String key, String field, BooleanResultHandler handler);

    /**
     * Gets the number of fields in a hash.
     *
     * @param key the key
     * @param handler receives the count
     */
    void hlen(String key, IntegerResultHandler handler);

    /**
     * Increments a hash field by an integer.
     *
     * @param key the key
     * @param field the field name
     * @param increment the amount
     * @param handler receives the new value
     */
    void hincrby(String key, String field, long increment, IntegerResultHandler handler);

    /**
     * Increments a hash field by a float.
     *
     * @param key the key
     * @param field the field name
     * @param increment the amount
     * @param handler receives the new value as a string
     */
    void hincrbyfloat(String key, String field, double increment, BulkResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Lists
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pushes values to the left (head) of a list.
     *
     * @param key the key
     * @param handler receives the new list length
     * @param values the values to push
     */
    void lpush(String key, IntegerResultHandler handler, String... values);

    /**
     * Pushes values to the right (tail) of a list.
     *
     * @param key the key
     * @param handler receives the new list length
     * @param values the values to push
     */
    void rpush(String key, IntegerResultHandler handler, String... values);

    /**
     * Pops a value from the left (head) of a list.
     *
     * @param key the key
     * @param handler receives the value or null if empty
     */
    void lpop(String key, BulkResultHandler handler);

    /**
     * Pops a value from the right (tail) of a list.
     *
     * @param key the key
     * @param handler receives the value or null if empty
     */
    void rpop(String key, BulkResultHandler handler);

    /**
     * Gets a range of elements from a list.
     *
     * @param key the key
     * @param start the start index (0-based, negative from end)
     * @param stop the stop index (inclusive, negative from end)
     * @param handler receives the elements
     */
    void lrange(String key, int start, int stop, ArrayResultHandler handler);

    /**
     * Gets the length of a list.
     *
     * @param key the key
     * @param handler receives the length
     */
    void llen(String key, IntegerResultHandler handler);

    /**
     * Gets an element by index.
     *
     * @param key the key
     * @param index the index (negative from end)
     * @param handler receives the element or null
     */
    void lindex(String key, int index, BulkResultHandler handler);

    /**
     * Sets an element by index.
     *
     * @param key the key
     * @param index the index
     * @param value the new value
     * @param handler receives "OK"
     */
    void lset(String key, int index, String value, StringResultHandler handler);

    /**
     * Trims a list to the specified range.
     *
     * @param key the key
     * @param start the start index
     * @param stop the stop index
     * @param handler receives "OK"
     */
    void ltrim(String key, int start, int stop, StringResultHandler handler);

    /**
     * Removes elements from a list.
     *
     * @param key the key
     * @param count number to remove (0=all, positive=from head, negative=from tail)
     * @param value the value to remove
     * @param handler receives the count removed
     */
    void lrem(String key, int count, String value, IntegerResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Sets
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds members to a set.
     *
     * @param key the key
     * @param handler receives count of new members added
     * @param members the members to add
     */
    void sadd(String key, IntegerResultHandler handler, String... members);

    /**
     * Removes members from a set.
     *
     * @param key the key
     * @param handler receives count of members removed
     * @param members the members to remove
     */
    void srem(String key, IntegerResultHandler handler, String... members);

    /**
     * Gets all members of a set.
     *
     * @param key the key
     * @param handler receives the members
     */
    void smembers(String key, ArrayResultHandler handler);

    /**
     * Checks if a value is a member of a set.
     *
     * @param key the key
     * @param member the member to check
     * @param handler receives true if member exists
     */
    void sismember(String key, String member, BooleanResultHandler handler);

    /**
     * Gets the number of members in a set.
     *
     * @param key the key
     * @param handler receives the cardinality
     */
    void scard(String key, IntegerResultHandler handler);

    /**
     * Pops a random member from a set.
     *
     * @param key the key
     * @param handler receives the member or null if empty
     */
    void spop(String key, BulkResultHandler handler);

    /**
     * Gets a random member without removing it.
     *
     * @param key the key
     * @param handler receives the member or null if empty
     */
    void srandmember(String key, BulkResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Sorted Sets
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a member with a score to a sorted set.
     *
     * @param key the key
     * @param score the score
     * @param member the member
     * @param handler receives count of new members added
     */
    void zadd(String key, double score, String member, IntegerResultHandler handler);

    /**
     * Gets the score of a member.
     *
     * @param key the key
     * @param member the member
     * @param handler receives the score or null if not found
     */
    void zscore(String key, String member, BulkResultHandler handler);

    /**
     * Gets members in a range by index.
     *
     * @param key the key
     * @param start the start index
     * @param stop the stop index
     * @param handler receives the members
     */
    void zrange(String key, int start, int stop, ArrayResultHandler handler);

    /**
     * Gets members in a range by index with scores.
     *
     * @param key the key
     * @param start the start index
     * @param stop the stop index
     * @param handler receives alternating member/score pairs
     */
    void zrangeWithScores(String key, int start, int stop, ArrayResultHandler handler);

    /**
     * Gets members in reverse order by index.
     *
     * @param key the key
     * @param start the start index
     * @param stop the stop index
     * @param handler receives the members
     */
    void zrevrange(String key, int start, int stop, ArrayResultHandler handler);

    /**
     * Gets the rank of a member (0-based, lowest score first).
     *
     * @param key the key
     * @param member the member
     * @param handler receives the rank or null if not found
     */
    void zrank(String key, String member, IntegerResultHandler handler);

    /**
     * Removes members from a sorted set.
     *
     * @param key the key
     * @param handler receives count of members removed
     * @param members the members to remove
     */
    void zrem(String key, IntegerResultHandler handler, String... members);

    /**
     * Gets the number of members in a sorted set.
     *
     * @param key the key
     * @param handler receives the cardinality
     */
    void zcard(String key, IntegerResultHandler handler);

    /**
     * Increments a member's score.
     *
     * @param key the key
     * @param increment the amount to add
     * @param member the member
     * @param handler receives the new score
     */
    void zincrby(String key, double increment, String member, BulkResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Pub/Sub
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subscribes to channels.
     *
     * <p>After subscribing, the connection enters Pub/Sub mode and can only
     * execute SUBSCRIBE, PSUBSCRIBE, UNSUBSCRIBE, PUNSUBSCRIBE, PING, and QUIT.
     *
     * @param handler receives messages and subscription confirmations
     * @param channels the channels to subscribe to
     */
    void subscribe(MessageHandler handler, String... channels);

    /**
     * Subscribes to channel patterns.
     *
     * @param handler receives messages and subscription confirmations
     * @param patterns the glob-style patterns to subscribe to
     */
    void psubscribe(MessageHandler handler, String... patterns);

    /**
     * Unsubscribes from channels.
     *
     * @param channels the channels to unsubscribe from (empty = all)
     */
    void unsubscribe(String... channels);

    /**
     * Unsubscribes from patterns.
     *
     * @param patterns the patterns to unsubscribe from (empty = all)
     */
    void punsubscribe(String... patterns);

    /**
     * Publishes a message to a channel.
     *
     * @param channel the channel
     * @param message the message
     * @param handler receives the number of subscribers who received the message
     */
    void publish(String channel, String message, IntegerResultHandler handler);

    /**
     * Publishes a binary message to a channel.
     *
     * @param channel the channel
     * @param message the message bytes
     * @param handler receives the number of subscribers who received the message
     */
    void publish(String channel, byte[] message, IntegerResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Transactions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Marks the start of a transaction.
     *
     * <p>After MULTI, all commands are queued until EXEC is called.
     *
     * @param handler receives "OK"
     */
    void multi(StringResultHandler handler);

    /**
     * Executes all queued commands in a transaction.
     *
     * @param handler receives array of results, or null if WATCH failed
     */
    void exec(ArrayResultHandler handler);

    /**
     * Discards all queued commands.
     *
     * @param handler receives "OK"
     */
    void discard(StringResultHandler handler);

    /**
     * Watches keys for changes (optimistic locking).
     *
     * @param handler receives "OK"
     * @param keys the keys to watch
     */
    void watch(StringResultHandler handler, String... keys);

    /**
     * Unwatches all keys.
     *
     * @param handler receives "OK"
     */
    void unwatch(StringResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Scripting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes a Lua script.
     *
     * @param script the Lua script
     * @param numKeys the number of keys
     * @param keys the keys (available as KEYS[1], KEYS[2], etc.)
     * @param args additional arguments (available as ARGV[1], ARGV[2], etc.)
     * @param handler receives the script result
     */
    void eval(String script, int numKeys, String[] keys, String[] args, ArrayResultHandler handler);

    /**
     * Executes a cached script by SHA1 hash.
     *
     * @param sha1 the script SHA1 hash
     * @param numKeys the number of keys
     * @param keys the keys
     * @param args additional arguments
     * @param handler receives the script result
     */
    void evalsha(String sha1, int numKeys, String[] keys, String[] args, ArrayResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Server
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gets server information.
     *
     * @param handler receives the info string
     */
    void info(BulkResultHandler handler);

    /**
     * Gets server information for a specific section.
     *
     * @param section the section name
     * @param handler receives the info string
     */
    void info(String section, BulkResultHandler handler);

    /**
     * Gets the number of keys in the current database.
     *
     * @param handler receives the count
     */
    void dbsize(IntegerResultHandler handler);

    /**
     * Flushes the current database.
     *
     * @param handler receives "OK"
     */
    void flushdb(StringResultHandler handler);

    /**
     * Flushes all databases.
     *
     * @param handler receives "OK"
     */
    void flushall(StringResultHandler handler);

    /**
     * Returns the server time.
     *
     * @param handler receives array of [unix timestamp seconds, microseconds]
     */
    void time(ArrayResultHandler handler);

    // ─────────────────────────────────────────────────────────────────────────
    // Generic command
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a raw command with string arguments.
     *
     * <p>Use this for commands not covered by specific methods.
     *
     * @param handler receives the result
     * @param command the command name
     * @param args the arguments
     */
    void command(ArrayResultHandler handler, String command, String... args);

    /**
     * Sends a raw command with byte array arguments.
     *
     * @param handler receives the result
     * @param command the command name
     * @param args the arguments as byte arrays
     */
    void command(ArrayResultHandler handler, String command, byte[]... args);

}
