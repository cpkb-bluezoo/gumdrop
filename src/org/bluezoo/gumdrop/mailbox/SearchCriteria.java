/*
 * SearchCriteria.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search criteria for IMAP SEARCH command (RFC 9051 Section 6.4.4).
 * 
 * <p>This interface represents a search predicate that can be evaluated
 * against a message. Criteria can be combined using boolean operators
 * (AND, OR, NOT) to form complex search expressions.
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Find unread messages from Alice
 * SearchCriteria criteria = SearchCriteria.and(
 *     SearchCriteria.unseen(),
 *     SearchCriteria.from("alice")
 * );
 * 
 * // Find messages larger than 1MB or flagged
 * SearchCriteria criteria = SearchCriteria.or(
 *     SearchCriteria.larger(1024 * 1024),
 *     SearchCriteria.flagged()
 * );
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageContext
 * @see Mailbox#search(SearchCriteria)
 */
public interface SearchCriteria {

    /**
     * Evaluates this criteria against the given message context.
     * 
     * @param context the message context providing access to message data
     * @return true if the message matches this criteria
     * @throws IOException if message data cannot be accessed
     */
    boolean matches(MessageContext context) throws IOException;

    // ========================================================================
    // Universal Criteria
    // ========================================================================

    /**
     * Matches all messages.
     * 
     * @return criteria matching all messages
     */
    static SearchCriteria all() {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) {
                return true;
            }
        };
    }

    // ========================================================================
    // Flag Criteria
    // ========================================================================

    /**
     * Matches messages with the \Answered flag.
     * 
     * @return criteria for answered messages
     */
    static SearchCriteria answered() {
        return hasFlag(Flag.ANSWERED);
    }

    /**
     * Matches messages without the \Answered flag.
     * 
     * @return criteria for unanswered messages
     */
    static SearchCriteria unanswered() {
        return not(answered());
    }

    /**
     * Matches messages with the \Deleted flag.
     * 
     * @return criteria for deleted messages
     */
    static SearchCriteria deleted() {
        return hasFlag(Flag.DELETED);
    }

    /**
     * Matches messages without the \Deleted flag.
     * 
     * @return criteria for non-deleted messages
     */
    static SearchCriteria undeleted() {
        return not(deleted());
    }

    /**
     * Matches messages with the \Draft flag.
     * 
     * @return criteria for draft messages
     */
    static SearchCriteria draft() {
        return hasFlag(Flag.DRAFT);
    }

    /**
     * Matches messages without the \Draft flag.
     * 
     * @return criteria for non-draft messages
     */
    static SearchCriteria undraft() {
        return not(draft());
    }

    /**
     * Matches messages with the \Flagged flag.
     * 
     * @return criteria for flagged messages
     */
    static SearchCriteria flagged() {
        return hasFlag(Flag.FLAGGED);
    }

    /**
     * Matches messages without the \Flagged flag.
     * 
     * @return criteria for unflagged messages
     */
    static SearchCriteria unflagged() {
        return not(flagged());
    }

    /**
     * Matches messages with the \Seen flag.
     * 
     * @return criteria for seen/read messages
     */
    static SearchCriteria seen() {
        return hasFlag(Flag.SEEN);
    }

    /**
     * Matches messages without the \Seen flag.
     * 
     * @return criteria for unseen/unread messages
     */
    static SearchCriteria unseen() {
        return not(seen());
    }

    /**
     * Matches messages with the \Recent flag.
     * 
     * @return criteria for recent messages
     */
    static SearchCriteria recent() {
        return hasFlag(Flag.RECENT);
    }

    /**
     * Matches messages that are recent and unseen (NEW).
     * 
     * @return criteria for new messages
     */
    static SearchCriteria newMessages() {
        return and(recent(), unseen());
    }

    /**
     * Matches messages that are not recent (OLD).
     * 
     * @return criteria for old messages
     */
    static SearchCriteria old() {
        return not(recent());
    }

    /**
     * Matches messages with the specified system flag.
     * 
     * @param flag the flag to check
     * @return criteria for messages with the flag
     */
    static SearchCriteria hasFlag(final Flag flag) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getFlags().contains(flag);
            }
        };
    }

    /**
     * Matches messages with the specified keyword flag.
     * Keywords are user-defined flags (non-system flags).
     * 
     * @param keyword the keyword
     * @return criteria for messages with the keyword
     */
    static SearchCriteria keyword(final String keyword) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getKeywords().contains(keyword);
            }
        };
    }

    /**
     * Matches messages without the specified keyword flag.
     * 
     * @param keyword the keyword
     * @return criteria for messages without the keyword
     */
    static SearchCriteria unkeyword(String keyword) {
        return not(keyword(keyword));
    }

    // ========================================================================
    // Size Criteria
    // ========================================================================

    /**
     * Matches messages larger than the specified size.
     * 
     * @param size the size in octets
     * @return criteria for large messages
     */
    static SearchCriteria larger(final long size) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getSize() > size;
            }
        };
    }

    /**
     * Matches messages smaller than the specified size.
     * 
     * @param size the size in octets
     * @return criteria for small messages
     */
    static SearchCriteria smaller(final long size) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getSize() < size;
            }
        };
    }

    // ========================================================================
    // Date Criteria (Internal Date)
    // ========================================================================

    /**
     * Matches messages with internal date before the specified date.
     * 
     * @param date the date
     * @return criteria for messages before the date
     */
    static SearchCriteria before(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getInternalLocalDate();
                return msgDate != null && msgDate.isBefore(date);
            }
        };
    }

    /**
     * Matches messages with internal date on the specified date.
     * 
     * @param date the date
     * @return criteria for messages on the date
     */
    static SearchCriteria on(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getInternalLocalDate();
                return msgDate != null && msgDate.equals(date);
            }
        };
    }

    /**
     * Matches messages with internal date on or after the specified date.
     * 
     * @param date the date
     * @return criteria for messages since the date
     */
    static SearchCriteria since(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getInternalLocalDate();
                return msgDate != null && !msgDate.isBefore(date);
            }
        };
    }

    // ========================================================================
    // Date Criteria (Sent Date from Date header)
    // ========================================================================

    /**
     * Matches messages with sent date before the specified date.
     * 
     * @param date the date
     * @return criteria for messages sent before the date
     */
    static SearchCriteria sentBefore(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getSentLocalDate();
                return msgDate != null && msgDate.isBefore(date);
            }
        };
    }

    /**
     * Matches messages with sent date on the specified date.
     * 
     * @param date the date
     * @return criteria for messages sent on the date
     */
    static SearchCriteria sentOn(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getSentLocalDate();
                return msgDate != null && msgDate.equals(date);
            }
        };
    }

    /**
     * Matches messages with sent date on or after the specified date.
     * 
     * @param date the date
     * @return criteria for messages sent since the date
     */
    static SearchCriteria sentSince(final LocalDate date) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                LocalDate msgDate = context.getSentLocalDate();
                return msgDate != null && !msgDate.isBefore(date);
            }
        };
    }

    // ========================================================================
    // Header Criteria
    // ========================================================================

    /**
     * Matches messages with From header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching From header
     */
    static SearchCriteria from(String pattern) {
        return header("From", pattern);
    }

    /**
     * Matches messages with To header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching To header
     */
    static SearchCriteria to(String pattern) {
        return header("To", pattern);
    }

    /**
     * Matches messages with Cc header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching Cc header
     */
    static SearchCriteria cc(String pattern) {
        return header("Cc", pattern);
    }

    /**
     * Matches messages with Bcc header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching Bcc header
     */
    static SearchCriteria bcc(String pattern) {
        return header("Bcc", pattern);
    }

    /**
     * Matches messages with Subject header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching Subject header
     */
    static SearchCriteria subject(String pattern) {
        return header("Subject", pattern);
    }

    /**
     * Matches messages with the specified header containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param headerName the header name (case-insensitive)
     * @param pattern the pattern to search for
     * @return criteria for matching the header
     */
    static SearchCriteria header(final String headerName, String pattern) {
        final String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                List<String> values = context.getHeaders(headerName);
                for (String value : values) {
                    if (value != null && value.toLowerCase(Locale.ROOT).contains(lowerPattern)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    // ========================================================================
    // Text Search Criteria
    // ========================================================================

    /**
     * Matches messages with body containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching body text
     */
    static SearchCriteria body(String pattern) {
        final String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                CharSequence body = context.getBodyText();
                if (body == null) {
                    return false;
                }
                return body.toString().toLowerCase(Locale.ROOT).contains(lowerPattern);
            }
        };
    }

    /**
     * Matches messages with headers or body containing the pattern.
     * The match is case-insensitive substring match.
     * 
     * @param pattern the pattern to search for
     * @return criteria for matching any text
     */
    static SearchCriteria text(String pattern) {
        final String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                // Check headers
                CharSequence headers = context.getHeadersText();
                if (headers != null && headers.toString().toLowerCase(Locale.ROOT).contains(lowerPattern)) {
                    return true;
                }
                // Check body
                CharSequence body = context.getBodyText();
                if (body != null && body.toString().toLowerCase(Locale.ROOT).contains(lowerPattern)) {
                    return true;
                }
                return false;
            }
        };
    }

    // ========================================================================
    // Sequence/UID Criteria
    // ========================================================================

    /**
     * Matches messages with the specified UID.
     * 
     * @param uid the UID to match
     * @return criteria for the UID
     */
    static SearchCriteria uid(final long uid) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getUID() == uid;
            }
        };
    }

    /**
     * Matches messages with UIDs in the specified set.
     * 
     * @param uids the UIDs to match
     * @return criteria for the UIDs
     */
    static SearchCriteria uidSet(final long... uids) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                long msgUid = context.getUID();
                for (long uid : uids) {
                    if (msgUid == uid) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * Matches messages with UIDs in the specified range (inclusive).
     * 
     * @param start the start UID
     * @param end the end UID
     * @return criteria for the UID range
     */
    static SearchCriteria uidRange(final long start, final long end) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                long uid = context.getUID();
                return uid >= start && uid <= end;
            }
        };
    }

    /**
     * Matches messages with the specified sequence number.
     * 
     * @param number the sequence number to match
     * @return criteria for the sequence number
     */
    static SearchCriteria sequenceNumber(final int number) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return context.getMessageNumber() == number;
            }
        };
    }

    /**
     * Matches messages with sequence numbers in the specified set.
     * 
     * @param numbers the sequence numbers to match
     * @return criteria for the sequence numbers
     */
    static SearchCriteria sequenceSet(final int... numbers) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                int msgNum = context.getMessageNumber();
                for (int num : numbers) {
                    if (msgNum == num) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * Matches messages with sequence numbers in the specified range (inclusive).
     * 
     * @param start the start sequence number
     * @param end the end sequence number
     * @return criteria for the sequence range
     */
    static SearchCriteria sequenceRange(final int start, final int end) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                int num = context.getMessageNumber();
                return num >= start && num <= end;
            }
        };
    }

    // ========================================================================
    // Boolean Operators
    // ========================================================================

    /**
     * Combines multiple criteria with AND (all must match).
     * 
     * @param criteria the criteria to combine
     * @return combined criteria
     */
    static SearchCriteria and(SearchCriteria... criteria) {
        if (criteria.length == 0) {
            return all();
        }
        if (criteria.length == 1) {
            return criteria[0];
        }
        final List<SearchCriteria> list = Arrays.asList(criteria);
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                for (SearchCriteria c : list) {
                    if (!c.matches(context)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    /**
     * Combines two criteria with OR (either must match).
     * 
     * @param a the first criteria
     * @param b the second criteria
     * @return combined criteria
     */
    static SearchCriteria or(final SearchCriteria a, final SearchCriteria b) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return a.matches(context) || b.matches(context);
            }
        };
    }

    /**
     * Negates a criteria (matches if criteria does not match).
     * 
     * @param criteria the criteria to negate
     * @return negated criteria
     */
    static SearchCriteria not(final SearchCriteria criteria) {
        return new SearchCriteria() {
            @Override
            public boolean matches(MessageContext context) throws IOException {
                return !criteria.matches(context);
            }
        };
    }

}

