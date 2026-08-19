/*
 * TransportParameters.java
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

package org.bluezoo.gumdrop.quic.packet;

import java.nio.ByteBuffer;

/**
 * QUIC transport parameters (RFC 9000 section 18), exchanged as a TLS
 * extension (RFC 9001 section 8.2) during the handshake. This is where
 * flow control limits are actually agreed -- without them, neither
 * side knows how much data the other is willing to buffer, so no
 * stream or connection data can be sent at all.
 *
 * <p>Only the parameters needed for basic connection setup, flow
 * control, and Retry are implemented: connection ID validation
 * ({@code original_destination_connection_id}, {@code initial_source_connection_id},
 * {@code retry_source_connection_id}), idle timeout, and the six
 * {@code initial_max_*} flow control limits. {@code ack_delay_exponent}
 * and {@code max_ack_delay} are not sent -- their RFC-specified defaults
 * (3 and 25ms) apply when absent, which is fine until real loss-recovery
 * timing needs tuning. {@code stateless_reset_token} is sent by servers
 * so peers can recognise a stateless reset for the handshake connection
 * ID (RFC 9000 section 10.3). {@code preferred_address} and
 * {@code active_connection_id_limit} are not implemented yet. Unknown
 * parameters received from a peer are ignored, per RFC 9000
 * section 18.1.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18">RFC 9000 section 18</a>
 */
public final class TransportParameters {

    /** Server-only: the client's original Destination Connection ID (RFC 9000 section 7.3). */
    public static final long ORIGINAL_DESTINATION_CONNECTION_ID = 0x00;
    public static final long MAX_IDLE_TIMEOUT = 0x01;
    /** Server-only: token for the handshake connection ID (RFC 9000 section 18.2). */
    public static final long STATELESS_RESET_TOKEN = 0x02;
    public static final long MAX_UDP_PAYLOAD_SIZE = 0x03;
    public static final long INITIAL_MAX_DATA = 0x04;
    public static final long INITIAL_MAX_STREAM_DATA_BIDI_LOCAL = 0x05;
    public static final long INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06;
    public static final long INITIAL_MAX_STREAM_DATA_UNI = 0x07;
    public static final long INITIAL_MAX_STREAMS_BIDI = 0x08;
    public static final long INITIAL_MAX_STREAMS_UNI = 0x09;
    /** The sender's own connection ID from its first Initial packet (RFC 9000 section 7.3). */
    public static final long INITIAL_SOURCE_CONNECTION_ID = 0x0f;
    /** Server-only: the Source Connection ID from a Retry packet this handshake used (RFC 9000 section 7.3). */
    public static final long RETRY_SOURCE_CONNECTION_ID = 0x10;

    /** RFC 9000 section 18.2: default when max_udp_payload_size is absent. */
    public static final long DEFAULT_MAX_UDP_PAYLOAD_SIZE = 65527;

    private byte[] originalDestinationConnectionId;
    private long maxIdleTimeout;
    private long maxUdpPayloadSize = DEFAULT_MAX_UDP_PAYLOAD_SIZE;
    private long initialMaxData;
    private long initialMaxStreamDataBidiLocal;
    private long initialMaxStreamDataBidiRemote;
    private long initialMaxStreamDataUni;
    private long initialMaxStreamsBidi;
    private long initialMaxStreamsUni;
    private byte[] initialSourceConnectionId;
    private byte[] retrySourceConnectionId;
    private byte[] statelessResetToken;

    public byte[] getOriginalDestinationConnectionId() {
        return originalDestinationConnectionId;
    }

    public void setOriginalDestinationConnectionId(byte[] originalDestinationConnectionId) {
        this.originalDestinationConnectionId = originalDestinationConnectionId;
    }

    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    public void setMaxIdleTimeout(long maxIdleTimeout) {
        this.maxIdleTimeout = maxIdleTimeout;
    }

    public long getMaxUdpPayloadSize() {
        return maxUdpPayloadSize;
    }

    public void setMaxUdpPayloadSize(long maxUdpPayloadSize) {
        this.maxUdpPayloadSize = maxUdpPayloadSize;
    }

    public long getInitialMaxData() {
        return initialMaxData;
    }

    public void setInitialMaxData(long initialMaxData) {
        this.initialMaxData = initialMaxData;
    }

    public long getInitialMaxStreamDataBidiLocal() {
        return initialMaxStreamDataBidiLocal;
    }

    public void setInitialMaxStreamDataBidiLocal(long initialMaxStreamDataBidiLocal) {
        this.initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal;
    }

    public long getInitialMaxStreamDataBidiRemote() {
        return initialMaxStreamDataBidiRemote;
    }

    public void setInitialMaxStreamDataBidiRemote(long initialMaxStreamDataBidiRemote) {
        this.initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote;
    }

    public long getInitialMaxStreamDataUni() {
        return initialMaxStreamDataUni;
    }

    public void setInitialMaxStreamDataUni(long initialMaxStreamDataUni) {
        this.initialMaxStreamDataUni = initialMaxStreamDataUni;
    }

    public long getInitialMaxStreamsBidi() {
        return initialMaxStreamsBidi;
    }

    public void setInitialMaxStreamsBidi(long initialMaxStreamsBidi) {
        this.initialMaxStreamsBidi = initialMaxStreamsBidi;
    }

    public long getInitialMaxStreamsUni() {
        return initialMaxStreamsUni;
    }

    public void setInitialMaxStreamsUni(long initialMaxStreamsUni) {
        this.initialMaxStreamsUni = initialMaxStreamsUni;
    }

    public byte[] getInitialSourceConnectionId() {
        return initialSourceConnectionId;
    }

    public void setInitialSourceConnectionId(byte[] initialSourceConnectionId) {
        this.initialSourceConnectionId = initialSourceConnectionId;
    }

    public byte[] getRetrySourceConnectionId() {
        return retrySourceConnectionId;
    }

    public void setRetrySourceConnectionId(byte[] retrySourceConnectionId) {
        this.retrySourceConnectionId = retrySourceConnectionId;
    }

    /**
     * Returns the stateless reset token for the handshake connection ID,
     * or {@code null} if none was sent (client endpoints never send this).
     *
     * @return the 16-byte token, or {@code null}
     */
    public byte[] getStatelessResetToken() {
        return statelessResetToken;
    }

    /**
     * Sets the stateless reset token (server-only transport parameter).
     *
     * @param statelessResetToken the 16-byte token
     */
    public void setStatelessResetToken(byte[] statelessResetToken) {
        this.statelessResetToken = statelessResetToken;
    }

    /**
     * Encodes these parameters as the transport-parameters TLV list
     * (RFC 9000 section 18.1) -- the extension_data of the
     * quic_transport_parameters TLS extension (RFC 9001 section 8.2),
     * without that extension's own type/length wrapper.
     *
     * @return the encoded parameter list
     */
    public byte[] encode() {
        int size = 0;
        size += entryLength(MAX_IDLE_TIMEOUT, varIntValueLength(maxIdleTimeout));
        size += entryLength(MAX_UDP_PAYLOAD_SIZE, varIntValueLength(maxUdpPayloadSize));
        size += entryLength(INITIAL_MAX_DATA, varIntValueLength(initialMaxData));
        size += entryLength(INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, varIntValueLength(initialMaxStreamDataBidiLocal));
        size += entryLength(INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, varIntValueLength(initialMaxStreamDataBidiRemote));
        size += entryLength(INITIAL_MAX_STREAM_DATA_UNI, varIntValueLength(initialMaxStreamDataUni));
        size += entryLength(INITIAL_MAX_STREAMS_BIDI, varIntValueLength(initialMaxStreamsBidi));
        size += entryLength(INITIAL_MAX_STREAMS_UNI, varIntValueLength(initialMaxStreamsUni));
        if (initialSourceConnectionId != null) {
            size += entryLength(INITIAL_SOURCE_CONNECTION_ID, initialSourceConnectionId.length);
        }
        if (originalDestinationConnectionId != null) {
            size += entryLength(ORIGINAL_DESTINATION_CONNECTION_ID, originalDestinationConnectionId.length);
        }
        if (retrySourceConnectionId != null) {
            size += entryLength(RETRY_SOURCE_CONNECTION_ID, retrySourceConnectionId.length);
        }
        if (statelessResetToken != null) {
            size += entryLength(STATELESS_RESET_TOKEN, statelessResetToken.length);
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        writeVarIntParam(buf, MAX_IDLE_TIMEOUT, maxIdleTimeout);
        writeVarIntParam(buf, MAX_UDP_PAYLOAD_SIZE, maxUdpPayloadSize);
        writeVarIntParam(buf, INITIAL_MAX_DATA, initialMaxData);
        writeVarIntParam(buf, INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, initialMaxStreamDataBidiLocal);
        writeVarIntParam(buf, INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, initialMaxStreamDataBidiRemote);
        writeVarIntParam(buf, INITIAL_MAX_STREAM_DATA_UNI, initialMaxStreamDataUni);
        writeVarIntParam(buf, INITIAL_MAX_STREAMS_BIDI, initialMaxStreamsBidi);
        writeVarIntParam(buf, INITIAL_MAX_STREAMS_UNI, initialMaxStreamsUni);
        if (initialSourceConnectionId != null) {
            writeBytesParam(buf, INITIAL_SOURCE_CONNECTION_ID, initialSourceConnectionId);
        }
        if (originalDestinationConnectionId != null) {
            writeBytesParam(buf, ORIGINAL_DESTINATION_CONNECTION_ID, originalDestinationConnectionId);
        }
        if (retrySourceConnectionId != null) {
            writeBytesParam(buf, RETRY_SOURCE_CONNECTION_ID, retrySourceConnectionId);
        }
        if (statelessResetToken != null) {
            writeBytesParam(buf, STATELESS_RESET_TOKEN, statelessResetToken);
        }
        return buf.array();
    }

    private static int varIntValueLength(long value) {
        return VarInt.encodedLength(value);
    }

    private static int entryLength(long id, int valueLength) {
        return VarInt.encodedLength(id) + VarInt.encodedLength(valueLength) + valueLength;
    }

    private static void writeVarIntParam(ByteBuffer buf, long id, long value) {
        int valueLength = VarInt.encodedLength(value);
        VarInt.encode(id, buf);
        VarInt.encode(valueLength, buf);
        VarInt.encode(value, buf);
    }

    private static void writeBytesParam(ByteBuffer buf, long id, byte[] value) {
        VarInt.encode(id, buf);
        VarInt.encode(value.length, buf);
        buf.put(value);
    }

    /**
     * Decodes a transport-parameters TLV list.
     *
     * @param buf the buffer to decode from, positioned at the start of
     *            the list, consumed fully on return
     * @return the decoded parameters
     */
    public static TransportParameters decode(ByteBuffer buf) {
        TransportParameters params = new TransportParameters();
        while (buf.hasRemaining()) {
            long id = VarInt.decode(buf);
            int length = (int) VarInt.decode(buf);
            int valueStart = buf.position();

            if (id == MAX_IDLE_TIMEOUT) {
                params.maxIdleTimeout = VarInt.decode(buf);
            } else if (id == MAX_UDP_PAYLOAD_SIZE) {
                params.maxUdpPayloadSize = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_DATA) {
                params.initialMaxData = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_STREAM_DATA_BIDI_LOCAL) {
                params.initialMaxStreamDataBidiLocal = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_STREAM_DATA_BIDI_REMOTE) {
                params.initialMaxStreamDataBidiRemote = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_STREAM_DATA_UNI) {
                params.initialMaxStreamDataUni = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_STREAMS_BIDI) {
                params.initialMaxStreamsBidi = VarInt.decode(buf);
            } else if (id == INITIAL_MAX_STREAMS_UNI) {
                params.initialMaxStreamsUni = VarInt.decode(buf);
            } else if (id == INITIAL_SOURCE_CONNECTION_ID) {
                params.initialSourceConnectionId = new byte[length];
                buf.get(params.initialSourceConnectionId);
            } else if (id == ORIGINAL_DESTINATION_CONNECTION_ID) {
                params.originalDestinationConnectionId = new byte[length];
                buf.get(params.originalDestinationConnectionId);
            } else if (id == RETRY_SOURCE_CONNECTION_ID) {
                params.retrySourceConnectionId = new byte[length];
                buf.get(params.retrySourceConnectionId);
            } else if (id == STATELESS_RESET_TOKEN) {
                params.statelessResetToken = new byte[length];
                buf.get(params.statelessResetToken);
            }
            // RFC 9000 section 18.1: ignore parameters we don't understand.

            buf.position(valueStart + length);
        }
        return params;
    }
}
