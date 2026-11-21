/*
 * PubSubReaderTest.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespException;
import org.bluezoo.gumdrop.redis.codec.RespType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The reader of what arrives on a subscribed connection, driven by the
 * decoder with a fake connection: for a catalogue of deliveries,
 * confirmations, pushes and replies, well formed and not, whole and a byte at
 * a time, with and without handlers, with the message handler continuing,
 * skipping or pausing at each of its calls in turn, and with the message
 * given up from outside, the reader reads every reply to its end and throws
 * nothing but what it means to. Then what some of them mean.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PubSubReaderTest {

    /** A message handler that logs and answers as told. */
    private static final class Messages implements MessageHandler {
        final List<String> log = new ArrayList<String>();
        int skipAt;
        int pauseAt;
        private int calls;
        // gives up the message at its first chunk, as a consumer would from outside
        PubSubReader reader;
        boolean cancelAtContent;

        private ReplyFlow flow(String what) {
            log.add(what);
            calls++;
            return calls == skipAt ? ReplyFlow.SKIP : calls == pauseAt ? ReplyFlow.PAUSE : ReplyFlow.CONTINUE;
        }

        public ReplyFlow messageBegin(MessageHeader h, long length, ReplyStream stream) {
            return flow("begin:" + h + ":" + length);
        }

        public ReplyFlow messageContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            if (cancelAtContent && reader != null) {
                reader.requestCancel();
            }
            return flow("content");
        }

        public void messageEnd() {
            log.add("end");
        }

        public void handleSubscribed(String c, int n) {
            log.add("subscribed:" + c + ":" + n);
        }

        public void handlePatternSubscribed(String c, int n) {
            log.add("psubscribed:" + c + ":" + n);
        }

        public void handleUnsubscribed(String c, int n) {
            log.add("unsubscribed:" + c + ":" + n);
        }

        public void handlePatternUnsubscribed(String c, int n) {
            log.add("punsubscribed:" + c + ":" + n);
        }

        public void handleError(String error) {
            log.add("error:" + error.split(" ")[0]);
        }
    }

    /** A push handler that logs coarse events and answers as told. */
    private static final class Pushes extends ReplyEventAdapter implements PushHandler {
        final List<String> log = new ArrayList<String>();
        int skipAt;
        int pauseAt;
        private int calls;

        private ReplyFlow flow(String what) {
            log.add(what);
            calls++;
            return calls == skipAt ? ReplyFlow.SKIP : calls == pauseAt ? ReplyFlow.PAUSE : ReplyFlow.CONTINUE;
        }

        public void handleStream(ReplyStream s) {
            log.add("stream");
        }

        public void beginReply(boolean push) {
            log.add("begin:" + push);
        }

        public void endReply(boolean skipped) {
            log.add("end");
        }

        public ReplyFlow nullValue() {
            return flow("null");
        }

        public ReplyFlow integer(long v) {
            return flow("int");
        }

        public ReplyFlow doubleValue(double v) {
            return flow("double");
        }

        public ReplyFlow booleanValue(boolean v) {
            return flow("bool");
        }

        public ReplyFlow bigNumber(String d) {
            return flow("big");
        }

        public ReplyFlow simpleString(String v) {
            return flow("simple:" + v);
        }

        public ReplyFlow error(String m) {
            return flow("error");
        }

        public ReplyFlow beginAggregate(RespType k, long n) {
            return flow("agg:" + k + ":" + n);
        }

        public ReplyFlow endAggregate() {
            return flow("endagg");
        }

        public ReplyFlow beginString(RespType k, long n, String f) {
            return flow("str:" + n);
        }

        public ReplyFlow stringChunk(ByteBuffer c) {
            StringBuilder sb = new StringBuilder();
            while (c.hasRemaining()) {
                sb.append((char) c.get());
            }
            return flow("chunk:" + sb);
        }

        public ReplyFlow endString() {
            return flow("endstr");
        }
    }

    /** What a reply that turns out to answer a command is given. */
    private static final class Replies extends ReplyEventAdapter {
        final List<String> log = new ArrayList<String>();

        public ReplyFlow beginAggregate(RespType k, long n) {
            log.add("agg:" + n);
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow endAggregate() {
            log.add("endagg");
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow simpleString(String v) {
            log.add("simple:" + v);
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow integer(long v) {
            log.add("int:" + v);
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow nullValue() {
            log.add("null");
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow error(String m) {
            log.add("error");
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow beginString(RespType k, long n, String f) {
            log.add("str:" + n);
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow stringChunk(ByteBuffer c) {
            c.position(c.limit());
            log.add("chunk");
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow endString() {
            log.add("endstr");
            return ReplyFlow.CONTINUE;
        }
    }

    /** The fake connection. */
    private static final class Fake implements PubSubReader.Host {
        Messages messages;
        Pushes pushes;
        final Replies replies = new Replies();
        final List<String> acks = new ArrayList<String>();
        int becameReply;

        public MessageHandler messageHandler() {
            return messages;
        }

        public PushHandler pushHandler() {
            return pushes;
        }

        public int maxField() {
            return 8;
        }

        public ReplyStream stream() {
            return ReplyStream.NONE;
        }

        public RespEventHandler becomeReply() {
            becameReply++;
            return replies;
        }

        public void acknowledged(String kind, String name, int count) {
            acks.add(kind + ":" + name + ":" + count);
        }
    }

    private static final String LONG = "xxxxxxxxxxxxxxxxxxxx";

    private static final String[] WIRES = {
        // deliveries, as arrays and as pushes
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc",
        ">3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc",
        "*3\r\n+message\r\n+c\r\n+abc",
        "*4\r\n$8\r\npmessage\r\n$2\r\np*\r\n$1\r\nc\r\n$3\r\nabc",
        ">4\r\n$8\r\npmessage\r\n$2\r\np*\r\n$1\r\nc\r\n$0\r\n",
        "*3\r\n$8\r\nsmessage\r\n$1\r\nc\r\n$3\r\nabc",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$?\r\n;2\r\nab\r\n;1\r\nc\r\n;0",
        "*3\r\n$7\r\nmessage\r\n$?\r\n;1\r\nc\r\n;0\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n:42",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n,1.5",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n#t",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n(12",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n_",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n*1\r\n:1",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n!3\r\nerr",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n-ERR e",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n=7\r\ntxt:abc",
        "*3\r\n$7\r\nmessage\r\n:5\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n,1.5\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n#f\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n(9\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n_\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n*0\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n-ERR e\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n!1\r\nx\r\n$1\r\nb",
        "*3\r\n$7\r\nmessage\r\n$20\r\n" + LONG + "\r\n$3\r\nabc",
        "*3\r\n$7\r\nmessage\r\n$?\r\n;5\r\nabcde\r\n;5\r\nfghij\r\n;0\r\n$3\r\nabc",
        "*4\r\n$8\r\npmessage\r\n$20\r\n" + LONG + "\r\n$1\r\nc\r\n$3\r\nabc",
        "*4\r\n$8\r\npmessage\r\n$1\r\np\r\n$20\r\n" + LONG + "\r\n$3\r\nabc",
        "*2\r\n$7\r\nmessage\r\n$1\r\nc",
        "*1\r\n$7\r\nmessage",
        "*4\r\n$7\r\nmessage\r\n$1\r\nc\r\n$1\r\nb\r\n$1\r\nx",
        "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$1\r\nb\r\n:1",
        // confirmations
        "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1",
        "*3\r\n$11\r\nunsubscribe\r\n$1\r\nc\r\n:0",
        "*3\r\n$10\r\npsubscribe\r\n$2\r\np*\r\n:2",
        "*3\r\n$12\r\npunsubscribe\r\n$2\r\np*\r\n:1",
        "*3\r\n$10\r\nssubscribe\r\n$1\r\nc\r\n:1",
        "*3\r\n$12\r\nsunsubscribe\r\n$1\r\nc\r\n:0",
        ">3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1",
        "*3\r\n$11\r\nunsubscribe\r\n_\r\n:0",
        "*3\r\n$9\r\nsubscribe\r\n+c\r\n+1",
        "*3\r\n$9\r\nsubscribe\r\n:7\r\n$1\r\n3",
        "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n(4",
        "*2\r\n$9\r\nsubscribe\r\n$1\r\nc",
        "*1\r\n$9\r\nsubscribe",
        "*4\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1\r\n:2",
        "*3\r\n$9\r\nsubscribe\r\n$20\r\n" + LONG + "\r\n:1",
        "*3\r\n$9\r\nsubscribe\r\n*0\r\n:1",
        "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n*0",
        "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n,1.5",
        "*3\r\n$9\r\nsubscribe\r\n-ERR e\r\n:1",
        // replies that are not deliveries
        "*2\r\n$4\r\npong\r\n$0\r\n", "+OK", ":1", "-ERR x", "_", "$3\r\nabc", "*0", "*1\r\n:1",
        "*2\r\n:1\r\n:2", "*2\r\n_\r\n:1", "*2\r\n*0\r\n:1", "*2\r\n,1.5\r\n:1", "*2\r\n#t\r\n:1",
        "*2\r\n(1\r\n:1", "*2\r\n-ERR e\r\n:1", "*1\r\n$20\r\n" + LONG, "%1\r\n+k\r\n+v",
        "~1\r\n+x", "$?\r\n;1\r\nx\r\n;0", ",1.5", "#t", "(5", "=7\r\ntxt:abc", "!3\r\nerr",
        "*2\r\n+PING\r\n+x", "*3\r\n$4\r\npong\r\n$1\r\nx\r\n:1",
        // pushes
        ">2\r\n$4\r\nmark\r\n:7", ">3\r\n$4\r\nmark\r\n*1\r\n:1\r\n_", ">2\r\n+invalidate\r\n*1\r\n$1\r\nk",
        ">1\r\n$4\r\nmark", ">0", ">2\r\n:1\r\n:2", ">2\r\n_\r\n:2", ">2\r\n$4\r\nmark\r\n%1\r\n+a\r\n,1.5",
        ">2\r\n$4\r\nmark\r\n-ERR e", ">2\r\n$4\r\nmark\r\n$?\r\n;1\r\na\r\n;0",
        ">2\r\n$4\r\nmark\r\n,1.5", ">2\r\n$4\r\nmark\r\n#t", ">2\r\n$4\r\nmark\r\n(1",
        ">2\r\n$4\r\nmark\r\n=7\r\ntxt:abc", ">2\r\n$4\r\nmark\r\n!1\r\nx", ">2\r\n$4\r\nmark\r\n_",
        ">2\r\n$20\r\n" + LONG + "\r\n:1", ">2\r\n*0\r\n:1",
        "|1\r\n+a\r\n+b\r\n:5", "*3\r\n|1\r\n+a\r\n+b\r\n$7\r\nmessage\r\n$1\r\nc\r\n$1\r\nb",
    };

    /** Whether a wire's confirmation has a count that is not a number. */
    private static boolean badCount(String wire) {
        return wire.endsWith("+1") && wire.contains("subscribe") || wire.endsWith("$1\r\n3")
                && wire.contains("subscribe") || wire.endsWith("(4") || wire.endsWith(",1.5")
                && wire.contains("subscribe\r\n$1\r\nc\r\n,") ;
    }

    private static void feed(PubSubReader reader, String wire, boolean byteAtATime)
            throws RespException {
        byte[] bytes = (wire + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        RespDecoder decoder = new RespDecoder();
        int step = byteAtATime ? 1 : bytes.length;
        for (int off = 0; off < bytes.length; off += step) {
            ByteBuffer buf = ByteBuffer.wrap(bytes, off, Math.min(step, bytes.length - off));
            int guard = 0;
            while (decoder.receive(buf, reader) && buf.hasRemaining() && guard++ < 100) {
                // paused: carry on, as a resume would
            }
        }
    }

    private static Fake fake(int config, int skipAt, int pauseAt) {
        Fake f = new Fake();
        if (config == 0 || config == 1) {
            f.messages = new Messages();
            f.messages.skipAt = skipAt;
            f.messages.pauseAt = pauseAt;
        }
        if (config == 0 || config == 2) {
            f.pushes = new Pushes();
            f.pushes.skipAt = skipAt;
            f.pushes.pauseAt = pauseAt;
        }
        return f;
    }

    @Test
    public void everyReplyIsReadToItsEndAndNothingIsThrownThatIsNotMeant() throws RespException {
        int[] skips = { 0, 1, 2, 3, 4, 7 };
        int[] pauses = { 0, 1, 2, 5 };
        for (int w = 0; w < WIRES.length; w++) {
            for (int config = 0; config < 4; config++) {
                for (int s = 0; s < skips.length; s++) {
                    for (int p = 0; p < pauses.length; p++) {
                        for (int mode = 0; mode < 4; mode++) {
                            Fake f = fake(config, skips[s], pauses[p]);
                            PubSubReader reader = new PubSubReader(f);
                            if (f.messages != null) {
                                f.messages.reader = reader;
                                f.messages.cancelAtContent = (mode & 2) == 2;
                            }
                            try {
                                feed(reader, WIRES[w], (mode & 1) == 1);
                            } catch (IllegalStateException e) {
                                // a confirmation whose count is not a number: the server is not trusted
                                assertTrue(WIRES[w].replace("\r\n", "|") + ": " + e,
                                        e.getMessage().contains("subscription count"));
                            }
                        }
                    }
                }
            }
        }
    }

    // ── what some of them mean ──

    private static Fake run(int config, String wire) throws RespException {
        Fake f = fake(config, 0, 0);
        feed(new PubSubReader(f), wire, false);
        return f;
    }

    @Test
    public void aDeliveryIsToldWithItsHeaderAndBody() throws RespException {
        assertEquals("[begin:MESSAGE c:3, content, end]",
                run(1, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc").messages.log.toString());
        assertEquals("[begin:PATTERN p* c:3, content, end]",
                run(1, ">4\r\n$8\r\npmessage\r\n$2\r\np*\r\n$1\r\nc\r\n$3\r\nabc").messages.log.toString());
        assertEquals("[begin:SHARD c:3, content, end]",
                run(1, "*3\r\n$8\r\nsmessage\r\n$1\r\nc\r\n$3\r\nabc").messages.log.toString());
        assertEquals("[begin:MESSAGE c:0, end]",
                run(1, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$0\r\n").messages.log.toString());
        assertEquals("[begin:MESSAGE c:-1, content, end]",
                run(1, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$?\r\n;2\r\nab\r\n;0").messages.log.toString());
    }

    @Test
    public void aShortScalarBodyIsDeliveredAsItsText() throws RespException {
        String[] bodies = { ":42", ",1.5", "#t", "(12", "+ab" };
        for (int i = 0; i < bodies.length; i++) {
            Fake f = run(1, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n" + bodies[i]);
            assertEquals(bodies[i], 3, f.messages.log.size());
        }
        assertEquals("[begin:MESSAGE c:0, end]",
                run(1, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n_").messages.log.toString());
    }

    @Test
    public void aNameOverTheLimitDropsTheMessageAndIsReported() throws RespException {
        assertEquals("[error:TOOLARGE]", run(1, "*3\r\n$7\r\nmessage\r\n$20\r\n" + LONG
                + "\r\n$3\r\nabc").messages.log.toString());
        assertEquals("[error:TOOLARGE]", run(1, "*4\r\n$8\r\npmessage\r\n$1\r\np\r\n$20\r\n" + LONG
                + "\r\n$3\r\nabc").messages.log.toString());
        assertEquals("[error:TOOLARGE]", run(1, "*3\r\n$7\r\nmessage\r\n$?\r\n;5\r\nabcde\r\n;5\r\nfghij\r\n;0"
                + "\r\n$3\r\nabc").messages.log.toString());
    }

    @Test
    public void aMessageThatIsMalformedIsDroppedQuietly() throws RespException {
        String[] bad = { "*3\r\n$7\r\nmessage\r\n*0\r\n$1\r\nb",
            "*3\r\n$7\r\nmessage\r\n_\r\n$1\r\nb", "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n*1\r\n:1",
            "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n!3\r\nerr", "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n-ERR e",
            "*2\r\n$7\r\nmessage\r\n$1\r\nc" };
        for (int i = 0; i < bad.length; i++) {
            Fake f = run(1, bad[i]);
            assertFalse(bad[i], f.messages.log.contains("end") && f.messages.log.contains("content"));
        }
    }

    @Test
    public void aSkippedMessageIsCutAndTheNextIsDelivered() throws RespException {
        Fake f = fake(1, 2, 0);
        PubSubReader reader = new PubSubReader(f);
        feed(reader, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc", false);
        assertEquals("[begin:MESSAGE c:3, content]", f.messages.log.toString());
        f.messages.skipAt = 0;
        feed(reader, "*3\r\n$7\r\nmessage\r\n$1\r\nd\r\n$1\r\nx", false);
        assertEquals("[begin:MESSAGE c:3, content, begin:MESSAGE d:1, content, end]",
                f.messages.log.toString());
    }

    @Test
    public void aMessageGivenUpFromOutsideIsCutAtItsNextEvent() throws RespException {
        Fake f = fake(1, 0, 0);
        PubSubReader reader = new PubSubReader(f);
        f.messages.reader = reader;
        f.messages.cancelAtContent = true;
        feed(reader, "*3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc", false);
        assertEquals("[begin:MESSAGE c:3, content]", f.messages.log.toString());
        // and the next message is not affected
        f.messages.cancelAtContent = false;
        feed(reader, "*3\r\n$7\r\nmessage\r\n$1\r\nd\r\n$1\r\nx", false);
        assertEquals("[begin:MESSAGE c:3, content, begin:MESSAGE d:1, content, end]",
                f.messages.log.toString());
    }

    @Test
    public void confirmationsUpdateTheConnectionWithTheirNamesAndCounts() throws RespException {
        assertEquals("[subscribe:c:1]", run(1, "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1").acks.toString());
        assertEquals("[unsubscribe:null:0]",
                run(1, "*3\r\n$11\r\nunsubscribe\r\n_\r\n:0").acks.toString());
        assertEquals("[psubscribe:p*:2]", run(1, "*3\r\n$10\r\npsubscribe\r\n$2\r\np*\r\n:2").acks.toString());
        assertEquals("[punsubscribe:p*:1]",
                run(1, ">3\r\n$12\r\npunsubscribe\r\n$2\r\np*\r\n:1").acks.toString());
        assertEquals("[ssubscribe:c:1]", run(1, "*3\r\n$10\r\nssubscribe\r\n$1\r\nc\r\n:1").acks.toString());
        assertEquals("[sunsubscribe:c:0]",
                run(1, "*3\r\n$12\r\nsunsubscribe\r\n$1\r\nc\r\n:0").acks.toString());
        assertEquals("[subscribe:7:3]", run(1, "*3\r\n$9\r\nsubscribe\r\n:7\r\n:3").acks.toString());
        assertEquals("[]", run(1, "*2\r\n$9\r\nsubscribe\r\n$1\r\nc").acks.toString());
        assertEquals("[]", run(1, "*3\r\n$9\r\nsubscribe\r\n$20\r\n" + LONG + "\r\n:1").acks.toString());
    }

    @Test
    public void aCountThatIsNotANumberIsNotTrusted() throws RespException {
        try {
            run(1, "*3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n+x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subscription count"));
        }
    }

    @Test
    public void aReplyThatIsNotADeliveryIsGivenToItsCommandWithWhatWasReadOfIt() throws RespException {
        Fake pong = run(1, "*2\r\n$4\r\npong\r\n$0\r\n");
        assertEquals(1, pong.becameReply);
        assertEquals("[agg:2, str:4, chunk, endstr, str:0, endstr, endagg]", pong.replies.log.toString());
        Fake simple = run(1, "*2\r\n+PING\r\n+x");
        assertEquals("[agg:2, simple:PING, simple:x, endagg]", simple.replies.log.toString());
        Fake ok = run(1, "+OK");
        assertEquals("[simple:OK]", ok.replies.log.toString());
        Fake integerKind = run(1, "*2\r\n:1\r\n:2");
        assertEquals("[agg:2, int:1, int:2, endagg]", integerKind.replies.log.toString());
        Fake nullKind = run(1, "*2\r\n_\r\n:2");
        assertEquals("[agg:2, null, int:2, endagg]", nullKind.replies.log.toString());
        Fake error = run(1, "-ERR x");
        assertEquals("[error]", error.replies.log.toString());
        Fake nested = run(1, "*1\r\n*1\r\n:1");
        assertEquals("[agg:1, agg:1, int:1, endagg, endagg]", nested.replies.log.toString());
    }

    @Test
    public void aPushThatIsNotADeliveryGoesToThePushHandlerWithWhatWasReadOfIt() throws RespException {
        Fake f = run(2, ">2\r\n$4\r\nmark\r\n:7");
        assertEquals("[stream, begin:true, agg:PUSH:2, str:4, chunk:mark, endstr, int, endagg, end]",
                f.pushes.log.toString());
        Fake simple = run(2, ">2\r\n+invalidate\r\n*1\r\n$1\r\nk");
        assertEquals("[stream, begin:true, agg:PUSH:2, simple:invalidate, agg:ARRAY:1, str:1, chunk:k,"
                + " endstr, endagg, endagg, end]", simple.pushes.log.toString());
        Fake none = run(1, ">2\r\n$4\r\nmark\r\n:7");
        assertEquals("a push with no handler is dropped", 0, none.becameReply);
        Fake odd = run(2, ">2\r\n:1\r\n:2");
        assertTrue(odd.pushes.log.toString(), odd.pushes.log.isEmpty());
    }

    @Test
    public void aDeliveryWithNoMessageHandlerGoesToThePushHandlerOrIsDropped() throws RespException {
        Fake f = run(2, ">3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc");
        assertEquals("[stream, begin:true, agg:PUSH:3, str:7, chunk:message, endstr, str:1, chunk:c,"
                + " endstr, str:3, chunk:abc, endstr, endagg, end]", f.pushes.log.toString());
        Fake dropped = run(3, ">3\r\n$7\r\nmessage\r\n$1\r\nc\r\n$3\r\nabc");
        assertEquals(0, dropped.becameReply);
    }

}
