/*
 * TestCertificateManager.java
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate management utility for integration testing.
 * 
 * <p>Provides functionality to generate and manage TLS certificates for testing:
 * <ul>
 *   <li>Self-signed Certificate Authority (CA) certificates</li>
 *   <li>Server certificates signed by the CA</li>
 *   <li>Client certificates for authentication testing</li>
 *   <li>PKCS12 keystore creation and management</li>
 *   <li>Trust store management</li>
 *   <li>SSLContext configuration for client/server usage</li>
 * </ul>
 *
 * <p>This implementation uses the keytool command-line utility for certificate
 * generation to ensure compatibility across JDK versions.
 *
 * <p>Example usage for server setup:
 * <pre>
 * TestCertificateManager mgr = new TestCertificateManager(certsDir);
 * mgr.generateCA("Test CA", 365);
 * mgr.generateServerCertificate("localhost", 365);
 * mgr.saveServerKeystore(new File(certsDir, "server.p12"), "password");
 * </pre>
 *
 * <p>Example usage for client certificate authentication:
 * <pre>
 * mgr.generateClientCertificate("testuser@example.com", "Test User", 365);
 * SSLContext ctx = mgr.createClientSSLContext("testuser@example.com", "password");
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestCertificateManager {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final File certsDirectory;
    private final SecureRandom random;
    
    // CA state
    private KeyPair caKeyPair;
    private X509Certificate caCertificate;
    private File caKeystore;
    private String caKeystorePassword;
    
    // Server state
    private KeyPair serverKeyPair;
    private X509Certificate serverCertificate;

    /**
     * Creates a certificate manager using the specified directory for storage.
     *
     * @param certsDirectory directory to store generated certificates
     */
    public TestCertificateManager(File certsDirectory) {
        this.certsDirectory = certsDirectory;
        this.random = new SecureRandom();
        if (!certsDirectory.exists()) {
            certsDirectory.mkdirs();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CA Certificate Generation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a self-signed Certificate Authority certificate.
     *
     * @param commonName the CN for the CA (e.g., "Test CA")
     * @param validDays number of days the certificate is valid
     * @throws GeneralSecurityException if certificate generation fails
     */
    public void generateCA(String commonName, int validDays) throws GeneralSecurityException, IOException {
        caKeystorePassword = generatePassword();
        caKeystore = new File(certsDirectory, "ca-keystore.p12");
        
        String dname = "CN=" + commonName + ", O=Test, C=US";
        
        // Generate CA keypair and self-signed certificate using keytool
        runKeytool(
            "-genkeypair",
            "-alias", "ca",
            "-keyalg", KEY_ALGORITHM,
            "-keysize", String.valueOf(KEY_SIZE),
            "-sigalg", SIGNATURE_ALGORITHM,
            "-validity", String.valueOf(validDays),
            "-dname", dname,
            "-ext", "bc:c=ca:true",
            "-ext", "ku=keyCertSign,cRLSign",
            "-keystore", caKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", caKeystorePassword
        );
        
        // Load the generated certificate and key
        KeyStore ks = loadKeyStore(caKeystore, caKeystorePassword);
        caCertificate = (X509Certificate) ks.getCertificate("ca");
        Key key = ks.getKey("ca", caKeystorePassword.toCharArray());
        if (key instanceof PrivateKey) {
            caKeyPair = new KeyPair(caCertificate.getPublicKey(), (PrivateKey) key);
        }
    }

    /**
     * Returns the CA certificate, or null if not yet generated.
     */
    public X509Certificate getCACertificate() {
        return caCertificate;
    }

    /**
     * Returns the CA key pair, or null if not yet generated.
     */
    public KeyPair getCAKeyPair() {
        return caKeyPair;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Server Certificate Generation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a server certificate signed by the CA.
     * The CA must be generated first via {@link #generateCA(String, int)}.
     *
     * @param hostname the server hostname (used as CN and SAN)
     * @param validDays number of days the certificate is valid
     * @throws GeneralSecurityException if certificate generation fails
     * @throws IllegalStateException if CA has not been generated
     */
    public void generateServerCertificate(String hostname, int validDays) 
            throws GeneralSecurityException, IOException {
        if (caCertificate == null || caKeyPair == null) {
            throw new IllegalStateException("CA must be generated before server certificate");
        }
        
        String serverPassword = generatePassword();
        File serverKeystore = new File(certsDirectory, "server-temp.p12");
        File serverCsr = new File(certsDirectory, "server.csr");
        File serverCertFile = new File(certsDirectory, "server.crt");
        
        String dname = "CN=" + hostname + ", O=Test Server, C=US";
        
        // Generate server keypair
        runKeytool(
            "-genkeypair",
            "-alias", "server",
            "-keyalg", KEY_ALGORITHM,
            "-keysize", String.valueOf(KEY_SIZE),
            "-sigalg", SIGNATURE_ALGORITHM,
            "-validity", String.valueOf(validDays),
            "-dname", dname,
            "-keystore", serverKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", serverPassword
        );
        
        // Generate CSR
        runKeytool(
            "-certreq",
            "-alias", "server",
            "-keystore", serverKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", serverPassword,
            "-file", serverCsr.getAbsolutePath()
        );
        
        // Sign CSR with CA
        runKeytool(
            "-gencert",
            "-alias", "ca",
            "-keystore", caKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", caKeystorePassword,
            "-infile", serverCsr.getAbsolutePath(),
            "-outfile", serverCertFile.getAbsolutePath(),
            "-validity", String.valueOf(validDays),
            "-ext", "ku=digitalSignature,keyEncipherment",
            "-ext", "eku=serverAuth",
            "-ext", "san=dns:" + hostname
        );
        
        // Export CA cert for import
        File caCertFile = new File(certsDirectory, "ca.crt");
        runKeytool(
            "-exportcert",
            "-alias", "ca",
            "-keystore", caKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", caKeystorePassword,
            "-file", caCertFile.getAbsolutePath(),
            "-rfc"
        );
        
        // Import CA cert into server keystore
        runKeytool(
            "-importcert",
            "-alias", "ca",
            "-keystore", serverKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", serverPassword,
            "-file", caCertFile.getAbsolutePath(),
            "-noprompt"
        );
        
        // Import signed server cert
        runKeytool(
            "-importcert",
            "-alias", "server",
            "-keystore", serverKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", serverPassword,
            "-file", serverCertFile.getAbsolutePath()
        );
        
        // Load the server certificate and key
        KeyStore ks = loadKeyStore(serverKeystore, serverPassword);
        serverCertificate = (X509Certificate) ks.getCertificate("server");
        Key key = ks.getKey("server", serverPassword.toCharArray());
        if (key instanceof PrivateKey) {
            serverKeyPair = new KeyPair(serverCertificate.getPublicKey(), (PrivateKey) key);
        }
        
        // Cleanup temp files
        serverCsr.delete();
        serverCertFile.delete();
        caCertFile.delete();
        serverKeystore.delete();
    }

    /**
     * Returns the server certificate, or null if not yet generated.
     */
    public X509Certificate getServerCertificate() {
        return serverCertificate;
    }

    /**
     * Saves the server keystore (containing server key and certificate chain).
     *
     * @param file the output file
     * @param password the keystore password
     * @throws GeneralSecurityException if keystore creation fails
     * @throws IOException if file writing fails
     */
    public void saveServerKeystore(File file, String password) 
            throws GeneralSecurityException, IOException {
        if (serverCertificate == null || serverKeyPair == null) {
            throw new IllegalStateException("Server certificate must be generated first");
        }
        
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, null);
        
        Certificate[] chain = new Certificate[] { serverCertificate, caCertificate };
        keyStore.setKeyEntry("server", serverKeyPair.getPrivate(), password.toCharArray(), chain);
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            keyStore.store(fos, password.toCharArray());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Client Certificate Generation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a client certificate for authentication testing.
     *
     * @param email the user's email address (used in CN)
     * @param displayName the user's display name
     * @param validDays number of days the certificate is valid
     * @return the generated client certificate
     * @throws GeneralSecurityException if certificate generation fails
     * @throws IllegalStateException if CA has not been generated
     */
    public ClientCertificate generateClientCertificate(String email, String displayName, int validDays) 
            throws GeneralSecurityException, IOException {
        if (caCertificate == null || caKeyPair == null) {
            throw new IllegalStateException("CA must be generated before client certificate");
        }
        
        String clientPassword = generatePassword();
        File clientKeystore = new File(certsDirectory, "client-" + sanitizeFilename(email) + "-temp.p12");
        File clientCsr = new File(certsDirectory, "client-" + sanitizeFilename(email) + ".csr");
        File clientCertFile = new File(certsDirectory, "client-" + sanitizeFilename(email) + ".crt");
        
        String dname = "CN=" + displayName + ", EMAILADDRESS=" + email + ", O=Test Client, C=US";
        
        // Generate client keypair
        runKeytool(
            "-genkeypair",
            "-alias", "client",
            "-keyalg", KEY_ALGORITHM,
            "-keysize", String.valueOf(KEY_SIZE),
            "-sigalg", SIGNATURE_ALGORITHM,
            "-validity", String.valueOf(validDays),
            "-dname", dname,
            "-keystore", clientKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", clientPassword
        );
        
        // Generate CSR
        runKeytool(
            "-certreq",
            "-alias", "client",
            "-keystore", clientKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", clientPassword,
            "-file", clientCsr.getAbsolutePath()
        );
        
        // Sign CSR with CA
        runKeytool(
            "-gencert",
            "-alias", "ca",
            "-keystore", caKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", caKeystorePassword,
            "-infile", clientCsr.getAbsolutePath(),
            "-outfile", clientCertFile.getAbsolutePath(),
            "-validity", String.valueOf(validDays),
            "-ext", "ku=digitalSignature,keyEncipherment",
            "-ext", "eku=clientAuth"
        );
        
        // Export CA cert for import
        File caCertFile = new File(certsDirectory, "ca-for-client.crt");
        runKeytool(
            "-exportcert",
            "-alias", "ca",
            "-keystore", caKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", caKeystorePassword,
            "-file", caCertFile.getAbsolutePath(),
            "-rfc"
        );
        
        // Import CA cert into client keystore
        runKeytool(
            "-importcert",
            "-alias", "ca",
            "-keystore", clientKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", clientPassword,
            "-file", caCertFile.getAbsolutePath(),
            "-noprompt"
        );
        
        // Import signed client cert
        runKeytool(
            "-importcert",
            "-alias", "client",
            "-keystore", clientKeystore.getAbsolutePath(),
            "-storetype", KEYSTORE_TYPE,
            "-storepass", clientPassword,
            "-file", clientCertFile.getAbsolutePath()
        );
        
        // Load the client certificate and key
        KeyStore ks = loadKeyStore(clientKeystore, clientPassword);
        X509Certificate clientCert = (X509Certificate) ks.getCertificate("client");
        Key key = ks.getKey("client", clientPassword.toCharArray());
        KeyPair clientKeyPair = null;
        if (key instanceof PrivateKey) {
            clientKeyPair = new KeyPair(clientCert.getPublicKey(), (PrivateKey) key);
        }
        
        // Cleanup temp files
        clientCsr.delete();
        clientCertFile.delete();
        caCertFile.delete();
        clientKeystore.delete();
        
        return new ClientCertificate(email, clientKeyPair, clientCert, caCertificate);
    }

    /**
     * Holds a client certificate with its private key and CA chain.
     */
    public static class ClientCertificate {
        private final String identifier;
        private final KeyPair keyPair;
        private final X509Certificate certificate;
        private final X509Certificate caCertificate;

        ClientCertificate(String identifier, KeyPair keyPair, 
                         X509Certificate certificate, X509Certificate caCertificate) {
            this.identifier = identifier;
            this.keyPair = keyPair;
            this.certificate = certificate;
            this.caCertificate = caCertificate;
        }

        public String getIdentifier() {
            return identifier;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }

        public PrivateKey getPrivateKey() {
            return keyPair.getPrivate();
        }

        public X509Certificate getCACertificate() {
            return caCertificate;
        }

        /**
         * Creates a PKCS12 keystore containing this client certificate.
         */
        public KeyStore toKeyStore(String password) throws GeneralSecurityException, IOException {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            
            Certificate[] chain = new Certificate[] { certificate, caCertificate };
            keyStore.setKeyEntry("client", keyPair.getPrivate(), password.toCharArray(), chain);
            
            return keyStore;
        }

        /**
         * Saves this client certificate to a PKCS12 file.
         */
        public void saveToFile(File file, String password) throws GeneralSecurityException, IOException {
            KeyStore keyStore = toKeyStore(password);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                keyStore.store(fos, password.toCharArray());
            }
        }

        /**
         * Creates an SSLContext configured to use this client certificate.
         */
        public SSLContext createSSLContext() throws GeneralSecurityException, IOException {
            return createSSLContext("changeit");
        }

        /**
         * Creates an SSLContext configured to use this client certificate.
         */
        public SSLContext createSSLContext(String password) throws GeneralSecurityException, IOException {
            KeyStore keyStore = toKeyStore(password);
            
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password.toCharArray());
            
            // Create trust store with CA
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", caCertificate);
            
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
            
            return sslContext;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Trust Store Management
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a trust store containing only the CA certificate.
     * Use this for servers that want to validate client certificates.
     *
     * @param file the output file
     * @param password the keystore password
     * @throws GeneralSecurityException if keystore creation fails
     * @throws IOException if file writing fails
     */
    public void saveTrustStore(File file, String password) 
            throws GeneralSecurityException, IOException {
        if (caCertificate == null) {
            throw new IllegalStateException("CA certificate must be generated first");
        }
        
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE);
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCertificate);
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            trustStore.store(fos, password.toCharArray());
        }
    }

    /**
     * Loads a trust store from a file.
     *
     * @param file the keystore file
     * @param password the keystore password
     * @return the loaded KeyStore
     */
    public static KeyStore loadTrustStore(File file, String password) 
            throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(file)) {
            trustStore.load(fis, password.toCharArray());
        }
        return trustStore;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SSLContext Creation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates an SSLContext for a server using the generated server certificate.
     * Optionally enables client certificate authentication.
     *
     * @param keystorePassword the server keystore password
     * @param requireClientCert if true, server will require client certificates
     * @return configured SSLContext
     */
    public SSLContext createServerSSLContext(String keystorePassword, boolean requireClientCert) 
            throws GeneralSecurityException, IOException {
        if (serverCertificate == null || serverKeyPair == null) {
            throw new IllegalStateException("Server certificate must be generated first");
        }
        
        // Create server keystore
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, null);
        Certificate[] chain = new Certificate[] { serverCertificate, caCertificate };
        keyStore.setKeyEntry("server", serverKeyPair.getPrivate(), keystorePassword.toCharArray(), chain);
        
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());
        
        TrustManager[] trustManagers;
        if (requireClientCert) {
            // Trust only our CA for client certificates
            KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE);
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", caCertificate);
            
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            trustManagers = tmf.getTrustManagers();
        } else {
            // No client cert validation
            trustManagers = new TrustManager[] { new TrustAllTrustManager() };
        }
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), trustManagers, random);
        
        return sslContext;
    }

    /**
     * Creates an SSLContext for a client that trusts our CA.
     *
     * @return configured SSLContext that trusts our test CA
     */
    public SSLContext createClientSSLContext() throws GeneralSecurityException, IOException {
        if (caCertificate == null) {
            throw new IllegalStateException("CA certificate must be generated first");
        }
        
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE);
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCertificate);
        
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), random);
        
        return sslContext;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Quick Setup Methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a complete test PKI with CA, server certificate, and saves to files.
     * This is a convenience method for simple test setup.
     *
     * @param hostname the server hostname
     * @param password the password for all keystores
     * @param validDays validity period in days
     */
    public void generateTestPKI(String hostname, String password, int validDays) 
            throws GeneralSecurityException, IOException {
        generateCA("Test CA", validDays);
        generateServerCertificate(hostname, validDays);
        
        saveServerKeystore(new File(certsDirectory, "test-keystore.p12"), password);
        saveTrustStore(new File(certsDirectory, "test-truststore.p12"), password);
    }

    /**
     * Checks if the test PKI files exist.
     */
    public boolean testPKIExists() {
        File keystore = new File(certsDirectory, "test-keystore.p12");
        File truststore = new File(certsDirectory, "test-truststore.p12");
        return keystore.exists() && truststore.exists();
    }

    /**
     * Loads existing CA and server certificates from keystores.
     *
     * @param password the keystore password
     */
    public void loadExistingPKI(String password) throws GeneralSecurityException, IOException {
        File keystoreFile = new File(certsDirectory, "test-keystore.p12");
        File truststoreFile = new File(certsDirectory, "test-truststore.p12");
        
        // Load truststore for CA
        KeyStore trustStore = loadTrustStore(truststoreFile, password);
        caCertificate = (X509Certificate) trustStore.getCertificate("ca");
        
        // Load keystore for server
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            keyStore.load(fis, password.toCharArray());
        }
        
        serverCertificate = (X509Certificate) keyStore.getCertificate("server");
        Key key = keyStore.getKey("server", password.toCharArray());
        if (key instanceof PrivateKey) {
            serverKeyPair = new KeyPair(serverCertificate.getPublicKey(), (PrivateKey) key);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal Helper Methods
    // ─────────────────────────────────────────────────────────────────────────────

    private String generatePassword() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
    
    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
    
    private KeyStore loadKeyStore(File file, String password) 
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(file)) {
            ks.load(fis, password.toCharArray());
        }
        return ks;
    }
    
    private void runKeytool(String... args) throws IOException, GeneralSecurityException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = getKeytoolPath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        // Read all output
        StringBuilder output = new StringBuilder();
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                output.append(new String(buffer, 0, len));
            }
        }
        
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Keytool process interrupted", e);
        }
        
        if (exitCode != 0) {
            throw new GeneralSecurityException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }
    
    private String getKeytoolPath() {
        String javaHome = System.getProperty("java.home");
        String separator = File.separator;
        String keytool = javaHome + separator + "bin" + separator + "keytool";
        
        // On Windows, add .exe
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            keytool += ".exe";
        }
        
        return keytool;
    }

    /**
     * TrustManager that accepts all certificates.
     * Only use for testing!
     */
    private static class TrustAllTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Accept all
        }
        
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Accept all
        }
        
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
