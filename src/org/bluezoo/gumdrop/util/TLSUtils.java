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
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.bluezoo.gumdrop.tls.ServerCredentials;

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
        for (Iterator<String> it = keyManagerCache.keySet().iterator();
                it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
        for (Iterator<String> it = trustManagerCache.keySet().iterator();
                it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
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

    /**
     * Extracts server credentials (certificate chain + private key) from a
     * keystore's first private-key entry -- the {@link ServerCredentials}
     * equivalent of {@link #loadKeyManagers}, for the in-tree TLS engine,
     * which takes a chain and key directly rather than a JSSE
     * {@link KeyManager}.
     *
     * @param path the keystore file
     * @param password the keystore password (also the private key password)
     * @param format the keystore format (e.g. "PKCS12")
     * @return the server credentials
     * @throws GeneralSecurityException if no private key entry is found,
     *         or the entry cannot be read
     * @throws IOException if the file cannot be read
     */
    public static ServerCredentials loadServerCredentials(Path path, String password, String format)
            throws GeneralSecurityException, IOException {
        return loadServerCredentials(path, password, format, null);
    }

    /**
     * Extracts server credentials (certificate chain + private key) from a
     * keystore, for a specific alias.
     *
     * @param path the keystore file
     * @param password the keystore password (also the private key password)
     * @param format the keystore format (e.g. "PKCS12")
     * @param alias the alias to extract, or null to use the keystore's
     *              first private-key entry
     * @return the server credentials
     * @throws GeneralSecurityException if the alias (or, when null, no
     *         alias at all) does not name a private key entry
     * @throws IOException if the file cannot be read
     */
    public static ServerCredentials loadServerCredentials(Path path, String password, String format, String alias)
            throws GeneralSecurityException, IOException {
        KeyStore ks = loadKeyStore(path, password, format);
        return loadServerCredentials(ks, password, alias);
    }

    /**
     * Extracts server credentials (certificate chain + private key) from
     * an already-loaded keystore, for a specific alias -- the
     * already-loaded-{@link KeyStore} counterpart of
     * {@link #loadServerCredentials(Path, String, String, String)}, for a
     * caller (such as SNI dispatch) that needs to extract credentials for
     * several different aliases from the same keystore without reloading
     * it from disk each time ({@link #loadKeyStore} already caches by
     * path and mtime, but this skips even that lookup).
     *
     * @param keyStore the already-loaded keystore
     * @param password the private key password
     * @param alias the alias to extract, or null to use the keystore's
     *              first private-key entry
     * @return the server credentials
     * @throws GeneralSecurityException if the alias (or, when null, no
     *         alias at all) does not name a private key entry
     */
    public static ServerCredentials loadServerCredentials(KeyStore keyStore, String password, String alias)
            throws GeneralSecurityException {
        String useAlias = (alias != null) ? alias : firstPrivateKeyAlias(keyStore);
        if (useAlias == null) {
            throw new GeneralSecurityException("No private key entry found in keystore");
        }
        Certificate[] chain = keyStore.getCertificateChain(useAlias);
        if (chain == null) {
            throw new GeneralSecurityException("No certificate chain for alias \"" + useAlias + "\"");
        }
        List<X509Certificate> x509Chain = new ArrayList<X509Certificate>(chain.length);
        for (int i = 0; i < chain.length; i++) {
            x509Chain.add((X509Certificate) chain[i]);
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(useAlias, password.toCharArray());
        return new ServerCredentials(x509Chain, key);
    }

    private static String firstPrivateKeyAlias(KeyStore ks) throws GeneralSecurityException {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (ks.isKeyEntry(a)) {
                return a;
            }
        }
        return null;
    }
}
