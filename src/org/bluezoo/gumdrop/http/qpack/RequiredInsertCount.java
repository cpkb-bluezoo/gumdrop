/*
 * RequiredInsertCount.java
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

package org.bluezoo.gumdrop.http.qpack;

/**
 * RFC 9204 section 4.5.1.1's Required Insert Count wire encoding -- a
 * compact wrapped form (not the raw integer) chosen so a decoder can
 * detect corruption: since a real Required Insert Count can never
 * exceed the peer's dynamic table capacity in entries, the wire form
 * wraps modulo twice that count.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5.1.1">RFC 9204 section 4.5.1.1</a>
 */
final class RequiredInsertCount {

    /** Sentinel returned by {@link #decode} for an encoded value that cannot be valid. */
    static final long INVALID = -1;

    private RequiredInsertCount() {
    }

    /**
     * Encodes a Required Insert Count for the wire, given the encoder's
     * dynamic table capacity in octets.
     *
     * @param requiredInsertCount the real Required Insert Count
     * @param maxTableCapacity the dynamic table capacity in octets
     * @return the wire-form encoded value
     */
    static long encode(long requiredInsertCount, int maxTableCapacity) {
        if (requiredInsertCount == 0) {
            return 0;
        }
        long maxEntries = maxTableCapacity / 32;
        return (requiredInsertCount % (2 * maxEntries)) + 1;
    }

    /**
     * Decodes a wire-form Required Insert Count.
     *
     * @param encoded the wire-form value read from the field section prefix
     * @param totalInserts the decoder's current Insert Count (how many
     *                     entries it has processed off the encoder
     *                     stream so far)
     * @param maxTableCapacity the decoder's dynamic table capacity in octets
     * @return the real Required Insert Count, or {@link #INVALID} if
     *         {@code encoded} is out of range for a table of this
     *         capacity, or decodes to a value that is never valid
     *         (RFC 9204's own corruption check)
     */
    static long decode(long encoded, long totalInserts, int maxTableCapacity) {
        if (encoded == 0) {
            return 0;
        }
        long maxEntries = maxTableCapacity / 32;
        if (maxEntries == 0) {
            return INVALID;
        }
        long fullRange = 2 * maxEntries;
        if (encoded > fullRange) {
            return INVALID;
        }
        long maxValue = totalInserts + maxEntries;
        long maxWrapped = (maxValue / fullRange) * fullRange;
        long requiredInsertCount = maxWrapped + encoded - 1;
        if (requiredInsertCount > maxValue) {
            if (requiredInsertCount <= fullRange) {
                return INVALID;
            }
            requiredInsertCount -= fullRange;
        }
        if (requiredInsertCount == 0) {
            return INVALID;
        }
        return requiredInsertCount;
    }
}
