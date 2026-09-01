/*
 * HttpDatagramContext.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * The Context ID prefix RFC 9298 section 5 (and, via the same reference,
 * RFC 9484) adds on top of an RFC 9297 HTTP Datagram's otherwise-opaque
 * payload: a QUIC variable-length integer identifying which of possibly
 * several concurrently-negotiated flows sharing one request's datagram
 * stream the remainder of the payload belongs to (for example, separate
 * compression contexts, or additional tunnelled flows), followed by that
 * flow's actual protocol data.
 *
 * <p>This sits above {@link org.bluezoo.gumdrop.http.h3.H3Datagram} and
 * {@link Capsule}, not in place of either -- both continue to carry an
 * RFC 9297 HTTP Datagram's payload exactly as before; a Context ID is
 * simply how a MASQUE-style protocol (CONNECT-UDP, CONNECT-IP) chooses to
 * structure that payload's own bytes. Encoding/decoding it once here,
 * rather than in each such protocol, is what lets them share one codec
 * instead of every one of them inlining the same varint prefix.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298#section-5">RFC 9298 section 5</a>
 */
public final class HttpDatagramContext {

    /**
     * RFC 9298 section 5: Context ID 0 is registered for the payload
     * associated directly with the request stream itself -- the UDP
     * payload for CONNECT-UDP, or the IP packet for CONNECT-IP. Every
     * other Context ID is registered by the application, or by an
     * earlier capsule on the same stream (e.g. to negotiate compression),
     * before any datagram using it is sent.
     */
    public static final long REGISTERED_CONTEXT_ID = 0L;

    private final long contextId;
    private final ByteBuffer payload;

    private HttpDatagramContext(long contextId, ByteBuffer payload) {
        this.contextId = contextId;
        this.payload = payload;
    }

    /**
     * Returns the Context ID this payload belongs to.
     *
     * @return the Context ID
     */
    public long getContextId() {
        return contextId;
    }

    /**
     * Returns the payload following the Context ID -- the flow's actual
     * protocol data (a UDP payload, an IP packet, or whatever the
     * registered Context ID's own protocol defines). A view onto the
     * buffer {@link #decode} was called with: valid only during the same
     * call that produced this instance, matching {@link
     * HTTPRequestHandler#datagramReceived}'s own contract for the buffer
     * it hands a handler.
     *
     * @return the payload
     */
    public ByteBuffer getPayload() {
        return payload;
    }

    /**
     * Encodes a Context ID and its payload as a Context ID-prefixed HTTP
     * Datagram payload (RFC 9298 section 5) -- suitable as-is for {@link
     * HTTPResponseState#sendDatagram(ByteBuffer)}.
     *
     * @param contextId the Context ID, must be in {@code [0, VarInt.MAX_VALUE]}
     * @param payload the flow's protocol data, or {@code null} for none; copied
     * @return the encoded Context ID-prefixed payload
     * @throws IllegalArgumentException if {@code contextId} is out of range
     */
    public static ByteBuffer encode(long contextId, ByteBuffer payload) {
        if (contextId < 0 || contextId > VarInt.MAX_VALUE) {
            throw new IllegalArgumentException("Context ID out of range: " + contextId);
        }
        int payloadLength = payload != null ? payload.remaining() : 0;
        ByteBuffer out = ByteBuffer.allocate(VarInt.encodedLength(contextId) + payloadLength);
        VarInt.encode(contextId, out);
        if (payload != null) {
            out.put(payload.duplicate());
        }
        out.flip();
        return out;
    }

    /**
     * Decodes a Context ID-prefixed HTTP Datagram payload (RFC 9298
     * section 5) -- the counterpart to what a Context ID-aware protocol
     * hands to {@link HTTPRequestHandler#datagramReceived}.
     *
     * <p>Per RFC 9298 section 5, an unrecognised Context ID is not an
     * error -- callers should ignore datagrams for Context IDs they did
     * not register, not treat decode returning a valid instance with an
     * unknown ID as reason to reset the stream.
     *
     * @param data the Context ID-prefixed payload
     * @return the decoded Context ID and remaining payload, or {@code
     *         null} if {@code data} is null, empty, or the Context ID
     *         varint is truncated
     */
    public static HttpDatagramContext decode(ByteBuffer data) {
        if (data == null || !data.hasRemaining()) {
            return null;
        }
        int contextIdLength = VarInt.peekEncodedLength(data, data.position());
        if (data.remaining() < contextIdLength) {
            return null;
        }
        long contextId = VarInt.decode(data);
        return new HttpDatagramContext(contextId, data.slice());
    }
}
