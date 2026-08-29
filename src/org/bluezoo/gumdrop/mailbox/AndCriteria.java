/*
 * AndCriteria.java
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
import java.util.List;

/**
 * A {@link SearchCriteria} matching messages that satisfy every one of a
 * list of sub-criteria, as produced by {@link SearchCriteria#and}.
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304) so {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex} can
 * recognise it and narrow the candidate set using whichever of its
 * sub-criteria a sub-index can answer, intersecting them, even when
 * others (e.g. a TEXT/BODY term) cannot. Constructed only by {@link
 * SearchCriteria#and}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AndCriteria implements SearchCriteria {

    private final List<SearchCriteria> criteria;

    AndCriteria(List<SearchCriteria> criteria) {
        this.criteria = criteria;
    }

    /**
     * Returns the sub-criteria that must all match, in order.
     *
     * @return the sub-criteria
     */
    public List<SearchCriteria> getCriteria() {
        return criteria;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        for (SearchCriteria c : criteria) {
            if (!c.matches(context)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "AndCriteria" + criteria;
    }
}
