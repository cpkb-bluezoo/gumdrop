/*
 * HttpConditionalRequests.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.http;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Origin-server conditional request evaluation (RFC 9110 section 13,
 * RFC 7232 validators, RFC 9111 caching semantics).
 */
public final class HttpConditionalRequests {

    private HttpConditionalRequests() {
    }

    /**
     * Builds a strong entity tag from representation metadata. Suitable
     * for static resources when a digest of the bytes is not available.
     */
    public static String strongEntityTag(long lastModifiedMillis,
                                         long contentLength) {
        return "\"" + Long.toHexString(lastModifiedMillis) + '-'
                + Long.toHexString(contentLength) + '"';
    }

    /**
     * Parses {@code If-Modified-Since}. Returns {@code -1} when absent
     * or not a valid HTTP-date (RFC 9110 section 5.6.7).
     */
    public static long parseIfModifiedSince(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return -1L;
        }
        try {
            java.util.Date parsed =
                    new HttpDateFormat().parse(headerValue.trim());
            if (parsed == null) {
                return -1L;
            }
            return parsed.getTime();
        } catch (ParseException e) {
            return -1L;
        }
    }

    /**
     * Returns {@code true} when {@code If-None-Match} is satisfied by
     * {@code entityTag} (RFC 7232 section 3.2, weak comparison).
     */
    public static boolean ifNoneMatchSatisfied(String ifNoneMatchHeader,
                                               String entityTag) {
        if (ifNoneMatchHeader == null || ifNoneMatchHeader.isEmpty()) {
            return false;
        }
        String trimmed = ifNoneMatchHeader.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        for (String candidate : splitEntityTags(trimmed)) {
            if (entityTagsWeakMatch(candidate.trim(), entityTag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a GET/HEAD should receive {@code 304 Not Modified} for the
     * given validators and precondition headers.
     */
    public static boolean shouldReturnNotModified(String ifNoneMatch,
            String ifModifiedSinceHeader, long lastModifiedMillis,
            String entityTag) {
        if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
            return ifNoneMatchSatisfied(ifNoneMatch, entityTag);
        }
        long ims = parseIfModifiedSince(ifModifiedSinceHeader);
        if (ims >= 0 && lastModifiedMillis / 1000 <= ims / 1000) {
            return true;
        }
        return false;
    }

    static List<String> splitEntityTags(String header) {
        List<String> tags = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                if (current.length() > 0) {
                    tags.add(current.toString().trim());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tags.add(current.toString().trim());
        }
        return tags;
    }

    private static boolean entityTagsWeakMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalizeOpaqueTag(a).equals(normalizeOpaqueTag(b));
    }

    private static String normalizeOpaqueTag(String tag) {
        tag = tag.trim();
        if (tag.length() >= 2 && tag.charAt(0) == 'W' && tag.charAt(1) == '/') {
            tag = tag.substring(2).trim();
        }
        return tag;
    }
}
