/*
 * OrCriteria.java
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
 * A {@link SearchCriteria} matching messages that satisfy either of two
 * sub-criteria, as produced by {@link SearchCriteria#or}.
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304), for consistency with {@link AndCriteria}/{@link NotCriteria} --
 * though {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex}'s
 * sub-index candidate narrowing does not attempt to answer an OR from
 * indexes (that would require a set <em>union</em> across sub-indexes
 * covering every disjunct, including ones that may not be indexable at
 * all), so an OR criteria always falls back to a full scan today.
 * Constructed only by {@link SearchCriteria#or}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class OrCriteria implements SearchCriteria {

    private final SearchCriteria left;
    private final SearchCriteria right;

    OrCriteria(SearchCriteria left, SearchCriteria right) {
        this.left = left;
        this.right = right;
    }

    /**
     * Returns the first sub-criteria.
     *
     * @return the first sub-criteria
     */
    public SearchCriteria getLeft() {
        return left;
    }

    /**
     * Returns the second sub-criteria.
     *
     * @return the second sub-criteria
     */
    public SearchCriteria getRight() {
        return right;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        return left.matches(context) || right.matches(context);
    }

    @Override
    public String toString() {
        return "OrCriteria[" + left + ", " + right + "]";
    }
}
