/*
 * MessageHeader.java
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

package org.bluezoo.gumdrop.redis.client;

/**
 * What names a Pub/Sub message: the channel it was published to and, for a
 * pattern subscription, the pattern that matched. These are identifiers, so
 * they are delivered whole, up to the connection's maximum field length. The
 * body of the message is a payload and arrives as chunks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageHeader {

    private final MessageKind kind;
    private final String pattern;
    private final String channel;

    MessageHeader(MessageKind kind, String pattern, String channel) {
        this.kind = kind;
        this.pattern = pattern;
        this.channel = channel;
    }

    /**
     * Returns the kind of subscription the message was delivered for.
     *
     * @return the kind
     */
    public MessageKind getKind() {
        return kind;
    }

    /**
     * Returns the pattern that matched, for a pattern subscription.
     *
     * @return the pattern, or null for any other kind
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Returns the channel the message was published to.
     *
     * @return the channel
     */
    public String getChannel() {
        return channel;
    }

    @Override
    public String toString() {
        return kind + (pattern != null ? " " + pattern : "") + " " + channel;
    }

}
