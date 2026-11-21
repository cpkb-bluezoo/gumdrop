/*
 * CommandTimeoutsTest.java
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

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * How long a command that waits on the server on purpose says it will wait,
 * from its arguments, as text or as bytes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CommandTimeoutsTest {

    private static byte[][] bytes(String... args) {
        byte[][] out = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    private static long text(String command, String... args) {
        return CommandTimeouts.blockingMillis(CommandInfo.of(command), args, null);
    }

    private static long binary(String command, String... args) {
        return CommandTimeouts.blockingMillis(CommandInfo.of(command), null, bytes(args));
    }

    @Test
    public void aTimeoutInSecondsIsReadFromTheLastOrFirstArgument() {
        assertEquals(5000, text("BLPOP", "q", "5"));
        assertEquals(1500, text("BRPOP", "a", "b", "1.5"));
        assertEquals(0, text("BLPOP", "q", "0"));
        assertEquals(2000, text("BLMPOP", "2", "2", "k1", "k2", "LEFT"));
    }

    @Test
    public void aTimeoutInMillisecondsIsReadFromTheLastArgument() {
        assertEquals(250, text("WAIT", "1", "250"));
    }

    @Test
    public void aBlockOptionIsFoundAnywhereBeforeTheEnd() {
        assertEquals(300, text("XREAD", "COUNT", "5", "block", "300", "STREAMS", "s", "$"));
        assertEquals(-1, text("XREAD", "COUNT", "5", "STREAMS", "s", "$"));
        assertEquals(-1, text("XREAD", "STREAMS", "s", "BLOCK"));
    }

    @Test
    public void binaryArgumentsAreReadTheSame() {
        assertEquals(5000, binary("BLPOP", "q", "5"));
        assertEquals(250, binary("WAIT", "1", "250"));
        assertEquals(300, binary("XREAD", "BLOCK", "300", "STREAMS", "s", "$"));
        assertEquals(2000, binary("BLMPOP", "2", "2", "k1", "LEFT"));
    }

    @Test
    public void whatIsNotANumberOrIsMissingMeansNoTimeoutIsKnown() {
        assertEquals(-1, text("BLPOP", "q", "soon"));
        assertEquals(-1, text("WAIT", "1", "soon"));
        assertEquals(-1, text("BLPOP"));
        assertEquals(-1, text("BLMPOP"));
        assertEquals(-1, CommandTimeouts.blockingMillis(CommandInfo.of("BLPOP"), null, null));
        assertEquals(-1, CommandTimeouts.blockingMillis(CommandInfo.of("BLPOP"), null, new byte[0][]));
        assertEquals(-1, binary("BLPOP", "q", "soon"));
        assertEquals(-1, binary("WAIT", "1", "soon"));
    }

    @Test
    public void aCommandThatDoesNotWaitHasNoTimeout() {
        assertEquals(-1, text("GET", "k"));
        assertEquals(-1, binary("GET", "k"));
    }

}
