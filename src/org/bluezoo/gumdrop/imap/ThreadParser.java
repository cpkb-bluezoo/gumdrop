/*
 * ThreadParser.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import java.text.ParseException;

/**
 * Parser for RFC 5256 THREAD and UID THREAD commands.
 */
public class ThreadParser {

    private final String input;
    private int pos;
    private final int length;

    public ThreadParser(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.length = this.input.length();
    }

    public ThreadRequest parse() throws ParseException {
        skipWhitespace();
        String algToken = parseAtom();
        ThreadAlgorithm algorithm = ThreadAlgorithm.fromToken(algToken);
        if (algorithm == null) {
            throw new ParseException("Unknown threading algorithm: "
                    + algToken, pos);
        }
        skipWhitespace();
        String charset = parseCharset();
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected search criteria after charset",
                    pos);
        }
        String searchArgs = input.substring(pos).trim();
        SearchCriteria criteria;
        try {
            criteria = new SearchParser(searchArgs).parse();
        } catch (ParseException e) {
            throw new ParseException("Invalid search criteria: "
                    + e.getMessage(), pos + e.getErrorOffset());
        }
        return new ThreadRequest(algorithm, charset, criteria);
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
        int start = pos;
        while (pos < length && input.charAt(pos) != ' '
                && input.charAt(pos) != '(' && input.charAt(pos) != ')') {
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
