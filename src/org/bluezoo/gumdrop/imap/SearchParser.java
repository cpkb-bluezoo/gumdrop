/*
 * SearchParser.java
 * Copyright (C) 2025 Chris Burdess
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

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for IMAP SEARCH command syntax (RFC 9051 Section 6.4.4).
 * 
 * <p>Converts IMAP search expressions into {@link SearchCriteria} objects.
 * 
 * <h2>Supported Search Keys</h2>
 * <ul>
 *   <li>ALL, ANSWERED, DELETED, DRAFT, FLAGGED, NEW, OLD, RECENT, SEEN</li>
 *   <li>UNANSWERED, UNDELETED, UNDRAFT, UNFLAGGED, UNSEEN</li>
 *   <li>BCC, CC, FROM, SUBJECT, TO (string arguments)</li>
 *   <li>HEADER field-name string</li>
 *   <li>BODY, TEXT (string arguments)</li>
 *   <li>BEFORE, ON, SINCE, SENTBEFORE, SENTON, SENTSINCE (date arguments)</li>
 *   <li>LARGER, SMALLER (number arguments)</li>
 *   <li>KEYWORD, UNKEYWORD (flag arguments)</li>
 *   <li>UID (sequence set)</li>
 *   <li>OR, NOT (boolean operators)</li>
 *   <li>Sequence sets (e.g., 1:100, 1,2,3)</li>
 *   <li>Parenthesized groups</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchParser {

    /** IMAP date format: dd-Mon-yyyy (e.g., "01-Jan-2025") */
    private static final DateTimeFormatter IMAP_DATE_FORMAT = 
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    private final String input;
    private int pos;
    private final int length;

    /**
     * Creates a new search parser for the given input.
     * 
     * @param input the IMAP SEARCH arguments
     */
    public SearchParser(String input) {
        this.input = input;
        this.pos = 0;
        this.length = input.length();
    }

    /**
     * Parses the input and returns the search criteria.
     * 
     * @return the parsed search criteria
     * @throws ParseException if the input is malformed
     */
    public SearchCriteria parse() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            // Empty search means ALL
            return SearchCriteria.all();
        }
        
        SearchCriteria result = parseSearchKeys();
        
        skipWhitespace();
        if (pos < length) {
            throw new ParseException("Unexpected input after search expression", pos);
        }
        
        return result;
    }

    /**
     * Parses one or more search keys (implicit AND).
     */
    private SearchCriteria parseSearchKeys() throws ParseException {
        List<SearchCriteria> criteria = new ArrayList<>();
        
        while (pos < length) {
            skipWhitespace();
            if (pos >= length) {
                break;
            }
            
            char c = input.charAt(pos);
            if (c == ')') {
                // End of parenthesized group
                break;
            }
            
            SearchCriteria key = parseSearchKey();
            if (key != null) {
                criteria.add(key);
            }
        }
        
        if (criteria.isEmpty()) {
            return SearchCriteria.all();
        } else if (criteria.size() == 1) {
            return criteria.get(0);
        } else {
            return SearchCriteria.and(criteria.toArray(new SearchCriteria[0]));
        }
    }

    /**
     * Parses a single search key.
     */
    private SearchCriteria parseSearchKey() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            return null;
        }
        
        char c = input.charAt(pos);
        
        // Parenthesized group
        if (c == '(') {
            pos++; // consume '('
            SearchCriteria inner = parseSearchKeys();
            skipWhitespace();
            if (pos >= length || input.charAt(pos) != ')') {
                throw new ParseException("Missing closing parenthesis", pos);
            }
            pos++; // consume ')'
            return inner;
        }
        
        // Sequence set (starts with digit or '*')
        if (Character.isDigit(c) || c == '*') {
            return parseSequenceSet();
        }
        
        // Keyword
        String keyword = parseAtom();
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        
        return parseKeyword(keyword.toUpperCase(Locale.ROOT));
    }

    /**
     * Parses a search keyword and its arguments.
     */
    private SearchCriteria parseKeyword(String keyword) throws ParseException {
        switch (keyword) {
            // Simple flags
            case "ALL":
                return SearchCriteria.all();
            case "ANSWERED":
                return SearchCriteria.answered();
            case "DELETED":
                return SearchCriteria.deleted();
            case "DRAFT":
                return SearchCriteria.draft();
            case "FLAGGED":
                return SearchCriteria.flagged();
            case "NEW":
                return SearchCriteria.newMessages();
            case "OLD":
                return SearchCriteria.old();
            case "RECENT":
                return SearchCriteria.recent();
            case "SEEN":
                return SearchCriteria.seen();
            case "UNANSWERED":
                return SearchCriteria.unanswered();
            case "UNDELETED":
                return SearchCriteria.undeleted();
            case "UNDRAFT":
                return SearchCriteria.undraft();
            case "UNFLAGGED":
                return SearchCriteria.unflagged();
            case "UNSEEN":
                return SearchCriteria.unseen();
            
            // Header searches
            case "BCC":
                return SearchCriteria.bcc(parseString());
            case "CC":
                return SearchCriteria.cc(parseString());
            case "FROM":
                return SearchCriteria.from(parseString());
            case "SUBJECT":
                return SearchCriteria.subject(parseString());
            case "TO":
                return SearchCriteria.to(parseString());
            case "HEADER":
                String headerName = parseAtom();
                String headerValue = parseString();
                return SearchCriteria.header(headerName, headerValue);
            
            // Text searches
            case "BODY":
                return SearchCriteria.body(parseString());
            case "TEXT":
                return SearchCriteria.text(parseString());
            
            // Date searches (internal date)
            case "BEFORE":
                return SearchCriteria.before(parseDate());
            case "ON":
                return SearchCriteria.on(parseDate());
            case "SINCE":
                return SearchCriteria.since(parseDate());
            
            // Date searches (sent date)
            case "SENTBEFORE":
                return SearchCriteria.sentBefore(parseDate());
            case "SENTON":
                return SearchCriteria.sentOn(parseDate());
            case "SENTSINCE":
                return SearchCriteria.sentSince(parseDate());
            
            // Size searches
            case "LARGER":
                return SearchCriteria.larger(parseNumber());
            case "SMALLER":
                return SearchCriteria.smaller(parseNumber());
            
            // Keyword flags
            case "KEYWORD":
                return SearchCriteria.keyword(parseAtom());
            case "UNKEYWORD":
                return SearchCriteria.unkeyword(parseAtom());
            
            // UID
            case "UID":
                return parseUidSet();
            
            // Boolean operators
            case "NOT":
                return SearchCriteria.not(parseSearchKey());
            case "OR":
                SearchCriteria a = parseSearchKey();
                SearchCriteria b = parseSearchKey();
                return SearchCriteria.or(a, b);
            
            default:
                throw new ParseException("Unknown search key: " + keyword, pos);
        }
    }

    /**
     * Parses a sequence set (e.g., "1:100", "1,2,3", "*").
     */
    private SearchCriteria parseSequenceSet() throws ParseException {
        List<SearchCriteria> parts = new ArrayList<>();
        
        while (pos < length) {
            skipWhitespace();
            if (pos >= length) {
                break;
            }
            
            char c = input.charAt(pos);
            if (!Character.isDigit(c) && c != '*') {
                break;
            }
            
            // Parse start of range
            int start = parseSequenceNumber();
            
            if (pos < length && input.charAt(pos) == ':') {
                pos++; // consume ':'
                int end = parseSequenceNumber();
                parts.add(SearchCriteria.sequenceRange(start, end));
            } else {
                parts.add(SearchCriteria.sequenceNumber(start));
            }
            
            // Check for comma
            skipWhitespace();
            if (pos < length && input.charAt(pos) == ',') {
                pos++; // consume ','
            } else {
                break;
            }
        }
        
        if (parts.isEmpty()) {
            throw new ParseException("Empty sequence set", pos);
        } else if (parts.size() == 1) {
            return parts.get(0);
        } else {
            return SearchCriteria.or(
                parts.get(0),
                parts.size() == 2 ? parts.get(1) : 
                    SearchCriteria.and(parts.subList(1, parts.size()).toArray(new SearchCriteria[0]))
            );
        }
    }

    /**
     * Parses a UID set (e.g., "UID 1:100").
     */
    private SearchCriteria parseUidSet() throws ParseException {
        skipWhitespace();
        List<SearchCriteria> parts = new ArrayList<>();
        
        while (pos < length) {
            skipWhitespace();
            if (pos >= length) {
                break;
            }
            
            char c = input.charAt(pos);
            if (!Character.isDigit(c) && c != '*') {
                break;
            }
            
            // Parse start of range
            long start = parseUidNumber();
            
            if (pos < length && input.charAt(pos) == ':') {
                pos++; // consume ':'
                long end = parseUidNumber();
                parts.add(SearchCriteria.uidRange(start, end));
            } else {
                parts.add(SearchCriteria.uid(start));
            }
            
            // Check for comma
            skipWhitespace();
            if (pos < length && input.charAt(pos) == ',') {
                pos++; // consume ','
            } else {
                break;
            }
        }
        
        if (parts.isEmpty()) {
            throw new ParseException("Empty UID set", pos);
        } else if (parts.size() == 1) {
            return parts.get(0);
        } else {
            // Combine with OR
            SearchCriteria result = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                result = SearchCriteria.or(result, parts.get(i));
            }
            return result;
        }
    }

    /**
     * Parses a sequence number or '*' for max.
     */
    private int parseSequenceNumber() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected sequence number", pos);
        }
        
        if (input.charAt(pos) == '*') {
            pos++;
            return Integer.MAX_VALUE; // '*' means maximum
        }
        
        return (int) parseNumber();
    }

    /**
     * Parses a UID number or '*' for max.
     */
    private long parseUidNumber() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected UID", pos);
        }
        
        if (input.charAt(pos) == '*') {
            pos++;
            return Long.MAX_VALUE; // '*' means maximum
        }
        
        return parseNumber();
    }

    /**
     * Parses a number.
     */
    private long parseNumber() throws ParseException {
        skipWhitespace();
        int start = pos;
        
        while (pos < length && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        
        if (start == pos) {
            throw new ParseException("Expected number", pos);
        }
        
        try {
            return Long.parseLong(input.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid number: " + input.substring(start, pos), start);
        }
    }

    /**
     * Parses a date in IMAP format (dd-Mon-yyyy).
     */
    private LocalDate parseDate() throws ParseException {
        skipWhitespace();
        
        // Date may be quoted or unquoted
        String dateStr;
        if (pos < length && input.charAt(pos) == '"') {
            dateStr = parseQuotedString();
        } else {
            dateStr = parseAtom();
        }
        
        if (dateStr == null || dateStr.isEmpty()) {
            throw new ParseException("Expected date", pos);
        }
        
        try {
            return LocalDate.parse(dateStr, IMAP_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new ParseException("Invalid date format: " + dateStr + 
                " (expected dd-Mon-yyyy)", pos);
        }
    }

    /**
     * Parses a string (quoted or atom).
     */
    private String parseString() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected string", pos);
        }
        
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        } else if (input.charAt(pos) == '{') {
            return parseLiteral();
        } else {
            return parseAtom();
        }
    }

    /**
     * Parses a quoted string.
     */
    private String parseQuotedString() throws ParseException {
        if (pos >= length || input.charAt(pos) != '"') {
            throw new ParseException("Expected quoted string", pos);
        }
        
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++; // consume closing quote
                return sb.toString();
            } else if (c == '\\' && pos + 1 < length) {
                pos++; // consume backslash
                sb.append(input.charAt(pos));
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        
        throw new ParseException("Unterminated quoted string", pos);
    }

    /**
     * Parses a literal string {n}.
     * Note: In a real implementation, this would need to handle
     * the literal continuation from the client.
     */
    private String parseLiteral() throws ParseException {
        if (pos >= length || input.charAt(pos) != '{') {
            throw new ParseException("Expected literal", pos);
        }
        
        pos++; // consume '{'
        int start = pos;
        
        while (pos < length && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        
        if (pos >= length || input.charAt(pos) != '}') {
            throw new ParseException("Invalid literal syntax", pos);
        }
        
        int literalLength;
        try {
            literalLength = Integer.parseInt(input.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid literal length", start);
        }
        
        pos++; // consume '}'
        
        // Skip CRLF after literal marker
        if (pos < length && input.charAt(pos) == '\r') pos++;
        if (pos < length && input.charAt(pos) == '\n') pos++;
        
        if (pos + literalLength > length) {
            throw new ParseException("Literal extends beyond input", pos);
        }
        
        String literal = input.substring(pos, pos + literalLength);
        pos += literalLength;
        return literal;
    }

    /**
     * Parses an atom (unquoted string).
     */
    private String parseAtom() {
        skipWhitespace();
        int start = pos;
        
        while (pos < length) {
            char c = input.charAt(pos);
            // Atom special characters that terminate the atom
            if (c == '(' || c == ')' || c == '{' || c == ' ' || 
                c == '"' || c == '\\' || c == ']' || c == '%' || c == '*' ||
                c < 0x20 || c > 0x7e) {
                break;
            }
            pos++;
        }
        
        if (start == pos) {
            return null;
        }
        
        return input.substring(start, pos);
    }

    /**
     * Skips whitespace.
     */
    private void skipWhitespace() {
        while (pos < length && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

}

