/*
 * WebSocketExtension.java
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

package org.bluezoo.gumdrop.websocket;

import java.util.Map;

/**
 * RFC 6455 §9 — WebSocket extension interface.
 *
 * <p>Extensions augment the WebSocket protocol by intercepting the
 * frame encoding/decoding pipeline. Each extension declares which
 * RSV bits it uses and provides encode/decode transforms that are
 * applied to message payloads.
 *
 * <p>The negotiation lifecycle differs by role:
 * <ul>
 * <li><b>Server:</b> {@link #acceptOffer} is called with the client's
 *     offered parameters. The extension returns the accepted parameters
 *     (included in the 101 response) or {@code null} to decline.</li>
 * <li><b>Client:</b> {@link #generateOffer} produces the offer for the
 *     request header. After the server responds, {@link #acceptResponse}
 *     configures the extension from the accepted parameters.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455#section-9">RFC 6455 §9</a>
 * @see PerMessageDeflateExtension
 */
public interface WebSocketExtension {

    /**
     * Returns the registered extension token (e.g. {@code "permessage-deflate"}).
     *
     * @return the extension name
     */
    String getName();

    /** Returns {@code true} if this extension uses the RSV1 bit. */
    boolean usesRsv1();

    /** Returns {@code true} if this extension uses the RSV2 bit. */
    boolean usesRsv2();

    /** Returns {@code true} if this extension uses the RSV3 bit. */
    boolean usesRsv3();

    /**
     * Server-side negotiation: evaluates the client's offered parameters
     * and returns the accepted parameters for the response, or {@code null}
     * to decline this offer.
     *
     * @param offeredParams the client's extension parameters
     * @return accepted parameters for the 101 response, or null to decline
     */
    Map<String, String> acceptOffer(Map<String, String> offeredParams);

    /**
     * Client-side: generates the extension parameters for the
     * {@code Sec-WebSocket-Extensions} request header.
     *
     * @return the offer parameters
     */
    Map<String, String> generateOffer();

    /**
     * Client-side: configures this extension from the server's accepted
     * parameters in the 101 response.
     *
     * @param responseParams the server's accepted parameters
     * @return {@code true} if the response is acceptable
     */
    boolean acceptResponse(Map<String, String> responseParams);

    /**
     * Transforms an outgoing message payload (e.g. compress).
     *
     * @param payload the uncompressed message payload
     * @return the transformed payload
     * @throws java.io.IOException if encoding fails
     */
    byte[] encode(byte[] payload) throws java.io.IOException;

    /**
     * Transforms an incoming message payload (e.g. decompress).
     *
     * @param payload the compressed message payload
     * @return the transformed payload
     * @throws java.io.IOException if decoding fails (e.g. corrupt data)
     */
    byte[] decode(byte[] payload) throws java.io.IOException;

    /**
     * Releases any resources held by this extension (e.g. compressor state).
     */
    void close();
}
