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
 * Core framework for the Gumdrop multipurpose Java server.
 *
 * <p>Gumdrop is an event-driven, non-blocking server and client framework
 * implementing HTTP/1.1, HTTP/2, and HTTP/3 (with WebSocket over all
 * three), SMTP, POP3, IMAP, FTP, LDAP, DNS (including DNS-over-TLS,
 * DNS-over-QUIC, and multicast DNS/DNS-SD), MQTT, AMQP 0-9-1, Redis,
 * gRPC, SOCKS, and WebDAV, plus a Servlet 4.0 container. Most protocols
 * are implemented on both the server and client side.
 *
 * <h2>Architecture</h2>
 *
 * <p>A single-threaded event loop per core ({@link org.bluezoo.gumdrop.SelectorLoop},
 * built on Java NIO) drives all I/O. {@link org.bluezoo.gumdrop.Endpoint} is
 * the transport-agnostic read/write interface every protocol handler is
 * written against, backed by TCP, UDP, or QUIC depending on the {@link
 * org.bluezoo.gumdrop.TransportFactory} in use; {@link
 * org.bluezoo.gumdrop.ProtocolHandler} is the callback interface protocol
 * implementations receive events through. {@link org.bluezoo.gumdrop.TCPListener}
 * is the base class for server-side connectors (TCP or, via {@link
 * org.bluezoo.gumdrop.TCPListener#setPath}, a UNIX domain socket); {@link
 * org.bluezoo.gumdrop.ClientEndpoint} is its client-side counterpart for
 * initiating outbound connections. {@link org.bluezoo.gumdrop.SecurityInfo}
 * exposes negotiated TLS/DTLS/QUIC session metadata to protocol handlers,
 * which otherwise only ever see plaintext.
 *
 * <p>Servers are wired together via XML configuration (a {@code gumdroprc}
 * file) read by a built-in dependency-injection container; the {@link
 * org.bluezoo.gumdrop.GumdropConfigurator} SPI allows an alternative DI
 * framework (Guice, Spring, CDI) to be plugged in instead.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http} - HTTP/1.1 and HTTP/2 (see {@link
 *       org.bluezoo.gumdrop.http.h3} for HTTP/3)</li>
 *   <li>{@link org.bluezoo.gumdrop.websocket} - WebSocket, over any HTTP version</li>
 *   <li>{@link org.bluezoo.gumdrop.quic} - the QUIC transport HTTP/3 and DNS-over-QUIC run on</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp}, {@link org.bluezoo.gumdrop.pop3},
 *       {@link org.bluezoo.gumdrop.imap} - mail transfer and access protocols</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp} - file transfer</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.client} - directory access</li>
 *   <li>{@link org.bluezoo.gumdrop.dns} - DNS resolution and proxying</li>
 *   <li>{@link org.bluezoo.gumdrop.mdns} - multicast DNS and DNS-SD</li>
 *   <li>{@link org.bluezoo.gumdrop.mqtt} - MQTT broker and client</li>
 *   <li>{@link org.bluezoo.gumdrop.amqp.client} - AMQP 0-9-1 client</li>
 *   <li>{@link org.bluezoo.gumdrop.redis.client} - Redis client</li>
 *   <li>{@link org.bluezoo.gumdrop.grpc} - gRPC</li>
 *   <li>{@link org.bluezoo.gumdrop.socks} - SOCKS proxy</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet} - Servlet 4.0 container</li>
 *   <li>{@link org.bluezoo.gumdrop.webdav} - static file serving and WebDAV</li>
 *   <li>{@link org.bluezoo.gumdrop.auth} - authentication realms and SASL</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry} - OpenTelemetry tracing and metrics</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox} - mail storage backends (mbox, Maildir)</li>
 *   <li>{@link org.bluezoo.gumdrop.mime} - MIME/RFC 5322 message parsing</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.SelectorLoop
 * @see org.bluezoo.gumdrop.TCPListener
 * @see org.bluezoo.gumdrop.ClientEndpoint
 */
package org.bluezoo.gumdrop;
