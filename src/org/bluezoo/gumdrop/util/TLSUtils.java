/*
 * TLSUtils.java
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

package org.bluezoo.gumdrop.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Centralised utilities for TLS keystore and truststore loading.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TLSUtils {

    private static final ConcurrentHashMap<Path, CachedKeyStore> keystoreCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, KeyManager[]> keyManagerCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TrustManager[]> trustManagerCache = new ConcurrentHashMap<>();

    private static final class CachedKeyStore {
        final KeyStore keyStore;
        final long lastModified;

        CachedKeyStore(KeyStore keyStore, long lastModified) {
            this.keyStore = keyStore;
            this.lastModified = lastModified;
        }
    }

    private TLSUtils() {
    }

    /**
     * Loads a {@link KeyStore} from the given file path.
     *
     * @param path the keystore/truststore file
     * @param password the store password
     * @param format the store format (e.g. "PKCS12", "JKS")
     * @return the loaded KeyStore
     * @throws GeneralSecurityException if the store cannot be initialised
     * @throws IOException if the file cannot be read
     */
    public static KeyStore loadKeyStore(Path path, String password, String format)
            throws GeneralSecurityException, IOException {
        Path canonicalPath = path.normalize().toAbsolutePath();
        long lastModified = Files.getLastModifiedTime(canonicalPath).toMillis();
        CachedKeyStore cached = keystoreCache.get(canonicalPath);
        if (cached != null && cached.lastModified == lastModified) {
            return cached.keyStore;
        }
        KeyStore ks = KeyStore.getInstance(format);
        try (InputStream in = Files.newInputStream(canonicalPath)) {
            ks.load(in, password.toCharArray());
        }
        keystoreCache.put(canonicalPath, new CachedKeyStore(ks, lastModified));
        String prefix = canonicalPath.toString() + "|";
        keyManagerCache.keySet().removeIf(k -> k.startsWith(prefix));
        trustManagerCache.keySet().removeIf(k -> k.startsWith(prefix));
        return ks;
    }

    /**
     * Creates {@link KeyManager}s from a keystore file.
     *
     * <p>Cache key uses path and format only (not password) to avoid credential
     * exposure in heap dumps. If the same keystore file is loaded with different
     * passwords without file modification, the cache may return previously loaded
     * managers; in practice path+format uniquely identifies a deployment's keystore.
     *
     * @param path the keystore file
     * @param password the keystore password
     * @param format the keystore format (e.g. "PKCS12")
     * @return the key managers
     * @throws GeneralSecurityException if initialisation fails
     * @throws IOException if the file cannot be read
     */
    public static KeyManager[] loadKeyManagers(Path path, String password, String format)
            throws GeneralSecurityException, IOException {
        Path canonicalPath = path.normalize().toAbsolutePath();
        String cacheKey = canonicalPath.toString() + "|" + format;
        KeyManager[] cached = keyManagerCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        KeyStore ks = loadKeyStore(path, password, format);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());
        KeyManager[] managers = kmf.getKeyManagers();
        keyManagerCache.put(cacheKey, managers);
        return managers;
    }

    /**
     * Creates {@link TrustManager}s from a truststore file.
     *
     * <p>Cache key uses path and format only (not password) to avoid credential
     * exposure in heap dumps. If the same truststore file is loaded with different
     * passwords without file modification, the cache may return previously loaded
     * managers; in practice path+format uniquely identifies a deployment's truststore.
     *
     * @param path the truststore file
     * @param password the truststore password
     * @param format the truststore format (e.g. "PKCS12")
     * @return the trust managers
     * @throws GeneralSecurityException if initialisation fails
     * @throws IOException if the file cannot be read
     */
    public static TrustManager[] loadTrustManagers(Path path, String password, String format)
            throws GeneralSecurityException, IOException {
        Path canonicalPath = path.normalize().toAbsolutePath();
        String cacheKey = canonicalPath.toString() + "|" + format;
        TrustManager[] cached = trustManagerCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        KeyStore ts = loadKeyStore(path, password, format);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        TrustManager[] managers = tmf.getTrustManagers();
        trustManagerCache.put(cacheKey, managers);
        return managers;
    }
}
