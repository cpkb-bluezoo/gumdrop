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
 * MIME parsing: multipart content, content-type/content-disposition
 * headers, and Base64/quoted-printable transfer encodings.
 *
 * <p>{@link org.bluezoo.gumdrop.mime.MIMEParser} is a streaming parser
 * delivering parsed parts to a {@link org.bluezoo.gumdrop.mime.MIMEHandler};
 * {@link org.bluezoo.gumdrop.mime.ContentType} and {@link
 * org.bluezoo.gumdrop.mime.ContentDisposition} parse and represent those
 * headers; {@link org.bluezoo.gumdrop.mime.Base64Decoder} and {@link
 * org.bluezoo.gumdrop.mime.QuotedPrintableDecoder} handle the two
 * standard transfer encodings.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc2047} - encoded words for non-ASCII header text</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc2231} - extended parameter encoding</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc5322} - Internet Message Format parsing/generation</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mime.rfc5322
 */
package org.bluezoo.gumdrop.mime;
