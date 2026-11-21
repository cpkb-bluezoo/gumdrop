/*
 * RespEncoderStreamedTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The head, payload and tail of a command with a streamed argument make the
 * same bytes as the command encoded whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RespEncoderStreamedTest {

    private static String text(ByteBuffer b) {
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void streamedPiecesEqualTheWholeCommandForEveryPositionOfTheArgument() {
        RespEncoder encoder = new RespEncoder();
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int count = 1 + random.nextInt(5);
            byte[][] args = new byte[count][];
            for (int i = 0; i < count; i++) {
                args[i] = new byte[random.nextInt(12)];
                for (int j = 0; j < args[i].length; j++) {
                    args[i][j] = (byte) ('a' + random.nextInt(26));
                }
            }
            int index = random.nextInt(count);
            byte[] payload = args[index];
            byte[][] placeholder = args.clone();
            placeholder[index] = new byte[0];
            String whole = text(encoder.encodeCommand("CMD", args));
            String streamed = text(encoder.encodeStreamedHead("CMD", placeholder, index, payload.length))
                    + new String(payload, StandardCharsets.ISO_8859_1)
                    + text(encoder.encodeStreamedTail(placeholder, index));
            assertEquals(whole, streamed);
        }
    }

    @Test
    public void aLengthOverTheRangeOfAnIntIsWrittenInFull() {
        RespEncoder encoder = new RespEncoder();
        String head = text(encoder.encodeStreamedHead("SET", new byte[][] { { 'k' }, {} }, 1,
                5L * 1024 * 1024 * 1024));
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5368709120\r\n", head);
    }

}
