/*
 * GrpcTimeoutTest.java
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
package org.bluezoo.gumdrop.grpc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link GrpcTimeout}: the {@code grpc-timeout} header value.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcTimeoutTest {

    @Test
    public void parsesEveryUnit() {
        assertEquals(7L, GrpcTimeout.parse("7n"));
        assertEquals(7000L, GrpcTimeout.parse("7u"));
        assertEquals(7000000L, GrpcTimeout.parse("7m"));
        assertEquals(7000000000L, GrpcTimeout.parse("7S"));
        assertEquals(420000000000L, GrpcTimeout.parse("7M"));
        assertEquals(25200000000000L, GrpcTimeout.parse("7H"));
        assertEquals(0L, GrpcTimeout.parse("0n"));
        assertEquals(99999999L * 1000000000L, GrpcTimeout.parse("99999999S"));
    }

    @Test
    public void refusesWhatIsNotATimeout() {
        String[] bad = {null, "", "5", "S", "x5S", "5s", "-5S", "5 S", "123456789S", "5.5S", "5SS"};
        for (int i = 0; i < bad.length; i++) {
            assertEquals("'" + bad[i] + "'", -1L, GrpcTimeout.parse(bad[i]));
        }
    }

    @Test
    public void formatsInTheFinestUnitThatFitsAndRoundsUp() {
        assertEquals("0n", GrpcTimeout.format(0));
        assertEquals("1n", GrpcTimeout.format(1));
        assertEquals("99999999n", GrpcTimeout.format(99999999L));
        assertEquals("100000u", GrpcTimeout.format(100000000L));
        assertEquals("5000000u", GrpcTimeout.format(5000000000L));
        assertEquals("100001u", GrpcTimeout.format(100000001L));
    }

    @Test
    public void aFormattedTimeoutNeverShortensTheWait() {
        long[] samples = {1L, 999L, 123456789L, 5000000000L, 86400000000000L, 123456789012345L};
        for (int i = 0; i < samples.length; i++) {
            String text = GrpcTimeout.format(samples[i]);
            assertTrue(text, text.length() <= 9);
            assertTrue(text, GrpcTimeout.parse(text) >= samples[i]);
        }
    }
}
