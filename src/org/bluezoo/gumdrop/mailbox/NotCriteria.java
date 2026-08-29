/*
 * NotCriteria.java
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
 * A {@link SearchCriteria} matching messages that do not satisfy a
 * sub-criteria, as produced by {@link SearchCriteria#not} -- and, in
 * turn, by every {@code un-} criteria ({@link SearchCriteria#unseen},
 * {@link SearchCriteria#unflagged}, etc.), which are defined as {@code
 * not(...)} of the corresponding positive one.
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304): {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex}
 * recognises a {@code NotCriteria} wrapping a {@link FlagCriteria}
 * specifically -- the shape every {@code UNSEEN}/{@code UNFLAGGED}/etc.
 * IMAP search term takes -- and answers it as the complement of that
 * flag's sub-index, without a full scan.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NotCriteria implements SearchCriteria {

    private final SearchCriteria criteria;

    NotCriteria(SearchCriteria criteria) {
        this.criteria = criteria;
    }

    /**
     * Returns the negated sub-criteria.
     *
     * @return the sub-criteria
     */
    public SearchCriteria getCriteria() {
        return criteria;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        return !criteria.matches(context);
    }

    @Override
    public String toString() {
        return "NotCriteria[" + criteria + "]";
    }
}
