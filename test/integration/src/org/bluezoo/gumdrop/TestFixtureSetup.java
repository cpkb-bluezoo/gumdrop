/*
 * TestFixtureSetup.java
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Utility for setting up test fixtures before running integration tests.
 * 
 * <p>This class should be run before the integration test suite to ensure
 * all necessary test infrastructure is in place:
 * <ul>
 *   <li>TLS certificates (CA, server, client)</li>
 *   <li>Test configuration files</li>
 *   <li>Test directories</li>
 *   <li>Sample data files</li>
 * </ul>
 *
 * <p>Run with: {@code java org.bluezoo.gumdrop.TestFixtureSetup}
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestFixtureSetup {

    private static final String CERTS_DIR = "test/integration/certs";
    private static final String RESULTS_DIR = "test/integration/results";
    private static final String KEYSTORE_PASSWORD = "testpass";
    
    private final PrintWriter log;
    private int warnings = 0;
    private int errors = 0;

    public TestFixtureSetup(PrintWriter log) {
        this.log = log;
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        TestFixtureSetup setup = new TestFixtureSetup(out);
        
        out.println("=== Integration Test Fixture Setup ===");
        out.println("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        out.println();
        
        try {
            setup.setupAll();
            
            out.println();
            out.println("=== Summary ===");
            out.println("Warnings: " + setup.warnings);
            out.println("Errors: " + setup.errors);
            
            if (setup.errors > 0) {
                out.println("SETUP FAILED - fix errors before running tests");
                System.exit(1);
            } else {
                out.println("Setup completed successfully");
                System.exit(0);
            }
            
        } catch (Exception e) {
            out.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace(out);
            System.exit(2);
        }
    }

    /**
     * Sets up all test fixtures.
     */
    public void setupAll() throws Exception {
        createDirectories();
        setupCertificates();
        validateConfigs();
        createManifest();
    }

    /**
     * Creates required directories.
     */
    public void createDirectories() {
        log.println("=== Creating Directories ===");
        
        String[] dirs = {
            CERTS_DIR,
            RESULTS_DIR,
            RESULTS_DIR + "/diagnostics",
            RESULTS_DIR + "/reports",
            "test/integration/mailbox"
        };
        
        for (String dir : dirs) {
            File f = new File(dir);
            if (!f.exists()) {
                if (f.mkdirs()) {
                    log.println("Created: " + dir);
                } else {
                    log.println("ERROR: Failed to create: " + dir);
                    errors++;
                }
            } else {
                log.println("Exists: " + dir);
            }
        }
    }

    /**
     * Sets up TLS certificates for testing.
     */
    public void setupCertificates() throws Exception {
        log.println();
        log.println("=== Setting Up Certificates ===");
        
        File certsDir = new File(CERTS_DIR);
        TestCertificateManager certMgr = new TestCertificateManager(certsDir);
        
        File keystoreFile = new File(certsDir, "test-keystore.p12");
        File truststoreFile = new File(certsDir, "test-truststore.p12");
        
        // Check if certs already exist and are recent
        if (keystoreFile.exists() && truststoreFile.exists()) {
            long age = System.currentTimeMillis() - keystoreFile.lastModified();
            long maxAge = 30L * 24 * 60 * 60 * 1000; // 30 days in ms
            
            if (age < maxAge) {
                log.println("Certificates exist and are recent (< 30 days old)");
                log.println("  Keystore: " + keystoreFile);
                log.println("  Truststore: " + truststoreFile);
                return;
            } else {
                log.println("Certificates are old, regenerating...");
            }
        }
        
        // Generate new PKI
        log.println("Generating test PKI...");
        
        try {
            certMgr.generateCA("Gumdrop Test CA", 365);
            log.println("  Generated CA certificate");
            
            certMgr.generateServerCertificate("localhost", 365);
            log.println("  Generated server certificate for 'localhost'");
            
            certMgr.saveServerKeystore(keystoreFile, KEYSTORE_PASSWORD);
            log.println("  Saved server keystore: " + keystoreFile);
            
            certMgr.saveTrustStore(truststoreFile, KEYSTORE_PASSWORD);
            log.println("  Saved trust store: " + truststoreFile);
            
            // Generate some client certificates for client auth testing
            TestCertificateManager.ClientCertificate client1 = 
                certMgr.generateClientCertificate("testuser@example.com", "Test User", 365);
            client1.saveToFile(new File(certsDir, "client-testuser.p12"), KEYSTORE_PASSWORD);
            log.println("  Generated client certificate: client-testuser.p12");
            
            TestCertificateManager.ClientCertificate client2 = 
                certMgr.generateClientCertificate("admin@example.com", "Admin User", 365);
            client2.saveToFile(new File(certsDir, "client-admin.p12"), KEYSTORE_PASSWORD);
            log.println("  Generated client certificate: client-admin.p12");
            
        } catch (Exception e) {
            log.println("ERROR: Failed to generate certificates: " + e.getMessage());
            errors++;
            throw e;
        }
    }

    /**
     * Validates test configuration files.
     */
    public void validateConfigs() {
        log.println();
        log.println("=== Validating Configurations ===");
        
        String[] configs = {
            "test/integration/config/http-server-test.xml",
            "test/integration/config/https-server-test.xml",
            "test/integration/config/smtp-server-test.xml",
            "test/integration/config/pop3-maildir-server-test.xml",
            "test/integration/config/pop3-mbox-server-test.xml"
        };
        
        for (String config : configs) {
            File f = new File(config);
            if (f.exists()) {
                if (f.canRead()) {
                    log.println("OK: " + config);
                } else {
                    log.println("WARNING: Cannot read: " + config);
                    warnings++;
                }
            } else {
                log.println("WARNING: Missing config: " + config);
                warnings++;
            }
        }
    }

    /**
     * Creates a manifest file documenting the test setup.
     */
    public void createManifest() throws IOException {
        log.println();
        log.println("=== Creating Manifest ===");
        
        File manifest = new File(RESULTS_DIR, "test-setup-manifest.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(manifest))) {
            pw.println("Integration Test Setup Manifest");
            pw.println("================================");
            pw.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println();
            
            pw.println("Java Version: " + System.getProperty("java.version"));
            pw.println("Java Home: " + System.getProperty("java.home"));
            pw.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            pw.println("User: " + System.getProperty("user.name"));
            pw.println("Working Dir: " + System.getProperty("user.dir"));
            pw.println();
            
            pw.println("Certificates:");
            File certsDir = new File(CERTS_DIR);
            File[] certs = certsDir.listFiles();
            if (certs != null) {
                for (File cert : certs) {
                    pw.println("  " + cert.getName() + " (" + cert.length() + " bytes)");
                }
            }
            pw.println();
            
            pw.println("Configuration Files:");
            File configDir = new File("test/integration/config");
            File[] configs = configDir.listFiles();
            if (configs != null) {
                for (File config : configs) {
                    pw.println("  " + config.getName());
                }
            }
            pw.println();
            
            pw.println("Keystore Password: " + KEYSTORE_PASSWORD);
            pw.println();
            
            pw.println("Notes:");
            pw.println("  - Certificates are valid for 365 days");
            pw.println("  - Server certificates use 'localhost' as hostname");
            pw.println("  - Client certificates include testuser@example.com and admin@example.com");
        }
        
        log.println("Created: " + manifest);
    }

    /**
     * Returns the number of errors encountered.
     */
    public int getErrorCount() {
        return errors;
    }

    /**
     * Returns the number of warnings encountered.
     */
    public int getWarningCount() {
        return warnings;
    }
}

