/*
 * BaseSubject.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mime.rfc2047.Rfc2047Decoder;

import java.util.Locale;

/**
 * RFC 5256 base subject extraction (section 2.1).
 */
public final class BaseSubject {

    private BaseSubject() {
    }

    /**
     * Returns true if RFC 5256 treats the message as a reply or forward
     * based on the original Subject value.
     */
    public static boolean isReplyOrForward(String rawSubject) {
        if (rawSubject == null || rawSubject.isEmpty()) {
            return false;
        }
        String subject = Rfc2047Decoder.decodeHeaderValue(rawSubject);
        subject = ImapUnicodeCasemap.normalizeSpaces(subject);
        String before = subject;
        subject = removeTrailers(subject);
        if (!subject.equals(before)) {
            return true;
        }
        if (stripOneLeaderOrBlob(subject) != null) {
            return true;
        }
        String lower = subject.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("[fwd:") && subject.trim().endsWith("]");
    }

    /**
     * Extracts the base subject from a raw Subject header value.
     */
    public static String extract(String rawSubject) {
        if (rawSubject == null || rawSubject.isEmpty()) {
            return "";
        }
        String subject = Rfc2047Decoder.decodeHeaderValue(rawSubject);
        subject = ImapUnicodeCasemap.normalizeSpaces(subject);

        for (;;) {
            subject = removeTrailers(subject);
            subject = removeLeadersAndBlobs(subject);
            String unwrapped = unwrapForwardWrapper(subject);
            if (unwrapped == null) {
                return subject.trim();
            }
            subject = ImapUnicodeCasemap.normalizeSpaces(unwrapped);
        }
    }

    private static String removeTrailers(String subject) {
        String s = subject.trim();
        for (;;) {
            if (s.regionMatches(true, s.length() - 5, "(fwd)", 0, 5)) {
                s = s.substring(0, s.length() - 5).trim();
                continue;
            }
            if (s.endsWith(" ")) {
                s = s.trim();
                continue;
            }
            return s;
        }
    }

    private static String removeLeadersAndBlobs(String subject) {
        String s = subject.trim();
        for (;;) {
            String next = stripOneLeaderOrBlob(s);
            if (next == null) {
                return s;
            }
            s = next.trim();
        }
    }

    private static String stripOneLeaderOrBlob(String s) {
        if (s.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        if (i >= s.length()) {
            return null;
        }
        if (s.charAt(i) == '[') {
            int close = s.indexOf(']', i);
            if (close > i) {
                String after = s.substring(close + 1).trim();
                if (!after.isEmpty()) {
                    return after;
                }
            }
            return null;
        }
        String tail = s.substring(i);
        String lower = tail.toLowerCase(Locale.ROOT);
        if (lower.startsWith("re:")) {
            return tail.substring(3);
        }
        if (lower.startsWith("fw:")) {
            return tail.substring(3);
        }
        if (lower.startsWith("fwd:")) {
            return tail.substring(4);
        }
        return null;
    }

    private static String unwrapForwardWrapper(String subject) {
        String s = subject.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[fwd:") && s.endsWith("]")) {
            String inner = s.substring(5, s.length() - 1).trim();
            if (!inner.isEmpty()) {
                return inner;
            }
        }
        return null;
    }
}
