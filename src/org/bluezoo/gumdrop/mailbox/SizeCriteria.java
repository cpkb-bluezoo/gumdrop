/*
 * SizeCriteria.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;

/**
 * A {@link SearchCriteria} matching messages by size, as produced by
 * {@link SearchCriteria#larger} and {@link SearchCriteria#smaller}.
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304) so {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex} can
 * recognise it and answer it from its size sub-index instead of scanning
 * every message. Constructed only by {@link SearchCriteria}'s own
 * factory methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SizeCriteria implements SearchCriteria {

    /** Which direction of comparison this criteria performs. */
    public enum Comparison { LARGER, SMALLER }

    private final Comparison comparison;
    private final long threshold;

    SizeCriteria(Comparison comparison, long threshold) {
        this.comparison = comparison;
        this.threshold = threshold;
    }

    /**
     * Returns whether this matches sizes larger or smaller than the
     * threshold.
     *
     * @return the comparison direction
     */
    public Comparison getComparison() {
        return comparison;
    }

    /**
     * Returns the size threshold (exclusive, per RFC 9051 LARGER/SMALLER
     * semantics).
     *
     * @return the threshold, in octets
     */
    public long getThreshold() {
        return threshold;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        long size = context.getSize();
        return comparison == Comparison.LARGER ? size > threshold : size < threshold;
    }

    @Override
    public String toString() {
        return "SizeCriteria[" + comparison + " " + threshold + "]";
    }
}
