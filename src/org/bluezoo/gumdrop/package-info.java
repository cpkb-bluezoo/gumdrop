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
 * <p>Gumdrop is an event-driven, non-blocking server framework that provides
 * implementations for multiple protocols including HTTP/1.1, HTTP/2, WebSocket,
 * SMTP, POP3, IMAP, FTP, and DNS. It also includes a Servlet 4.0 container
 * for hosting Java web applications.
 *
 * <h2>Architecture</h2>
 *
 * <p>The core architecture is based on Java NIO with a single-threaded
 * event loop ({@link org.bluezoo.gumdrop.SelectorLoop}) that handles all I/O
 * operations. This design enables high concurrency with minimal thread overhead.
 *
 * <h3>Key Components</h3>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.Endpoint} - Transport-agnostic I/O
 *       interface (TCP, UDP, QUIC)</li>
 *   <li>{@link org.bluezoo.gumdrop.ProtocolHandler} - Callback interface
 *       for protocol logic</li>
 *   <li>{@link org.bluezoo.gumdrop.TCPListener} - Base class for
 *       protocol servers</li>
 *   <li>{@link org.bluezoo.gumdrop.ClientEndpoint} - Initiates outbound
 *       connections</li>
 *   <li>{@link org.bluezoo.gumdrop.TransportFactory} - Pluggable transport
 *       layer (TCP, UDP, QUIC)</li>
 *   <li>{@link org.bluezoo.gumdrop.SecurityInfo} - Security metadata after
 *       TLS/DTLS/QUIC handshake</li>
 *   <li>{@link org.bluezoo.gumdrop.SelectorLoop} - The central event loop
 *       handling all non-blocking I/O operations</li>
 *   <li>{@link org.bluezoo.gumdrop.ComponentRegistry} - Dependency injection
 *       container for wiring components</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>Servers are configured via XML files (typically {@code gumdroprc}) which
 * define components, their properties, and dependencies. The
 * {@link org.bluezoo.gumdrop.ConfigurationParser} reads these files and
 * populates the {@link org.bluezoo.gumdrop.ComponentRegistry}.
 *
 * <h3>Example Configuration</h3>
 *
 * <pre>{@code
 * <gumdrop>
 *   <http-server id="http" port="8080">
 *     <property name="handler">
 *       <file-handler document-root="/var/www"/>
 *     </property>
 *   </http-server>
 * </gumdrop>
 * }</pre>
 *
 * <h2>SSL/TLS Support</h2>
 *
 * <p>Security is configured on the {@link org.bluezoo.gumdrop.TransportFactory}
 * and is transparent to protocol handlers. All endpoints support TLS (TCP),
 * DTLS (UDP), and QUIC (always TLS 1.3). Protocol handlers receive plaintext
 * and query {@link org.bluezoo.gumdrop.SecurityInfo} for TLS metadata.
 * SNI (Server Name Indication) is supported for virtual hosting with
 * different certificates per hostname.
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.http} - HTTP/1.1 and HTTP/2 server</li>
 *   <li>{@link org.bluezoo.gumdrop.smtp} - SMTP mail server</li>
 *   <li>{@link org.bluezoo.gumdrop.pop3} - POP3 mail server</li>
 *   <li>{@link org.bluezoo.gumdrop.imap} - IMAP mail server</li>
 *   <li>{@link org.bluezoo.gumdrop.ftp} - FTP file server</li>
 *   <li>{@link org.bluezoo.gumdrop.dns} - DNS server</li>
 *   <li>{@link org.bluezoo.gumdrop.servlet} - Servlet 4.0 container</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry} - OpenTelemetry integration</li>
 *   <li>{@link org.bluezoo.gumdrop.mailbox} - Mail storage backends</li>
 *   <li>{@link org.bluezoo.gumdrop.mime} - MIME parsing utilities</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.Bootstrap
 * @see org.bluezoo.gumdrop.SelectorLoop
 */
package org.bluezoo.gumdrop;


