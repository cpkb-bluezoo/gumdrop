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
