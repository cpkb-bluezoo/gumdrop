/*
 * IfHeaderParser.java
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

package org.bluezoo.gumdrop.webdav;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4918 §10.4 — full If header parser and evaluator.
 *
 * <p>Parses the If header grammar into a structured representation and
 * evaluates it against the current resource state (lock tokens and ETags).
 *
 * <h4>Grammar (RFC 4918 §10.4.2)</h4>
 * <pre>
 * If           = "If" ":" ( 1*No-tag-list | 1*Tagged-list )
 * No-tag-list  = List
 * Tagged-list  = Resource-Tag 1*List
 * Resource-Tag = "&lt;" Simple-ref "&gt;"
 * List         = "(" 1*Condition ")"
 * Condition    = ["Not"] (State-token | "[" entity-tag "]")
 * State-token  = Coded-URL
 * Coded-URL    = "&lt;" absolute-URI "&gt;"
 * </pre>
 *
 * <p>Evaluation semantics:
 * <ul>
 *   <li>Multiple lists for the same resource are OR'd</li>
 *   <li>Conditions within a single list are AND'd</li>
 *   <li>"Not" negates the condition result</li>
 *   <li>A state-token matches if the resource has a lock with that token</li>
 *   <li>An entity-tag matches if the resource's current ETag matches</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918#section-10.4">RFC 4918 §10.4</a>
 */
class IfHeaderParser {

    /**
     * Represents a single condition within a parenthesized list.
     */
    static class Condition {
        final boolean negated;
        final String stateToken;   // lock token URI, or null
        final String entityTag;    // ETag value (with quotes), or null

        Condition(boolean negated, String stateToken, String entityTag) {
            this.negated = negated;
            this.stateToken = stateToken;
            this.entityTag = entityTag;
        }
    }

    /**
     * Represents a parenthesized list of conditions (AND'd together).
     */
    static class ConditionList {
        final List<Condition> conditions = new ArrayList<>();
    }

    /**
     * Represents a tagged or untagged group of condition lists (OR'd together).
     */
    static class IfGroup {
        final String resourceTag;   // URI for tagged-list, or null for no-tag-list
        final List<ConditionList> lists = new ArrayList<>();

        IfGroup(String resourceTag) {
            this.resourceTag = resourceTag;
        }
    }

    private final String header;
    private int pos;

    IfHeaderParser(String header) {
        this.header = header;
        this.pos = 0;
    }

    /**
     * Parses the If header value into a list of groups.
     *
     * @return the parsed groups, or an empty list if the header is malformed
     */
    List<IfGroup> parse() {
        List<IfGroup> groups = new ArrayList<>();
        skipWhitespace();

        while (pos < header.length()) {
            if (header.charAt(pos) == '<') {
                // Could be a Resource-Tag (tagged-list) or a state-token in a no-tag-list
                // Peek ahead to see if there's a '(' after the '>'
                int savedPos = pos;
                String uri = readAngleBracketedURI();
                if (uri == null) {
                    break;
                }
                skipWhitespace();
                if (pos < header.length() && header.charAt(pos) == '(') {
                    // Tagged-list: URI is the resource tag
                    IfGroup group = new IfGroup(uri);
                    while (pos < header.length() && header.charAt(pos) == '(') {
                        ConditionList list = readConditionList();
                        if (list != null) {
                            group.lists.add(list);
                        }
                        skipWhitespace();
                    }
                    groups.add(group);
                } else {
                    // Not a resource tag — rewind and treat as no-tag-list
                    pos = savedPos;
                    IfGroup group = new IfGroup(null);
                    while (pos < header.length() && header.charAt(pos) == '(') {
                        ConditionList list = readConditionList();
                        if (list != null) {
                            group.lists.add(list);
                        }
                        skipWhitespace();
                    }
                    if (!group.lists.isEmpty()) {
                        groups.add(group);
                    }
                }
            } else if (header.charAt(pos) == '(') {
                // No-tag-list
                IfGroup group = new IfGroup(null);
                while (pos < header.length() && header.charAt(pos) == '(') {
                    ConditionList list = readConditionList();
                    if (list != null) {
                        group.lists.add(list);
                    }
                    skipWhitespace();
                }
                if (!group.lists.isEmpty()) {
                    groups.add(group);
                }
            } else {
                // Skip unexpected characters
                pos++;
            }
            skipWhitespace();
        }

        return groups;
    }

    /**
     * Reads a parenthesized condition list: "(" 1*Condition ")".
     */
    private ConditionList readConditionList() {
        if (pos >= header.length() || header.charAt(pos) != '(') {
            return null;
        }
        pos++; // skip '('
        skipWhitespace();

        ConditionList list = new ConditionList();

        while (pos < header.length() && header.charAt(pos) != ')') {
            boolean negated = false;

            // Check for "Not"
            if (pos + 3 <= header.length() &&
                    "Not".equalsIgnoreCase(header.substring(pos, pos + 3))) {
                char afterNot = pos + 3 < header.length() ? header.charAt(pos + 3) : ' ';
                if (afterNot == ' ' || afterNot == '\t' || afterNot == '<' || afterNot == '[') {
                    negated = true;
                    pos += 3;
                    skipWhitespace();
                }
            }

            if (pos >= header.length()) {
                break;
            }

            char c = header.charAt(pos);
            if (c == '<') {
                // State-token (Coded-URL)
                String token = readAngleBracketedURI();
                if (token != null) {
                    list.conditions.add(new Condition(negated, token, null));
                }
            } else if (c == '[') {
                // Entity-tag
                String etag = readEntityTag();
                if (etag != null) {
                    list.conditions.add(new Condition(negated, null, etag));
                }
            } else {
                pos++;
            }
            skipWhitespace();
        }

        if (pos < header.length() && header.charAt(pos) == ')') {
            pos++; // skip ')'
        }

        return list.conditions.isEmpty() ? null : list;
    }

    /** Reads a "<...>" URI. */
    private String readAngleBracketedURI() {
        if (pos >= header.length() || header.charAt(pos) != '<') {
            return null;
        }
        int end = header.indexOf('>', pos + 1);
        if (end < 0) {
            return null;
        }
        String uri = header.substring(pos + 1, end);
        pos = end + 1;
        return uri;
    }

    /** Reads a "[...]" entity-tag. */
    private String readEntityTag() {
        if (pos >= header.length() || header.charAt(pos) != '[') {
            return null;
        }
        int end = header.indexOf(']', pos + 1);
        if (end < 0) {
            return null;
        }
        String etag = header.substring(pos + 1, end).trim();
        pos = end + 1;
        return etag;
    }

    private void skipWhitespace() {
        while (pos < header.length()) {
            char c = header.charAt(pos);
            if (c != ' ' && c != '\t') {
                break;
            }
            pos++;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evaluation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RFC 4918 §10.4 — evaluates the parsed If header against the given
     * resource state.
     *
     * @param groups the parsed If header groups
     * @param resourcePath the path of the resource being checked
     * @param resourceHref the href (URI) of the resource for tagged-list matching
     * @param lockManager the lock manager for token validation
     * @param currentETag the current ETag of the resource (with quotes), or null
     * @return true if the If header conditions are satisfied
     */
    static boolean evaluate(List<IfGroup> groups, Path resourcePath,
                            String resourceHref, WebDAVLockManager lockManager,
                            String currentETag) {
        if (groups.isEmpty()) {
            return true;
        }

        for (IfGroup group : groups) {
            // For tagged-lists, check if the resource tag matches this resource
            if (group.resourceTag != null
                    && !resourceTagMatches(group.resourceTag, resourceHref)) {
                continue; // This group is for a different resource
            }

            // Multiple lists within a group are OR'd
            for (ConditionList list : group.lists) {
                if (evaluateList(list, resourcePath, lockManager, currentETag)) {
                    return true;
                }
            }
        }

        // For no-tag-lists: if no group matched, return false
        // For tagged-lists: if no group targeted this resource, conditions are satisfied
        boolean anyTargetsThisResource = false;
        for (IfGroup group : groups) {
            if (group.resourceTag == null ||
                    resourceTagMatches(group.resourceTag, resourceHref)) {
                anyTargetsThisResource = true;
                break;
            }
        }
        return !anyTargetsThisResource;
    }

    /**
     * Evaluates a single condition list (all conditions AND'd).
     */
    private static boolean evaluateList(ConditionList list, Path resourcePath,
                                        WebDAVLockManager lockManager,
                                        String currentETag) {
        for (Condition cond : list.conditions) {
            boolean result;
            if (cond.stateToken != null) {
                result = lockManager != null &&
                         lockManager.validateToken(resourcePath, cond.stateToken);
            } else if (cond.entityTag != null) {
                result = etagMatches(cond.entityTag, currentETag);
            } else {
                result = false;
            }

            if (cond.negated) {
                result = !result;
            }

            if (!result) {
                return false; // AND semantics: one false → entire list false
            }
        }
        return true;
    }

    /** Checks if the resource tag URI matches the resource href. */
    private static boolean resourceTagMatches(String tag, String href) {
        if (tag == null || href == null) {
            return false;
        }
        // Resource tags may be absolute URIs; compare path portion
        if (tag.contains("://")) {
            int pathStart = tag.indexOf('/', tag.indexOf("://") + 3);
            if (pathStart >= 0) {
                return tag.substring(pathStart).equals(href);
            }
        }
        return tag.equals(href);
    }

    /** Compares ETags per RFC 7232 §2.3.2 (weak comparison). */
    private static boolean etagMatches(String conditionETag, String currentETag) {
        if (conditionETag == null || currentETag == null) {
            return false;
        }
        String cNorm = stripWeakPrefix(conditionETag);
        String rNorm = stripWeakPrefix(currentETag);
        return cNorm.equals(rNorm);
    }

    private static String stripWeakPrefix(String etag) {
        if (etag.startsWith("W/")) {
            return etag.substring(2);
        }
        return etag;
    }

}
