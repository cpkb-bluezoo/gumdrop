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
 * HTTP server-side SPI: listeners, request handlers, response state,
 * authentication, and metrics. Application facades {@link org.bluezoo.gumdrop.http.HttpServer}
 * and {@link org.bluezoo.gumdrop.http.HttpClient} live in the protocol root package.
 *
 * <p>Shared codec types ({@link org.bluezoo.gumdrop.http.Headers},
 * {@link org.bluezoo.gumdrop.http.HttpStatus}, {@link org.bluezoo.gumdrop.http.HttpVersion})
 * remain in {@link org.bluezoo.gumdrop.http}.
 */
package org.bluezoo.gumdrop.http.server;
