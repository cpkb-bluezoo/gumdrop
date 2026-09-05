/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

/**
 * RFC 5322 Internet Message Format parsing and generation.
 *
 * <p>{@link org.bluezoo.gumdrop.mime.rfc5322.MessageParser} parses a
 * complete message, delivering parsed parts to a {@link
 * org.bluezoo.gumdrop.mime.rfc5322.MessageHandler}; {@link
 * org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser} parses address
 * lists (From, To, Cc, Bcc), including groups, into {@link
 * org.bluezoo.gumdrop.mime.rfc5322.EmailAddress}/{@link
 * org.bluezoo.gumdrop.mime.rfc5322.GroupEmailAddress}; {@link
 * org.bluezoo.gumdrop.mime.rfc5322.MessageDateTimeFormatter} handles the
 * RFC 5322 date-time format leniently enough for real-world messages;
 * {@link org.bluezoo.gumdrop.mime.rfc5322.MessageIDParser} parses
 * Message-ID and References headers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a>
 * @see org.bluezoo.gumdrop.mime
 */
package org.bluezoo.gumdrop.mime.rfc5322;
