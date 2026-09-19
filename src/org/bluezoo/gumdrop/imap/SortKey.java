/*
 * SortKey.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.imap;

/**
 * Sort keys for RFC 5256 SORT.
 */
public enum SortKey {
    ARRIVAL,
    CC,
    DATE,
    FROM,
    SIZE,
    SUBJECT,
    TO;

    static SortKey fromToken(String token) {
        if (token == null) {
            return null;
        }
        try {
            return SortKey.valueOf(token.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
