/*
 * SNIKeyManager.java
 * Copyright (C) 2025 Chris Burdess
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

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * An X509ExtendedKeyManager that selects certificates based on SNI (Server Name Indication).
 * 
 * <p>This key manager wraps an existing key manager and adds SNI-based certificate selection.
 * When a client provides an SNI hostname during the TLS handshake, this manager looks up
 * the corresponding certificate alias from a configured mapping and returns that certificate.
 * 
 * <p>If no mapping exists for the SNI hostname, or if no SNI is provided, the default
 * certificate from the underlying key manager is used.
 * 
 * <p>Example configuration:
 * <pre>
 * Map&lt;String, String&gt; sniMapping = new HashMap&lt;&gt;();
 * sniMapping.put("example.com", "example-cert");
 * sniMapping.put("other.com", "other-cert");
 * 
 * SNIKeyManager sniKeyManager = new SNIKeyManager(baseKeyManager, sniMapping, "default-cert");
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SNIKeyManager extends X509ExtendedKeyManager {
    
    private static final Logger LOGGER = Logger.getLogger(SNIKeyManager.class.getName());
    
    private final X509KeyManager delegate;
    private final Map<String, String> hostToAliasMap;
    private final String defaultAlias;
    
    /**
     * Creates a new SNI-aware key manager.
     *
     * @param delegate the underlying key manager to delegate to
     * @param hostToAliasMap mapping from SNI hostnames to certificate aliases
     * @param defaultAlias the default alias to use when no SNI match is found (may be null)
     */
    public SNIKeyManager(X509KeyManager delegate, Map<String, String> hostToAliasMap, String defaultAlias) {
        this.delegate = delegate;
        this.hostToAliasMap = hostToAliasMap;
        this.defaultAlias = defaultAlias;
    }
    
    /**
     * Creates a new SNI-aware key manager with no default alias.
     *
     * @param delegate the underlying key manager to delegate to
     * @param hostToAliasMap mapping from SNI hostnames to certificate aliases
     */
    public SNIKeyManager(X509KeyManager delegate, Map<String, String> hostToAliasMap) {
        this(delegate, hostToAliasMap, null);
    }
    
    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        String sniHostname = extractSNIHostname(engine);
        
        if (sniHostname != null) {
            String alias = findAliasForHostname(sniHostname);
            if (alias != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("SNI: Selected alias '" + alias + "' for hostname '" + sniHostname + "'");
                }
                return alias;
            }
        }
        
        // Fall back to default alias or delegate
        if (defaultAlias != null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("SNI: Using default alias '" + defaultAlias + 
                           "' (no match for hostname '" + sniHostname + "')");
            }
            return defaultAlias;
        }
        
        // Delegate to underlying key manager
        if (delegate instanceof X509ExtendedKeyManager) {
            return ((X509ExtendedKeyManager) delegate).chooseEngineServerAlias(keyType, issuers, engine);
        }
        return delegate.chooseServerAlias(keyType, issuers, null);
    }
    
    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        if (socket instanceof SSLSocket) {
            String sniHostname = extractSNIHostname((SSLSocket) socket);
            
            if (sniHostname != null) {
                String alias = findAliasForHostname(sniHostname);
                if (alias != null) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("SNI: Selected alias '" + alias + "' for hostname '" + sniHostname + "'");
                    }
                    return alias;
                }
            }
        }
        
        // Fall back to default alias or delegate
        if (defaultAlias != null) {
            return defaultAlias;
        }
        return delegate.chooseServerAlias(keyType, issuers, socket);
    }
    
    /**
     * Extracts the SNI hostname from an SSLEngine's handshake session.
     */
    private String extractSNIHostname(SSLEngine engine) {
        if (engine == null) {
            return null;
        }
        
        try {
            ExtendedSSLSession session = (ExtendedSSLSession) engine.getHandshakeSession();
            if (session == null) {
                return null;
            }
            
            for (SNIServerName serverName : session.getRequestedServerNames()) {
                if (serverName.getType() == StandardConstants.SNI_HOST_NAME) {
                    return ((SNIHostName) serverName).getAsciiName();
                }
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Failed to extract SNI hostname from engine", e);
            }
        }
        
        return null;
    }
    
    /**
     * Extracts the SNI hostname from an SSLSocket's handshake session.
     */
    private String extractSNIHostname(SSLSocket socket) {
        try {
            ExtendedSSLSession session = (ExtendedSSLSession) socket.getHandshakeSession();
            if (session == null) {
                return null;
            }
            
            for (SNIServerName serverName : session.getRequestedServerNames()) {
                if (serverName.getType() == StandardConstants.SNI_HOST_NAME) {
                    return ((SNIHostName) serverName).getAsciiName();
                }
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Failed to extract SNI hostname from socket", e);
            }
        }
        
        return null;
    }
    
    /**
     * Finds the certificate alias for a given hostname.
     * Supports exact matches and wildcard domains (e.g., "*.example.com").
     */
    private String findAliasForHostname(String hostname) {
        if (hostname == null || hostToAliasMap == null) {
            return null;
        }
        
        // Normalize to lowercase
        String normalizedHostname = hostname.toLowerCase();
        
        // Try exact match first
        String alias = hostToAliasMap.get(normalizedHostname);
        if (alias != null) {
            return alias;
        }
        
        // Try wildcard match (*.example.com matches sub.example.com)
        int dotIndex = normalizedHostname.indexOf('.');
        if (dotIndex > 0) {
            String wildcardDomain = "*" + normalizedHostname.substring(dotIndex);
            alias = hostToAliasMap.get(wildcardDomain);
            if (alias != null) {
                return alias;
            }
        }
        
        return null;
    }
    
    // Delegate methods
    
    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return delegate.chooseClientAlias(keyType, issuers, socket);
    }
    
    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        if (delegate instanceof X509ExtendedKeyManager) {
            return ((X509ExtendedKeyManager) delegate).chooseEngineClientAlias(keyType, issuers, engine);
        }
        return delegate.chooseClientAlias(keyType, issuers, null);
    }
    
    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return delegate.getClientAliases(keyType, issuers);
    }
    
    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return delegate.getServerAliases(keyType, issuers);
    }
    
    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return delegate.getCertificateChain(alias);
    }
    
    @Override
    public PrivateKey getPrivateKey(String alias) {
        return delegate.getPrivateKey(alias);
    }
}

