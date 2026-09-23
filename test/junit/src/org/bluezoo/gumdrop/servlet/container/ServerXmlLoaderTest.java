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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
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
        ServerXmlLoader.loadFromMemoryForTesting(new File("/nonexistent-base"),
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
    public void clusterKeyWithHighBitSetIsAcceptedAsThirtyTwoRawBytes() throws Exception {
        org.bluezoo.gumdrop.servlet.Container container = new org.bluezoo.gumdrop.servlet.Container();
        String hex = "ff00112233445566778899aabbccddeeff00112233445566778899aabbccddee";
        container.setClusterKey(hex);
        byte[] key = container.getClusterKey();
        assertEquals(32, key.length);
        assertEquals((byte) 0xff, key[0]);
        assertEquals((byte) 0xee, key[31]);
    }

    @Test
    public void leadingZeroClusterKeyKeepsAllThirtyTwoBytes() throws Exception {
        org.bluezoo.gumdrop.servlet.Container container = new org.bluezoo.gumdrop.servlet.Container();
        container.setClusterKey("0000000000000000000000000000000000000000000000000000000000000001");
        byte[] key = container.getClusterKey();
        assertEquals(32, key.length);
        assertEquals(1, key[31]);
    }
}
