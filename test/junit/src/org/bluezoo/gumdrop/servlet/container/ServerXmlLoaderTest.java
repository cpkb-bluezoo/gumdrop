/*
 * ServerXmlLoaderTest.java
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

package org.bluezoo.gumdrop.servlet.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.testsupport.TestCertificates;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HttpServer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests server.xml interpretation in {@link ServerXmlLoader} using the
 * in-memory test entry point, so no files are read.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServerXmlLoaderTest {

    private HttpServer server;
    private String error;

    @Before
    public void setUp() {
        server = null;
        error = null;
    }

    private void load(String xml) {
        loadIn(new File("/nonexistent-base"), xml);
    }

    private void loadIn(File baseDir, String xml) {
        ServerXmlLoader.loadFromMemoryForTesting(baseDir,
                ByteBuffer.wrap(xml.getBytes(StandardCharsets.UTF_8)),
                new ServerXmlLoader.Callback() {
                    @Override
                    public void onServer(HttpServer s) {
                        server = s;
                    }

                    @Override
                    public void onError(String e) {
                        error = e;
                    }
                });
    }

    private org.bluezoo.gumdrop.servlet.Container loadedContainer() throws Exception {
        java.lang.reflect.Method m = HttpServer.class.getDeclaredMethod("getStreamHandler");
        m.setAccessible(true);
        return ((org.bluezoo.gumdrop.servlet.server.ServletRequestHandler) m.invoke(server))
                .getContainer();
    }

    @Test
    public void containerElementConfiguresHotDeployAndPools() throws Exception {
        load("<server><container hot-deploy='true' buffer-size='4096' "
                + "worker-core-pool-size='3' worker-maximum-pool-size='9' "
                + "worker-keep-alive='45'/><listener port='1'/></server>");
        assertNull(error, error);
        org.bluezoo.gumdrop.servlet.Container container = loadedContainer();
        assertTrue(container.isHotDeploy());
        assertEquals(4096, container.getBufferSize());
        assertEquals(3, container.getWorkerThreadPool().getCorePoolSize());
        assertEquals(9, container.getWorkerThreadPool().getMaximumPoolSize());
        assertEquals(java.time.Duration.ofSeconds(45), container.getWorkerKeepAlive());
    }

    @Test
    public void containerElementCanTurnHotDeployOff() throws Exception {
        load("<server><container hot-deploy='false'/><listener port='1'/></server>");
        assertNull(error, error);
        assertFalse(loadedContainer().isHotDeploy());
    }

    @Test
    public void containerElementRejectsNonNumericBufferSize() {
        load("<server><container buffer-size='big'/><listener port='1'/></server>");
        assertError("buffer-size");
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = Listener.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private Listener listener(int index) {
        return (Listener) server.getListeners().get(index);
    }

    private static final String SECURE = "<listener port='8443' secure='true' cert-file='c.pem' key-file='k.pem' ";

    @Test
    public void cipherSuitesAndNamedGroupsApplyToTcpAndQuicListeners() throws Exception {
        load("<server>" + SECURE + "cipher-suites='TLS_AES_128_GCM_SHA256' "
                + "named-groups='X25519:SECP256R1'/></server>");
        assertNull(error, error);
        assertEquals(2, server.getListeners().size());
        for (int i = 0; i < 2; i++) {
            assertEquals("TLS_AES_128_GCM_SHA256", field(listener(i), "cipherSuites"));
            assertEquals("X25519:SECP256R1", field(listener(i), "namedGroups"));
        }
    }

    @Test
    public void tlsVersionAppliesToTheTcpListener() {
        load("<server>" + SECURE + "tls-version='TLS_1_2'/></server>");
        assertNull(error, error);
        assertEquals(org.bluezoo.gumdrop.tls.TlsVersion.TLS_1_2, listener(0).getTlsVersion());
    }

    @Test
    public void unknownTlsVersionIsRejected() {
        load("<server>" + SECURE + "tls-version='SSL_3'/></server>");
        assertError("tls-version");
    }

    @Test
    public void clientAuthIsOffByDefaultAndMustBeNoneOrRequired() throws Exception {
        load("<server>" + SECURE + "/></server>");
        assertNull(error, error);
        assertEquals(Boolean.FALSE, field(listener(0), "needClientAuth"));
        server = null;
        load("<server>" + SECURE + "client-auth='sometimes'/></server>");
        assertError("client-auth");
    }

    @Test
    public void clientAuthWithPemCaBundleTrustsThatCa() throws Exception {
        Path dir = Files.createTempDirectory("server-xml-test");
        TestCertificates.Identity ca = TestCertificates.newCa(TestCertificates.KeyKind.EC_P256, "Test CA");
        TestCertificates.writeCertificatePem(dir, "ca.pem", ca);
        loadIn(dir.toFile(), "<server>" + SECURE + "client-auth='required' ca-file='ca.pem'/></server>");
        assertNull(error, error);
        for (int i = 0; i < 2; i++) {
            assertEquals(Boolean.TRUE, field(listener(i), "needClientAuth"));
            assertNotNull(field(listener(i), "trustManager"));
        }
    }

    @Test
    public void clientAuthWithTruststoreTrustsThatStore() throws Exception {
        Path dir = Files.createTempDirectory("server-xml-test");
        TestCertificates.Identity ca = TestCertificates.newCa(TestCertificates.KeyKind.EC_P256, "Test CA");
        TestCertificates.writeKeyStore(dir, "trust.p12", ca, "ca", "pw".toCharArray());
        loadIn(dir.toFile(), "<server>" + SECURE + "client-auth='required' "
                + "truststore-file='trust.p12' truststore-pass='pw'/></server>");
        assertNull(error, error);
        assertEquals(Boolean.TRUE, field(listener(0), "needClientAuth"));
        assertNotNull(field(listener(0), "trustManager"));
    }

    @Test
    public void missingCaFileIsReportedByName() {
        load("<server>" + SECURE + "client-auth='required' ca-file='absent.pem'/></server>");
        assertError("ca-file");
    }

    @Test
    public void caFileAndTruststoreAreMutuallyExclusive() {
        load("<server>" + SECURE + "client-auth='required' ca-file='ca.pem' "
                + "truststore-file='t.p12' truststore-pass='pw'/></server>");
        assertError("ca-file");
    }

    @Test
    public void trustMaterialWithoutClientAuthIsRejected() {
        load("<server>" + SECURE + "ca-file='ca.pem'/></server>");
        assertError("client-auth");
    }

    @Test
    public void pemIdentityFilesAreWiredToTheListeners() throws Exception {
        Path dir = Files.createTempDirectory("server-xml-test");
        TestCertificates.Identity id = TestCertificates.newEc256("pem.example");
        TestCertificates.writeCertificatePem(dir, "cert.pem", id);
        TestCertificates.writePrivateKeyPem(dir, "key.pem", id);
        loadIn(dir.toFile(), "<server><listener port='8443' secure='true' "
                + "cert-file='cert.pem' key-file='key.pem'/></server>");
        assertNull(error, error);
        // the HTTP/3 listener keeps its own copy of the PEM paths
        assertEquals(dir.resolve("cert.pem"), field(listener(0), "certFile"));
        assertEquals(dir.resolve("key.pem"), field(listener(0), "keyFile"));
        for (String name : new String[] { "certFile", "keyFile" }) {
            java.lang.reflect.Field f =
                    org.bluezoo.gumdrop.http.h3.Http3Listener.class.getDeclaredField(name);
            f.setAccessible(true);
            assertEquals(dir.resolve(name.equals("certFile") ? "cert.pem" : "key.pem"),
                    f.get(listener(1)));
        }
        assertNotNull(org.bluezoo.gumdrop.quic.tls.PemCredentials.loadServerCredentials(
                dir.resolve("cert.pem"), dir.resolve("key.pem")));
    }

    @Test
    public void keystoreIdentityIsWiredToTheListeners() throws Exception {
        Path dir = Files.createTempDirectory("server-xml-test");
        TestCertificates.Identity id = TestCertificates.newEc256("ks.example");
        TestCertificates.writeKeyStore(dir, "ks.p12", id, "server", "pw".toCharArray());
        loadIn(dir.toFile(), "<server><listener port='8443' secure='true' "
                + "keystore-file='ks.p12' keystore-pass='pw'/></server>");
        assertNull(error, error);
        for (int i = 0; i < 2; i++) {
            assertEquals(dir.resolve("ks.p12"), field(listener(i), "keystoreFile"));
            assertEquals("pw", field(listener(i), "keystorePass"));
        }
    }

    private static final String KEYSTORE = "<listener port='8443' secure='true' "
            + "keystore-file='ks.p12' keystore-pass='pw' ";

    @Test
    public void sniElementsMapHostnamesToKeystoreAliasesOnBothListeners() throws Exception {
        load("<server>" + KEYSTORE + "sni-default-alias='fallback'>"
                + "<sni host='example.com' alias='example-cert'/>"
                + "<sni host='*.example.org' alias='wild-cert'/>"
                + "</listener></server>");
        assertNull(error, error);
        assertEquals(2, server.getListeners().size());
        for (int i = 0; i < 2; i++) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) field(listener(i), "sniHostnameToAlias");
            assertEquals(2, map.size());
            assertEquals("example-cert", map.get("example.com"));
            assertEquals("wild-cert", map.get("*.example.org"));
            assertEquals("fallback", field(listener(i), "sniDefaultAlias"));
        }
    }

    @Test
    public void sniRequiresAKeystoreIdentity() {
        load("<server>" + SECURE + "><sni host='a.example' alias='a'/></listener></server>");
        assertError("keystore");
    }

    @Test
    public void sniDefaultAliasRequiresAKeystoreIdentity() {
        load("<server>" + SECURE + "sni-default-alias='a'/></server>");
        assertError("keystore");
    }

    @Test
    public void sniRequiresASecureListener() {
        load("<server><listener port='8080'><sni host='a.example' alias='a'/></listener></server>");
        assertError("secure listener");
    }

    @Test
    public void sniNeedsHostAndAlias() {
        load("<server>" + KEYSTORE + "><sni alias='a'/></listener></server>");
        assertError("requires a host");
        server = null;
        load("<server>" + KEYSTORE + "><sni host='a.example'/></listener></server>");
        assertError("requires a alias");
    }

    @Test
    public void duplicateSniHostIsRejected() {
        load("<server>" + KEYSTORE + "><sni host='a.example' alias='a'/>"
                + "<sni host='a.example' alias='b'/></listener></server>");
        assertError("a.example");
    }

    @Test
    public void sniOutsideAListenerIsRejected() {
        load("<server><sni host='a.example' alias='a'/><listener port='1'/></server>");
        assertError("inside a listener");
    }

    @Test
    public void listenersWithoutSniLeaveItDisabled() throws Exception {
        load("<server>" + KEYSTORE + "/></server>");
        assertNull(error, error);
        assertEquals(Boolean.FALSE, listener(0).isSNIEnabled());
    }

    private static final String HEX_63 = "123456789012345678901234567890123456789012345678901234567890123";

    private void assertError(String fragment) {
        assertNull("unexpected server", server);
        assertNotNull("expected error containing " + fragment, error);
        assertTrue(error, error.contains(fragment));
    }

    @Test
    public void minimalPlainListenerBuildsServer() {
        load("<server><listener port='8080'/></server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void wildcardListenerAccepted() {
        load("<server><listener port='8080' bind-wildcard='true'/></server>");
        assertNotNull(error, server);
    }

    @Test
    public void secureListenerBuildsH2AndH3() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-file='ks.p12' keystore-pass='pw' "
                + "bind-wildcard='true' ech-config-list-file='ech.cfg' "
                + "ech-private-key-file='ech.key' ech-required='true'/>"
                + "</server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void secureListenerRequiresKeystore() {
        load("<server><listener port='8443' secure='true'/></server>");
        assertError("keystore-file");
    }

    @Test
    public void secureListenerRequiresKeystorePass() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-file='ks.p12'/></server>");
        assertError("keystore-pass");
    }

    @Test
    public void secureListenerAcceptsPemFiles() {
        load("<server><listener port='8443' secure='true' "
                + "cert-file='tls/cert.pem' key-file='tls/key.pem' "
                + "bind-wildcard='true'/></server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void secureListenerAcceptsKeystoreFormat() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-file='ks.jks' keystore-pass='pw' "
                + "keystore-format='JKS'/></server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void keystoreFormatIsNotForPemFiles() {
        load("<server><listener port='8443' secure='true' "
                + "cert-file='cert.pem' key-file='key.pem' "
                + "keystore-format='JKS'/></server>");
        assertError("keystore-format");
    }

    @Test
    public void pemListenerRequiresKeyFile() {
        load("<server><listener port='8443' secure='true' "
                + "cert-file='cert.pem'/></server>");
        assertError("key-file");
    }

    @Test
    public void pemListenerRequiresCertFile() {
        load("<server><listener port='8443' secure='true' "
                + "key-file='key.pem'/></server>");
        assertError("cert-file");
    }

    @Test
    public void secureListenerRejectsKeystoreAndPemTogether() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-file='ks.p12' keystore-pass='pw' "
                + "cert-file='cert.pem' key-file='key.pem'/></server>");
        assertError("not both");
    }

    @Test
    public void secureListenerErrorMentionsBothWaysToGiveAnIdentity() {
        load("<server><listener port='8443' secure='true'/></server>");
        assertError("keystore-file");
        assertTrue(error, error.contains("cert-file"));
    }

    @Test
    public void listenerRequiresPort() {
        load("<server><listener/></server>");
        assertError("port");
    }

    @Test
    public void missingListenerRejected() {
        load("<server/>");
        assertError("at least one listener");
    }

    @Test
    public void unknownElementRejected() {
        load("<server><bogus/></server>");
        assertError("Unrecognised server.xml element: bogus");
    }

    @Test
    public void malformedXmlReportsParseError() {
        load("<server><listener port='8080'></server>");
        assertError("parse error");
    }

    @Test
    public void truncatedXmlReportsParseError() {
        load("<server><listener port='8080'/>");
        assertError("parse error");
    }

    @Test
    public void clusterRequiresPortAndKey() {
        load("<server><cluster key='k'/><listener port='1'/></server>");
        assertError("port");
        error = null;
        load("<server><cluster port='4000'/><listener port='1'/></server>");
        assertError("key");
    }

    @Test
    public void clusterAccepted() {
        load("<server><cluster port='4000' "
                + "key='00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff' "
                + "group-address='230.0.0.1'/><listener port='1'/></server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void contextRequiresPathAndRoot() {
        load("<server><context root='r'/><listener port='1'/></server>");
        assertError("path");
        error = null;
        load("<server><context path=''/><listener port='1'/></server>");
        assertError("root");
    }

    @Test
    public void realmRequiresNameAndClass() {
        load("<server><realm class='x'/><listener port='1'/></server>");
        assertError("name");
        error = null;
        load("<server><realm name='r'/><listener port='1'/></server>");
        assertError("class");
    }

    @Test
    public void realmWithUnknownClassRejected() {
        load("<server><realm name='r' class='no.such.Realm'/>"
                + "<listener port='1'/></server>");
        assertError("Cannot instantiate realm class");
    }

    @Test
    public void realmClassMustImplementRealm() {
        load("<server><realm name='r' class='java.lang.String'/>"
                + "<listener port='1'/></server>");
        assertError("Cannot instantiate realm class");
    }

    @Test
    public void realmAliasesRegistered() {
        load("<server><realm name='a, b ,,c' "
                + "class='org.bluezoo.gumdrop.auth.BasicRealm'/>"
                + "<listener port='1'/></server>");
        assertNull(error, error);
        assertNotNull(server);
    }

    @Test
    public void nonNumericListenerPortReportsError() {
        load("<server><listener port='http'/></server>");
        assertError("listener port");
    }

    @Test
    public void nonNumericClusterPortReportsError() {
        load("<server><cluster port='x' key='00'/><listener port='1'/></server>");
        assertError("cluster port");
    }

    @Test
    public void nonHexClusterKeyReportsError() {
        load("<server><cluster port='4000' key='not-hex'/>"
                + "<listener port='1'/></server>");
        assertError("cluster key");
    }

    @Test
    public void shortClusterKeyIsRejected() {
        load("<server><cluster port='4000' key='00112233445566778899aabbccddeeff'/>"
                + "<listener port='1'/></server>");
        assertError("cluster key");
        assertTrue(error, error.contains("64"));
    }

    @Test
    public void clusterKeyOneCharacterShortIsRejected() {
        load("<server><cluster port='4000' key='" + HEX_63 + "'/><listener port='1'/></server>");
        assertError("cluster key");
    }

    @Test
    public void clusterKeyOneCharacterLongIsRejected() {
        load("<server><cluster port='4000' key='" + HEX_63 + "00'/><listener port='1'/></server>");
        assertError("cluster key");
    }

    @Test
    public void clusterKeyOfExactlySixtyFourCharactersIsAccepted() {
        load("<server><cluster port='4000' key='" + HEX_63 + "0'/><listener port='1'/></server>");
        assertNull(error, error);
    }

    @Test
    public void clusterKeyBytesAreStoredAsGiven() {
        org.bluezoo.gumdrop.servlet.Container container = new org.bluezoo.gumdrop.servlet.Container();
        byte[] given = new byte[32];
        given[0] = (byte) 0xff;
        given[31] = (byte) 0xee;
        container.clusterKey(given);
        byte[] key = container.getClusterKey();
        assertEquals(32, key.length);
        assertEquals((byte) 0xff, key[0]);
        assertEquals((byte) 0xee, key[31]);
        given[0] = 0;
        assertEquals("the key is copied", (byte) 0xff, container.getClusterKey()[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void clusterKeyOfWrongLengthIsRejected() {
        new org.bluezoo.gumdrop.servlet.Container().clusterKey(new byte[16]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void clusterGroupAddressMustBeMulticast() throws Exception {
        new org.bluezoo.gumdrop.servlet.Container().clusterGroupAddress(
                java.net.InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 }));
    }

    @Test
    public void clusterGroupAddressLiteralsAreAccepted() {
        load("<server><cluster port='4000' group-address='ff12::8080' key='" + HEX_63 + "0'/>"
                + "<listener port='1'/></server>");
        assertNull(error, error);
        load("<server><cluster port='4000' group-address='224.0.80.81' key='" + HEX_63 + "0'/>"
                + "<listener port='1'/></server>");
        assertNull(error, error);
    }

    @Test
    public void clusterGroupAddressIsNeverResolvedAsAName() {
        load("<server><cluster port='4000' group-address='localhost' key='" + HEX_63 + "0'/>"
                + "<listener port='1'/></server>");
        assertError("group-address");
    }

    @Test
    public void clusterGroupAddressMustBeMulticastInXml() {
        load("<server><cluster port='4000' group-address='10.0.0.1' key='" + HEX_63 + "0'/>"
                + "<listener port='1'/></server>");
        assertError("group-address");
    }

    @Test
    public void emptyDocumentReportsErrorInsteadOfThrowing() {
        load("");
        assertNull(server);
        assertNotNull(error);
    }

    @Test
    public void realmHrefIsOnlySupportedForBasicRealm() {
        load("<server><realm name='r' class='org.bluezoo.gumdrop.auth.ldap.LdapRealm' "
                + "href='realm.xml'/><listener port='1'/></server>");
        assertError("realm href is only supported for BasicRealm");
        assertTrue(error, error.contains("LdapRealm"));
    }

    @Test
    public void keystorePassWithoutKeystoreFileIsRejected() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-pass='pw'/></server>");
        assertError("keystore-file");
    }

    @Test
    public void unknownKeystoreFormatIsRejected() {
        load("<server><listener port='8443' secure='true' "
                + "keystore-file='ks.p12' keystore-pass='pw' "
                + "keystore-format='BOGUS'/></server>");
        assertError("keystore-format must be PKCS12, JKS or JCEKS");
        assertTrue(error, error.contains("BOGUS"));
    }

    @Test
    public void clusterKeyWithNonAsciiDigitsIsRejected() {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            key.append('\uFF11');
        }
        load("<server><cluster port='4000' key='" + key + "'/><listener port='1'/></server>");
        assertError("cluster key");
    }
}
