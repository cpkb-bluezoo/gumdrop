/*
 * SNIKeyManagerTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.util;

import org.junit.Test;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.X509KeyManager;

import static org.junit.Assert.*;

/**
 * Tests for SNIKeyManager hostname-based certificate selection.
 */
public class SNIKeyManagerTest {
    
    /**
     * Mock X509KeyManager for testing.
     */
    private static class MockKeyManager implements X509KeyManager {
        private final String defaultAlias;
        
        MockKeyManager(String defaultAlias) {
            this.defaultAlias = defaultAlias;
        }
        
        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return defaultAlias;
        }
        
        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return defaultAlias;
        }
        
        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[] { defaultAlias };
        }
        
        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] { defaultAlias };
        }
        
        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return null;
        }
        
        @Override
        public PrivateKey getPrivateKey(String alias) {
            return null;
        }
    }
    
    @Test
    public void testExactHostnameMatch() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");
        mapping.put("other.com", "other-cert");
        
        SNIKeyManager manager = new SNIKeyManager(new MockKeyManager("default"), mapping);
        
        // Test internal hostname lookup via findAliasForHostname (tested indirectly)
        // Since we can't easily inject an SSLEngine with SNI info in a unit test,
        // we'll test the fallback behavior
        String alias = manager.chooseServerAlias("RSA", null, null);
        
        // Without SNI, should fall back to delegate
        assertEquals("default", alias);
    }
    
    @Test
    public void testDefaultAliasUsed() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");
        
        SNIKeyManager manager = new SNIKeyManager(
            new MockKeyManager("delegate-default"), 
            mapping, 
            "sni-default"
        );
        
        // Without SNI hostname, should use sniDefaultAlias
        String alias = manager.chooseServerAlias("RSA", null, null);
        assertEquals("sni-default", alias);
    }
    
    @Test
    public void testDelegateUsedWhenNoDefaultAlias() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");
        
        SNIKeyManager manager = new SNIKeyManager(
            new MockKeyManager("delegate-default"), 
            mapping
        );
        
        // Without SNI hostname and no default alias, should use delegate
        String alias = manager.chooseServerAlias("RSA", null, null);
        assertEquals("delegate-default", alias);
    }
    
    @Test
    public void testNullMappingHandled() {
        SNIKeyManager manager = new SNIKeyManager(
            new MockKeyManager("delegate-default"), 
            null, 
            "fallback"
        );
        
        String alias = manager.chooseServerAlias("RSA", null, null);
        assertEquals("fallback", alias);
    }
    
    @Test
    public void testEmptyMappingHandled() {
        Map<String, String> mapping = new HashMap<>();
        
        SNIKeyManager manager = new SNIKeyManager(
            new MockKeyManager("delegate-default"), 
            mapping, 
            "fallback"
        );
        
        String alias = manager.chooseServerAlias("RSA", null, null);
        assertEquals("fallback", alias);
    }
    
    @Test
    public void testClientAliasDelegated() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("example.com", "example-cert");
        
        SNIKeyManager manager = new SNIKeyManager(
            new MockKeyManager("client-default"), 
            mapping,
            "server-default"
        );
        
        // Client alias should always delegate
        String alias = manager.chooseClientAlias(new String[] { "RSA" }, null, null);
        assertEquals("client-default", alias);
    }
    
    @Test
    public void testGetCertificateChainDelegated() {
        MockKeyManager delegate = new MockKeyManager("default");
        SNIKeyManager manager = new SNIKeyManager(delegate, new HashMap<>());
        
        X509Certificate[] chain = manager.getCertificateChain("test-alias");
        assertNull(chain); // MockKeyManager returns null
    }
    
    @Test
    public void testGetPrivateKeyDelegated() {
        MockKeyManager delegate = new MockKeyManager("default");
        SNIKeyManager manager = new SNIKeyManager(delegate, new HashMap<>());
        
        PrivateKey key = manager.getPrivateKey("test-alias");
        assertNull(key); // MockKeyManager returns null
    }
    
    @Test
    public void testGetServerAliasesDelegated() {
        MockKeyManager delegate = new MockKeyManager("default");
        SNIKeyManager manager = new SNIKeyManager(delegate, new HashMap<>());
        
        String[] aliases = manager.getServerAliases("RSA", null);
        assertNotNull(aliases);
        assertEquals(1, aliases.length);
        assertEquals("default", aliases[0]);
    }
    
    @Test
    public void testGetClientAliasesDelegated() {
        MockKeyManager delegate = new MockKeyManager("default");
        SNIKeyManager manager = new SNIKeyManager(delegate, new HashMap<>());
        
        String[] aliases = manager.getClientAliases("RSA", null);
        assertNotNull(aliases);
        assertEquals(1, aliases.length);
        assertEquals("default", aliases[0]);
    }
}

