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
 * HTTP/1.1 and HTTP/2 server implementation.
 *
 * <p>This package provides a full-featured HTTP server supporting both
 * HTTP/1.1 and HTTP/2 protocols, with automatic protocol negotiation
 * via ALPN (Application-Layer Protocol Negotiation) for TLS connections.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.HTTPServer} - The main HTTP server
 *       that listens for connections and delegates to handlers</li>
 *   <li>{@link org.bluezoo.gumdrop.http.HTTPConnection} - Handles a single
 *       HTTP connection, supporting both HTTP/1.1 and HTTP/2</li>
 *   <li>{@link org.bluezoo.gumdrop.http.Stream} - Represents an HTTP/2
 *       stream or HTTP/1.1 request/response pair</li>
 *   <li>{@link org.bluezoo.gumdrop.http.Frame} - Base class for HTTP/2
 *       protocol frames</li>
 *   <li>{@link org.bluezoo.gumdrop.http.Headers} - HTTP header collection</li>
 * </ul>
 *
 * <h2>HTTP/2 Support</h2>
 *
 * <p>HTTP/2 features include:
 * <ul>
 *   <li>Binary framing with multiplexed streams</li>
 *   <li>HPACK header compression</li>
 *   <li>Server push</li>
 *   <li>Flow control per stream and connection</li>
 *   <li>Stream prioritization</li>
 * </ul>
 *
 * <h2>HTTP/2 Frame Types</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.DataFrame} - Carries request/response body</li>
 *   <li>{@link org.bluezoo.gumdrop.http.HeadersFrame} - Carries HTTP headers</li>
 *   <li>{@link org.bluezoo.gumdrop.http.SettingsFrame} - Connection settings</li>
 *   <li>{@link org.bluezoo.gumdrop.http.PingFrame} - Keepalive mechanism</li>
 *   <li>{@link org.bluezoo.gumdrop.http.GoawayFrame} - Connection shutdown</li>
 *   <li>{@link org.bluezoo.gumdrop.http.WindowUpdateFrame} - Flow control</li>
 *   <li>{@link org.bluezoo.gumdrop.http.PushPromiseFrame} - Server push</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * <http-server id="https" port="443">
 *   <property name="keystore-file">/etc/gumdrop/keystore.p12</property>
 *   <property name="keystore-password">secret</property>
 *   <property name="handler">
 *     <file-handler document-root="/var/www"/>
 *   </property>
 * </http-server>
 * }</pre>
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http.client} - HTTP client</li>
 *   <li>{@link org.bluezoo.gumdrop.http.file} - Static file serving</li>
 *   <li>{@link org.bluezoo.gumdrop.http.hpack} - HPACK compression</li>
 *   <li>{@link org.bluezoo.gumdrop.http.websocket} - WebSocket support</li>
 * </ul>
 *
 * <h2>Telemetry</h2>
 *
 * <p>When telemetry is configured, the HTTP server provides:
 * <ul>
 *   <li>Distributed tracing with W3C Trace Context propagation</li>
 *   <li>Request/response metrics (latency, size, status codes)</li>
 *   <li>Connection and stream metrics</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.HTTPServer
 * @see org.bluezoo.gumdrop.http.HTTPConnection
 * @see org.bluezoo.gumdrop.http.websocket
 */
package org.bluezoo.gumdrop.http;
