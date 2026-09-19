/*
 * SortSentDate.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * RFC 5256 section 2.2 sent date for SORT DATE key.
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
