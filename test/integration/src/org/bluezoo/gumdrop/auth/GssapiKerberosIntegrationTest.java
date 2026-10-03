/*
 * GssapiKerberosIntegrationTest.java
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

package org.bluezoo.gumdrop.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.security.auth.Subject;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test of {@link GssapiServer} and {@link GssapiClientMechanism}
 * against the JDK's real Kerberos provider, using a keytab written to a
 * temporary directory. There is no KDC: the Kerberos realm is supplied
 * through system properties and every exchange here is decided locally (the
 * server accepts or rejects a token from the keytab alone; a client with no
 * credentials fails before any network access). The context logic proper is
 * unit tested with a mock context in {@code GssapiMockContextTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GssapiKerberosIntegrationTest {

    private static final String REALM = "EXAMPLE.COM";
    private static final String PRINCIPAL = "imap/mail.example.com@" + REALM;

    private static String previousRealm;
    private static String previousKdc;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private GssapiServer.GssapiExchange exchange;

    @BeforeClass
    public static void configureRealm() {
        previousRealm = System.getProperty("java.security.krb5.realm");
        previousKdc = System.getProperty("java.security.krb5.kdc");
        System.setProperty("java.security.krb5.realm", REALM);
        System.setProperty("java.security.krb5.kdc", "127.0.0.1");
    }

    @AfterClass
    public static void restoreRealm() {
        restore("java.security.krb5.realm", previousRealm);
        restore("java.security.krb5.kdc", previousKdc);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Before
    public void clearExchange() {
        exchange = null;
    }

    @After
    public void disposeExchange() {
        if (exchange != null) {
            exchange.dispose();
        }
    }

    /** Writes a version 2 keytab holding one AES256 key for the principal. */
    private Path writeKeytab() throws IOException {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        DataOutputStream e = new DataOutputStream(entry);
        e.writeShort(2);
        e.writeShort(REALM.length());
        e.write(REALM.getBytes(StandardCharsets.US_ASCII));
        String[] components = {"imap", "mail.example.com"};
        for (int i = 0; i < components.length; i++) {
            e.writeShort(components[i].length());
            e.write(components[i].getBytes(StandardCharsets.US_ASCII));
        }
        e.writeInt(1);
        e.writeInt(0);
        e.writeByte(1);
        e.writeShort(18);
        e.writeShort(32);
        for (int i = 0; i < 32; i++) {
            e.writeByte(i * 7 + 1);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream f = new DataOutputStream(file);
        f.writeByte(5);
        f.writeByte(2);
        f.writeInt(entry.size());
        f.write(entry.toByteArray());
        Path path = tmp.getRoot().toPath().resolve("service.keytab");
        Files.write(path, file.toByteArray());
        return path;
    }

    @Test
    public void serverLoadsKeytabAndCreatesExchange() throws Exception {
        Path keytab = writeKeytab();
        GssapiServer server = new GssapiServer(keytab, PRINCIPAL);
        assertEquals(PRINCIPAL, server.getServicePrincipal());
        exchange = server.createExchange();
        assertNotNull(exchange);
        assertFalse(exchange.isContextEstablished());
    }

    @Test
    public void serverRejectsGarbageToken() throws Exception {
        Path keytab = writeKeytab();
        GssapiServer server = new GssapiServer(keytab, PRINCIPAL);
        exchange = server.createExchange();
        try {
            exchange.acceptToken(new byte[] {1, 2, 3});
            fail("expected IOException");
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().startsWith("GSSAPI token rejected"));
        }
        assertFalse(exchange.isContextEstablished());
    }

    @Test
    public void clientWithoutCredentialsFailsLocally() throws Exception {
        GssapiClientMechanism client =
                new GssapiClientMechanism("ldap@ldap.example.com", new Subject());
        assertEquals("GSSAPI", client.getMechanismName());
        try {
            client.evaluateChallenge(new byte[0]);
            fail("expected IOException");
        } catch (IOException ex) {
            assertEquals("GSSAPI token exchange failed", ex.getMessage());
        }
        assertFalse(client.isComplete());
    }
}
