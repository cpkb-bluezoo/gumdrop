/*
 * ArticleRef.java
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

package org.bluezoo.gumdrop.nntp.server;

/**
 * How a client identified an article or articles (RFC 3977 section 6):
 * by number, by message-id, relative to the current article, or by a
 * range of numbers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ArticleRef {

    /** The kinds of reference. */
    public enum Kind {
        /** The current article, whose number is {@link #getNumber()}. */
        CURRENT,
        /** An article number. */
        NUMBER,
        /** A message-id, including the angle brackets. */
        MESSAGE_ID,
        /** The article after the one numbered {@link #getNumber()}. */
        NEXT,
        /** The article before the one numbered {@link #getNumber()}. */
        PREVIOUS,
        /** Articles numbered {@link #getNumber()} to {@link #getLast()}. */
        RANGE
    }

    private final Kind kind;
    private final long first;
    private final long last;
    private final String messageId;

    private ArticleRef(Kind kind, long first, long last, String messageId) {
        this.kind = kind;
        this.first = first;
        this.last = last;
        this.messageId = messageId;
    }

    /**
     * The current article.
     * @param number its number
     */
    public static ArticleRef current(long number) {
        return new ArticleRef(Kind.CURRENT, number, number, null);
    }

    /**
     * An article by number.
     * @param number the number
     */
    public static ArticleRef number(long number) {
        return new ArticleRef(Kind.NUMBER, number, number, null);
    }

    /**
     * An article by message-id.
     * @param messageId the message-id, with angle brackets
     */
    public static ArticleRef messageId(String messageId) {
        return new ArticleRef(Kind.MESSAGE_ID, 0, 0, messageId);
    }

    /**
     * The article after another.
     * @param from the number of the current article
     */
    public static ArticleRef next(long from) {
        return new ArticleRef(Kind.NEXT, from, from, null);
    }

    /**
     * The article before another.
     * @param from the number of the current article
     */
    public static ArticleRef previous(long from) {
        return new ArticleRef(Kind.PREVIOUS, from, from, null);
    }

    /**
     * A range of article numbers.
     * @param first the first number
     * @param last the last number, {@link Long#MAX_VALUE} if open ended
     */
    public static ArticleRef range(long first, long last) {
        return new ArticleRef(Kind.RANGE, first, last, null);
    }

    /** The kind of reference. */
    public Kind getKind() {
        return kind;
    }

    /** The article number, or the first of a range. */
    public long getNumber() {
        return first;
    }

    /** The last number of a range. */
    public long getLast() {
        return last;
    }

    /** The message-id, for {@link Kind#MESSAGE_ID}. */
    public String getMessageId() {
        return messageId;
    }

    @Override
    public String toString() {
        switch (kind) {
        case MESSAGE_ID:
            return messageId;
        case RANGE:
            return first + "-" + (last == Long.MAX_VALUE ? "" : Long.toString(last));
        default:
            return kind + ":" + first;
        }
    }

}
