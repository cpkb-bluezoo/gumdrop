/*
 * MessageSet.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Represents an IMAP message set (sequence set or UID set).
 * 
 * <p>A message set specifies a collection of message numbers or UIDs.
 * It can contain single numbers, ranges, and wildcards. Examples:
 * <ul>
 *   <li>{@code 5} - single message 5</li>
 *   <li>{@code 1:10} - messages 1 through 10</li>
 *   <li>{@code *} - the last message (wildcard)</li>
 *   <li>{@code 10:*} - messages 10 through the last</li>
 *   <li>{@code 1:5,7,10:*} - combination of ranges</li>
 * </ul>
 * 
 * <p>The same structure is used for both sequence numbers (message order)
 * and UIDs (unique identifiers). The interpretation depends on context.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051#section-4.1.1">RFC 9051 Section 4.1.1</a>
 */
public final class MessageSet implements Iterable<MessageSet.Range> {

    /**
     * Represents the wildcard value {@code *} meaning "last message".
     */
    public static final long WILDCARD = -1;

    /**
     * A range within a message set.
     * 
     * <p>A range can be a single number (start == end), a numeric range,
     * or include wildcards. Use {@link #WILDCARD} for the {@code *} value.
     */
    public static final class Range {
        
        private final long start;
        private final long end;

        /**
         * Creates a range.
         * 
         * <p>IMAP allows reverse ranges like 10:1; these are normalized
         * to ascending order.
         * 
         * @param start the start of the range, or {@link #WILDCARD}
         * @param end the end of the range (inclusive), or {@link #WILDCARD}
         */
        public Range(long start, long end) {
            if (start < WILDCARD || end < WILDCARD) {
                throw new IllegalArgumentException("Invalid range values");
            }
            // IMAP allows reverse ranges like 10:1, normalize them
            if (start != WILDCARD && end != WILDCARD && start > end) {
                this.start = end;
                this.end = start;
            } else {
                this.start = start;
                this.end = end;
            }
        }

        /**
         * Returns the start of the range.
         */
        public long getStart() {
            return start;
        }

        /**
         * Returns the end of the range (inclusive).
         */
        public long getEnd() {
            return end;
        }

        /**
         * Returns true if this is a single number (not a range).
         */
        public boolean isSingle() {
            return start == end && start != WILDCARD;
        }

        /**
         * Returns true if this range contains the wildcard.
         */
        public boolean hasWildcard() {
            return start == WILDCARD || end == WILDCARD;
        }

        /**
         * Tests if this range contains the given number.
         * 
         * @param number the number to test
         * @param lastNumber the value to use for wildcards (e.g., message count or max UID)
         * @return true if the number is within this range
         */
        public boolean contains(long number, long lastNumber) {
            long resolvedStart = (start == WILDCARD) ? lastNumber : start;
            long resolvedEnd = (end == WILDCARD) ? lastNumber : end;
            // Handle reverse ranges after wildcard resolution
            if (resolvedStart > resolvedEnd) {
                long temp = resolvedStart;
                resolvedStart = resolvedEnd;
                resolvedEnd = temp;
            }
            return number >= resolvedStart && number <= resolvedEnd;
        }

        /**
         * Returns the IMAP string representation.
         */
        public String toImapString() {
            String startStr = (start == WILDCARD) ? "*" : String.valueOf(start);
            if (start == end) {
                return startStr;
            }
            String endStr = (end == WILDCARD) ? "*" : String.valueOf(end);
            return startStr + ":" + endStr;
        }

        @Override
        public String toString() {
            return toImapString();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Range)) {
                return false;
            }
            Range other = (Range) obj;
            return start == other.start && end == other.end;
        }

        @Override
        public int hashCode() {
            return (int) (start * 31 + end);
        }
    }

    private final List<Range> ranges;

    private MessageSet(List<Range> ranges) {
        this.ranges = Collections.unmodifiableList(new ArrayList<Range>(ranges));
    }

    /**
     * Parses an IMAP message set string.
     * 
     * @param imapSet the IMAP set string (e.g., "1:5,7,10:*")
     * @return the parsed MessageSet
     * @throws IllegalArgumentException if the string is invalid
     */
    public static MessageSet parse(String imapSet) {
        if (imapSet == null) {
            throw new NullPointerException("imapSet");
        }
        if (imapSet.isEmpty()) {
            throw new IllegalArgumentException("Empty message set");
        }

        List<Range> ranges = new ArrayList<Range>();
        String[] parts = imapSet.split(",");
        
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            
            int colonIndex = part.indexOf(':');
            if (colonIndex < 0) {
                // Single number or wildcard
                long value = parseValue(part);
                ranges.add(new Range(value, value));
            } else {
                // Range
                String startStr = part.substring(0, colonIndex).trim();
                String endStr = part.substring(colonIndex + 1).trim();
                long start = parseValue(startStr);
                long end = parseValue(endStr);
                ranges.add(new Range(start, end));
            }
        }
        
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("Invalid message set: " + imapSet);
        }
        
        return new MessageSet(ranges);
    }

    private static long parseValue(String value) {
        if ("*".equals(value)) {
            return WILDCARD;
        }
        try {
            long num = Long.parseLong(value);
            if (num < 1) {
                throw new IllegalArgumentException("Invalid message number: " + value);
            }
            return num;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid message number: " + value, e);
        }
    }

    /**
     * Creates a message set containing a single number.
     */
    public static MessageSet single(long number) {
        if (number < 1) {
            throw new IllegalArgumentException("Invalid message number: " + number);
        }
        return new MessageSet(Collections.singletonList(new Range(number, number)));
    }

    /**
     * Creates a message set containing a range.
     */
    public static MessageSet range(long start, long end) {
        return new MessageSet(Collections.singletonList(new Range(start, end)));
    }

    /**
     * Creates a message set representing all messages (equivalent to "1:*").
     */
    public static MessageSet all() {
        return new MessageSet(Collections.singletonList(new Range(1, WILDCARD)));
    }

    /**
     * Creates a message set representing just the last message (equivalent to "*").
     */
    public static MessageSet last() {
        return new MessageSet(Collections.singletonList(new Range(WILDCARD, WILDCARD)));
    }

    /**
     * Returns the ranges in this message set.
     */
    public List<Range> getRanges() {
        return ranges;
    }

    /**
     * Tests if this set contains the given number.
     * 
     * @param number the number to test
     * @param lastNumber the value to use for wildcards
     * @return true if any range contains the number
     */
    public boolean contains(long number, long lastNumber) {
        for (Range range : ranges) {
            if (range.contains(number, lastNumber)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this set contains any wildcards.
     */
    public boolean hasWildcard() {
        for (Range range : ranges) {
            if (range.hasWildcard()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this is a single number (not a range or multiple values).
     */
    public boolean isSingle() {
        return ranges.size() == 1 && ranges.get(0).isSingle();
    }

    @Override
    public Iterator<Range> iterator() {
        return ranges.iterator();
    }

    /**
     * Returns the IMAP string representation.
     */
    public String toImapString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ranges.get(i).toImapString());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toImapString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MessageSet)) {
            return false;
        }
        MessageSet other = (MessageSet) obj;
        return ranges.equals(other.ranges);
    }

    @Override
    public int hashCode() {
        return ranges.hashCode();
    }
}
