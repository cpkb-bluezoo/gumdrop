/*
 * QuicheNative.java
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

package org.bluezoo.gumdrop.quic;

import java.nio.ByteBuffer;

/**
 * Thin JNI wrapper around quiche's C API and BoringSSL SSL_CTX management.
 *
 * <p>All native pointer handles are represented as {@code long}.
 * Data I/O uses direct {@link ByteBuffer} for zero-copy via
 * {@code GetDirectBufferAddress} in the C JNI code. Small control
 * metadata (connection IDs, addresses, ALPN protocols) uses
 * {@code byte[]} where the JNI copy overhead is negligible.
 *
 * <p>This class is package-private; all access is through the higher-level
 * {@code QuicEngine}, {@code QuicConnection}, and {@code QuicStreamEndpoint}
 * classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicheNative {

    static {
        System.loadLibrary("gumdrop_quic");
    }

    private QuicheNative() {
    }

    // ── Error codes ──

    /** No more work to do (not a real error). */
    public static final int QUICHE_ERR_DONE = -1;
    /** Provided buffer is too short. */
    static final int QUICHE_ERR_BUFFER_TOO_SHORT = -2;
    /** Unknown QUIC version. */
    static final int QUICHE_ERR_UNKNOWN_VERSION = -3;
    /** Invalid QUIC frame. */
    static final int QUICHE_ERR_INVALID_FRAME = -4;
    /** Invalid QUIC packet. */
    static final int QUICHE_ERR_INVALID_PACKET = -5;
    /** Connection state is invalid for the operation. */
    static final int QUICHE_ERR_INVALID_STATE = -6;
    /** Stream state is invalid for the operation. */
    static final int QUICHE_ERR_INVALID_STREAM_STATE = -7;
    /** Invalid transport parameter. */
    static final int QUICHE_ERR_INVALID_TRANSPORT_PARAM = -8;
    /** Cryptographic operation failed. */
    static final int QUICHE_ERR_CRYPTO_FAIL = -9;
    /** TLS handshake failed. */
    static final int QUICHE_ERR_TLS_FAIL = -10;
    /** Flow control limit was exceeded. */
    static final int QUICHE_ERR_FLOW_CONTROL = -11;
    /** Stream limit was exceeded. */
    static final int QUICHE_ERR_STREAM_LIMIT = -12;
    /** Final size mismatch. */
    static final int QUICHE_ERR_FINAL_SIZE = -13;
    /** Congestion control error. */
    static final int QUICHE_ERR_CONGESTION_CONTROL = -14;
    /** Stream was stopped by peer. */
    static final int QUICHE_ERR_STREAM_STOPPED = -15;
    /** Stream was reset by peer. */
    static final int QUICHE_ERR_STREAM_RESET = -16;
    /** Connection ID limit was exceeded. */
    static final int QUICHE_ERR_ID_LIMIT = -17;
    /** Out of available connection identifiers. */
    static final int QUICHE_ERR_OUT_OF_IDENTIFIERS = -18;
    /** Key update error. */
    static final int QUICHE_ERR_KEY_UPDATE = -19;
    /** Crypto buffer exceeded. */
    static final int QUICHE_ERR_CRYPTO_BUFFER_EXCEEDED = -20;
    /** Invalid ACK range. */
    static final int QUICHE_ERR_INVALID_ACK_RANGE = -21;
    /** Optimistic ACK attack detected. */
    static final int QUICHE_ERR_OPTIMISTIC_ACK_DETECTED = -22;

    /**
     * Returns a human-readable description for a quiche error code.
     *
     * @param rc the error code returned by a quiche function
     * @return the description
     */
    static String errorString(int rc) {
        switch (rc) {
            case QUICHE_ERR_DONE:
                return "DONE (no more work)";
            case QUICHE_ERR_BUFFER_TOO_SHORT:
                return "buffer too short";
            case QUICHE_ERR_UNKNOWN_VERSION:
                return "unknown QUIC version";
            case QUICHE_ERR_INVALID_FRAME:
                return "invalid frame";
            case QUICHE_ERR_INVALID_PACKET:
                return "invalid packet";
            case QUICHE_ERR_INVALID_STATE:
                return "invalid connection state";
            case QUICHE_ERR_INVALID_STREAM_STATE:
                return "invalid stream state";
            case QUICHE_ERR_INVALID_TRANSPORT_PARAM:
                return "invalid transport parameter";
            case QUICHE_ERR_CRYPTO_FAIL:
                return "cryptographic operation failed";
            case QUICHE_ERR_TLS_FAIL:
                return "TLS handshake failed";
            case QUICHE_ERR_FLOW_CONTROL:
                return "flow control limit exceeded";
            case QUICHE_ERR_STREAM_LIMIT:
                return "stream limit exceeded";
            case QUICHE_ERR_FINAL_SIZE:
                return "final size mismatch";
            case QUICHE_ERR_CONGESTION_CONTROL:
                return "congestion control error";
            case QUICHE_ERR_STREAM_STOPPED:
                return "stream stopped by peer";
            case QUICHE_ERR_STREAM_RESET:
                return "stream reset by peer";
            case QUICHE_ERR_ID_LIMIT:
                return "connection ID limit exceeded";
            case QUICHE_ERR_OUT_OF_IDENTIFIERS:
                return "out of connection identifiers";
            case QUICHE_ERR_KEY_UPDATE:
                return "key update error";
            case QUICHE_ERR_CRYPTO_BUFFER_EXCEEDED:
                return "crypto buffer exceeded";
            case QUICHE_ERR_INVALID_ACK_RANGE:
                return "invalid ACK range";
            case QUICHE_ERR_OPTIMISTIC_ACK_DETECTED:
                return "optimistic ACK attack detected";
            default:
                return "unknown error (" + rc + ")";
        }
    }

    // ── BoringSSL SSL_CTX management (for cipher/group control) ──

    /** Creates a new BoringSSL SSL_CTX configured for QUIC. */
    static native long ssl_ctx_new(boolean isServer);

    /** Loads certificate chain (PEM) into the SSL_CTX. */
    static native int ssl_ctx_load_cert_chain(long sslCtx, String path);

    /** Loads private key (PEM) into the SSL_CTX. */
    static native int ssl_ctx_load_priv_key(long sslCtx, String path);

    /** Loads trusted CA certificates for peer verification. */
    static native int ssl_ctx_load_verify_locations(long sslCtx,
                                                     String path);

    /**
     * Sets the TLS 1.3 cipher suites (colon-separated names).
     * Maps to SSL_CTX_set_strict_cipher_list() in BoringSSL.
     */
    static native int ssl_ctx_set_ciphersuites(long sslCtx,
                                                String ciphers);

    /**
     * Sets the supported key exchange groups (colon-separated names).
     * Maps to SSL_CTX_set1_curves_list() in BoringSSL.
     */
    static native int ssl_ctx_set_groups(long sslCtx, String groups);

    /** Sets ALPN protocols on the SSL_CTX. */
    static native int ssl_ctx_set_alpn_protos(long sslCtx, byte[] protos);

    /** Configures whether to verify the peer's certificate. */
    static native void ssl_ctx_set_verify_peer(long sslCtx,
                                                boolean verify);

    /** Creates a new SSL object from the SSL_CTX (for one connection). */
    static native long ssl_new(long sslCtx);

    /**
     * Sets the TLS SNI hostname on an SSL object.
     * Must be called before the connection handshake begins.
     * Maps to SSL_set_tlsext_host_name() in BoringSSL.
     */
    static native void ssl_set_hostname(long ssl, String hostname);

    /** Frees an SSL_CTX. */
    static native void ssl_ctx_free(long sslCtx);

    // ── quiche Config ──

    static native long quiche_config_new(int version);

    static native void quiche_config_set_application_protos(long config,
                                                             byte[] protos);

    static native void quiche_config_set_max_idle_timeout(long config,
                                                           long ms);

    static native void quiche_config_set_initial_max_data(long config,
                                                           long bytes);

    static native void quiche_config_set_initial_max_stream_data_bidi_local(
            long config, long bytes);

    static native void quiche_config_set_initial_max_stream_data_bidi_remote(
            long config, long bytes);

    static native void quiche_config_set_initial_max_stream_data_uni(
            long config, long bytes);

    static native void quiche_config_set_initial_max_streams_bidi(
            long config, long count);

    static native void quiche_config_set_initial_max_streams_uni(
            long config, long count);

    static native void quiche_config_set_cc_algorithm(long config,
                                                       int algo);

    static native void quiche_config_set_max_recv_udp_payload_size(
            long config, long size);

    static native void quiche_config_set_max_send_udp_payload_size(
            long config, long size);

    static native void quiche_config_free(long config);

    // ── Connection lifecycle (using pre-configured SSL) ──

    /**
     * Creates a new QUIC connection using a pre-configured BoringSSL SSL.
     * Maps to quiche_conn_new_with_tls().
     */
    static native long quiche_conn_new_with_tls(
            byte[] scid, byte[] odcid,
            byte[] localAddr, byte[] peerAddr,
            long config, long ssl, boolean isServer);

    // ── Packet I/O (uses direct ByteBuffer for zero-copy) ──

    static native int quiche_conn_recv(long conn, ByteBuffer buf, int len,
                                        byte[] fromAddr, byte[] toAddr);

    static native int quiche_conn_send(long conn, ByteBuffer buf, int len);

    // ── Stream I/O (uses direct ByteBuffer) ──

    static native int quiche_conn_stream_recv(long conn, long streamId,
                                               ByteBuffer buf, int len,
                                               boolean[] fin);

    static native int quiche_conn_stream_send(long conn, long streamId,
                                               ByteBuffer buf, int len,
                                               boolean fin);

    // ── Polling and timers ──

    static native long[] quiche_conn_readable(long conn);

    static native long quiche_conn_timeout_as_millis(long conn);

    static native void quiche_conn_on_timeout(long conn);

    static native boolean quiche_conn_is_established(long conn);

    static native boolean quiche_conn_is_closed(long conn);

    // ── Debug logging ──

    static native void quiche_enable_debug_logging();

    // ── Header parsing (for demultiplexing before connection exists) ──

    /**
     * Parses a QUIC packet header.
     *
     * <p>Returns a byte array with the following layout:
     * <pre>
     *   [version (4 bytes big-endian)]
     *   [type (1 byte)]
     *   [dcid_len (1 byte)][dcid ...]
     *   [scid_len (1 byte)][scid ...]
     *   [token_len (1 byte)][token ...]
     * </pre>
     */
    static native byte[] quiche_header_info(ByteBuffer buf, int len);

    // ── Version negotiation ──

    static native boolean quiche_version_is_supported(int version);

    /**
     * Writes a QUIC Version Negotiation packet into the direct
     * {@link ByteBuffer} {@code out}.
     *
     * @return the number of bytes written, or a negative error code.
     */
    static native int quiche_negotiate_version(byte[] scid, byte[] dcid,
                                               ByteBuffer out, int outLen);

    // ── Security info (post-handshake) ──

    /** Returns the negotiated cipher suite name from the SSL object. */
    static native String ssl_get_cipher_name(long ssl);

    /** Returns the peer certificate chain in DER format. */
    static native byte[] quiche_conn_peer_cert(long conn);

    /** Returns the negotiated ALPN protocol. */
    static native String quiche_conn_application_proto(long conn);

    // ── Cleanup ──

    static native void quiche_conn_free(long conn);

    // ── HTTP/3 Config ──

    /** Creates a new quiche HTTP/3 config. */
    public static native long quiche_h3_config_new();

    /** Frees an HTTP/3 config. */
    public static native void quiche_h3_config_free(long h3Config);

    /** Sets the QPACK maximum dynamic table capacity. */
    public static native void quiche_h3_config_set_max_dynamic_table_capacity(
            long h3Config, long capacity);

    // ── HTTP/3 Connection ──

    /**
     * Creates a new HTTP/3 connection on top of an existing QUIC connection.
     * Returns a handle to the h3 connection.
     */
    public static native long quiche_h3_conn_new_with_transport(
            long quicheConn, long h3Config);

    /**
     * Polls for the next HTTP/3 event on the connection.
     *
     * <p>Returns a two-element long array: {@code [streamId, eventType]},
     * or {@code null} if there are no pending events. Event types:
     * <ul>
     *   <li>0 = HEADERS</li>
     *   <li>1 = DATA</li>
     *   <li>2 = FINISHED</li>
     *   <li>3 = GOAWAY</li>
     *   <li>4 = RESET</li>
     * </ul>
     *
     * <p>After receiving a HEADERS event, call
     * {@link #quiche_h3_event_headers} to retrieve the header list.
     * After receiving a DATA event, call
     * {@link #quiche_h3_recv_body} to read the body data.
     */
    public static native long[] quiche_h3_conn_poll(long h3Conn,
                                                     long quicheConn);

    /**
     * Retrieves headers from the most recently polled HEADERS event.
     *
     * <p>Returns a flat String array of alternating name/value pairs:
     * {@code [name0, value0, name1, value1, ...]}.
     */
    public static native String[] quiche_h3_event_headers(long h3Conn);

    /**
     * Receives HTTP/3 request body data into the supplied direct ByteBuffer.
     *
     * @return the number of bytes read, or a negative error code
     */
    public static native int quiche_h3_recv_body(long h3Conn,
                                                  long quicheConn,
                                                  long streamId,
                                                  ByteBuffer buf, int len);

    // ── HTTP/3 Response Sending ──

    /**
     * Sends HTTP/3 response headers on the specified stream.
     *
     * @param headers flat array of alternating name/value pairs
     * @param fin true to include FIN (no body will follow)
     * @return 0 on success, negative error code on failure
     */
    public static native int quiche_h3_send_response(long h3Conn,
                                                      long quicheConn,
                                                      long streamId,
                                                      String[] headers,
                                                      boolean fin);

    /**
     * Sends HTTP/3 response body data on the specified stream.
     *
     * @param fin true if this is the last body data
     * @return the number of bytes written, or a negative error code
     */
    public static native int quiche_h3_send_body(long h3Conn,
                                                  long quicheConn,
                                                  long streamId,
                                                  ByteBuffer data, int len,
                                                  boolean fin);

    // ── HTTP/3 Request Sending (client-side) ──

    /**
     * Sends an HTTP/3 request on a new stream.
     *
     * @param headers flat array of alternating name/value pairs
     *                (must include :method, :scheme, :authority, :path)
     * @param fin true if no request body will follow
     * @return the stream ID on success, or a negative error code
     */
    public static native long quiche_h3_send_request(long h3Conn,
                                                      long quicheConn,
                                                      String[] headers,
                                                      boolean fin);

    /** Frees an HTTP/3 connection. */
    public static native void quiche_h3_conn_free(long h3Conn);
}
