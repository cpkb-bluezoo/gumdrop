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
 * Protocol Buffers encoding and decoding for OTLP.
 *
 * <p>This package provides a lightweight Protocol Buffers implementation
 * for encoding and decoding OpenTelemetry Protocol (OTLP) messages without
 * requiring the full protobuf library or generated code.
 *
 * <h2>Key Components</h2>
 *
 * <h3>Writing (Serialization)</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter} -
 *       Writes protobuf-encoded binary data to a {@link java.nio.channels.WritableByteChannel}</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel} -
 *       A channel implementation that writes to an auto-expanding ByteBuffer</li>
 * </ul>
 *
 * <h3>Reading (Deserialization)</h3>
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser} -
 *       Push-based parser that processes protobuf data incrementally</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufHandler} -
 *       Callback interface for receiving parsed field values</li>
 *   <li>{@link org.bluezoo.gumdrop.telemetry.protobuf.DefaultProtobufHandler} -
 *       Default handler implementation with helper methods for value interpretation</li>
 * </ul>
 *
 * <h2>Wire Format Support</h2>
 *
 * <p>The implementation supports all protobuf wire types:
 * <ul>
 *   <li>Varint (int32, int64, uint32, uint64, sint32, sint64, bool, enum)</li>
 *   <li>64-bit (fixed64, sfixed64, double)</li>
 *   <li>Length-delimited (string, bytes, embedded messages, packed repeated)</li>
 *   <li>32-bit (fixed32, sfixed32, float)</li>
 * </ul>
 *
 * <h2>Push Parser Usage</h2>
 *
 * <pre>
 * ProtobufHandler handler = new DefaultProtobufHandler() {
 *     &#64;Override
 *     public void handleVarint(int fieldNumber, long value) {
 *         // Process varint field
 *     }
 *
 *     &#64;Override
 *     public void handleBytes(int fieldNumber, ByteBuffer data) {
 *         // Process bytes/string field
 *     }
 *
 *     &#64;Override
 *     public boolean isMessage(int fieldNumber) {
 *         return fieldNumber == 1; // Field 1 is an embedded message
 *     }
 *
 *     &#64;Override
 *     public void startMessage(int fieldNumber) {
 *         // Push nested context
 *     }
 *
 *     &#64;Override
 *     public void endMessage() {
 *         // Pop nested context
 *     }
 * };
 *
 * ProtobufParser parser = new ProtobufParser(handler);
 * parser.receive(buffer);
 * parser.close();
 * </pre>
 *
 * <h2>OTLP Message Types</h2>
 *
 * <p>This package is used to encode:
 * <ul>
 *   <li>TracesData - Distributed tracing spans</li>
 *   <li>MetricsData - Metric measurements</li>
 *   <li>LogsData - Log records</li>
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <p>Unlike generated protobuf code, this implementation uses manual
 * encoding/decoding with explicit field numbers. This provides:
 * <ul>
 *   <li>Zero external dependencies</li>
 *   <li>Smaller code footprint</li>
 *   <li>Full control over serialization</li>
 *   <li>Incremental parsing support for streaming</li>
 * </ul>
 *
 * <h2>Internal Use</h2>
 *
 * <p>This package is used internally by the OTLP exporter and session
 * replication, and is not intended for direct use by application code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.OTLPExporter
 * @see <a href="https://protobuf.dev/programming-guides/encoding/">Protobuf Encoding</a>
 */
package org.bluezoo.gumdrop.telemetry.protobuf;
