/*
 * ssl_ctx_jni.c
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
 * JNI bindings for BoringSSL SSL_CTX management.
 *
 * These functions create and configure a BoringSSL SSL_CTX with specific
 * cipher suites and key exchange groups (including PQC groups such as
 * X25519MLKEM768). The configured SSL_CTX produces SSL objects that are
 * passed to quiche_conn_new_with_tls() for full control over the TLS 1.3
 * parameters used by QUIC connections.
 *
 * Build: see the project README for compilation instructions.
 */

#include <jni.h>
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <stdlib.h>
#include <string.h>

/* ── SSL_CTX management ── */

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1new(
        JNIEnv *env, jclass cls, jboolean is_server) {
    const SSL_METHOD *method = TLS_method();
    SSL_CTX *ctx = SSL_CTX_new(method);
    if (ctx == NULL) {
        return 0;
    }

    /* QUIC requires TLS 1.3 minimum */
    SSL_CTX_set_min_proto_version(ctx, TLS1_3_VERSION);
    SSL_CTX_set_max_proto_version(ctx, TLS1_3_VERSION);

    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1load_1cert_1chain(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jstring path) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int ret = SSL_CTX_use_certificate_chain_file(ctx, c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return ret == 1 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1load_1priv_1key(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jstring path) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int ret = SSL_CTX_use_PrivateKey_file(ctx, c_path, SSL_FILETYPE_PEM);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return ret == 1 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1load_1verify_1locations(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jstring path) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    int ret = SSL_CTX_load_verify_locations(ctx, c_path, NULL);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return ret == 1 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1set_1ciphersuites(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jstring ciphers) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    const char *c_ciphers = (*env)->GetStringUTFChars(env, ciphers, NULL);
    int ret = SSL_CTX_set_strict_cipher_list(ctx, c_ciphers);
    (*env)->ReleaseStringUTFChars(env, ciphers, c_ciphers);
    return ret == 1 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1set_1groups(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jstring groups) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    const char *c_groups = (*env)->GetStringUTFChars(env, groups, NULL);
    int ret = SSL_CTX_set1_curves_list(ctx, c_groups);
    (*env)->ReleaseStringUTFChars(env, groups, c_groups);
    return ret == 1 ? 0 : -1;
}

/* ── Server-side ALPN selection ── */

/* Per-SSL_CTX storage for the supported ALPN wire-format bytes. */
typedef struct {
    unsigned char *data;
    unsigned int len;
} alpn_protos_t;

static int ssl_ctx_ex_data_index = -1;

static void alpn_protos_free(void *parent, void *ptr, CRYPTO_EX_DATA *ad,
                             int index, long argl, void *argp) {
    alpn_protos_t *protos = (alpn_protos_t *)ptr;
    if (protos != NULL) {
        free(protos->data);
        free(protos);
    }
}

static int alpn_select_cb(SSL *ssl, const unsigned char **out,
                          unsigned char *outlen,
                          const unsigned char *in, unsigned int inlen,
                          void *arg) {
    SSL_CTX *ctx = SSL_get_SSL_CTX(ssl);
    alpn_protos_t *protos =
            (alpn_protos_t *)SSL_CTX_get_ex_data(ctx,
                                                  ssl_ctx_ex_data_index);
    if (protos == NULL) {
        return SSL_TLSEXT_ERR_NOACK;
    }

    if (SSL_select_next_proto((unsigned char **)out, outlen,
                              protos->data, protos->len,
                              in, inlen) != OPENSSL_NPN_NEGOTIATED) {
        return SSL_TLSEXT_ERR_NOACK;
    }
    return SSL_TLSEXT_ERR_OK;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1set_1alpn_1protos(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jbyteArray protos) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    jbyte *buf = (*env)->GetByteArrayElements(env, protos, NULL);
    jsize len = (*env)->GetArrayLength(env, protos);

    /* Client-side: advertise these protocols */
    int ret = SSL_CTX_set_alpn_protos(ctx, (const unsigned char *)buf,
                                       (unsigned int)len);

    /* Server-side: install selection callback with the same protocols */
    if (ssl_ctx_ex_data_index < 0) {
        ssl_ctx_ex_data_index = SSL_CTX_get_ex_new_index(
                0, NULL, NULL, NULL, alpn_protos_free);
    }

    alpn_protos_t *ap = (alpn_protos_t *)malloc(sizeof(alpn_protos_t));
    ap->data = (unsigned char *)malloc(len);
    memcpy(ap->data, buf, len);
    ap->len = (unsigned int)len;
    SSL_CTX_set_ex_data(ctx, ssl_ctx_ex_data_index, ap);
    SSL_CTX_set_alpn_select_cb(ctx, alpn_select_cb, NULL);

    (*env)->ReleaseByteArrayElements(env, protos, buf, JNI_ABORT);
    return ret == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1set_1verify_1peer(
        JNIEnv *env, jclass cls, jlong ctx_ptr, jboolean verify) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    int mode = verify ? SSL_VERIFY_PEER : SSL_VERIFY_NONE;
    SSL_CTX_set_verify(ctx, mode, NULL);
}

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1new(
        JNIEnv *env, jclass cls, jlong ctx_ptr) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    SSL *ssl = SSL_new(ctx);
    return (jlong)(intptr_t)ssl;
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1set_1hostname(
        JNIEnv *env, jclass cls, jlong ssl_ptr, jstring hostname) {
    SSL *ssl = (SSL *)(intptr_t)ssl_ptr;
    const char *c_hostname = (*env)->GetStringUTFChars(env, hostname, NULL);
    SSL_set_tlsext_host_name(ssl, c_hostname);
    (*env)->ReleaseStringUTFChars(env, hostname, c_hostname);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_ssl_1ctx_1free(
        JNIEnv *env, jclass cls, jlong ctx_ptr) {
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    SSL_CTX_free(ctx);
}
