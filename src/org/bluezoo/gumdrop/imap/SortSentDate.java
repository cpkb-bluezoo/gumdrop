/*
 * SortSentDate.java
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

package org.bluezoo.gumdrop.imap;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * RFC 5256 section 2.2 sent date for SORT DATE key.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SortSentDate {

    private SortSentDate() {
    }

    /**
     * Returns UTC instant for sort comparison, using internal date if sent
     * date is unavailable.
     */
    public static Instant toSortInstant(OffsetDateTime sentDate,
            OffsetDateTime internalDate) {
        OffsetDateTime use = sentDate != null ? sentDate : internalDate;
        if (use == null) {
            return Instant.EPOCH;
        }
        try {
            return use.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
