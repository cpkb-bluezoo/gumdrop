# Redis Client Examples

These examples demonstrate the Gumdrop Redis client's asynchronous, event-driven architecture.

## Prerequisites

A Redis server must be running. The easiest way is with Docker:

```bash
docker run -d -p 6379:6379 redis
```

## Building

From the Gumdrop root directory:

```bash
ant build
javac -cp build -d build examples/redis-client/*.java
```

## Running the Examples

### RedisClientExample

Demonstrates basic Redis operations with the **simplified standalone API**:

```bash
java -cp build RedisClientExample [host] [port]
```

This example shows:
- **No Gumdrop setup needed** - infrastructure is managed automatically
- String commands (SET, GET, INCR, INCRBY)
- Key expiration (SETEX, TTL)
- Hash operations (HSET, HGET, HGETALL)
- List operations (RPUSH, LRANGE, LPOP)
- Key pattern matching (KEYS)
- Automatic cleanup and shutdown

The simplified API:
```java
// Just create and connect - that's it!
RedisClient client = new RedisClient("localhost", 6379);
client.connect(handler);
// Infrastructure auto-starts on connect, auto-stops when done
```

### RedisPubSubExample

Demonstrates Redis publish/subscribe:

```bash
java -cp build RedisPubSubExample [host] [port]
```

This example shows:
- Creating multiple Redis connections
- Subscribing to channels
- Publishing messages
- Handling subscription confirmations
- Pattern-based subscriptions

### RedisServerIntegrationExample

Demonstrates using Redis within a Gumdrop server with **SelectorLoop affinity**:

```bash
java -cp build RedisServerIntegrationExample [server-port] [redis-host] [redis-port]

# Then test with:
telnet localhost 9000
```

This example shows:
- **SelectorLoop affinity** - Redis client uses the same event loop as the server
- Per-SelectorLoop Redis connection pooling
- Rate limiting using Redis INCR and EXPIRE
- Session storage using Redis hashes
- Commands: `stats`, `history`, `quit`

Server integration pattern:
```java
// Get SelectorLoop from the server connection
SelectorLoop loop = getSelectorLoop();

// Create Redis client with same SelectorLoop for optimal performance
RedisClient client = new RedisClient(loop, "localhost", 6379);
client.connect(handler);
```

## API Patterns

### Standalone Usage (New Simplified API)

For scripts, tools, and standalone applications:

```java
// No setup needed!
RedisClient client = new RedisClient("localhost", 6379);
client.connect(new RedisConnectionReady() {
    public void handleReady(RedisSession session) {
        session.get("key", handler);
    }
    // ... callbacks
});
// JVM exits cleanly when done
```

### Server Integration (SelectorLoop Affinity)

For use within Gumdrop servers, pass the SelectorLoop explicitly:

```java
// In a Connection handler
RedisClient client = new RedisClient(getSelectorLoop(), "localhost", 6379);
```

Benefits of SelectorLoop affinity:
- No thread context switches between server and Redis I/O
- Simpler state management (everything on one thread)
- Better cache locality

## Error Handling

All result handlers have `handleError(String error, RedisSession session)` methods:

```java
session.get("key", new BulkResultHandler() {
    public void handleResult(byte[] value, RedisSession s) {
        // Success
    }
    
    public void handleNull(RedisSession s) {
        // Key doesn't exist
    }
    
    public void handleError(String error, RedisSession s) {
        // Redis error (WRONGTYPE, NOAUTH, etc.)
    }
});
```

## Connection Lifecycle

Connection events are delivered via `ClientHandler` methods:

- `onConnected(ConnectionInfo)` - TCP connection established
- `onTLSStarted(TLSInfo)` - TLS handshake complete (if secure)
- `handleReady(RedisSession)` - Ready for commands
- `onDisconnected()` - Connection closed
- `onError(Exception)` - Connection error

## Supported Commands

See `RedisSession` interface for the complete list including:
- Strings: GET, SET, SETEX, INCR, DECR, APPEND, etc.
- Keys: DEL, EXISTS, EXPIRE, TTL, KEYS, RENAME, etc.
- Hashes: HGET, HSET, HGETALL, HMGET, HMSET, etc.
- Lists: LPUSH, RPUSH, LPOP, RPOP, LRANGE, etc.
- Sets: SADD, SREM, SMEMBERS, SISMEMBER, etc.
- Sorted Sets: ZADD, ZRANGE, ZSCORE, ZRANK, etc.
- Pub/Sub: SUBSCRIBE, PSUBSCRIBE, PUBLISH, etc.
- Transactions: MULTI, EXEC, WATCH, etc.
- Scripting: EVAL, EVALSHA
