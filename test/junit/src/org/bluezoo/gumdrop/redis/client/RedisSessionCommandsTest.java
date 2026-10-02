/*
 * RedisSessionCommandsTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies that every {@link RedisSession} command method on
 * {@link RedisClientProtocolHandler} encodes and sends the expected RESP
 * command verb.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisSessionCommandsTest {

    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisSession session;

    @Before
    public void setUp() {
        AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        RedisClientProtocolHandler handler = new RedisClientProtocolHandler(
                new RedisClientProtocolHandlerTest.StubConnectionReady(ref));
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        session = ref.get();
        assertNotNull(session);
    }

    private void assertSent(String verb) {
        assertFalse(endpoint.sentBuffers.isEmpty());
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < endpoint.sentBuffers.size(); i++) {
            ByteBuffer buf = endpoint.sentBuffers.get(i);
            buf.rewind();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            all.append(new String(bytes, StandardCharsets.UTF_8));
        }
        assertTrue(all.toString(), all.indexOf(verb) >= 0);
    }

    @Test
    public void commandAuth() {
        session.auth("k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("AUTH");
    }

    @Test
    public void commandAuth2() {
        session.auth("k", "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("AUTH");
    }

    @Test
    public void commandHello() {
        session.hello(2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("HELLO");
    }

    @Test
    public void commandHello2() {
        session.hello(2, "k", "k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("HELLO");
    }

    @Test
    public void commandClientSetName() {
        session.clientSetName("k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("CLIENT");
    }

    @Test
    public void commandClientGetName() {
        session.clientGetName(new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("CLIENT");
    }

    @Test
    public void commandClientId() {
        session.clientId(new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("CLIENT");
    }

    @Test
    public void commandPing() {
        session.ping(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("PING");
    }

    @Test
    public void commandPing2() {
        session.ping("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("PING");
    }

    @Test
    public void commandSelect() {
        session.select(2, new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("SELECT");
    }

    @Test
    public void commandEcho() {
        session.echo("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("ECHO");
    }

    @Test
    public void commandQuit() {
        session.quit();
        assertSent("QUIT");
    }

    @Test
    public void commandReset() {
        session.reset(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("RESET");
    }

    @Test
    public void commandGet() {
        session.get("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("GET");
    }

    @Test
    public void commandSet() {
        session.set("k", "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("SET");
    }

    @Test
    public void commandSet2() {
        session.set("k", new byte[] {1, 2}, new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("SET");
    }

    @Test
    public void commandSetex() {
        session.setex("k", 2, "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("SETEX");
    }

    @Test
    public void commandPsetex() {
        session.psetex("k", 3L, "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("PSETEX");
    }

    @Test
    public void commandSetnx() {
        session.setnx("k", "k", new BoolStub());
        assertSent("SETNX");
    }

    @Test
    public void commandGetset() {
        session.getset("k", "k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("GETSET");
    }

    @Test
    public void commandMget() {
        session.mget(new RedisClientProtocolHandlerTest.StubArrayHandler(), "a", "b");
        assertSent("MGET");
    }

    @Test
    public void commandMset() {
        session.mset(new RedisClientProtocolHandlerTest.StubStringHandler(), "a", "b");
        assertSent("MSET");
    }

    @Test
    public void commandIncr() {
        session.incr("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("INCR");
    }

    @Test
    public void commandIncrby() {
        session.incrby("k", 3L, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("INCRBY");
    }

    @Test
    public void commandIncrbyfloat() {
        session.incrbyfloat("k", 1.5, new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("INCRBYFLOAT");
    }

    @Test
    public void commandDecr() {
        session.decr("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("DECR");
    }

    @Test
    public void commandDecrby() {
        session.decrby("k", 3L, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("DECRBY");
    }

    @Test
    public void commandAppend() {
        session.append("k", "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("APPEND");
    }

    @Test
    public void commandStrlen() {
        session.strlen("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("STRLEN");
    }

    @Test
    public void commandDel() {
        session.del(new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("DEL");
    }

    @Test
    public void commandExists() {
        session.exists("k", new BoolStub());
        assertSent("EXISTS");
    }

    @Test
    public void commandExists2() {
        session.exists(new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("EXISTS");
    }

    @Test
    public void commandExpire() {
        session.expire("k", 2, new BoolStub());
        assertSent("EXPIRE");
    }

    @Test
    public void commandPexpire() {
        session.pexpire("k", 3L, new BoolStub());
        assertSent("PEXPIRE");
    }

    @Test
    public void commandExpireat() {
        session.expireat("k", 3L, new BoolStub());
        assertSent("EXPIREAT");
    }

    @Test
    public void commandTtl() {
        session.ttl("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("TTL");
    }

    @Test
    public void commandPttl() {
        session.pttl("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("PTTL");
    }

    @Test
    public void commandPersist() {
        session.persist("k", new BoolStub());
        assertSent("PERSIST");
    }

    @Test
    public void commandKeys() {
        session.keys("k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("KEYS");
    }

    @Test
    public void commandRename() {
        session.rename("k", "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("RENAME");
    }

    @Test
    public void commandRenamenx() {
        session.renamenx("k", "k", new BoolStub());
        assertSent("RENAMENX");
    }

    @Test
    public void commandType() {
        session.type("k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("TYPE");
    }

    @Test
    public void commandScan() {
        session.scan("k", new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("SCAN");
    }

    @Test
    public void commandScan2() {
        session.scan("k", "k", 2, new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("SCAN");
    }

    @Test
    public void commandHscan() {
        session.hscan("k", "k", new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("HSCAN");
    }

    @Test
    public void commandHscan2() {
        session.hscan("k", "k", "k", 2, new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("HSCAN");
    }

    @Test
    public void commandSscan() {
        session.sscan("k", "k", new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("SSCAN");
    }

    @Test
    public void commandSscan2() {
        session.sscan("k", "k", "k", 2, new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("SSCAN");
    }

    @Test
    public void commandZscan() {
        session.zscan("k", "k", new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("ZSCAN");
    }

    @Test
    public void commandZscan2() {
        session.zscan("k", "k", "k", 2, new RedisClientProtocolHandlerTest.StubScanHandler());
        assertSent("ZSCAN");
    }

    @Test
    public void commandHget() {
        session.hget("k", "k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("HGET");
    }

    @Test
    public void commandHset() {
        session.hset("k", "k", "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("HSET");
    }

    @Test
    public void commandHset2() {
        session.hset("k", "k", new byte[] {1, 2}, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("HSET");
    }

    @Test
    public void commandHsetnx() {
        session.hsetnx("k", "k", "k", new BoolStub());
        assertSent("HSETNX");
    }

    @Test
    public void commandHmget() {
        session.hmget("k", new RedisClientProtocolHandlerTest.StubArrayHandler(), "a", "b");
        assertSent("HMGET");
    }

    @Test
    public void commandHmset() {
        session.hmset("k", new RedisClientProtocolHandlerTest.StubStringHandler(), "a", "b");
        assertSent("HMSET");
    }

    @Test
    public void commandHgetall() {
        session.hgetall("k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("HGETALL");
    }

    @Test
    public void commandHkeys() {
        session.hkeys("k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("HKEYS");
    }

    @Test
    public void commandHvals() {
        session.hvals("k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("HVALS");
    }

    @Test
    public void commandHdel() {
        session.hdel("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("HDEL");
    }

    @Test
    public void commandHexists() {
        session.hexists("k", "k", new BoolStub());
        assertSent("HEXISTS");
    }

    @Test
    public void commandHlen() {
        session.hlen("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("HLEN");
    }

    @Test
    public void commandHincrby() {
        session.hincrby("k", "k", 3L, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("HINCRBY");
    }

    @Test
    public void commandHincrbyfloat() {
        session.hincrbyfloat("k", "k", 1.5, new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("HINCRBYFLOAT");
    }

    @Test
    public void commandLpush() {
        session.lpush("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("LPUSH");
    }

    @Test
    public void commandRpush() {
        session.rpush("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("RPUSH");
    }

    @Test
    public void commandLpop() {
        session.lpop("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("LPOP");
    }

    @Test
    public void commandRpop() {
        session.rpop("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("RPOP");
    }

    @Test
    public void commandLrange() {
        session.lrange("k", 2, 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("LRANGE");
    }

    @Test
    public void commandLlen() {
        session.llen("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("LLEN");
    }

    @Test
    public void commandLindex() {
        session.lindex("k", 2, new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("LINDEX");
    }

    @Test
    public void commandLset() {
        session.lset("k", 2, "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("LSET");
    }

    @Test
    public void commandLtrim() {
        session.ltrim("k", 2, 2, new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("LTRIM");
    }

    @Test
    public void commandLrem() {
        session.lrem("k", 2, "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("LREM");
    }

    @Test
    public void commandBlpop() {
        session.blpop(1.5, new RedisClientProtocolHandlerTest.StubArrayHandler(), "a", "b");
        assertSent("BLPOP");
    }

    @Test
    public void commandBrpop() {
        session.brpop(1.5, new RedisClientProtocolHandlerTest.StubArrayHandler(), "a", "b");
        assertSent("BRPOP");
    }

    @Test
    public void commandBlmove() {
        session.blmove("k", "k", "k", "k", 1.5, new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("BLMOVE");
    }

    @Test
    public void commandSadd() {
        session.sadd("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("SADD");
    }

    @Test
    public void commandSrem() {
        session.srem("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("SREM");
    }

    @Test
    public void commandSmembers() {
        session.smembers("k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("SMEMBERS");
    }

    @Test
    public void commandSismember() {
        session.sismember("k", "k", new BoolStub());
        assertSent("SISMEMBER");
    }

    @Test
    public void commandScard() {
        session.scard("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("SCARD");
    }

    @Test
    public void commandSpop() {
        session.spop("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("SPOP");
    }

    @Test
    public void commandSrandmember() {
        session.srandmember("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("SRANDMEMBER");
    }

    @Test
    public void commandZadd() {
        session.zadd("k", 1.5, "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("ZADD");
    }

    @Test
    public void commandZscore() {
        session.zscore("k", "k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("ZSCORE");
    }

    @Test
    public void commandZrange() {
        session.zrange("k", 2, 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("ZRANGE");
    }

    @Test
    public void commandZrangeWithScores() {
        session.zrangeWithScores("k", 2, 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("ZRANGE");
    }

    @Test
    public void commandZrevrange() {
        session.zrevrange("k", 2, 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("ZREVRANGE");
    }

    @Test
    public void commandZrank() {
        session.zrank("k", "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("ZRANK");
    }

    @Test
    public void commandZrem() {
        session.zrem("k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("ZREM");
    }

    @Test
    public void commandZcard() {
        session.zcard("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("ZCARD");
    }

    @Test
    public void commandZincrby() {
        session.zincrby("k", 1.5, "k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("ZINCRBY");
    }

    @Test
    public void commandSubscribe() {
        session.subscribe(new NoOpMessages(), "a", "b");
        assertSent("SUBSCRIBE");
    }

    @Test
    public void commandPsubscribe() {
        session.psubscribe(new NoOpMessages(), "a", "b");
        assertSent("PSUBSCRIBE");
    }

    @Test
    public void commandUnsubscribe() {
        session.unsubscribe("a", "b");
        assertSent("UNSUBSCRIBE");
    }

    @Test
    public void commandPunsubscribe() {
        session.punsubscribe("a", "b");
        assertSent("PUNSUBSCRIBE");
    }

    @Test
    public void commandPublish() {
        session.publish("k", "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("PUBLISH");
    }

    @Test
    public void commandPublish2() {
        session.publish("k", new byte[] {1, 2}, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("PUBLISH");
    }

    @Test
    public void commandMulti() {
        session.multi(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("MULTI");
    }

    @Test
    public void commandExec() {
        session.exec(new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("EXEC");
    }

    @Test
    public void commandDiscard() {
        session.discard(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("DISCARD");
    }

    @Test
    public void commandWatch() {
        session.watch(new RedisClientProtocolHandlerTest.StubStringHandler(), "a", "b");
        assertSent("WATCH");
    }

    @Test
    public void commandUnwatch() {
        session.unwatch(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("UNWATCH");
    }

    @Test
    public void commandEval() {
        session.eval("k", 2, new String[] {"x"}, new String[] {"x"}, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("EVAL");
    }

    @Test
    public void commandEvalsha() {
        session.evalsha("k", 2, new String[] {"x"}, new String[] {"x"}, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("EVALSHA");
    }

    @Test
    public void commandXadd() {
        session.xadd("k", "k", new RedisClientProtocolHandlerTest.StubBulkHandler(), "a", "b");
        assertSent("XADD");
    }

    @Test
    public void commandXlen() {
        session.xlen("k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("XLEN");
    }

    @Test
    public void commandXrange() {
        session.xrange("k", "k", "k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("XRANGE");
    }

    @Test
    public void commandXrange2() {
        session.xrange("k", "k", "k", 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("XRANGE");
    }

    @Test
    public void commandXrevrange() {
        session.xrevrange("k", "k", "k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("XREVRANGE");
    }

    @Test
    public void commandXrevrange2() {
        session.xrevrange("k", "k", "k", 2, new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("XREVRANGE");
    }

    @Test
    public void commandXread() {
        session.xread(2, 3L, new RedisClientProtocolHandlerTest.StubArrayHandler(), "a", "b");
        assertSent("COUNT");
    }

    @Test
    public void commandXtrim() {
        session.xtrim("k", 3L, new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("XTRIM");
    }

    @Test
    public void commandXack() {
        session.xack("k", "k", new RedisClientProtocolHandlerTest.StubIntHandler(), "a", "b");
        assertSent("XACK");
    }

    @Test
    public void commandXgroupCreate() {
        session.xgroupCreate("k", "k", "k", new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("XGROUP");
    }

    @Test
    public void commandXgroupDestroy() {
        session.xgroupDestroy("k", "k", new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("XGROUP");
    }

    @Test
    public void commandXpending() {
        session.xpending("k", "k", new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("XPENDING");
    }

    @Test
    public void commandInfo() {
        session.info(new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("INFO");
    }

    @Test
    public void commandInfo2() {
        session.info("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertSent("INFO");
    }

    @Test
    public void commandDbsize() {
        session.dbsize(new RedisClientProtocolHandlerTest.StubIntHandler());
        assertSent("DBSIZE");
    }

    @Test
    public void commandFlushdb() {
        session.flushdb(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("FLUSHDB");
    }

    @Test
    public void commandFlushall() {
        session.flushall(new RedisClientProtocolHandlerTest.StubStringHandler());
        assertSent("FLUSHALL");
    }

    @Test
    public void commandTime() {
        session.time(new RedisClientProtocolHandlerTest.StubArrayHandler());
        assertSent("TIME");
    }

    private static final class BoolStub implements BooleanResultHandler {
        @Override
        public void handleResult(boolean value, RedisSession s) {
        }

        @Override
        public void handleError(String error, RedisSession s) {
        }
    }

    private static final class NoOpMessages implements MessageHandler {
        @Override
        public void handleMessage(String channel, byte[] message) {
        }

        @Override
        public void handlePatternMessage(String pattern, String channel,
                                         byte[] message) {
        }

        @Override
        public void handleSubscribed(String channel, int subscriptionCount) {
        }

        @Override
        public void handlePatternSubscribed(String pattern,
                                            int subscriptionCount) {
        }

        @Override
        public void handleUnsubscribed(String channel,
                                       int subscriptionCount) {
        }

        @Override
        public void handlePatternUnsubscribed(String pattern,
                                              int subscriptionCount) {
        }

        @Override
        public void handleError(String error) {
        }
    }
}
