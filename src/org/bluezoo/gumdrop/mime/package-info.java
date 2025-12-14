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
 * MIME (Multipurpose Internet Mail Extensions) parsing utilities.
 *
 * <p>This package provides low-level parsers for MIME message formats,
 * including RFC 822/5322 headers, RFC 2047 encoded words, and MIME
 * multipart content.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mime.MIMEParser} - Main parser for
 *       MIME messages and multipart content</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.MIMEHandler} - Callback interface
 *       for parsed MIME parts</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.ContentType} - Represents and
 *       parses MIME content-type headers</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.ContentDisposition} - Represents
 *       content-disposition headers</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.Base64Decoder} - Base64 decoding</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.QuotedPrintableDecoder} -
 *       Quoted-printable decoding</li>
 * </ul>
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc2047} - Encoded word handling
 *       for non-ASCII text in headers</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322} - Internet message format
 *       parsing and generation</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li>Streaming parser for memory efficiency</li>
 *   <li>Charset conversion with fallback handling</li>
 *   <li>Quoted-printable and Base64 decoding</li>
 *   <li>Multipart boundary detection</li>
 *   <li>Content-type parameter parsing</li>
 * </ul>
 *
 * <h2>Internal Use</h2>
 *
 * <p>These utilities are used internally by HttpServletRequest Part
 * implementation for handling HTTP multipart/form-data requests, the mail
 * server packages (SMTP, POP3, IMAP) and the mailbox package for
 * message parsing and indexing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mime.rfc2047
 * @see org.bluezoo.gumdrop.mime.rfc5322
 */
package org.bluezoo.gumdrop.mime;
