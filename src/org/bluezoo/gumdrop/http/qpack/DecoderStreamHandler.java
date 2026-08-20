/*
 * DecoderStreamHandler.java
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

package org.bluezoo.gumdrop.http.qpack;

/**
 * Callback interface for receiving parsed QPACK decoder-stream
 * instructions (RFC 9204 section 4.4) from a {@link DecoderStreamParser}.
 * {@link Encoder} implements this directly. Unlike
 * {@link EncoderStreamHandler}, there is no error callback: every
 * decoder-stream instruction is a bare integer (RFC 9204 section 4.4),
 * so there is no malformed-content case distinct from "not enough data
 * yet".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DecoderStreamParser
 * @see DecoderStreamWriter
 */
interface DecoderStreamHandler {

    /**
     * RFC 9204 section 4.4.1: the request/push stream identified has
     * been fully processed.
     *
     * @param streamId the stream ID
     */
    void sectionAcknowledgment(long streamId);

    /**
     * RFC 9204 section 4.4.2: the request/push stream identified was
     * reset or abandoned without processing its (possibly still
     * in-flight) field section.
     *
     * @param streamId the stream ID
     */
    void streamCancellation(long streamId);

    /**
     * RFC 9204 section 4.4.3: the decoder's Known Received Count has
     * advanced by this many entries beyond what Section Acknowledgments
     * alone conveyed.
     *
     * @param increment the increment
     */
    void insertCountIncrement(long increment);
}
