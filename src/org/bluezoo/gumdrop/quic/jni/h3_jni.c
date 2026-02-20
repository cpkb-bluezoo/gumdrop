/*
 * h3_jni.c
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
 * JNI bindings for quiche's HTTP/3 (h3) module.
 *
 * These functions implement the native methods declared in QuicheNative.java
 * for HTTP/3 config, connection management, event polling, header/body I/O,
 * and request/response sending. All h3 operations are built on top of an
 * existing quiche QUIC connection.
 *
 * Build: compile alongside quiche_jni.c and ssl_ctx_jni.c, linking with
 * quiche built with the "ffi" and "h3" features enabled.
 */

#include <jni.h>
#include <quiche.h>
#include <string.h>
#include <stdlib.h>

/*
 * Thread-local storage for the most recently polled h3 event.
 * quiche_h3_conn_poll() returns an event that must be inspected before
 * the next poll call. We store it here so that quiche_h3_event_headers()
 * can retrieve the header list from the same event.
 */
static __thread quiche_h3_event *current_event = NULL;

/* ── HTTP/3 Config ── */

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1config_1new(
        JNIEnv *env, jclass cls) {
    quiche_h3_config *config = quiche_h3_config_new();
    return (jlong)(intptr_t)config;
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1config_1free(
        JNIEnv *env, jclass cls, jlong config_ptr) {
    quiche_h3_config *config = (quiche_h3_config *)(intptr_t)config_ptr;
    quiche_h3_config_free(config);
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1config_1set_1max_1dynamic_1table_1capacity(
        JNIEnv *env, jclass cls, jlong config_ptr, jlong capacity) {
    quiche_h3_config *config = (quiche_h3_config *)(intptr_t)config_ptr;
    quiche_h3_config_set_max_field_section_size(config,
                                                 (uint64_t)capacity);
}

/* ── HTTP/3 Connection ── */

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1conn_1new_1with_1transport(
        JNIEnv *env, jclass cls, jlong quiche_conn_ptr,
        jlong h3_config_ptr) {
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;
    quiche_h3_config *config =
            (quiche_h3_config *)(intptr_t)h3_config_ptr;
    quiche_h3_conn *h3 = quiche_h3_conn_new_with_transport(conn, config);
    return (jlong)(intptr_t)h3;
}

JNIEXPORT void JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1conn_1free(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_h3_conn_free(h3);
    if (current_event != NULL) {
        quiche_h3_event_free(current_event);
        current_event = NULL;
    }
}

/* ── Event Polling ── */

JNIEXPORT jlongArray JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1conn_1poll(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr,
        jlong quiche_conn_ptr) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;

    /* Free previous event if any */
    if (current_event != NULL) {
        quiche_h3_event_free(current_event);
        current_event = NULL;
    }

    quiche_h3_event *ev = NULL;
    int64_t stream_id = quiche_h3_conn_poll(h3, conn, &ev);
    if (stream_id < 0) {
        return NULL;
    }

    current_event = ev;

    int event_type;
    switch (quiche_h3_event_type(ev)) {
        case QUICHE_H3_EVENT_HEADERS:
            event_type = 0;
            break;
        case QUICHE_H3_EVENT_DATA:
            event_type = 1;
            break;
        case QUICHE_H3_EVENT_FINISHED:
            event_type = 2;
            break;
        case QUICHE_H3_EVENT_GOAWAY:
            event_type = 3;
            break;
        case QUICHE_H3_EVENT_RESET:
            event_type = 4;
            break;
        default:
            event_type = -1;
            break;
    }

    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result == NULL) {
        return NULL;
    }
    jlong elems[2];
    elems[0] = (jlong)stream_id;
    elems[1] = (jlong)event_type;
    (*env)->SetLongArrayRegion(env, result, 0, 2, elems);
    return result;
}

/* ── Header retrieval ── */

/*
 * Callback for quiche_h3_event_for_each_header(). Collects header
 * name/value pairs into a dynamically growing array.
 */
struct header_collector {
    JNIEnv *env;
    jobjectArray array;
    int count;
    int capacity;
    /* Temporary storage for name/value strings */
    char **names;
    char **values;
};

static int header_cb(uint8_t *name, size_t name_len,
                     uint8_t *value, size_t value_len,
                     void *argp) {
    struct header_collector *hc = (struct header_collector *)argp;

    if (hc->count >= hc->capacity) {
        int new_cap = hc->capacity * 2;
        char **new_names = realloc(hc->names, new_cap * sizeof(char *));
        char **new_values = realloc(hc->values, new_cap * sizeof(char *));
        if (new_names == NULL || new_values == NULL) {
            free(new_names);
            free(new_values);
            return -1;
        }
        hc->names = new_names;
        hc->values = new_values;
        hc->capacity = new_cap;
    }

    char *n = malloc(name_len + 1);
    char *v = malloc(value_len + 1);
    if (n == NULL || v == NULL) {
        free(n);
        free(v);
        return -1;
    }
    memcpy(n, name, name_len);
    n[name_len] = '\0';
    memcpy(v, value, value_len);
    v[value_len] = '\0';

    hc->names[hc->count] = n;
    hc->values[hc->count] = v;
    hc->count++;
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1event_1headers(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr) {
    if (current_event == NULL) {
        return NULL;
    }

    struct header_collector hc;
    hc.env = env;
    hc.count = 0;
    hc.capacity = 32;
    hc.names = malloc(hc.capacity * sizeof(char *));
    hc.values = malloc(hc.capacity * sizeof(char *));
    if (hc.names == NULL || hc.values == NULL) {
        free(hc.names);
        free(hc.values);
        return NULL;
    }

    int rc = quiche_h3_event_for_each_header(current_event, header_cb,
                                              &hc);
    if (rc != 0) {
        int i;
        for (i = 0; i < hc.count; i++) {
            free(hc.names[i]);
            free(hc.values[i]);
        }
        free(hc.names);
        free(hc.values);
        return NULL;
    }

    /* Build a flat String[] of [name0, value0, name1, value1, ...] */
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, hc.count * 2,
                                                  string_class, NULL);
    if (result != NULL) {
        int i;
        for (i = 0; i < hc.count; i++) {
            jstring jname = (*env)->NewStringUTF(env, hc.names[i]);
            jstring jvalue = (*env)->NewStringUTF(env, hc.values[i]);
            (*env)->SetObjectArrayElement(env, result, i * 2, jname);
            (*env)->SetObjectArrayElement(env, result, i * 2 + 1,
                                          jvalue);
            (*env)->DeleteLocalRef(env, jname);
            (*env)->DeleteLocalRef(env, jvalue);
        }
    }

    int i;
    for (i = 0; i < hc.count; i++) {
        free(hc.names[i]);
        free(hc.values[i]);
    }
    free(hc.names);
    free(hc.values);

    return result;
}

/* ── Body I/O ── */

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1recv_1body(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr,
        jlong quiche_conn_ptr, jlong stream_id,
        jobject buf, jint len) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;
    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data == NULL) {
        return -1;
    }

    ssize_t recv_len = quiche_h3_recv_body(h3, conn,
                                            (uint64_t)stream_id,
                                            data, (size_t)len);
    return (jint)recv_len;
}

/* ── Response Sending ── */

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1send_1response(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr,
        jlong quiche_conn_ptr, jlong stream_id,
        jobjectArray headers, jboolean fin) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;

    jsize header_count = (*env)->GetArrayLength(env, headers);
    jsize num_headers = header_count / 2;

    quiche_h3_header *h3_headers =
            malloc(num_headers * sizeof(quiche_h3_header));
    if (h3_headers == NULL) {
        return -1;
    }

    jsize i;
    for (i = 0; i < num_headers; i++) {
        jstring jname = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2);
        jstring jvalue = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2 + 1);

        const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
        const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

        h3_headers[i].name = (const uint8_t *)name;
        h3_headers[i].name_len = strlen(name);
        h3_headers[i].value = (const uint8_t *)value;
        h3_headers[i].value_len = strlen(value);
    }

    int rc = quiche_h3_send_response(h3, conn, (uint64_t)stream_id,
                                      h3_headers, num_headers,
                                      fin == JNI_TRUE);

    /* Release all string references */
    for (i = 0; i < num_headers; i++) {
        jstring jname = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2);
        jstring jvalue = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2 + 1);

        (*env)->ReleaseStringUTFChars(
                env, jname, (const char *)h3_headers[i].name);
        (*env)->ReleaseStringUTFChars(
                env, jvalue, (const char *)h3_headers[i].value);
        (*env)->DeleteLocalRef(env, jname);
        (*env)->DeleteLocalRef(env, jvalue);
    }

    free(h3_headers);
    return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1send_1body(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr,
        jlong quiche_conn_ptr, jlong stream_id,
        jobject buf, jint len, jboolean fin) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;

    if (len == 0) {
        ssize_t written = quiche_h3_send_body(h3, conn,
                                               (uint64_t)stream_id,
                                               NULL, 0,
                                               fin == JNI_TRUE);
        return (jint)written;
    }

    jint pos = (*env)->CallIntMethod(env,
            buf, (*env)->GetMethodID(env,
                    (*env)->GetObjectClass(env, buf), "position", "()I"));

    uint8_t *data = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf);
    if (data != NULL) {
        data += pos;
        ssize_t written = quiche_h3_send_body(h3, conn,
                                               (uint64_t)stream_id,
                                               data, (size_t)len,
                                               fin == JNI_TRUE);
        return (jint)written;
    }

    /* Non-direct buffer: copy to temporary array */
    jclass cls_buf = (*env)->GetObjectClass(env, buf);
    jmethodID mid_array = (*env)->GetMethodID(env, cls_buf, "array", "()[B");
    jmethodID mid_offset = (*env)->GetMethodID(env, cls_buf,
                                                "arrayOffset", "()I");
    if (mid_array == NULL || mid_offset == NULL) {
        (*env)->ExceptionClear(env);
        return QUICHE_ERR_DONE;
    }

    jbyteArray arr = (jbyteArray)(*env)->CallObjectMethod(env, buf, mid_array);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return QUICHE_ERR_DONE;
    }
    jint offset = (*env)->CallIntMethod(env, buf, mid_offset) + pos;

    jbyte *bytes = (*env)->GetByteArrayElements(env, arr, NULL);
    if (bytes == NULL) {
        return QUICHE_ERR_DONE;
    }

    ssize_t written = quiche_h3_send_body(h3, conn,
                                           (uint64_t)stream_id,
                                           (uint8_t *)bytes + offset,
                                           (size_t)len,
                                           fin == JNI_TRUE);
    (*env)->ReleaseByteArrayElements(env, arr, bytes, JNI_ABORT);
    return (jint)written;
}

/* ── Request Sending (client-side) ── */

JNIEXPORT jlong JNICALL
Java_org_bluezoo_gumdrop_quic_QuicheNative_quiche_1h3_1send_1request(
        JNIEnv *env, jclass cls, jlong h3_conn_ptr,
        jlong quiche_conn_ptr, jobjectArray headers,
        jboolean fin) {
    quiche_h3_conn *h3 = (quiche_h3_conn *)(intptr_t)h3_conn_ptr;
    quiche_conn *conn = (quiche_conn *)(intptr_t)quiche_conn_ptr;

    jsize header_count = (*env)->GetArrayLength(env, headers);
    jsize num_headers = header_count / 2;

    quiche_h3_header *h3_headers =
            malloc(num_headers * sizeof(quiche_h3_header));
    if (h3_headers == NULL) {
        return -1;
    }

    jsize i;
    for (i = 0; i < num_headers; i++) {
        jstring jname = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2);
        jstring jvalue = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2 + 1);

        const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
        const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

        h3_headers[i].name = (const uint8_t *)name;
        h3_headers[i].name_len = strlen(name);
        h3_headers[i].value = (const uint8_t *)value;
        h3_headers[i].value_len = strlen(value);
    }

    int64_t stream_id = quiche_h3_send_request(h3, conn,
                                                h3_headers, num_headers,
                                                fin == JNI_TRUE);

    /* Release all string references */
    for (i = 0; i < num_headers; i++) {
        jstring jname = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2);
        jstring jvalue = (jstring)(*env)->GetObjectArrayElement(
                env, headers, i * 2 + 1);

        (*env)->ReleaseStringUTFChars(
                env, jname, (const char *)h3_headers[i].name);
        (*env)->ReleaseStringUTFChars(
                env, jvalue, (const char *)h3_headers[i].value);
        (*env)->DeleteLocalRef(env, jname);
        (*env)->DeleteLocalRef(env, jvalue);
    }

    free(h3_headers);
    return (jlong)stream_id;
}
