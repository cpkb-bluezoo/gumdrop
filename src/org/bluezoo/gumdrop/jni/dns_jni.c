/*
 * dns_jni.c
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
 * JNI native implementation for platform DNS nameserver discovery.
 *
 * Linux / macOS: uses res_init() to populate the resolver state,
 * then reads _res.nsaddr_list / _res.nscount.
 *
 * Windows: uses GetNetworkParams() from iphlpapi to iterate the
 * DNS server list.
 */

#include <jni.h>

#ifdef _WIN32
#include <windows.h>
#include <iphlpapi.h>
#pragma comment(lib, "iphlpapi.lib")
#else
#include <resolv.h>
#include <arpa/inet.h>
#include <string.h>
#endif

JNIEXPORT jobjectArray JNICALL
Java_org_bluezoo_gumdrop_GumdropNative_getSystemNameservers(
        JNIEnv *env, jclass cls) {

#ifdef _WIN32
    FIXED_INFO *info = NULL;
    ULONG bufLen = 0;
    DWORD ret;

    ret = GetNetworkParams(NULL, &bufLen);
    if (ret != ERROR_BUFFER_OVERFLOW) {
        return NULL;
    }

    info = (FIXED_INFO *)malloc(bufLen);
    if (info == NULL) {
        return NULL;
    }

    ret = GetNetworkParams(info, &bufLen);
    if (ret != NO_ERROR) {
        free(info);
        return NULL;
    }

    /* Count servers */
    int count = 0;
    IP_ADDR_STRING *addr = &info->DnsServerList;
    while (addr != NULL) {
        if (addr->IpAddress.String[0] != '\0') {
            count++;
        }
        addr = addr->Next;
    }

    if (count == 0) {
        free(info);
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count,
            stringClass, NULL);

    int i = 0;
    addr = &info->DnsServerList;
    while (addr != NULL && i < count) {
        if (addr->IpAddress.String[0] != '\0') {
            jstring jaddr = (*env)->NewStringUTF(env,
                    addr->IpAddress.String);
            (*env)->SetObjectArrayElement(env, result, i, jaddr);
            (*env)->DeleteLocalRef(env, jaddr);
            i++;
        }
        addr = addr->Next;
    }

    free(info);
    return result;

#else
    /* POSIX: Linux, macOS, BSD */
    if (res_init() != 0) {
        return NULL;
    }

    int nscount = _res.nscount;
    if (nscount <= 0) {
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, nscount,
            stringClass, NULL);

    for (int i = 0; i < nscount; i++) {
        char buf[INET6_ADDRSTRLEN];
        const char *addr = NULL;
        struct sockaddr_in *sa =
                (struct sockaddr_in *)&_res.nsaddr_list[i];
        addr = inet_ntop(AF_INET, &sa->sin_addr, buf, sizeof(buf));
        if (addr == NULL) {
            continue;
        }
        jstring jaddr = (*env)->NewStringUTF(env, addr);
        (*env)->SetObjectArrayElement(env, result, i, jaddr);
        (*env)->DeleteLocalRef(env, jaddr);
    }

    return result;
#endif
}
