/*
 * quiche_jni.c
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

/*
 * JNI bindings for quiche QUIC library functions.
 *
 * This file provides thin wrappers around quiche's C FFI functions.
 * Data I/O functions use GetDirectBufferAddress for zero-copy access
 * to Java direct ByteBuffers. Small control metadata (connection IDs,
 * addresses) is passed as byte[] with JNI copy.
 *
 * Build: see the project README for compilation instructions.
 */

#include <jni.h>
#include <quiche.h>
#include <string.h>
#include <stdlib.h>
#include <openssl/ssl.h>
#include <netinet/in.h>
#include <sys/socket.h>

/* Forward declarations for JNI method names */
#define JNI_CLASS "org/bluezoo/gumdrop/quic/QuicheNative"

/* ── quiche Config ── */

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1new(
        JNIEnv *env, jclass cls, jint version) {
    quiche_config *config = quiche_config_new((uint32_t)version);
    return (jlong)(intptr_t)config;
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1application_1protos(
        JNIEnv *env, jclass cls, jlong config_ptr, jbyteArray protos) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    jbyte *buf = (*env)->GetByteArrayElements(env, protos, NULL);
    jsize len = (*env)->GetArrayLength(env, protos);
    quiche_config_set_application_protos(config, (uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, protos, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1max_1idle_1timeout(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong ms) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_max_idle_timeout(config, (uint64_t)ms);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1data(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong bytes) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_data(config, (uint64_t)bytes);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1stream_1data_1bidi_1local(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong bytes) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_stream_data_bidi_local(config,
                                                          (uint64_t)bytes);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1stream_1data_1bidi_1remote(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong bytes) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_stream_data_bidi_remote(config,
                                                           (uint64_t)bytes);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1stream_1data_1uni(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong bytes) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_stream_data_uni(config, (uint64_t)bytes);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1streams_1bidi(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong count) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_streams_bidi(config, (uint64_t)count);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1initial_1max_1streams_1uni(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong count) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_initial_max_streams_uni(config, (uint64_t)count);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1cc_1algorithm(
        JNIEnv *env, jclass cls, jlong config_ptr, jint algo) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_cc_algorithm(config, (enum quiche_cc_algorithm)algo);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1max_1recv_1udp_1payload_1size(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong size) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_max_recv_udp_payload_size(config, (size_t)size);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1set_1max_1send_1udp_1payload_1size(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong size) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_set_max_send_udp_payload_size(config, (size_t)size);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1config_1free(
        JNIEnv *env, jclass cls, jlong config_ptr) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    quiche_config_free(config);
}

/* ── Connection lifecycle ── */

/*
 * Decodes a Java address byte array into a struct sockaddr_storage.
 * Format: [family (1)][port (2, big-endian)][addr (4 or 16)]
 * Returns the sockaddr length, or 0 on error.
 */
static socklen_t decode_address(JNIEnv *env, jbyteArray addr_arr,
                                struct sockaddr_storage *ss) {
    jsize arr_len = (*env)->GetArrayLength(env, addr_arr);
    jbyte *bytes = (*env)->GetByteArrayElements(env, addr_arr, NULL);
    if (bytes == NULL) {
        return 0;
    }

    memset(ss, 0, sizeof(*ss));
    uint8_t family = (uint8_t)bytes[0];
    uint16_t port = (uint16_t)(((uint8_t)bytes[1] << 8) |
                                (uint8_t)bytes[2]);
    socklen_t sa_len = 0;

    if (family == 4 && arr_len >= 7) {
        struct sockaddr_in *sin = (struct sockaddr_in *)ss;
        sin->sin_family = AF_INET;
        sin->sin_port = htons(port);
        memcpy(&sin->sin_addr, bytes + 3, 4);
        sa_len = sizeof(struct sockaddr_in);
    } else if (family == 6 && arr_len >= 19) {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ss;
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(port);
        memcpy(&sin6->sin6_addr, bytes + 3, 16);
        sa_len = sizeof(struct sockaddr_in6);
    }

    (*env)->ReleaseByteArrayElements(env, addr_arr, bytes, JNI_ABORT);
    return sa_len;
}

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1new_1with_1tls(
        JNIEnv *env, jclass cls,
        jbyteArray scid, jbyteArray odcid,
        jbyteArray local_addr, jbyteArray peer_addr,
        jlong config_ptr, jlong ssl_ptr, jboolean is_server) {
    quiche_config *config = (quiche_config *)(intptr_t)config_ptr;
    SSL *ssl = (SSL *)(intptr_t)ssl_ptr;

    jbyte *scid_buf = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize scid_len = (*env)->GetArrayLength(env, scid);

    jbyte *odcid_buf = NULL;
    jsize odcid_len = 0;
    if (odcid != NULL) {
        odcid_buf = (*env)->GetByteArrayElements(env, odcid, NULL);
        odcid_len = (*env)->GetArrayLength(env, odcid);
    }

    struct sockaddr_storage local_ss, peer_ss;
    socklen_t local_len = decode_address(env, local_addr, &local_ss);
    socklen_t peer_len = decode_address(env, peer_addr, &peer_ss);

    quiche_conn *conn = quiche_conn_new_with_tls(
            (const uint8_t *)scid_buf, (size_t)scid_len,
            odcid_buf != NULL ? (const uint8_t *)odcid_buf : NULL,
            (size_t)odcid_len,
            (struct sockaddr *)&local_ss, local_len,
            (struct sockaddr *)&peer_ss, peer_len,
            config, ssl, is_server == JNI_TRUE);

    (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
    if (odcid_buf != NULL) {
        (*env)->ReleaseByteArrayElements(env, odcid, odcid_buf, JNI_ABORT);
    }

    return (jlong)(intptr_t)conn;
}

/* ── Packet I/O (zero-copy via direct ByteBuffer) ── */

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1recv(
        JNIEnv *env, jclass cls, jlong conn_ptr,
        jobject buf, jint len,
        jbyteArray from_addr, jbyteArray to_addr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return -1;
    }

    struct sockaddr_storage from_ss, to_ss;
    socklen_t from_len = decode_address(env, from_addr, &from_ss);
    socklen_t to_len = decode_address(env, to_addr, &to_ss);

    quiche_recv_info recv_info;
    recv_info.from = (struct sockaddr *)&from_ss;
    recv_info.from_len = from_len;
    recv_info.to = (struct sockaddr *)&to_ss;
    recv_info.to_len = to_len;

    ssize_t recv_len = quiche_conn_recv(conn, data, (size_t)len,
                                         &recv_info);
    return (jint)recv_len;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1send(
        JNIEnv *env, jclass cls, jlong conn_ptr,
        jobject buf, jint len) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return -1;
    }

    quiche_send_info send_info;
    ssize_t written = quiche_conn_send(conn, data, (size_t)len,
                                        &send_info);
    return (jint)written;
}

/* ── Stream I/O (zero-copy via direct ByteBuffer) ── */

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1stream_1recv(
        JNIEnv *env, jclass cls, jlong conn_ptr, jlong stream_id,
        jobject buf, jint len, jbooleanArray fin) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return -1;
    }
    bool is_fin = false;
    uint64_t error_code = 0;
    ssize_t recv_len = quiche_conn_stream_recv(conn,
                                                (uint64_t)stream_id,
                                                data, (size_t)len,
                                                &is_fin, &error_code);
    if (recv_len >= 0) {
        jboolean jfin = is_fin ? JNI_TRUE : JNI_FALSE;
        (*env)->SetBooleanArrayRegion(env, fin, 0, 1, &jfin);
    }
    return (jint)recv_len;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1stream_1send(
        JNIEnv *env, jclass cls, jlong conn_ptr, jlong stream_id,
        jobject buf, jint len, jboolean fin) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return -1;
    }
    uint64_t error_code = 0;
    ssize_t sent = quiche_conn_stream_send(conn,
                                            (uint64_t)stream_id,
                                            data, (size_t)len,
                                            fin == JNI_TRUE,
                                            &error_code);
    return (jint)sent;
}

/* ── Polling and timers ── */

JNIEXPORT jlongArray JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1readable(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    quiche_stream_iter *iter = quiche_conn_readable(conn);
    if (iter == NULL) {
        return (*env)->NewLongArray(env, 0);
    }

    /* Collect stream IDs into a temporary buffer */
    uint64_t ids[256];
    int count = 0;
    uint64_t id;
    while (quiche_stream_iter_next(iter, &id) && count < 256) {
        ids[count++] = id;
    }
    quiche_stream_iter_free(iter);

    jlongArray result = (*env)->NewLongArray(env, count);
    if (count > 0) {
        jlong *elems = (*env)->GetLongArrayElements(env, result, NULL);
        int i;
        for (i = 0; i < count; i++) {
            elems[i] = (jlong)ids[i];
        }
        (*env)->ReleaseLongArrayElements(env, result, elems, 0);
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1timeout_1as_1millis(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    return (jlong)quiche_conn_timeout_as_millis(conn);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1on_1timeout(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    quiche_conn_on_timeout(conn);
}

JNIEXPORT jboolean JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1is_1established(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    return quiche_conn_is_established(conn) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1is_1closed(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    return quiche_conn_is_closed(conn) ? JNI_TRUE : JNI_FALSE;
}

/* ── Header parsing ── */

JNIEXPORT jbyteArray JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1header_1info(
        JNIEnv *env, jclass cls, jobject buf, jint len) {
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return NULL;
    }

    uint8_t type;
    uint32_t version;
    uint8_t scid[QUICHE_MAX_CONN_ID_LEN];
    size_t scid_len = sizeof(scid);
    uint8_t dcid[QUICHE_MAX_CONN_ID_LEN];
    size_t dcid_len = sizeof(dcid);
    uint8_t token[64];
    size_t token_len = sizeof(token);

    int rc = quiche_header_info(data, (size_t)len,
                                 QUICHE_MAX_CONN_ID_LEN,
                                 &version, &type,
                                 scid, &scid_len,
                                 dcid, &dcid_len,
                                 token, &token_len);
    if (rc < 0) {
        return NULL;
    }

    /*
     * Return format:
     *   [version (4 bytes, big-endian)]
     *   [type (1 byte)]
     *   [dcid_len (1 byte)][dcid (dcid_len bytes)]
     *   [scid_len (1 byte)][scid (scid_len bytes)]
     *   [token_len (1 byte)][token (token_len bytes)]
     */
    jsize result_len = 4 + 1
            + 1 + (jsize)dcid_len
            + 1 + (jsize)scid_len
            + 1 + (jsize)token_len;
    jbyteArray result = (*env)->NewByteArray(env, result_len);
    if (result == NULL) {
        return NULL;
    }

    jbyte ver_bytes[4];
    ver_bytes[0] = (jbyte)((version >> 24) & 0xFF);
    ver_bytes[1] = (jbyte)((version >> 16) & 0xFF);
    ver_bytes[2] = (jbyte)((version >>  8) & 0xFF);
    ver_bytes[3] = (jbyte)( version        & 0xFF);
    jsize off = 0;
    (*env)->SetByteArrayRegion(env, result, off, 4, ver_bytes);
    off += 4;

    jbyte type_byte = (jbyte)type;
    (*env)->SetByteArrayRegion(env, result, off, 1, &type_byte);
    off += 1;

    jbyte dcid_len_byte = (jbyte)dcid_len;
    (*env)->SetByteArrayRegion(env, result, off, 1, &dcid_len_byte);
    off += 1;
    (*env)->SetByteArrayRegion(env, result, off, (jsize)dcid_len,
                               (jbyte *)dcid);
    off += (jsize)dcid_len;

    jbyte scid_len_byte = (jbyte)scid_len;
    (*env)->SetByteArrayRegion(env, result, off, 1, &scid_len_byte);
    off += 1;
    (*env)->SetByteArrayRegion(env, result, off, (jsize)scid_len,
                               (jbyte *)scid);
    off += (jsize)scid_len;

    jbyte token_len_byte = (jbyte)token_len;
    (*env)->SetByteArrayRegion(env, result, off, 1, &token_len_byte);
    off += 1;
    (*env)->SetByteArrayRegion(env, result, off, (jsize)token_len,
                               (jbyte *)token);

    return result;
}

/* ── Version negotiation ── */

JNIEXPORT jboolean JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1version_1is_1supported(
        JNIEnv *env, jclass cls, jint version) {
    return quiche_version_is_supported((uint32_t)version) ? JNI_TRUE
                                                          : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1negotiate_1version(
        JNIEnv *env, jclass cls,
        jbyteArray scid, jbyteArray dcid,
        jobject out, jint out_len) {
    jbyte *scid_buf = (*env)->GetByteArrayElements(env, scid, NULL);
    jsize scid_len = (*env)->GetArrayLength(env, scid);
    jbyte *dcid_buf = (*env)->GetByteArrayElements(env, dcid, NULL);
    jsize dcid_len = (*env)->GetArrayLength(env, dcid);

    uint8_t *out_data =
            (uint8_t *)(*env)->GetDirectBufferAddress(env, out);
    if (out_data == NULL) {
        (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, dcid, dcid_buf, JNI_ABORT);
        return -1;
    }

    ssize_t written = quiche_negotiate_version(
            (const uint8_t *)scid_buf, (size_t)scid_len,
            (const uint8_t *)dcid_buf, (size_t)dcid_len,
            out_data, (size_t)out_len);

    (*env)->ReleaseByteArrayElements(env, scid, scid_buf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dcid, dcid_buf, JNI_ABORT);
    return (jint)written;
}

/* ── Security info ── */

JNIEXPORT jstring JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1get_1cipher_1name(
        JNIEnv *env, jclass cls, jlong ssl_ptr) {
    SSL *ssl = (SSL *)(intptr_t)ssl_ptr;
    if (ssl == NULL) {
        return NULL;
    }
    const char *cipher = SSL_get_cipher_name(ssl);
    if (cipher == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, cipher);
}

JNIEXPORT jbyteArray JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1peer_1cert(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    const uint8_t *cert_data = NULL;
    size_t cert_len = 0;
    quiche_conn_peer_cert(conn, &cert_data, &cert_len);
    if (cert_data == NULL || cert_len == 0) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)cert_len);
    if (result == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)cert_len,
                               (const jbyte *)cert_data);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1application_1proto(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    const uint8_t *proto = NULL;
    size_t proto_len = 0;
    quiche_conn_application_proto(conn, &proto, &proto_len);
    if (proto == NULL || proto_len == 0) {
        return NULL;
    }

    /* ALPN protocol bytes need null-termination for NewStringUTF */
    char *proto_str = (char *)malloc(proto_len + 1);
    if (proto_str == NULL) {
        return NULL;
    }
    memcpy(proto_str, proto, proto_len);
    proto_str[proto_len] = '\0';
    jstring result = (*env)->NewStringUTF(env, proto_str);
    free(proto_str);
    return result;
}

/* ── Debug logging ── */

static void quiche_log_callback(const char *line, void *argp) {
    fprintf(stderr, "[quiche] %s\n", line);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1enable_1debug_1logging(
        JNIEnv *env, jclass cls) {
    quiche_enable_debug_logging(quiche_log_callback, NULL);
}

/* ── Cleanup ── */

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1conn_1free(
        JNIEnv *env, jclass cls, jlong conn_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)conn_ptr;
    quiche_conn_free(conn);
}
