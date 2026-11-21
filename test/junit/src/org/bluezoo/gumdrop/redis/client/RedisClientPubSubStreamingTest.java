/*
 * RedisClientPubSubStreamingTest.java
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pub/Sub deliveries and pushes as streams: a message body as chunks in
 * constant memory, the channel and pattern whole and bounded, a message the
 * handler skips or whose channel is too long dropped without disturbing the
 * next, pause and resume, and the pushes that are not deliveries going to the
 * push handler as events, and to a cache as invalidations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientPubSubStreamingTest {

    /** A message handler that records, and chooses what to do with each message. */
    private static class Rec implements MessageHandler {
        final List<String> log = new ArrayList<String>();
        ReplyFlow afterBegin = ReplyFlow.CONTINUE;
        ReplyFlow afterFirstChunk = ReplyFlow.CONTINUE;
        boolean cancelAtFirstChunk;
        ReplyStream stream;
        long total;
        int chunks;
        int largest;
        private int chunkOfMessage;

        @Override
        public ReplyFlow messageBegin(MessageHeader header, long length, ReplyStream s) {
            stream = s;
            chunkOfMessage = 0;
            log.add("begin:" + header + ":" + length);
            return afterBegin;
        }

        @Override
        public ReplyFlow messageContent(ByteBuffer chunk) {
            chunks++;
            total += chunk.remaining();
            largest = Math.max(largest, chunk.remaining());
            chunk.position(chunk.limit());
            if (++chunkOfMessage == 1) {
                if (cancelAtFirstChunk) {
                    stream.cancel();
                }
                return afterFirstChunk;
            }
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void messageEnd() {
            log.add("end");
        }

        @Override
        public void handleSubscribed(String channel, int count) {
            log.add("subscribed:" + channel + ":" + count);
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
            log.add("psubscribed:" + pattern + ":" + count);
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
            log.add("unsubscribed:" + channel + ":" + count);
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
            log.add("punsubscribed:" + pattern + ":" + count);
        }

        @Override
        public void handleError(String error) {
            log.add("error:" + error.split(" ")[0]);
        }
    }

    private RedisClientProtocolHandler handler;
    private RedisClientStreamingTest.Ep endpoint;
    private RedisSession session;

    @Before
    public void setUp() {
        final AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        handler = new RedisClientProtocolHandler(new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession s) {
                ref.set(s);
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint ep) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
            }

            @Override
            public void onDisconnected() {
            }
        });
        endpoint = new RedisClientStreamingTest.Ep();
        handler.connected(endpoint);
        session = ref.get();
    }

    private ByteBuffer feed(String resp) {
        ByteBuffer buf = ByteBuffer.wrap(resp.getBytes(StandardCharsets.ISO_8859_1));
        handler.receive(buf);
        return buf;
    }

    private static String message(String channel, String body) {
        return "*3\r\n$7\r\nmessage\r\n$" + channel.length() + "\r\n" + channel + "\r\n$"
                + body.length() + "\r\n" + body + "\r\n";
    }

    // ── deliveries ──

    @Test
    public void aMessageBodyArrivesAsChunksWhateverItsSize() {
        Rec rec = new Rec();
        session.subscribe(rec, "news");
        int size = 3 * 1024 * 1024;
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$" + size + "\r\n");
        byte[] piece = new byte[16 * 1024];
        for (int sent = 0; sent < size; sent += piece.length) {
            ByteBuffer b = ByteBuffer.wrap(piece);
            handler.receive(b);
            assertFalse(b.hasRemaining());
        }
        feed("\r\n");
        assertEquals(size, rec.total);
        assertEquals(piece.length, rec.largest);
        assertEquals("[begin:MESSAGE news:" + size + ", end]", rec.log.toString());
    }

    @Test
    public void everyKindOfMessageIsToldWithItsHeaderInAnySplit() {
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        String wire = message("c", "one")
                + "*4\r\n$8\r\npmessage\r\n$2\r\np*\r\n$2\r\npq\r\n$3\r\ntwo\r\n"
                + "*3\r\n$8\r\nsmessage\r\n$1\r\ns\r\n$5\r\nthree\r\n"
                + ">3\r\n$7\r\nmessage\r\n$1\r\nr\r\n$4\r\nfour\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("[begin:MESSAGE c:3, end, begin:PATTERN p* pq:3, end, begin:SHARD s:5, end,"
                + " begin:MESSAGE r:4, end]", rec.log.toString());
    }

    @Test
    public void aSkippedMessageIsDroppedAndTheNextIsDelivered() {
        Rec rec = new Rec();
        rec.afterFirstChunk = ReplyFlow.SKIP;
        session.subscribe(rec, "c");
        feed(message("c", "first message") + message("c", "second"));
        // the handler skips after the first chunk of each: both are cut
        assertEquals("[begin:MESSAGE c:13, begin:MESSAGE c:6]", rec.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void skippingAtTheBeginDropsThatMessageOnly() {
        final Rec rec = new Rec() {
            private int begun;

            @Override
            public ReplyFlow messageBegin(MessageHeader header, long length, ReplyStream s) {
                super.messageBegin(header, length, s);
                return ++begun == 1 ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
            }
        };
        session.subscribe(rec, "c");
        feed(message("c", "first") + message("c", "second"));
        assertEquals("[begin:MESSAGE c:5, begin:MESSAGE c:6, end]", rec.log.toString());
    }

    @Test
    public void aChannelOverTheLimitDropsItsMessageAndIsReported() {
        handler.setMaxField(8);
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        feed(message("a-channel-that-is-far-too-long", "lost") + message("c", "kept"));
        assertEquals("[error:TOOLARGE, begin:MESSAGE c:4, end]", rec.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void aPatternOverTheLimitDropsItsMessageToo() {
        handler.setMaxField(8);
        Rec rec = new Rec();
        session.psubscribe(rec, "p");
        feed("*4\r\n$8\r\npmessage\r\n$20\r\naaaaaaaaaaaaaaaaaaaa\r\n$1\r\nc\r\n$4\r\nlost\r\n"
                + "*4\r\n$8\r\npmessage\r\n$1\r\np\r\n$1\r\nc\r\n$4\r\nkept\r\n");
        assertEquals("[error:TOOLARGE, begin:PATTERN p c:4, end]", rec.log.toString());
    }

    @Test
    public void aPausedMessageStopsReadingAndResumesTheSameMessage() {
        Rec rec = new Rec();
        rec.afterFirstChunk = ReplyFlow.PAUSE;
        session.subscribe(rec, "c");
        ByteBuffer first = feed(message("c", "0123456789") + message("c", "next"));
        assertEquals(0, first.remaining());
        assertEquals(1, endpoint.pauses);
        assertEquals("[begin:MESSAGE c:10]", rec.log.toString());
        rec.stream.resume();
        // the second message pauses at its first chunk too
        assertEquals("[begin:MESSAGE c:10, end, begin:MESSAGE c:4]", rec.log.toString());
        rec.stream.resume();
        assertEquals("[begin:MESSAGE c:10, end, begin:MESSAGE c:4, end]", rec.log.toString());
        assertEquals(1, endpoint.resumes);
    }

    @Test
    public void cancellingAMessageGivesUpThatMessageOnly() {
        Rec rec = new Rec();
        rec.cancelAtFirstChunk = true;
        session.subscribe(rec, "c");
        feed(message("c", "0123456789") + message("c", "next"));
        assertEquals("[begin:MESSAGE c:10, begin:MESSAGE c:4]", rec.log.toString());
    }

    @Test
    public void confirmationsAreToldWithTheirNames() {
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        feed("*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1\r\n");
        feed("*3\r\n$11\r\nunsubscribe\r\n$1\r\nc\r\n:0\r\n");
        assertEquals("[subscribed:c:1, unsubscribed:c:0]", rec.log.toString());
    }

    @Test
    public void anUnsubscribeWithNoChannelIsToldWithANullName() {
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        feed("*3\r\n$11\r\nunsubscribe\r\n$-1\r\n:0\r\n");
        assertEquals("[unsubscribed:null:0]", rec.log.toString());
    }

    @Test
    public void aReplyThatIsNotADeliveryGoesToItsCommand() {
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        feed("*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1\r\n");
        rec.log.clear();
        final List<String> seen = new ArrayList<String>();
        session.command(new WholeEvents() {
            @Override
            public void handleResult(List<RespValue> values, RedisSession s) {
                seen.add("array:" + values.size() + ":" + values.get(0).asString());
            }

            @Override
            public void handleNull(RedisSession s) {
                seen.add("null");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("error:" + error);
            }
        }, "PING", new String[0]);
        String wire = "*2\r\n$4\r\npong\r\n$0\r\n\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        feed(message("c", "after"));
        assertEquals("[array:2:pong]", seen.toString());
        assertEquals("[begin:MESSAGE c:5, end]", rec.log.toString());
    }

    @Test
    public void aSimpleReplyOnASubscribedConnectionGoesToItsCommand() {
        Rec rec = new Rec();
        session.subscribe(rec, "c");
        feed("*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1\r\n");
        final List<String> seen = new ArrayList<String>();
        session.reset(new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                seen.add(result);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("error");
            }
        });
        feed("+RESET\r\n");
        assertEquals("[RESET]", seen.toString());
    }

    // ── pushes ──

    /** Records the events of the pushes it is given. */
    private static final class Pushes implements PushHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void handleStream(ReplyStream stream) {
            log.add("stream");
        }

        @Override
        public void beginReply(boolean push) {
            log.add("begin:" + push);
        }

        @Override
        public void endReply(boolean skipped) {
            log.add("end");
        }

        @Override
        public ReplyFlow nullValue() {
            log.add("null");
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow integer(long value) {
            log.add("int:" + value);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow doubleValue(double value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow booleanValue(boolean value) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow bigNumber(String digits) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow simpleString(String value) {
            log.add("simple:" + value);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow error(String message) {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginAggregate(org.bluezoo.gumdrop.redis.codec.RespType kind, long length) {
            log.add("agg:" + kind + ":" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endAggregate() {
            log.add("endagg");
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginString(org.bluezoo.gumdrop.redis.codec.RespType kind, long length,
                                     String format) {
            log.add("str:" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow stringChunk(ByteBuffer chunk) {
            byte[] b = new byte[chunk.remaining()];
            chunk.get(b);
            log.add("chunk:" + new String(b, StandardCharsets.ISO_8859_1));
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endString() {
            log.add("endstr");
            return ReplyFlow.CONTINUE;
        }
    }

    @Test
    public void aPushThatIsNotADeliveryGoesToThePushHandlerAsEvents() {
        Pushes p = new Pushes();
        session.setPushHandler(p);
        feed(">3\r\n$4\r\nmark\r\n:7\r\n$2\r\nhi\r\n");
        assertEquals("[stream, begin:true, agg:PUSH:3, str:4, chunk:mark, endstr, int:7, str:2,"
                + " chunk:hi, endstr, endagg, end]", p.log.toString());
    }

    @Test
    public void aPushWithNoHandlerIsDiscardedAndTheNextReplyIsRead() {
        Rec r = new Rec();
        final List<String> seen = new ArrayList<String>();
        session.ping(new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                seen.add(result);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("error");
            }
        });
        feed(">2\r\n$4\r\nmark\r\n$3\r\nabc\r\n+PONG\r\n");
        assertEquals("[PONG]", seen.toString());
    }

    private static final class Cache extends InvalidationHandler {
        final List<String> log = new ArrayList<String>();

        Cache(int max) {
            super(max);
        }

        @Override
        protected void invalidate(String key) {
            log.add("key:" + key);
        }

        @Override
        protected void invalidateAll() {
            log.add("all");
        }
    }

    @Test
    public void anInvalidationPushNamesEachKeyInAnySplit() {
        Cache c = new Cache(64);
        session.setPushHandler(c);
        String wire = ">2\r\n$10\r\ninvalidate\r\n*2\r\n$1\r\na\r\n$2\r\nbc\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("[key:a, key:bc]", c.log.toString());
    }

    @Test
    public void aFlushIsEverythingInvalidated() {
        Cache c = new Cache(64);
        session.setPushHandler(c);
        feed(">2\r\n$10\r\ninvalidate\r\n_\r\n");
        assertEquals("[all]", c.log.toString());
    }

    @Test
    public void aKeyTooLongToDeliverIsReportedAsEverythingChangedOnce() {
        Cache c = new Cache(4);
        session.setPushHandler(c);
        feed(">2\r\n$10\r\ninvalidate\r\n*3\r\n$1\r\na\r\n$10\r\nwaytoolong\r\n$1\r\nb\r\n");
        assertEquals("[key:a, all]", c.log.toString());
        // and the next push is read as usual
        feed(">2\r\n$10\r\ninvalidate\r\n*1\r\n$1\r\nz\r\n");
        assertEquals("[key:a, all, key:z]", c.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void otherPushesAreIgnoredByTheCache() {
        Cache c = new Cache(64);
        session.setPushHandler(c);
        feed(">2\r\n$4\r\nmark\r\n*1\r\n$1\r\nx\r\n");
        assertTrue(c.log.isEmpty());
        assertTrue(endpoint.open);
    }

}
