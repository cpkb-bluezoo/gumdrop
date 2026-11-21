/*
 * InvalidationHandlerTest.java
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

import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespException;

import static org.junit.Assert.assertEquals;

/**
 * The reader of client-side caching invalidations, driven by the decoder: the
 * keys named, a flush, a key too long to deliver, and every other push, in
 * every kind of shape, whole and a byte at a time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class InvalidationHandlerTest {

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

    private static List<String> read(int max, String wire, boolean byteAtATime) throws RespException {
        Cache cache = new Cache(max);
        byte[] bytes = (wire + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        RespDecoder decoder = new RespDecoder();
        int step = byteAtATime ? 1 : bytes.length;
        for (int off = 0; off < bytes.length; off += step) {
            decoder.receive(ByteBuffer.wrap(bytes, off, Math.min(step, bytes.length - off)), cache);
        }
        return cache.log;
    }

    private static void check(String expected, int max, String wire) throws RespException {
        assertEquals(wire.replace("\r\n", "|"), expected, read(max, wire, false).toString());
        assertEquals(wire.replace("\r\n", "|") + " a byte at a time", expected,
                read(max, wire, true).toString());
    }

    @Test
    public void keysAreNamedWhetherTheKindIsBulkOrSimple() throws RespException {
        check("[key:a, key:bc]", 8, ">2\r\n$10\r\ninvalidate\r\n*2\r\n$1\r\na\r\n$2\r\nbc");
        check("[key:a]", 8, ">2\r\n+invalidate\r\n*1\r\n$1\r\na");
        check("[key:a, key:b]", 8, ">2\r\n+invalidate\r\n*2\r\n+a\r\n+b");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n*0");
        check("[key:s]", 8, ">2\r\n$10\r\ninvalidate\r\n~1\r\n$1\r\ns");
    }

    @Test
    public void aFlushIsEverythingInvalidatedOnce() throws RespException {
        check("[all]", 8, ">2\r\n$10\r\ninvalidate\r\n_");
    }

    @Test
    public void aKeyTooLongToDeliverIsEverythingInvalidatedOnceAndTheRestIsDiscarded() throws RespException {
        check("[key:a, all]", 4, ">2\r\n$10\r\ninvalidate\r\n*3\r\n$1\r\na\r\n$9\r\nwaytoolon\r\n$1\r\nb");
        check("[all]", 4, ">2\r\n$10\r\ninvalidate\r\n*2\r\n$9\r\nwaytoolon\r\n$9\r\nalsolongg");
        check("[all]", 4, ">2\r\n$10\r\ninvalidate\r\n*1\r\n$?\r\n;3\r\nabc\r\n;3\r\ndef\r\n;0");
    }

    @Test
    public void otherPushesAndOddShapesAreIgnored() throws RespException {
        check("[]", 8, ">2\r\n$4\r\nmark\r\n*1\r\n$1\r\nx");
        check("[]", 8, ">2\r\n+mark\r\n:1");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n$1\r\nx");
        check("[]", 8, ">1\r\n$4\r\nmark");
        check("[]", 8, ">2\r\n:1\r\n:2");
        check("[]", 8, ">2\r\n_\r\n:2");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n,1.5");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n#t");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n(12");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n_");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n-ERR e");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n-ERR e");
        check("[]", 8, ">2\r\n$4\r\nmark\r\n=7\r\ntxt:abc");
    }

    @Test
    public void oddElementsOfAnInvalidationAreSkippedWithoutHarm() throws RespException {
        check("[key:a]", 8, ">2\r\n$10\r\ninvalidate\r\n*3\r\n$1\r\na\r\n:5\r\n,1.5");
        check("[key:a]", 8, ">2\r\n$10\r\ninvalidate\r\n*3\r\n$1\r\na\r\n#t\r\n(7");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n:5");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n,1.5");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n#t");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n(5");
        check("[]", 8, ">2\r\n$10\r\ninvalidate\r\n+x");
    }

    @Test
    public void theDefaultLimitIsLargeAndTheHandlerIsReusable() throws RespException {
        Cache cache = new Cache(4);
        RespDecoder decoder = new RespDecoder();
        byte[] first = ">2\r\n$10\r\ninvalidate\r\n*1\r\n$1\r\na\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] second = ">2\r\n$10\r\ninvalidate\r\n_\r\n".getBytes(StandardCharsets.ISO_8859_1);
        decoder.receive(ByteBuffer.wrap(first), cache);
        decoder.receive(ByteBuffer.wrap(second), cache);
        assertEquals("[key:a, all]", cache.log.toString());
        InvalidationHandler dflt = new InvalidationHandler() {
            @Override
            protected void invalidate(String key) {
            }

            @Override
            protected void invalidateAll() {
            }
        };
        dflt.handleStream(ReplyStream.NONE);
        dflt.endReply(false);
    }

}
