/*
 * SortParser.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
/**
 * Parser for RFC 5256 SORT and UID SORT commands.
 */
public class SortParser {

    private final String input;
    private int pos;
    private final int length;

    public SortParser(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.length = this.input.length();
    }

    public SortRequest parse() throws ParseException {
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            throw new ParseException("Expected sort program in parentheses", pos);
        }
        List<SortCriterion> program = parseSortProgram();
        skipWhitespace();
        String charset = parseCharset();
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected search criteria after charset", pos);
        }
        String searchArgs = input.substring(pos).trim();
        SearchCriteria criteria;
        try {
            criteria = new SearchParser(searchArgs).parse();
        } catch (ParseException e) {
            throw new ParseException("Invalid search criteria: " + e.getMessage(),
                    pos + e.getErrorOffset());
        }
        return new SortRequest(program, charset, criteria);
    }

    private List<SortCriterion> parseSortProgram() throws ParseException {
        pos++; // '('
        List<SortCriterion> list = new ArrayList<>();
        skipWhitespace();
        while (pos < length && input.charAt(pos) != ')') {
            boolean reverse = false;
            String atom = parseAtom();
            if (atom == null || atom.isEmpty()) {
                throw new ParseException("Expected sort criterion", pos);
            }
            if ("REVERSE".equalsIgnoreCase(atom)) {
                reverse = true;
                skipWhitespace();
                atom = parseAtom();
                if (atom == null || atom.isEmpty()) {
                    throw new ParseException("Expected sort key after REVERSE", pos);
                }
            }
            SortKey key = SortKey.fromToken(atom);
            if (key == null) {
                throw new ParseException("Unknown sort key: " + atom, pos);
            }
            list.add(new SortCriterion(key, reverse));
            skipWhitespace();
        }
        if (pos >= length || input.charAt(pos) != ')') {
            throw new ParseException("Missing closing parenthesis in sort program", pos);
        }
        pos++; // ')'
        if (list.isEmpty()) {
            throw new ParseException("Sort program must not be empty", pos);
        }
        return list;
    }

    private String parseCharset() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected charset", pos);
        }
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        return parseAtom();
    }

    private String parseAtom() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            return null;
        }
        char c = input.charAt(pos);
        if (c == '(' || c == ')') {
            return null;
        }
        if (c == '"') {
            return parseQuotedString();
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
            throw new ParseException("Expected atom", pos);
        }
        return input.substring(start, pos);
    }

    private String parseQuotedString() throws ParseException {
        if (pos >= length || input.charAt(pos) != '"') {
            throw new ParseException("Expected quoted string", pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\' && pos + 1 < length) {
                pos++;
                sb.append(input.charAt(pos));
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new ParseException("Unterminated quoted string", pos);
    }

    private void skipWhitespace() {
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }
}
