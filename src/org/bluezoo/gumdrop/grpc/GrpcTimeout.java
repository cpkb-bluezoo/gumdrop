/*
 * GrpcTimeout.java
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

/**
 * The {@code grpc-timeout} header value: up to eight digits and a unit,
 * {@code H}, {@code M}, {@code S}, {@code m}, {@code u} or {@code n} for
 * hours, minutes, seconds, milliseconds, microseconds and nanoseconds.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class GrpcTimeout {

    private static final int MAX_DIGITS = 8;
    private static final long MAX_VALUE = 99999999L;
    /** The unit letters, finest first. */
    private static final char[] UNIT_CHARS = {'n', 'u', 'm', 'S', 'M', 'H'};
    /** The nanoseconds in each unit of {@link #UNIT_CHARS}. */
    private static final long[] UNIT_NANOS = {
        1L, 1000L, 1000000L, 1000000000L, 60000000000L, 3600000000000L
    };

    private GrpcTimeout() {
    }

    /**
     * Parses a {@code grpc-timeout} value.
     *
     * @param value the header value
     * @return the timeout in nanoseconds, or -1 if the value is not valid
     */
    public static long parse(String value) {
        if (value == null || value.length() < 2 || value.length() > MAX_DIGITS + 1) {
            return -1;
        }
        int unit = unitIndex(value.charAt(value.length() - 1));
        if (unit < 0) {
            return -1;
        }
        long amount = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            amount = amount * 10 + (c - '0');
        }
        long nanos = amount * UNIT_NANOS[unit];
        return nanos / UNIT_NANOS[unit] == amount ? nanos : Long.MAX_VALUE;
    }

    /**
     * Formats a timeout, rounding up to the finest unit that fits in eight
     * digits so that the receiver never waits less than was asked.
     *
     * @param nanos the timeout in nanoseconds; zero or negative is sent as 0
     * @return the header value
     */
    public static String format(long nanos) {
        if (nanos <= 0) {
            return "0n";
        }
        for (int i = 0; i < UNIT_NANOS.length; i++) {
            long amount = (nanos + UNIT_NANOS[i] - 1) / UNIT_NANOS[i];
            if (amount <= MAX_VALUE) {
                return amount + String.valueOf(UNIT_CHARS[i]);
            }
        }
        return MAX_VALUE + "H";
    }

    private static int unitIndex(char c) {
        for (int i = 0; i < UNIT_CHARS.length; i++) {
            if (UNIT_CHARS[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
