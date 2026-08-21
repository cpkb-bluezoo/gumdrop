/*
 * PriorityParams.java
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

package org.bluezoo.gumdrop.http;

/**
 * RFC 9218 Extensible Prioritization parameters ({@code u} / {@code i})
 * shared by the {@code Priority} header field and {@code PRIORITY_UPDATE}
 * frames on HTTP/2 and HTTP/3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9218">RFC 9218</a>
 */
public final class PriorityParams {

    /** Default urgency when the parameter is absent (RFC 9218 section 4.1). */
    public static final int DEFAULT_URGENCY = 3;
    /** Highest urgency (most important). */
    public static final int URGENCY_HIGHEST = 0;
    /** Lowest urgency (background). */
    public static final int URGENCY_LOWEST = 7;
    /** {@code Priority} header field name (RFC 9218 section 5). */
    public static final String PRIORITY_HEADER = "priority";

    /** Default parameters ({@code u=3}, not incremental). */
    public static final PriorityParams DEFAULT = new PriorityParams(DEFAULT_URGENCY, false);

    private final int urgency;
    private final boolean incremental;

    /**
     * Constructs parameters, clamping urgency into {@code 0..7}.
     *
     * @param urgency the urgency (0 = highest)
     * @param incremental true if incremental delivery is useful
     */
    public PriorityParams(int urgency, boolean incremental) {
        this.urgency = Math.min(URGENCY_LOWEST, Math.max(URGENCY_HIGHEST, urgency));
        this.incremental = incremental;
    }

    /**
     * Returns urgency 0..7 (0 = highest).
     *
     * @return the urgency
     */
    public int getUrgency() {
        return urgency;
    }

    /**
     * Returns whether the response is useful when delivered incrementally.
     *
     * @return true if incremental
     */
    public boolean isIncremental() {
        return incremental;
    }

    /**
     * Maps urgency onto a send-scheduler priority: higher means sooner
     * (the inverse of RFC 9218 urgency).
     *
     * @return a send priority suitable for {@code QuicConnection}
     */
    public int quicSendPriority() {
        return URGENCY_LOWEST - urgency;
    }

    /**
     * Encodes as a Structured Fields Dictionary suitable for the
     * {@code Priority} header or a PRIORITY_UPDATE payload.
     *
     * @return the encoded field value (possibly empty)
     */
    public String encode() {
        if (incremental) {
            if (urgency == DEFAULT_URGENCY) {
                return "i";
            }
            return "u=" + urgency + ", i";
        }
        if (urgency == DEFAULT_URGENCY) {
            return "";
        }
        return "u=" + urgency;
    }

    /**
     * Parses a Priority Field Value (Dictionary). Unknown keys and
     * out-of-range values are ignored (RFC 9218 section 4). On total
     * parse failure returns {@link #DEFAULT}.
     *
     * @param input the field value; may be null
     * @return the parsed parameters
     */
    public static PriorityParams parse(String input) {
        if (input == null || input.isEmpty()) {
            return DEFAULT;
        }
        int urgency = DEFAULT_URGENCY;
        boolean incremental = false;
        int start = 0;
        int length = input.length();
        while (start < length) {
            int comma = input.indexOf(',', start);
            if (comma < 0) {
                comma = length;
            }
            String member = input.substring(start, comma).trim();
            start = comma + 1;
            if (member.isEmpty()) {
                continue;
            }
            int eq = member.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = member;
                value = null;
            } else {
                key = member.substring(0, eq).trim();
                value = member.substring(eq + 1).trim();
            }
            if (key.isEmpty()) {
                continue;
            }
            if ("u".equals(key)) {
                if (value != null) {
                    try {
                        long n = Long.parseLong(value);
                        if (n >= URGENCY_HIGHEST && n <= URGENCY_LOWEST) {
                            urgency = (int) n;
                        }
                    } catch (NumberFormatException ignored) {
                        // RFC 9218 section 4: ignore unrecognised / invalid values
                    }
                }
            } else if ("i".equals(key)) {
                if (value == null) {
                    incremental = true;
                } else if ("?1".equals(value) || "1".equals(value) || "true".equals(value)) {
                    incremental = true;
                } else if ("?0".equals(value) || "0".equals(value) || "false".equals(value)) {
                    incremental = false;
                }
            }
        }
        return new PriorityParams(urgency, incremental);
    }

    /**
     * Parses from request/response headers ({@code Priority} field), or
     * {@link #DEFAULT} if absent.
     *
     * @param headers the header set
     * @return the parsed parameters
     */
    public static PriorityParams fromHeaders(Headers headers) {
        if (headers == null) {
            return DEFAULT;
        }
        String value = headers.getValue(PRIORITY_HEADER);
        if (value == null) {
            return DEFAULT;
        }
        return parse(value);
    }

    /**
     * Maps an HTTPRequest {@code priority(weight)} value (0–255, higher
     * weight = more important) onto urgency 0–7.
     *
     * @param weight the 0–255 weight
     * @return urgency 0–7
     */
    public static int urgencyFromWeight(int weight) {
        int clamped = Math.min(255, Math.max(0, weight));
        return 7 - Math.min(7, clamped * 7 / 255);
    }

    /**
     * Scheduling comparison: lower result means flush sooner.
     *
     * <p>Order: urgency ascending, then incremental before
     * non-incremental, then stream ID (request order) for stable ties
     * (RFC 9218 section 10).
     *
     * @param a first parameters
     * @param streamIdA first stream ID
     * @param b second parameters
     * @param streamIdB second stream ID
     * @return negative if {@code a} should be flushed first
     */
    public static int compareSchedule(PriorityParams a, long streamIdA,
            PriorityParams b, long streamIdB) {
        if (a.urgency != b.urgency) {
            return a.urgency - b.urgency;
        }
        int ia = a.incremental ? 0 : 1;
        int ib = b.incremental ? 0 : 1;
        if (ia != ib) {
            return ia - ib;
        }
        return Long.compare(streamIdA, streamIdB);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PriorityParams)) {
            return false;
        }
        PriorityParams other = (PriorityParams) o;
        return urgency == other.urgency && incremental == other.incremental;
    }

    @Override
    public int hashCode() {
        return 31 * urgency + (incremental ? 1 : 0);
    }

    @Override
    public String toString() {
        return encode();
    }
}
