/*
 * TestCertificateManagerTest.java
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

package org.bluezoo.gumdrop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

import javax.net.ssl.SSLContext;

import static org.junit.Assert.*;

/**
 * Tests for TestCertificateManager.
 * 
 * <p>Verifies certificate generation, keystore creation, and SSLContext setup
 * for integration testing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestCertificateManagerTest {

    private File tempDir;
    private TestCertificateManager manager;

    @Before
    public void setUp() throws Exception {
        tempDir = createTempDir();
        manager = new TestCertificateManager(tempDir);
    }

    @After
    public void tearDown() {
        // Clean up temp files
        if (tempDir != null && tempDir.exists()) {
            deleteRecursive(tempDir);
        }
    }

    private File createTempDir() throws Exception {
        File dir = File.createTempFile("cert-test-", "");
        dir.delete();
        dir.mkdirs();
        return dir;
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CA Certificate Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerateCA() throws Exception {
        manager.generateCA("Test CA", 365);
        
        X509Certificate ca = manager.getCACertificate();
        assertNotNull("CA certificate should be generated", ca);
        
        // Verify it's a CA certificate
        assertTrue("Should have CA basic constraint", ca.getBasicConstraints() >= 0);
        
        // Verify subject
        String subject = ca.getSubjectX500Principal().getName();
        assertTrue("Subject should contain CN=Test CA", subject.contains("CN=Test CA"));
        
        // Verify it's self-signed
        assertEquals("Issuer should match subject (self-signed)", 
            ca.getIssuerX500Principal(), ca.getSubjectX500Principal());
    }

    @Test
    public void testCAKeyUsage() throws Exception {
        manager.generateCA("Test CA", 365);
        
        X509Certificate ca = manager.getCACertificate();
        boolean[] keyUsage = ca.getKeyUsage();
        
        assertNotNull("CA should have key usage", keyUsage);
        assertTrue("CA should have keyCertSign", keyUsage[5]); // keyCertSign
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Server Certificate Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerateServerCertificate() throws Exception {
        manager.generateCA("Test CA", 365);
        manager.generateServerCertificate("localhost", 365);
        
        X509Certificate serverCert = manager.getServerCertificate();
        assertNotNull("Server certificate should be generated", serverCert);
        
        // Verify it's NOT a CA
        assertEquals("Server cert should not be a CA", -1, serverCert.getBasicConstraints());
        
        // Verify subject
        String subject = serverCert.getSubjectX500Principal().getName();
        assertTrue("Subject should contain CN=localhost", subject.contains("CN=localhost"));
        
        // Verify signed by CA
        X509Certificate ca = manager.getCACertificate();
        assertEquals("Should be signed by CA", 
            ca.getSubjectX500Principal(), serverCert.getIssuerX500Principal());
    }

    @Test(expected = IllegalStateException.class)
    public void testServerCertRequiresCA() throws Exception {
        // Should fail without CA
        manager.generateServerCertificate("localhost", 365);
    }

    @Test
    public void testSaveServerKeystore() throws Exception {
        manager.generateCA("Test CA", 365);
        manager.generateServerCertificate("localhost", 365);
        
        File keystoreFile = new File(tempDir, "server.p12");
        manager.saveServerKeystore(keystoreFile, "testpass");
        
        assertTrue("Keystore file should exist", keystoreFile.exists());
        assertTrue("Keystore should have content", keystoreFile.length() > 0);
        
        // Load and verify
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.FileInputStream(keystoreFile), "testpass".toCharArray());
        
        assertTrue("Keystore should have server entry", ks.containsAlias("server"));
        assertTrue("Entry should be a key entry", ks.isKeyEntry("server"));
        
        // Verify certificate chain
        java.security.cert.Certificate[] chain = ks.getCertificateChain("server");
        assertEquals("Chain should have 2 certificates", 2, chain.length);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Client Certificate Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerateClientCertificate() throws Exception {
        manager.generateCA("Test CA", 365);
        
        TestCertificateManager.ClientCertificate clientCert = 
            manager.generateClientCertificate("user@test.com", "Test User", 365);
        
        assertNotNull("Client certificate should be generated", clientCert);
        assertEquals("Identifier should match email", "user@test.com", clientCert.getIdentifier());
        
        X509Certificate cert = clientCert.getCertificate();
        assertNotNull("Certificate should not be null", cert);
        
        // Verify subject contains user info
        String subject = cert.getSubjectX500Principal().getName();
        assertTrue("Subject should contain CN", subject.contains("CN=Test User"));
        // Email may be formatted as EMAILADDRESS=, E=, or OID=1.2.840.113549.1.9.1
        assertTrue("Subject should contain email", 
            subject.contains("EMAILADDRESS=user@test.com") || 
            subject.contains("E=user@test.com") ||
            subject.contains("1.2.840.113549.1.9.1=#") || // DER-encoded email OID
            subject.contains("user@test.com")); // Just check the email exists somewhere
    }

    @Test
    public void testClientCertificateKeystore() throws Exception {
        manager.generateCA("Test CA", 365);
        
        TestCertificateManager.ClientCertificate clientCert = 
            manager.generateClientCertificate("user@test.com", "Test User", 365);
        
        KeyStore ks = clientCert.toKeyStore("clientpass");
        
        assertTrue("Keystore should have client entry", ks.containsAlias("client"));
        
        java.security.cert.Certificate[] chain = ks.getCertificateChain("client");
        assertEquals("Chain should have 2 certificates", 2, chain.length);
    }

    @Test
    public void testClientCertificateSaveToFile() throws Exception {
        manager.generateCA("Test CA", 365);
        
        TestCertificateManager.ClientCertificate clientCert = 
            manager.generateClientCertificate("user@test.com", "Test User", 365);
        
        File clientFile = new File(tempDir, "client.p12");
        clientCert.saveToFile(clientFile, "clientpass");
        
        assertTrue("Client keystore file should exist", clientFile.exists());
        
        // Load and verify
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.FileInputStream(clientFile), "clientpass".toCharArray());
        assertTrue("Should have client entry", ks.containsAlias("client"));
    }

    @Test
    public void testClientCertificateSSLContext() throws Exception {
        manager.generateCA("Test CA", 365);
        
        TestCertificateManager.ClientCertificate clientCert = 
            manager.generateClientCertificate("user@test.com", "Test User", 365);
        
        SSLContext ctx = clientCert.createSSLContext("testpass");
        assertNotNull("SSLContext should be created", ctx);
        assertNotNull("Should have socket factory", ctx.getSocketFactory());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Trust Store Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testSaveTrustStore() throws Exception {
        manager.generateCA("Test CA", 365);
        
        File truststoreFile = new File(tempDir, "truststore.p12");
        manager.saveTrustStore(truststoreFile, "trustpass");
        
        assertTrue("Truststore file should exist", truststoreFile.exists());
        
        // Load and verify
        KeyStore ts = TestCertificateManager.loadTrustStore(truststoreFile, "trustpass");
        assertTrue("Should have CA entry", ts.containsAlias("ca"));
        assertTrue("Entry should be a certificate entry", ts.isCertificateEntry("ca"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SSLContext Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testCreateServerSSLContext() throws Exception {
        manager.generateCA("Test CA", 365);
        manager.generateServerCertificate("localhost", 365);
        
        SSLContext ctx = manager.createServerSSLContext("serverpass", false);
        assertNotNull("Server SSLContext should be created", ctx);
        assertNotNull("Should have server socket factory", ctx.getServerSocketFactory());
    }

    @Test
    public void testCreateServerSSLContextWithClientAuth() throws Exception {
        manager.generateCA("Test CA", 365);
        manager.generateServerCertificate("localhost", 365);
        
        SSLContext ctx = manager.createServerSSLContext("serverpass", true);
        assertNotNull("Server SSLContext with client auth should be created", ctx);
    }

    @Test
    public void testCreateClientSSLContext() throws Exception {
        manager.generateCA("Test CA", 365);
        
        SSLContext ctx = manager.createClientSSLContext();
        assertNotNull("Client SSLContext should be created", ctx);
        assertNotNull("Should have socket factory", ctx.getSocketFactory());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Quick Setup Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerateTestPKI() throws Exception {
        manager.generateTestPKI("localhost", "testpass", 365);
        
        assertTrue("Test PKI should exist", manager.testPKIExists());
        
        File keystore = new File(tempDir, "test-keystore.p12");
        File truststore = new File(tempDir, "test-truststore.p12");
        
        assertTrue("Keystore should exist", keystore.exists());
        assertTrue("Truststore should exist", truststore.exists());
    }

    @Test
    public void testLoadExistingPKI() throws Exception {
        // First generate
        manager.generateTestPKI("localhost", "testpass", 365);
        
        // Create new manager and load
        TestCertificateManager manager2 = new TestCertificateManager(tempDir);
        manager2.loadExistingPKI("testpass");
        
        assertNotNull("CA should be loaded", manager2.getCACertificate());
        assertNotNull("Server cert should be loaded", manager2.getServerCertificate());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Multiple Client Certificates Test
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void testMultipleClientCertificates() throws Exception {
        manager.generateCA("Test CA", 365);
        
        TestCertificateManager.ClientCertificate client1 = 
            manager.generateClientCertificate("alice@test.com", "Alice", 365);
        TestCertificateManager.ClientCertificate client2 = 
            manager.generateClientCertificate("bob@test.com", "Bob", 365);
        TestCertificateManager.ClientCertificate client3 = 
            manager.generateClientCertificate("charlie@test.com", "Charlie", 365);
        
        // Each should be unique
        assertNotEquals("Certs should be different", 
            client1.getCertificate().getSerialNumber(),
            client2.getCertificate().getSerialNumber());
        assertNotEquals("Certs should be different", 
            client2.getCertificate().getSerialNumber(),
            client3.getCertificate().getSerialNumber());
        
        // All should be signed by same CA
        X509Certificate ca = manager.getCACertificate();
        assertEquals("Client 1 signed by CA", 
            ca.getSubjectX500Principal(), client1.getCertificate().getIssuerX500Principal());
        assertEquals("Client 2 signed by CA", 
            ca.getSubjectX500Principal(), client2.getCertificate().getIssuerX500Principal());
        assertEquals("Client 3 signed by CA", 
            ca.getSubjectX500Principal(), client3.getCertificate().getIssuerX500Principal());
    }
}

