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
 * RFC 2047 encoded word handling for MIME headers.
 *
 * <p>This package implements RFC 2047 (MIME Part Three: Message Header
 * Extensions for Non-ASCII Text), enabling proper handling of non-ASCII
 * characters in email headers.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc2047.RFC2047Encoder} - Encodes
 *       text into RFC 2047 format</li>
 *   <li>{@link org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder} - Decodes
 *       RFC 2047 encoded words back to Unicode</li>
 * </ul>
 *
 * <h2>Encoded Word Format</h2>
 *
 * <p>RFC 2047 encoded words have the format:
 * <pre>=?charset?encoding?encoded_text?=</pre>
 *
 * <p>Where:
 * <ul>
 *   <li>charset - Character set (e.g., UTF-8, ISO-8859-1)</li>
 *   <li>encoding - Either B (Base64) or Q (Quoted-Printable)</li>
 *   <li>encoded_text - The encoded content</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>This package is used internally when parsing email headers that
 * contain non-ASCII characters, such as Subject lines, display names
 * in address headers, and other structured header fields.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc2047">RFC 2047</a>
 * @see org.bluezoo.gumdrop.mime
 */
package org.bluezoo.gumdrop.mime.rfc2047;
