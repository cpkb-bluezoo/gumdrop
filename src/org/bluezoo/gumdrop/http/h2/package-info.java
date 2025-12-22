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
 * HTTP/2 framing support shared by server and client.
 *
 * <p>This package provides low-level HTTP/2 frame parsing and writing
 * that can be used by both server-side ({@code HTTPConnection}) and
 * client-side ({@code HTTPClientConnection}) implementations.
 *
 * <h2>Key Components</h2>
 *
 * <dl>
 *   <dt>{@link org.bluezoo.gumdrop.http.h2.H2Parser}</dt>
 *   <dd>Push-parser for HTTP/2 frames. Consumes complete frames from a
 *       ByteBuffer and delivers them via typed callback methods. Uses
 *       zero-copy parsing with ByteBuffer slices. No Frame objects are
 *       allocated - data flows directly to the handler.</dd>
 *
 *   <dt>{@link org.bluezoo.gumdrop.http.h2.H2Writer}</dt>
 *   <dd>Streaming writer for HTTP/2 frames. Provides methods for each
 *       frame type with automatic buffering and NIO channel support.
 *       No Frame objects required.</dd>
 *
 *   <dt>{@link org.bluezoo.gumdrop.http.h2.H2FrameHandler}</dt>
 *   <dd>Callback interface for receiving parsed frames. Has typed methods
 *       for each frame type (dataFrameReceived, headersFrameReceived, etc.)
 *       with parsed fields as parameters. Also defines constants for frame
 *       types, flags, error codes, and SETTINGS parameters.</dd>
 * </dl>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><strong>Zero allocation</strong> - No intermediate Frame objects</li>
 *   <li><strong>Zero copy</strong> - ByteBuffer slices for payloads</li>
 *   <li><strong>Push parsing</strong> - Data pushed in, callbacks invoked</li>
 *   <li><strong>Typed callbacks</strong> - Explicit parameters per frame type</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Implement the handler interface
 * class MyConnection implements H2FrameHandler {
 *     public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
 *         // Process data frame
 *     }
 *     // ... other frame handlers
 * }
 *
 * // Parser setup
 * H2Parser parser = new H2Parser(myConnection);
 *
 * // Writer setup
 * H2Writer writer = new H2Writer(channel);
 *
 * // Receive data and parse frames
 * void onDataReceived(ByteBuffer data) {
 *     parser.receive(data);
 *     data.compact(); // Preserve partial frames
 * }
 *
 * // Send frames
 * writer.writeSettings(settings);
 * writer.writeHeaders(streamId, headerBlock, true, true, 0, 0, false);
 * writer.writeData(streamId, data, true);
 * writer.flush();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.http.hpack
 */
package org.bluezoo.gumdrop.http.h2;
