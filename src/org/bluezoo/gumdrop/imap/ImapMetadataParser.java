/*
 * ImapMetadataParser.java
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

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for RFC 5464 GETMETADATA and SETMETADATA arguments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapMetadataParser {

    private final String input;
    private int pos;
    private final int length;

    public ImapMetadataParser(String input) {
        this.input = input != null ? input.trim() : "";
        this.pos = 0;
        this.length = this.input.length();
    }

    public ImapMetadataGetRequest parseGet() throws ParseException {
        skipWhitespace();
        String mailbox = parseMailboxName();
        skipWhitespace();
        int maxSize = -1;
        int depth = 0;
        if (pos < length && input.charAt(pos) == '(') {
            String group = parseParenthesized();
            if (looksLikeOptions(group)) {
                maxSize = parseMaxSize(group);
                depth = parseDepth(group);
                skipWhitespace();
            } else {
                List<String> entries = parseEntryListContent(group);
                return new ImapMetadataGetRequest(mailbox, maxSize, depth,
                        entries);
            }
        }
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected entry list", pos);
        }
        List<String> entries;
        if (input.charAt(pos) == '(') {
            entries = parseEntryListContent(parseParenthesized());
        } else {
            entries = new ArrayList<String>();
            entries.add(parseEntryAtom());
        }
        return new ImapMetadataGetRequest(mailbox, maxSize, depth, entries);
    }

    public ImapMetadataSetRequest parseSet() throws ParseException {
        skipWhitespace();
        String mailbox = parseMailboxName();
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            throw new ParseException("Expected entry values", pos);
        }
        String group = parseParenthesized();
        List<ImapMetadataSetRequest.EntryValue> entries =
                parseEntryValues(group);
        return new ImapMetadataSetRequest(mailbox, entries);
    }

    private List<ImapMetadataSetRequest.EntryValue> parseEntryValues(String group)
            throws ParseException {
        ImapMetadataParser inner = new ImapMetadataParser(group);
        List<ImapMetadataSetRequest.EntryValue> list =
                new ArrayList<ImapMetadataSetRequest.EntryValue>();
        inner.skipWhitespace();
        while (inner.pos < inner.length) {
            String entry = inner.parseEntryAtom();
            inner.skipWhitespace();
            String value = inner.parseValue();
            list.add(new ImapMetadataSetRequest.EntryValue(entry, value));
            inner.skipWhitespace();
        }
        return list;
    }

    private String parseValue() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected value", pos);
        }
        if (peekAtom() != null && "NIL".equalsIgnoreCase(peekAtom())) {
            parseAtom();
            return null;
        }
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        if (input.charAt(pos) == '{') {
            throw new ParseException("Literal values must be assembled before parse", pos);
        }
        return parseQuotedStringOrAtom();
    }

    private String parseQuotedStringOrAtom() throws ParseException {
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        return parseEntryAtom();
    }

    private boolean looksLikeOptions(String group) {
        String upper = group.toUpperCase(Locale.ENGLISH);
        return upper.contains("MAXSIZE") || upper.contains("DEPTH");
    }

    private int parseMaxSize(String group) throws ParseException {
        String upper = group.toUpperCase(Locale.ENGLISH);
        int idx = upper.indexOf("MAXSIZE");
        if (idx < 0) {
            return -1;
        }
        String rest = group.substring(idx + 7).trim();
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        if (num.length() == 0) {
            throw new ParseException("Invalid MAXSIZE", pos);
        }
        return Integer.parseInt(num.toString());
    }

    private int parseDepth(String group) throws ParseException {
        String upper = group.toUpperCase(Locale.ENGLISH);
        int idx = upper.indexOf("DEPTH");
        if (idx < 0) {
            return 0;
        }
        String rest = group.substring(idx + 5).trim();
        if (rest.toLowerCase(Locale.ENGLISH).startsWith("infinity")) {
            return Integer.MAX_VALUE;
        }
        if (rest.startsWith("1")) {
            return 1;
        }
        if (rest.startsWith("0")) {
            return 0;
        }
        throw new ParseException("Invalid DEPTH", pos);
    }

    private List<String> parseEntryListContent(String group)
            throws ParseException {
        ImapMetadataParser inner = new ImapMetadataParser(group);
        List<String> entries = new ArrayList<String>();
        inner.skipWhitespace();
        while (inner.pos < inner.length) {
            entries.add(inner.parseEntryAtom());
            inner.skipWhitespace();
        }
        return entries;
    }

    private String parseMailboxName() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected mailbox name", pos);
        }
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        if (input.charAt(pos) == '(') {
            return "";
        }
        String atom = parseAtom();
        if (atom == null) {
            throw new ParseException("Expected mailbox name", pos);
        }
        return atom;
    }

    private String parseEntryAtom() throws ParseException {
        skipWhitespace();
        if (pos < length && input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        String atom = parseAtom();
        if (atom == null || atom.isEmpty()) {
            throw new ParseException("Expected entry name", pos);
        }
        if (!atom.startsWith("/")) {
            atom = "/" + atom;
        }
        return atom;
    }

    private String parseParenthesized() throws ParseException {
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            throw new ParseException("Expected '('", pos);
        }
        pos++;
        int start = pos;
        int depth = 1;
        while (pos < length && depth > 0) {
            char c = input.charAt(pos++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        if (depth != 0) {
            throw new ParseException("Unbalanced parentheses", pos);
        }
        return input.substring(start, pos - 1).trim();
    }

    private String parseQuotedString() throws ParseException {
        if (pos >= length || input.charAt(pos) != '"') {
            throw new ParseException("Expected quoted string", pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < length) {
            char c = input.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && pos < length) {
                sb.append(input.charAt(pos++));
            } else {
                sb.append(c);
            }
        }
        throw new ParseException("Unterminated quoted string", pos);
    }

    private String parseAtom() {
        skipWhitespace();
        if (pos >= length) {
            return null;
        }
        char c = input.charAt(pos);
        if (c == '(' || c == ')') {
            return null;
        }
        int start = pos;
        while (pos < length) {
            c = input.charAt(pos);
            if (c == ' ' || c == '(' || c == ')') {
                break;
            }
            pos++;
        }
        if (start == pos) {
            return null;
        }
        return input.substring(start, pos);
    }

    private String peekAtom() {
        int saved = pos;
        String atom = parseAtom();
        pos = saved;
        return atom;
    }

    private void skipWhitespace() {
        while (pos < length && input.charAt(pos) == ' ') {
            pos++;
        }
    }
}
