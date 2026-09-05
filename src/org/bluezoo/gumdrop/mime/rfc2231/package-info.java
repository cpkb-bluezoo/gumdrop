/*
 * package-info.java
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

/**
 * RFC 2231 extended parameter value encoding: charset/language tagging
 * and continuation for long parameter values (typically {@code
 * Content-Type}/{@code Content-Disposition} {@code filename*}), as used
 * for non-ASCII filenames in MIME attachments.
 *
 * <p>{@link org.bluezoo.gumdrop.mime.rfc2231.RFC2231Decoder} decodes a
 * parameter value (or set of continuation segments) back to its
 * original Unicode text.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2231">RFC 2231</a>
 * @see org.bluezoo.gumdrop.mime
 */
package org.bluezoo.gumdrop.mime.rfc2231;
