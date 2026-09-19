/*
 * ImapCharset.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import java.util.Locale;

/**
 * Charset names for SORT/THREAD (RFC 5256) and related extensions.
 */
public final class ImapCharset {

    private ImapCharset() {
    }

    /**
     * Returns true if this server implements the charset for SORT/THREAD.
     * RFC 5256 requires US-ASCII and UTF-8.
     */
    public static boolean isSortThreadSupported(String charset) {
        if (charset == null) {
            return false;
        }
        String norm = normalize(charset);
        return "US-ASCII".equals(norm) || "UTF-8".equals(norm);
    }

    public static String normalize(String charset) {
        return charset.trim().toUpperCase(Locale.ROOT);
    }
}
