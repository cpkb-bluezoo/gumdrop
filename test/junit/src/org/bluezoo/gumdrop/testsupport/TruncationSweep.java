/*
 * TruncationSweep.java
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

package org.bluezoo.gumdrop.testsupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic robustness sweeps for decoders fed by network bytes. Given
 * one valid encoding, it feeds the decoder every prefix of it and every
 * single-byte corruption of it (a handful of boundary values at each
 * offset: zero, one, 0x7f, 0x80, 0xff), and fails if anything other than
 * the decoder's documented protocol exception escapes. An {@link Error}
 * (for example an OutOfMemoryError from a declared length) is always a
 * failure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TruncationSweep {

    /** Boundary values written over each byte of the valid encoding. */
    private static final int[] CORRUPT_VALUES = {0x00, 0x01, 0x7f, 0x80, 0xff};

    /** The decoder under test, invoked with a fresh copy of the input. */
    public interface Target {
        void decode(byte[] input) throws Exception;
    }

    private TruncationSweep() {
    }

    /**
     * Feeds every prefix of {@code valid} (including the empty one) to the
     * target.
     *
     * @param valid a valid encoding
     * @param target the decoder
     * @param allowed the exception types the decoder may legitimately throw
     * @return a description of every distinct failure; empty if none
     */
    public static List<String> prefixFailures(byte[] valid, Target target,
            Class<?>... allowed) {
        List<String> failures = new ArrayList<String>();
        for (int n = 0; n < valid.length; n++) {
            byte[] input = new byte[n];
            System.arraycopy(valid, 0, input, 0, n);
            run(target, input, "prefix " + n, allowed, failures);
        }
        return failures;
    }

    /**
     * Feeds every single-byte corruption of {@code valid} to the target.
     *
     * @param valid a valid encoding
     * @param target the decoder
     * @param allowed the exception types the decoder may legitimately throw
     * @return a description of every distinct failure; empty if none
     */
    public static List<String> corruptionFailures(byte[] valid, Target target,
            Class<?>... allowed) {
        List<String> failures = new ArrayList<String>();
        for (int i = 0; i < valid.length; i++) {
            for (int v : CORRUPT_VALUES) {
                byte[] input = valid.clone();
                input[i] = (byte) v;
                run(target, input, "byte " + i + "=" + v, allowed, failures);
            }
        }
        return failures;
    }

    /**
     * Runs prefix and corruption sweeps and returns all failures.
     *
     * @param valid a valid encoding
     * @param target the decoder
     * @param allowed the exception types the decoder may legitimately throw
     * @return a description of every distinct failure; empty if none
     */
    public static List<String> allFailures(byte[] valid, Target target,
            Class<?>... allowed) {
        List<String> failures = prefixFailures(valid, target, allowed);
        failures.addAll(corruptionFailures(valid, target, allowed));
        return failures;
    }

    /**
     * Runs the target once on a specific input.
     *
     * @param target the decoder
     * @param input the bytes to decode
     * @param label a label for failure messages
     * @param allowed the exception types the decoder may legitimately throw
     * @return failure descriptions; empty if none
     */
    public static List<String> inputFailures(Target target, byte[] input,
            String label, Class<?>... allowed) {
        List<String> failures = new ArrayList<String>();
        run(target, input, label, allowed, failures);
        return failures;
    }

    private static void run(Target target, byte[] input, String label,
            Class<?>[] allowed, List<String> failures) {
        try {
            target.decode(input);
        } catch (Throwable t) {
            if (t instanceof Error) {
                add(failures, label, t);
                return;
            }
            for (Class<?> c : allowed) {
                if (c.isInstance(t)) {
                    return;
                }
            }
            add(failures, label, t);
        }
    }

    /** Keeps one entry per distinct exception class and top frame. */
    private static void add(List<String> failures, String label, Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String where = st.length > 0 ? st[0].toString() : "?";
        String key = t.getClass().getName() + " at " + where;
        for (String f : failures) {
            if (f.endsWith(key)) {
                return;
            }
        }
        failures.add(label + ": " + key);
    }
}
