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
 * <p>This package implements RFC 5322 (Internet Message Format), providing
 * parsing and generation of email message headers and structure.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser} -
 *       Parses email address lists (From, To, Cc, Bcc, etc.)</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.MessageDateTimeFormatter} -
 *       Formats and parses RFC 5322 date-time values</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.MessageParser} - Parses
 *       complete email messages</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.MessageHandler} - Callback
 *       interface for parsed message parts</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.EmailAddress} - Represents
 *       an email address</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.GroupEmailAddress} -
 *       Represents an email group address</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322.MessageIDParser} - Parses
 *       Message-ID and References headers</li>
 * </ul>
 *
 * <h2>Address Format</h2>
 *
 * <p>Supported address formats include:
 * <ul>
 *   <li>Simple addresses: user@example.com</li>
 *   <li>Named addresses: "Display Name" &lt;user@example.com&gt;</li>
 *   <li>Groups: Group Name: addr1@ex.com, addr2@ex.com;</li>
 * </ul>
 *
 * <h2>Date Format</h2>
 *
 * <p>RFC 5322 date-time format:
 * <pre>Thu, 13 Feb 2020 14:30:00 +0000</pre>
 *
 * <p>The parser is lenient and handles common variations found in
 * real-world email messages.
 *
 * <h2>Usage</h2>
 *
 * <p>This package is used internally by the mail server packages for
 * parsing and generating email message headers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a>
 * @see org.bluezoo.gumdrop.mime
 */
package org.bluezoo.gumdrop.mime.rfc5322;
