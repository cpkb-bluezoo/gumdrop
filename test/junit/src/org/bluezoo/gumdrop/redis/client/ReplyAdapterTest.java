/*
 * ReplyAdapterTest.java
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
import org.bluezoo.gumdrop.redis.codec.RespException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The adapters that turn a reply's events into a handler's calls, driven by
 * the decoder with no connection: for every shape of handler and a catalogue
 * of replies, well formed and not, whole and a byte at a time, with the
 * handler continuing, skipping or pausing at each of its calls in turn, the
 * handler is completed exactly once and nothing is thrown. Then what some of
 * the replies mean.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ReplyAdapterTest {

    private static final int MAX_FIELD = 8;

    /** Counts calls and decides what each answers; the base of every recorder. */
    private static class Script {
        int completions;
        String error;
        final List<String> log = new ArrayList<String>();
        // the call that answers SKIP, or PAUSE; 0 for none
        int skipAt;
        int pauseAt;
        private int calls;
        ReplyStream stream;

        ReplyFlow flow(String what) {
            log.add(what);
            calls++;
            if (calls == skipAt) {
                return ReplyFlow.SKIP;
            }
            if (calls == pauseAt) {
                return ReplyFlow.PAUSE;
            }
            return ReplyFlow.CONTINUE;
        }

        void completed(String e) {
            completions++;
            error = e;
            log.add("complete:" + (e == null ? "" : e.split(" ")[0]));
        }

        void keep(ReplyStream s) {
            stream = s;
        }
    }

    private static final class Bulk extends Script implements BulkResultHandler {
        public void handleNull() {
            flow("null");
        }

        public ReplyFlow handleBegin(long length, ReplyStream s) {
            keep(s);
            return flow("begin:" + length);
        }

        public ReplyFlow handleContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return flow("content");
        }

        public void handleEnd() {
            flow("end");
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Elements extends Script implements ArrayResultHandler {
        public void handleNull() {
            flow("null");
        }

        public ReplyFlow handleBegin(long count, ReplyStream s) {
            keep(s);
            return flow("begin:" + count);
        }

        public ReplyFlow handleElementNull() {
            return flow("enull");
        }

        public ReplyFlow handleElementBegin(long length) {
            return flow("ebegin:" + length);
        }

        public ReplyFlow handleElementContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return flow("econtent");
        }

        public ReplyFlow handleElementEnd() {
            return flow("eend");
        }

        public void handleEnd() {
            flow("end");
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Keys extends Script implements KeyArrayResultHandler {
        public void handleNull() {
            flow("null");
        }

        public ReplyFlow handleBegin(long count, ReplyStream s) {
            keep(s);
            return flow("begin:" + count);
        }

        public ReplyFlow handleKey(String key) {
            return flow("key:" + key);
        }

        public void handleEnd() {
            flow("end");
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Pairs extends Script implements MapResultHandler {
        public void handleNull() {
            flow("null");
        }

        public ReplyFlow handleBegin(long pairs, ReplyStream s) {
            keep(s);
            return flow("begin:" + pairs);
        }

        public ReplyFlow handleField(String field) {
            return flow("field:" + field);
        }

        public ReplyFlow handleValueNull() {
            return flow("vnull");
        }

        public ReplyFlow handleValueBegin(long length) {
            return flow("vbegin:" + length);
        }

        public ReplyFlow handleValueContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return flow("vcontent");
        }

        public ReplyFlow handleValueEnd() {
            return flow("vend");
        }

        public void handleEnd() {
            flow("end");
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Scan extends Script implements ScanResultHandler {
        public ReplyFlow handleCursor(String cursor, ReplyStream s) {
            keep(s);
            return flow("cursor:" + cursor);
        }

        public ReplyFlow handleKey(String key) {
            return flow("key:" + key);
        }

        public void handleEnd() {
            flow("end");
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Events extends Script implements ReplyEventHandler {
        public void handleStream(ReplyStream s) {
            keep(s);
        }

        public void handleComplete(String e, RedisSession s) {
            completed(e);
        }

        public void beginReply(boolean push) {
            log.add("beginReply");
        }

        public void endReply(boolean skipped) {
            log.add("endReply");
        }

        public ReplyFlow nullValue() {
            return flow("null");
        }

        public ReplyFlow integer(long value) {
            return flow("int");
        }

        public ReplyFlow doubleValue(double value) {
            return flow("double");
        }

        public ReplyFlow booleanValue(boolean value) {
            return flow("bool");
        }

        public ReplyFlow bigNumber(String digits) {
            return flow("big");
        }

        public ReplyFlow simpleString(String value) {
            return flow("simple");
        }

        public ReplyFlow error(String message) {
            return flow("error");
        }

        public ReplyFlow beginAggregate(org.bluezoo.gumdrop.redis.codec.RespType kind, long length) {
            return flow("agg");
        }

        public ReplyFlow endAggregate() {
            return flow("endagg");
        }

        public ReplyFlow beginString(org.bluezoo.gumdrop.redis.codec.RespType kind, long length, String f) {
            return flow("str");
        }

        public ReplyFlow stringChunk(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return flow("chunk");
        }

        public ReplyFlow endString() {
            return flow("endstr");
        }
    }

    private static final class Int extends Script implements IntegerResultHandler {
        long value;

        public void handleResult(long v, RedisSession s) {
            value = v;
            log.add("value:" + v);
            completions++;
        }

        public void handleError(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Flag extends Script implements BooleanResultHandler {
        boolean value;

        public void handleResult(boolean v, RedisSession s) {
            value = v;
            log.add("value:" + v);
            completions++;
        }

        public void handleError(String e, RedisSession s) {
            completed(e);
        }
    }

    private static final class Str extends Script implements StringResultHandler {
        String value;

        public void handleResult(String v, RedisSession s) {
            value = v;
            log.add("value:" + v);
            completions++;
        }

        public void handleError(String e, RedisSession s) {
            completed(e);
        }
    }

    private static Script[] handlers() {
        return new Script[] { new Bulk(), new Elements(), new Keys(), new Pairs(), new Scan(),
            new Events(), new Int(), new Flag(), new Str() };
    }

    /** Replies of every shape, some meant for no handler. */
    private static final String[] REPLIES = {
        "+OK", "-ERR x", ":5", ":-1", "_", "#t", "#f", ",1.5", ",3.0", ",inf", "(123",
        "$3\r\nabc", "$0\r\n", "$-1", "*-1", "=7\r\ntxt:abc", "!5\r\nERR x", "$?\r\n;2\r\nab\r\n;0",
        "$20\r\nxxxxxxxxxxxxxxxxxxxx", "$2\r\n42", "$1\r\n1", "$4\r\ntrue",
        "*0", "*2\r\n$1\r\na\r\n$-1", "*2\r\n:1\r\n+x", "*2\r\n,1.5\r\n#t", "*2\r\n(12\r\n_",
        "*1\r\n*1\r\n:1", "*2\r\n-ERR e\r\n:1", "*2\r\n!3\r\nerr\r\n:1", "*?\r\n$1\r\na\r\n.",
        "~2\r\n+a\r\n+b", "%2\r\n+k\r\n$1\r\nv\r\n+j\r\n_", "%1\r\n*1\r\n:1\r\n:2",
        "%2\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx\r\n$1\r\nv\r\n+j\r\n$-1",
        "%?\r\n+k\r\n$1\r\nv\r\n.",
        "*4\r\n$1\r\nf\r\n$1\r\nv\r\n$1\r\ng\r\n$-1",
        "*4\r\n$1\r\nf\r\n$1\r\nv\r\n_\r\n$1\r\nv", "*2\r\n:1\r\n:2", "*2\r\n,1\r\n,2", "*2\r\n#t\r\n#f",
        "*2\r\n(1\r\n(2",
        "*3\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx\r\n$1\r\nb\r\n$1\r\nc",
        "*3\r\n$?\r\n;4\r\nabcd\r\n;6\r\nefghij\r\n;0\r\n$1\r\nb\r\n$1\r\nc",
        "*2\r\n$1\r\n0\r\n*2\r\n$1\r\na\r\n$1\r\nb", "*2\r\n:5\r\n*1\r\n:7", "*2\r\n$1\r\n0\r\n*0",
        "*3\r\n$1\r\n0\r\n*0\r\n:1", "*1\r\n$1\r\n0", "*2\r\n$1\r\n0\r\n:1", "*2\r\n*0\r\n*0",
        "*2\r\n_\r\n*0", "*2\r\n$1\r\n0\r\n*2\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx\r\n$1\r\nb",
        "*2\r\n$1\r\n0\r\n*2\r\n_\r\n$1\r\nb", "*2\r\n$1\r\n0\r\n*1\r\n*0",
        "*2\r\n$1\r\n0\r\n*2\r\n!1\r\nx\r\n$1\r\nb", "*2\r\n!1\r\nx\r\n*0",
        "*2\r\n$?\r\n;1\r\n0\r\n;0\r\n*1\r\n$?\r\n;1\r\na\r\n;0", "*2\r\n,1.5\r\n*0",
        "|1\r\n+a\r\n+b\r\n:5", "*2\r\n|1\r\n+a\r\n+b\r\n:1\r\n:2", ">2\r\n+x\r\n:1",
        "*2\r\n=7\r\ntxt:abc\r\n(5", "*2\r\n,1.5\r\n,2.5", "*2\r\n:1\r\n_", "*1\r\n-ERR e",
        "*1\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx", "*1\r\n$?\r\n;9\r\nxxxxxxxxx\r\n;0",
    };

    /** Feeds a reply, resuming whenever the decoder was paused. */
    private static void feed(RespEventHandlerSource source, String reply, boolean byteAtATime)
            throws RespException {
        byte[] bytes = (reply + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        RespDecoder decoder = new RespDecoder();
        int step = byteAtATime ? 1 : bytes.length;
        ByteBuffer pending = null;
        for (int off = 0; off < bytes.length; off += step) {
            ByteBuffer buf = pending != null ? pending : ByteBuffer.wrap(bytes, off,
                    Math.min(step, bytes.length - off));
            pending = null;
            int guard = 0;
            while (decoder.receive(buf, source.handler()) && guard++ < 100) {
                // paused: carry on, as a resume would
                if (!buf.hasRemaining()) {
                    break;
                }
            }
        }
    }

    /** The adapter being fed. */
    private interface RespEventHandlerSource {
        org.bluezoo.gumdrop.redis.codec.RespEventHandler handler();
    }

    @Test
    public void everyHandlerIsCompletedExactlyOnceWhateverTheReplyAndWhateverItAnswers()
            throws RespException {
        int[] skips = { 0, 1, 2, 3, 4, 6, 9 };
        int[] pauses = { 0, 1, 3 };
        for (int h = 0; h < handlers().length; h++) {
            for (int r = 0; r < REPLIES.length; r++) {
                for (int s = 0; s < skips.length; s++) {
                    for (int p = 0; p < pauses.length; p++) {
                        for (int split = 0; split < 2; split++) {
                            final Script script = handlers()[h];
                            script.skipAt = skips[s];
                            script.pauseAt = pauses[p];
                            final ReplyAdapter adapter = ReplyAdapter.forHandler(script, null, MAX_FIELD);
                            assertNotNull(adapter);
                            feed(new RespEventHandlerSource() {
                                public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                                    return adapter;
                                }
                            }, REPLIES[r], split == 1);
                            assertEquals(script.getClass().getSimpleName() + " " + REPLIES[r]
                                    .replace("\r\n", "|") + " skip " + skips[s] + " pause " + pauses[p]
                                    + " " + script.log, 1, script.completions);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aReplyCancelledBeforeItArrivesCompletesCancelledWhateverItIs() throws RespException {
        for (int h = 0; h < handlers().length; h++) {
            for (int r = 0; r < REPLIES.length; r++) {
                final Script script = handlers()[h];
                final ReplyAdapter adapter = ReplyAdapter.forHandler(script, null, MAX_FIELD);
                adapter.requestCancel();
                feed(new RespEventHandlerSource() {
                    public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                        return adapter;
                    }
                }, REPLIES[r], r % 2 == 0);
                assertEquals(script.getClass().getSimpleName() + " " + REPLIES[r].replace("\r\n", "|"),
                        1, script.completions);
            }
        }
    }

    @Test
    public void anAdapterCompletedFromOutsideIsNotCompletedAgainByTheReplyWhateverItIs()
            throws RespException {
        for (int h = 0; h < handlers().length; h++) {
            for (int r = 0; r < REPLIES.length; r++) {
                final Script script = handlers()[h];
                final ReplyAdapter adapter = ReplyAdapter.forHandler(script, null, MAX_FIELD);
                adapter.abort("TIMEOUT late");
                assertTrue(adapter.isDone());
                feed(new RespEventHandlerSource() {
                    public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                        return adapter;
                    }
                }, REPLIES[r], r % 2 == 1);
                assertEquals(script.getClass().getSimpleName() + " " + REPLIES[r].replace("\r\n", "|"),
                        1, script.completions);
                assertEquals("TIMEOUT late", script.error);
            }
        }
    }

    @Test
    public void anIdentifierLongerThanTheScratchIsCollectedWhole() throws RespException {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            key.append((char) ('a' + i % 26));
        }
        Keys keys = new Keys();
        final ReplyAdapter adapter = ReplyAdapter.forHandler(keys, null, 1000);
        feed(new RespEventHandlerSource() {
            public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                return adapter;
            }
        }, "*1\r\n$300\r\n" + key, true);
        assertEquals("[begin:1, key:" + key + ", end, complete:]", keys.log.toString());
        Str str = new Str();
        final ReplyAdapter stringAdapter = ReplyAdapter.forHandler(str, null, 1000);
        feed(new RespEventHandlerSource() {
            public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                return stringAdapter;
            }
        }, "$300\r\n" + key, true);
        assertEquals(key.toString(), str.value);
    }

    @Test
    public void anAbandonedAdapterTellsItsHandlerNothing() throws RespException {
        Bulk bulk = new Bulk();
        final ReplyAdapter adapter = ReplyAdapter.forHandler(bulk, null, MAX_FIELD);
        adapter.abandon();
        feed(new RespEventHandlerSource() {
            public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                return adapter;
            }
        }, "-MOVED 1 x:1", false);
        assertEquals(0, bulk.completions);
    }

    @Test
    public void aHandlerOfAnUnknownKindGetsNoAdapter() {
        assertNull(ReplyAdapter.forHandler(new Object(), null, MAX_FIELD));
        assertNull(ReplyAdapter.forHandler(null, null, MAX_FIELD));
    }

    // ── what some replies mean ──

    private static void run(Script script, String reply) throws RespException {
        final ReplyAdapter adapter = ReplyAdapter.forHandler(script, null, MAX_FIELD);
        feed(new RespEventHandlerSource() {
            public org.bluezoo.gumdrop.redis.codec.RespEventHandler handler() {
                return adapter;
            }
        }, reply, false);
    }

    @Test
    public void anIntegerHandlerTakesIntegersIntegralDoublesBooleansAndNumericText() throws RespException {
        String[][] cases = { { ":5", "5" }, { ",3.0", "3" }, { "#t", "1" }, { "#f", "0" },
            { "$2\r\n42", "42" }, { "+7", "7" }, { ":-1", "-1" } };
        for (int i = 0; i < cases.length; i++) {
            Int h = new Int();
            run(h, cases[i][0]);
            assertEquals(cases[i][0], cases[i][1], Long.toString(h.value));
        }
    }

    @Test
    public void anIntegerHandlerRefusesTextNullsAndCollections() throws RespException {
        String[] bad = { "+OK", "$3\r\nabc", "_", "$-1", ",1.5", "*1\r\n:1", "%1\r\n+k\r\n+v",
            "$20\r\nxxxxxxxxxxxxxxxxxxxx", "!1\r\nx", "-ERR no" };
        for (int i = 0; i < bad.length; i++) {
            Int h = new Int();
            run(h, bad[i]);
            assertEquals(bad[i], 1, h.completions);
            assertNotNull(bad[i] + " " + h.log, h.error);
        }
    }

    @Test
    public void aBooleanHandlerTakesIntegersAndBooleans() throws RespException {
        Flag a = new Flag();
        run(a, ":1");
        assertTrue(a.value);
        Flag b = new Flag();
        run(b, ":0");
        assertFalse(b.value);
        Flag c = new Flag();
        run(c, "#t");
        assertTrue(c.value);
        Flag d = new Flag();
        run(d, "+x");
        assertNotNull(d.error);
        Flag e = new Flag();
        run(e, "*0");
        assertNotNull(e.error);
    }

    @Test
    public void aStringHandlerTakesShortStringsNullsAndNumbers() throws RespException {
        Str a = new Str();
        run(a, "+PONG");
        assertEquals("PONG", a.value);
        Str b = new Str();
        run(b, "$3\r\nabc");
        assertEquals("abc", b.value);
        Str c = new Str();
        run(c, "_");
        assertNull(c.value);
        assertEquals(1, c.completions);
        Str d = new Str();
        run(d, ":12");
        assertEquals("12", d.value);
        Str e = new Str();
        run(e, ",1.5");
        assertEquals("1.5", e.value);
        Str f = new Str();
        run(f, "#t");
        assertEquals("1", f.value);
        Str g = new Str();
        run(g, "(99");
        assertEquals("99", g.value);
        Str h = new Str();
        run(h, "$20\r\nxxxxxxxxxxxxxxxxxxxx");
        assertTrue(h.error, h.error.startsWith("TOOLARGE"));
        Str i = new Str();
        run(i, "$?\r\n;5\r\nabcde\r\n;5\r\nfghij\r\n;0");
        assertTrue(i.error, i.error.startsWith("TOOLARGE"));
        Str j = new Str();
        run(j, "*0");
        assertTrue(j.error, j.error.startsWith("ERR"));
        Str k = new Str();
        run(k, "!1\r\nx");
        assertTrue(k.error, k.error != null);
    }

    @Test
    public void aBulkHandlerTakesAnyScalarAsItsText() throws RespException {
        String[] ok = { "+OK", ":5", ",1.5", "#t", "(12", "$3\r\nabc", "=7\r\ntxt:abc", "$0\r\n" };
        for (int i = 0; i < ok.length; i++) {
            Bulk h = new Bulk();
            run(h, ok[i]);
            assertNull(ok[i] + " " + h.log, h.error);
            assertTrue(ok[i] + " " + h.log, h.log.get(0).startsWith("begin:"));
        }
        Bulk shape = new Bulk();
        run(shape, "*1\r\n:1");
        assertTrue(shape.error, shape.error.startsWith("ERR"));
        Bulk blob = new Bulk();
        run(blob, "!1\r\nx");
        assertTrue(blob.error, blob.error.startsWith("ERR"));
        Bulk nul = new Bulk();
        run(nul, "_");
        assertEquals("[null, complete:]", nul.log.toString());
        Bulk nested = new Bulk();
        run(nested, "*1\r\n_");
        assertNotNull(nested.error);
    }

    @Test
    public void aBulkHandlerThatPausesAScalarGetsItWholeAndThenThePause() throws RespException {
        Bulk h = new Bulk();
        h.pauseAt = 1;
        run(h, ":5");
        assertEquals("[begin:1, content, end, complete:]", h.log.toString());
    }

    @Test
    public void anArrayHandlerRefusesNamesAndNestedAggregates() throws RespException {
        Elements nested = new Elements();
        run(nested, "*1\r\n*1\r\n:1");
        assertTrue(nested.error, nested.error.startsWith("ERR"));
        Elements notArray = new Elements();
        run(notArray, ":1");
        assertTrue(notArray.error, notArray.error.startsWith("ERR"));
        Elements mapped = new Elements();
        run(mapped, "%1\r\n+k\r\n$1\r\nv");
        assertEquals("[begin:2, ebegin:1, econtent, eend, ebegin:1, econtent, eend, end, complete:]",
                mapped.log.toString());
        Elements inner = new Elements();
        run(inner, "*2\r\n-ERR e\r\n:1");
        assertTrue(inner.error, inner.error.startsWith("ERR"));
    }

    @Test
    public void aKeyHandlerTakesIdentifiersAndRefusesTheRest() throws RespException {
        Keys ok = new Keys();
        run(ok, "*3\r\n$1\r\na\r\n:7\r\n+b");
        assertEquals("[begin:3, key:a, key:7, key:b, end, complete:]", ok.log.toString());
        Keys nul = new Keys();
        run(nul, "*-1");
        assertEquals("[null, complete:]", nul.log.toString());
        Keys elementNull = new Keys();
        run(elementNull, "*1\r\n_");
        assertTrue(elementNull.error, elementNull.error.startsWith("ERR"));
        Keys nested = new Keys();
        run(nested, "*1\r\n*0");
        assertNotNull(nested.error);
        Keys tooLong = new Keys();
        run(tooLong, "*1\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx");
        assertTrue(tooLong.error, tooLong.error.startsWith("TOOLARGE"));
        Keys scalarLong = new Keys();
        run(scalarLong, "*1\r\n(1234567890123");
        assertTrue(scalarLong.error, scalarLong.error.startsWith("TOOLARGE"));
        Keys topLevel = new Keys();
        run(topLevel, "+x");
        assertNotNull(topLevel.error);
        Keys map = new Keys();
        run(map, "%1\r\n+k\r\n+v");
        assertEquals("[begin:2, key:k, key:v, end, complete:]", map.log.toString());
    }

    @Test
    public void aMapHandlerTakesAMapOrFlatPairsAndRefusesNames() throws RespException {
        Pairs map = new Pairs();
        run(map, "%1\r\n+f\r\n:7");
        assertEquals("[begin:1, field:f, vbegin:1, vcontent, vend, end, complete:]", map.log.toString());
        Pairs nullName = new Pairs();
        run(nullName, "*2\r\n_\r\n$1\r\nv");
        assertTrue(nullName.error, nullName.error.startsWith("ERR"));
        Pairs longName = new Pairs();
        run(longName, "*2\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx\r\n$1\r\nv");
        assertTrue(longName.error, longName.error.startsWith("TOOLARGE"));
        Pairs streamedName = new Pairs();
        run(streamedName, "*2\r\n$?\r\n;6\r\nabcdef\r\n;6\r\nghijkl\r\n;0\r\n$1\r\nv");
        assertTrue(streamedName.error, streamedName.error.startsWith("TOOLARGE"));
        Pairs nul = new Pairs();
        run(nul, "_");
        assertEquals("[null, complete:]", nul.log.toString());
        Pairs scalarName = new Pairs();
        run(scalarName, "*2\r\n:9\r\n$-1");
        assertEquals("[begin:1, field:9, vnull, end, complete:]", scalarName.log.toString());
    }

    @Test
    public void aScanHandlerWantsACursorThenKeys() throws RespException {
        Scan ok = new Scan();
        run(ok, "*2\r\n$2\r\n17\r\n*2\r\n$1\r\na\r\n:5");
        assertEquals("[cursor:17, key:a, key:5, end, complete:]", ok.log.toString());
        Scan intCursor = new Scan();
        run(intCursor, "*2\r\n:9\r\n*0");
        assertEquals("[cursor:9, end, complete:]", intCursor.log.toString());
        Scan nul = new Scan();
        run(nul, "_");
        assertNotNull(nul.error);
        Scan nullKey = new Scan();
        run(nullKey, "*2\r\n$1\r\n0\r\n*1\r\n_");
        assertNotNull(nullKey.error);
        Scan nestedKeys = new Scan();
        run(nestedKeys, "*2\r\n$1\r\n0\r\n*1\r\n*0");
        assertNotNull(nestedKeys.error);
        Scan aggregateCursor = new Scan();
        run(aggregateCursor, "*2\r\n*0\r\n*0");
        assertNotNull(aggregateCursor.error);
        Scan longCursor = new Scan();
        run(longCursor, "*2\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx\r\n*0");
        assertTrue(longCursor.error, longCursor.error.startsWith("TOOLARGE"));
        Scan longKey = new Scan();
        run(longKey, "*2\r\n$1\r\n0\r\n*1\r\n$20\r\nxxxxxxxxxxxxxxxxxxxx");
        assertTrue(longKey.error, longKey.error.startsWith("TOOLARGE"));
        Scan blob = new Scan();
        run(blob, "*2\r\n!1\r\nx\r\n*0");
        assertNotNull(blob.error);
        Scan streamed = new Scan();
        run(streamed, "*2\r\n$?\r\n;1\r\n0\r\n;0\r\n*1\r\n$?\r\n;1\r\na\r\n;0");
        assertEquals("[cursor:0, key:a, end, complete:]", streamed.log.toString());
    }

    @Test
    public void anEventHandlerHearsEverythingAndAnErrorThatIsTheWholeReplyOnlyCompletes()
            throws RespException {
        Events whole = new Events();
        run(whole, "-EXECABORT x");
        assertEquals("[complete:EXECABORT]", whole.log.toString());
        Events blob = new Events();
        run(blob, "!5\r\nERR x");
        assertTrue(blob.error, blob.error != null);
        Events all = new Events();
        run(all, "*8\r\n+a\r\n:1\r\n,1.5\r\n#t\r\n(9\r\n_\r\n$1\r\nb\r\n-ERR in");
        assertEquals("[beginReply, agg, simple, int, double, bool, big, null, str, chunk, endstr,"
                + " error, endagg, endReply, complete:]", all.log.toString());
        Events push = new Events();
        run(push, ">1\r\n+x");
        assertTrue(push.log.get(0).equals("beginReply"));
    }

    @Test
    public void streamHandlesAreGivenToTheHandler() throws RespException {
        for (int h = 1; h < 6; h++) {
            Script script = handlers()[h];
            ReplyAdapter adapter = ReplyAdapter.forHandler(script, null, MAX_FIELD);
            adapter.setStream(ReplyStream.NONE);
            run(script, "*2\r\n$1\r\n0\r\n*0");
            assertNotNull(script.getClass().getSimpleName(), script.stream);
        }
        ReplyStream.NONE.resume();
        ReplyStream.NONE.cancel();
    }

    @Test
    public void theNoOpAdapterAndTheForwarderPassEverythingOn() {
        final List<String> log = new ArrayList<String>();
        ReplyEventAdapter adapter = new ReplyEventAdapter() {
        };
        ControlReplies.Forwarding forward = new ControlReplies.Forwarding(adapter) {
            public void handleComplete(String error, RedisSession session) {
                log.add("complete");
            }
        };
        org.bluezoo.gumdrop.redis.codec.RespType t = org.bluezoo.gumdrop.redis.codec.RespType.ARRAY;
        forward.handleStream(ReplyStream.NONE);
        forward.beginReply(false);
        assertEquals(ReplyFlow.CONTINUE, forward.nullValue());
        assertEquals(ReplyFlow.CONTINUE, forward.integer(1));
        assertEquals(ReplyFlow.CONTINUE, forward.doubleValue(1));
        assertEquals(ReplyFlow.CONTINUE, forward.booleanValue(true));
        assertEquals(ReplyFlow.CONTINUE, forward.bigNumber("1"));
        assertEquals(ReplyFlow.CONTINUE, forward.simpleString("x"));
        assertEquals(ReplyFlow.CONTINUE, forward.error("x"));
        assertEquals(ReplyFlow.CONTINUE, forward.beginAggregate(t, 1));
        assertEquals(ReplyFlow.CONTINUE, forward.endAggregate());
        assertEquals(ReplyFlow.CONTINUE, forward.beginString(
                org.bluezoo.gumdrop.redis.codec.RespType.BULK_STRING, 1, null));
        assertEquals(ReplyFlow.CONTINUE, forward.stringChunk(ByteBuffer.wrap(new byte[] { 1 })));
        assertEquals(ReplyFlow.CONTINUE, forward.endString());
        forward.endReply(false);
        forward.handleComplete(null, null);
        adapter.handleComplete(null, null);
        assertEquals("[complete]", log.toString());
    }

}
