/*
 * IndexedMessageContext.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MessageContext;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A {@link MessageContext} implementation backed by a {@link MessageIndexEntry}.
 * 
 * <p>This allows {@link org.bluezoo.gumdrop.mailbox.SearchCriteria} to evaluate
 * against indexed data without parsing the actual message.
 * 
 * <p>For TEXT and BODY searches, this context returns empty content since
 * body text is not indexed. Searches requiring body content must fall back
 * to {@link org.bluezoo.gumdrop.mailbox.ParsedMessageContext}.
 * 
 * <p>All string comparisons in search criteria are case-insensitive, and
 * the index stores all values in lowercase, so matching is straightforward.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IndexedMessageContext implements MessageContext {

    private final MessageIndexEntry entry;

    /**
     * Creates a new indexed message context.
     *
     * @param entry the index entry providing the data
     * @throws NullPointerException if entry is null
     */
    public IndexedMessageContext(MessageIndexEntry entry) {
        if (entry == null) {
            throw new NullPointerException("entry must not be null");
        }
        this.entry = entry;
    }

    @Override
    public int getMessageNumber() {
        return entry.getMessageNumber();
    }

    @Override
    public long getUID() {
        return entry.getUid();
    }

    @Override
    public long getSize() {
        return entry.getSize();
    }

    @Override
    public Set<Flag> getFlags() {
        return entry.getFlags();
    }

    @Override
    public Set<String> getKeywords() {
        String keywords = entry.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> kwList = MessageIndexBuilder.extractKeywords(keywords);
        return new java.util.HashSet<>(kwList);
    }

    @Override
    public OffsetDateTime getInternalDate() {
        long millis = entry.getInternalDate();
        if (millis == 0) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @Override
    public String getHeader(String name) throws IOException {
        return getHeaderValue(name);
    }

    @Override
    public List<String> getHeaders(String name) throws IOException {
        String value = getHeaderValue(name);
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        result.add(value);
        return result;
    }

    /**
     * Gets the indexed value for a header.
     * Only commonly searched headers are indexed.
     */
    private String getHeaderValue(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        switch (lowerName) {
            case "from":
            case "sender":
                return entry.getFrom();
            case "to":
                return entry.getTo();
            case "cc":
                return entry.getCc();
            case "bcc":
                return entry.getBcc();
            case "subject":
                return entry.getSubject();
            case "message-id":
                return entry.getMessageId();
            default:
                // Header not indexed - return empty
                // Searches on non-indexed headers will need to fall back to parsing
                return "";
        }
    }

    @Override
    public OffsetDateTime getSentDate() throws IOException {
        long millis = entry.getSentDate();
        if (millis == 0) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @Override
    public CharSequence getHeadersText() throws IOException {
        // Headers text is not indexed
        // Return concatenation of indexed headers as approximation
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, "From", entry.getFrom());
        appendHeader(sb, "To", entry.getTo());
        appendHeader(sb, "Cc", entry.getCc());
        appendHeader(sb, "Subject", entry.getSubject());
        appendHeader(sb, "Message-ID", entry.getMessageId());
        return sb;
    }

    private void appendHeader(StringBuilder sb, String name, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(name).append(": ").append(value).append("\r\n");
        }
    }

    @Override
    public CharSequence getBodyText() throws IOException {
        // Body text is NOT indexed - this is intentional to keep index size small
        // TEXT and BODY searches must fall back to ParsedMessageContext
        return "";
    }

    /**
     * Returns the underlying index entry.
     *
     * @return the index entry
     */
    public MessageIndexEntry getEntry() {
        return entry;
    }

    /**
     * Checks if this context can fully evaluate the given search type.
     * 
     * @param searchType type of search being performed
     * @return true if this context has the required data
     */
    public boolean canEvaluate(SearchType searchType) {
        switch (searchType) {
            case FLAG:
            case SIZE:
            case DATE:
            case UID:
            case SEQUENCE:
            case FROM:
            case TO:
            case CC:
            case BCC:
            case SUBJECT:
                return true;
            case HEADER:
                // Only indexed headers are supported
                return false;
            case BODY:
            case TEXT:
                // Body/text search requires message parsing
                return false;
            default:
                return false;
        }
    }

    /**
     * Types of IMAP searches.
     */
    public enum SearchType {
        FLAG,
        SIZE,
        DATE,
        UID,
        SEQUENCE,
        FROM,
        TO,
        CC,
        BCC,
        SUBJECT,
        HEADER,
        BODY,
        TEXT
    }

}

