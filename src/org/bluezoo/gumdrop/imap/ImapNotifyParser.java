/*
 * ImapNotifyParser.java
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
 * Parser for RFC 5465 NOTIFY command arguments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapNotifyParser {

    private final String input;
    private int pos;
    private final int length;

    public ImapNotifyParser(String input) {
        this.input = input != null ? input.trim() : "";
        this.pos = 0;
        this.length = this.input.length();
    }

    public ImapNotifyRequest parse() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected NOTIFY SET or NOTIFY NONE", pos);
        }
        String keyword = parseAtom();
        if (keyword == null) {
            throw new ParseException("Expected NOTIFY SET or NOTIFY NONE", pos);
        }
        if ("NONE".equalsIgnoreCase(keyword)) {
            skipWhitespace();
            if (pos < length) {
                throw new ParseException("Unexpected input after NOTIFY NONE", pos);
            }
            return ImapNotifyRequest.none();
        }
        if (!"SET".equalsIgnoreCase(keyword)) {
            throw new ParseException("Expected NOTIFY SET or NOTIFY NONE", pos);
        }
        boolean statusIndicator = false;
        skipWhitespace();
        if (pos < length) {
            String maybeStatus = peekAtom();
            if ("STATUS".equalsIgnoreCase(maybeStatus)) {
                parseAtom();
                statusIndicator = true;
            }
        }
        List<ImapNotifyEventGroup> groups = new ArrayList<ImapNotifyEventGroup>();
        skipWhitespace();
        while (pos < length) {
            groups.add(parseEventGroup());
            skipWhitespace();
        }
        if (groups.isEmpty()) {
            throw new ParseException("Expected at least one event group", pos);
        }
        return ImapNotifyRequest.set(statusIndicator, groups);
    }

    private ImapNotifyEventGroup parseEventGroup() throws ParseException {
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            throw new ParseException("Expected '(' starting event group", pos);
        }
        pos++;
        skipWhitespace();
        ImapNotifyMailboxFilter filter = parseMailboxFilter();
        skipWhitespace();
        EventsParseResult eventsResult = parseEventsList();
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != ')') {
            throw new ParseException("Expected ')' ending event group", pos);
        }
        pos++;
        return new ImapNotifyEventGroup(filter, eventsResult.events,
                eventsResult.eventsNone);
    }

    private ImapNotifyMailboxFilter parseMailboxFilter() throws ParseException {
        String atom = parseAtom();
        if (atom == null) {
            throw new ParseException("Expected mailbox filter", pos);
        }
        String upper = atom.toUpperCase(Locale.ENGLISH);
        if ("SELECTED".equals(upper)) {
            return ImapNotifyMailboxFilter.selected(false);
        }
        if ("SELECTED-DELAYED".equals(upper)) {
            return ImapNotifyMailboxFilter.selected(true);
        }
        if ("INBOXES".equals(upper)) {
            return ImapNotifyMailboxFilter.inboxes();
        }
        if ("PERSONAL".equals(upper)) {
            return ImapNotifyMailboxFilter.personal();
        }
        if ("SUBSCRIBED".equals(upper)) {
            return ImapNotifyMailboxFilter.subscribed();
        }
        if ("SUBTREE".equals(upper)) {
            return ImapNotifyMailboxFilter.subtree(parseMailboxNameList());
        }
        if ("MAILBOXES".equals(upper)) {
            return ImapNotifyMailboxFilter.mailboxes(parseMailboxNameList());
        }
        throw new ParseException("Unknown mailbox filter: " + atom, pos);
    }

    private List<String> parseMailboxNameList() throws ParseException {
        skipWhitespace();
        List<String> names = new ArrayList<String>();
        if (pos < length && input.charAt(pos) == '(') {
            pos++;
            skipWhitespace();
            while (pos < length && input.charAt(pos) != ')') {
                names.add(parseMailboxName());
                skipWhitespace();
            }
            if (pos >= length || input.charAt(pos) != ')') {
                throw new ParseException("Expected ')' ending mailbox list", pos);
            }
            pos++;
            return names;
        }
        names.add(parseMailboxName());
        return names;
    }

    private String parseMailboxName() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected mailbox name", pos);
        }
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        String atom = parseAtom();
        if (atom == null || atom.isEmpty()) {
            throw new ParseException("Expected mailbox name", pos);
        }
        return atom;
    }

    private static final class EventsParseResult {
        final List<ImapNotifyEventSpec> events;
        final boolean eventsNone;

        EventsParseResult(List<ImapNotifyEventSpec> events, boolean eventsNone) {
            this.events = events;
            this.eventsNone = eventsNone;
        }
    }

    private EventsParseResult parseEventsList() throws ParseException {
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            throw new ParseException("Expected '(' starting events list", pos);
        }
        pos++;
        skipWhitespace();
        List<ImapNotifyEventSpec> events = new ArrayList<ImapNotifyEventSpec>();
        if (pos < length && input.charAt(pos) == ')') {
            pos++;
            return new EventsParseResult(events, false);
        }
        String first = parseAtom();
        if (first == null) {
            throw new ParseException("Expected event name", pos);
        }
        if ("NONE".equalsIgnoreCase(first)) {
            skipWhitespace();
            if (pos >= length || input.charAt(pos) != ')') {
                throw new ParseException("Expected ')' after NONE", pos);
            }
            pos++;
            return new EventsParseResult(events, true);
        }
        events.add(parseEventSpec(first));
        skipWhitespace();
        while (pos < length && input.charAt(pos) != ')') {
            String next = parseAtom();
            if (next == null) {
                throw new ParseException("Expected event name", pos);
            }
            events.add(parseEventSpec(next));
            skipWhitespace();
        }
        if (pos >= length || input.charAt(pos) != ')') {
            throw new ParseException("Expected ')' ending events list", pos);
        }
        pos++;
        return new EventsParseResult(events, false);
    }

    private ImapNotifyEventSpec parseEventSpec(String name) throws ParseException {
        ImapNotifyEventType type = ImapNotifyEventType.fromImapName(name);
        if (type == null) {
            throw new ParseException("Unknown NOTIFY event: " + name, pos);
        }
        if (type != ImapNotifyEventType.MESSAGE_NEW) {
            return new ImapNotifyEventSpec(type);
        }
        skipWhitespace();
        if (pos >= length || input.charAt(pos) != '(') {
            return new ImapNotifyEventSpec(type);
        }
        pos++;
        List<String> fetchAtts = new ArrayList<String>();
        skipWhitespace();
        while (pos < length && input.charAt(pos) != ')') {
            fetchAtts.add(parseFetchAttToken());
            skipWhitespace();
        }
        if (pos >= length || input.charAt(pos) != ')') {
            throw new ParseException("Expected ')' ending MessageNew fetch list", pos);
        }
        pos++;
        return new ImapNotifyEventSpec(type, fetchAtts);
    }

    private String parseFetchAttToken() throws ParseException {
        skipWhitespace();
        if (pos >= length) {
            throw new ParseException("Expected fetch attribute", pos);
        }
        int start = pos;
        if (input.charAt(pos) == '"') {
            return parseQuotedString();
        }
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ' ' || c == ')' || c == '(') {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw new ParseException("Expected fetch attribute", pos);
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
