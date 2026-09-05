/*
 * package-info.java
 * Copyright (C) 2025, 2026 Chris Burdess
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
 * Static file serving and WebDAV (RFC 4918).
 *
 * <p>{@link org.bluezoo.gumdrop.webdav.WebDAVService} is an {@link
 * org.bluezoo.gumdrop.http.HTTPService} that serves files from a
 * filesystem root directory, and optionally supports RFC 4918's
 * distributed authoring methods: PROPFIND, PROPPATCH, MKCOL, COPY,
 * MOVE, LOCK, and UNLOCK.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
 */
package org.bluezoo.gumdrop.webdav;
