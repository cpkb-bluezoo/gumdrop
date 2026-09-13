/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
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
 * Non-blocking HTTP client supporting HTTP/1.1, HTTP/2, and HTTP/3, with
 * automatic transport negotiation (DNS HTTPS-record discovery, cached
 * Alt-Svc, ALPN) choosing between them.
 *
 * <p>{@link org.bluezoo.gumdrop.http.client.HttpClient} is the facade
 * applications use to make requests; {@link
 * org.bluezoo.gumdrop.http.client.HttpRequest} represents one request,
 * {@link org.bluezoo.gumdrop.http.client.HttpResponseHandler} the
 * callback interface for response events (status, headers including
 * trailers, streamed body, completion), and {@link
 * org.bluezoo.gumdrop.http.client.HttpResponse} carries status and
 * redirect information. {@link org.bluezoo.gumdrop.http.client.PushPromise}
 * exposes HTTP/2 server push. Request and response bodies are streamed
 * rather than buffered, with backpressure support for large uploads.
 *
 * <p>Also supported: keep-alive and stream multiplexing appropriate to
 * the negotiated version, automatic redirect following, request
 * cancellation, and Basic/Bearer/Digest/OAuth authentication.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.client.HttpClient
 * @see org.bluezoo.gumdrop.http.client.HttpResponseHandler
 * @see org.bluezoo.gumdrop.http.h3
 */
package org.bluezoo.gumdrop.http.client;
