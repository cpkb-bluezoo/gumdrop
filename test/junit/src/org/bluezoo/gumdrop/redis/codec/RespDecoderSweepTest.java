/*
 * RespDecoderSweepTest.java
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
import java.util.List;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation, corruption and hostile-length sweeps over {@link RespDecoder}:
 * only {@link RespException} may escape, and a truncated value must not
 * produce an error (it waits for more input).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RespDecoderSweepTest {

    private static final String VALID = ""
            + "*4\r\n$3\r\nfoo\r\n:-42\r\n%2\r\n+k\r\n,1.5\r\n$-1\r\n_\r\n"
            + "~2\r\n#t\r\n#f\r\n"
            + ">2\r\n+pubsub\r\n$2\r\nhi\r\n"
            + "=7\r\ntxt:abc\r\n(12345678901234567890\r\n!5\r\nERR x\r\n-ERR bad\r\n";

    private static TruncationSweep.Target target() {
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                RespDecoder decoder = new RespDecoder(16);
                decoder.receive(ByteBuffer.wrap(input));
                while (decoder.next() != null) {
                    // drain
                }
            }
        };
    }

    private static void assertNone(List<String> failures) {
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void onlyRespExceptionEscapes() {
        byte[] valid = VALID.getBytes(StandardCharsets.UTF_8);
        assertNone(TruncationSweep.allFailures(valid, target(), RespException.class));
    }

    @Test
    public void everyPrefixOfAValidStreamIsSimplyIncomplete() {
        byte[] valid = VALID.getBytes(StandardCharsets.UTF_8);
        assertNone(TruncationSweep.prefixFailures(valid, target()));
    }

    @Test
    public void hostileLengthsAndNestingAreRejected() {
        String[] hostile = {
            "$2147483647\r\n", "$-2\r\n", "$4294967296\r\n", "*2147483647\r\n",
            "*-5\r\n", "%2147483647\r\n", "~99999999999\r\n", ">-1\r\n",
            "=2147483647\r\n", "!2147483647\r\n", "=2\r\nab\r\n", "=1\r\na\r\n",
            ":999999999999999999999999\r\n", ",abc\r\n", "#x\r\n", "?\r\n",
        };
        for (int i = 0; i < hostile.length; i++) {
            byte[] b = hostile[i].getBytes(StandardCharsets.UTF_8);
            assertNone(TruncationSweep.inputFailures(target(), b, hostile[i],
                    RespException.class));
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            deep.append("*1\r\n");
        }
        assertNone(TruncationSweep.inputFailures(target(),
                deep.toString().getBytes(StandardCharsets.UTF_8), "deep", RespException.class));
    }
}
