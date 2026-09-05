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
 * RFC 2047 encoded words ({@code =?charset?encoding?text?=}, encoding
 * being {@code B} for Base64 or {@code Q} for Quoted-Printable) for
 * non-ASCII text in message headers.
 *
 * <p>{@link org.bluezoo.gumdrop.mime.rfc2047.RFC2047Encoder} and {@link
 * org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder} implement encoding
 * and decoding respectively.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc2047">RFC 2047</a>
 * @see org.bluezoo.gumdrop.mime
 */
package org.bluezoo.gumdrop.mime.rfc2047;
