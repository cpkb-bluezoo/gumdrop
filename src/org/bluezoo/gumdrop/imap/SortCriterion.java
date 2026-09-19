/*
 * SortCriterion.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

/**
 * One entry in an RFC 5256 sort program ({@code REVERSE} + sort key).
 */
public final class SortCriterion {

    private final SortKey key;
    private final boolean reverse;

    public SortCriterion(SortKey key, boolean reverse) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        this.key = key;
        this.reverse = reverse;
    }

    public SortKey getKey() {
        return key;
    }

    public boolean isReverse() {
        return reverse;
    }
}
