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
 * Hand-written Protocol Buffers encoding/decoding for OTLP messages
 * (TracesData, MetricsData, LogsData), with no generated code or
 * dependency on the protobuf library.
 *
 * <p>{@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter}
 * writes protobuf's four wire types (varint, 64-bit, length-delimited,
 * 32-bit) to a {@link java.nio.channels.WritableByteChannel}, typically
 * {@link org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel}, an
 * auto-expanding in-memory channel. {@link
 * org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser} is the
 * corresponding push parser, delivering decoded fields incrementally to
 * a {@link org.bluezoo.gumdrop.telemetry.protobuf.ProtobufHandler};
 * {@link org.bluezoo.gumdrop.telemetry.protobuf.DefaultProtobufHandler}
 * supplies value-interpretation helpers so most handlers only override
 * the field callbacks they care about.
 *
 * <p>Used internally by OTLP export ({@link
 * org.bluezoo.gumdrop.telemetry.otlp}) and by {@link
 * org.bluezoo.gumdrop.servlet.session}'s cluster replication; not
 * intended for direct use by application code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.telemetry.otlp
 * @see <a href="https://protobuf.dev/programming-guides/encoding/">Protobuf Encoding</a>
 */
package org.bluezoo.gumdrop.telemetry.protobuf;
