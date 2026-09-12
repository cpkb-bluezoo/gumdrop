/*
 * SniCredentialsResolver.java
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

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.ServerCredentialsResolver;

/**
 * SNI-based {@link ServerCredentialsResolver} selecting a certificate
 * alias within a single keystore by hostname, with a single
 * leftmost-label {@code *.domain} wildcard and an optional default alias
 * -- the direct replacement for the former JSSE-specific
 * {@code SNIKeyManager}'s matching logic (ported verbatim: exact
 * hostname, then wildcard, then default, case-insensitive), now
 * producing {@link ServerCredentials} instead of selecting a JSSE key
 * manager alias. Resolved credentials are cached per alias, since a
 * keystore's aliases and their entries don't change without reloading
 * the keystore itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SniCredentialsResolver implements ServerCredentialsResolver {

    private final KeyStore keyStore;
    private final String password;
    private final Map<String, String> hostnameToAlias;
    private final String defaultAlias;
    private final ConcurrentMap<String, ServerCredentials> cache = new ConcurrentHashMap<String, ServerCredentials>();

    /**
     * Creates an SNI-based resolver.
     *
     * @param keyStore the keystore to select aliases from
     * @param password the keystore's private key password
     * @param hostnameToAlias hostname (or {@code *.domain} wildcard) to
     *                        certificate alias
     * @param defaultAlias the alias to use when no hostname matches, or
     *                      when the client sent no SNI, or null for none
     */
    public SniCredentialsResolver(KeyStore keyStore, String password, Map<String, String> hostnameToAlias,
            String defaultAlias) {
        this.keyStore = keyStore;
        this.password = password;
        this.hostnameToAlias = hostnameToAlias;
        this.defaultAlias = defaultAlias;
    }

    @Override
    public ServerCredentials resolve(String serverName) {
        String alias = findAlias(serverName);
        if (alias == null) {
            return null;
        }
        ServerCredentials cached = cache.get(alias);
        if (cached != null) {
            return cached;
        }
        try {
            ServerCredentials creds = TLSUtils.loadServerCredentials(keyStore, password, alias);
            cache.put(alias, creds);
            return creds;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    // Package-private (rather than private) so unit tests can exercise the
    // exact/wildcard/default matching logic directly, without needing a
    // real KeyStore with per-alias key entries -- the same trade-off
    // DirectionalKeys.seq makes for TlsRecordEngineTest.
    String findAlias(String hostname) {
        if (hostname != null) {
            String normalized = hostname.toLowerCase(Locale.ROOT);
            String exact = hostnameToAlias.get(normalized);
            if (exact != null) {
                return exact;
            }
            int dot = normalized.indexOf('.');
            if (dot >= 0) {
                String wildcard = hostnameToAlias.get("*" + normalized.substring(dot));
                if (wildcard != null) {
                    return wildcard;
                }
            }
        }
        return defaultAlias;
    }

}
