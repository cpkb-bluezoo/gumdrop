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
 * <p>A request arrives as a sequence of events delivered to an {@link
 * org.bluezoo.gumdrop.http.server.HttpRequestHandler} (an {@link
 * org.bluezoo.gumdrop.http.HttpMessageHandler}): {@code method}, {@code
 * target}, {@code scheme}, {@code authority}, one {@code header} (or typed
 * {@code longHeader}, {@code dateHeader}, {@code contentType},
 * {@code contentDisposition}) event per field, {@code endHeaders}, any
 * number of {@code bodyContent} events, trailer fields as further field
 * events, and {@code endMessage}. The response is written through {@link
 * org.bluezoo.gumdrop.http.server.HttpResponse} with the same vocabulary:
 * {@code status}, one {@code header} call per field, {@code bodyContent}
 * and {@code endMessage}. Server push is {@code startPushPromise}, field
 * calls, then {@code endPushPromise}. The same handler code serves
 * HTTP/1.1, HTTP/2 and HTTP/3.
 *
 * <p>Shared codec types ({@link org.bluezoo.gumdrop.http.HttpStatus},
 * {@link org.bluezoo.gumdrop.http.HttpVersion},
 * {@link org.bluezoo.gumdrop.http.HttpMethod})
 * remain in {@link org.bluezoo.gumdrop.http}.
 */
package org.bluezoo.gumdrop.http.server;
