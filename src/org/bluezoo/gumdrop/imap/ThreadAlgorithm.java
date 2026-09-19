/*
 * ThreadAlgorithm.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.util.Locale;

/**
 * RFC 5256 threading algorithms.
 */
public enum ThreadAlgorithm {
    ORDEREDSUBJECT,
    REFERENCES;

    public static ThreadAlgorithm fromToken(String token) {
        if (token == null) {
            return null;
        }
        try {
            return ThreadAlgorithm.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
