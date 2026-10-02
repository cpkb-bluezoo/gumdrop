/*
 * BasicRealmHashAndXmlTest.java
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

package org.bluezoo.gumdrop.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link BasicRealm} hashed password schemes, certificate
 * authentication, SCRAM caching and XML configuration loading.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicRealmHashAndXmlTest {

    private static final String CERT_B64 =
            "MIIBPzCB5qADAgECAgkAq4yq548MdtwwCgYIKoZIzj0EAwMwEzERMA8GA1UEAxMI"
            + "Y29yZXRlc3QwIBcNMjYxMDAyMDcwNDU2WhgPMjEyNjA5MDgwNzA0NTZaMBMxETAP"
            + "BgNVBAMTCGNvcmV0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiWdYBF51"
            + "K/pRe6OXwsHiOgFphDQQZWJ8HAtqoXpJZwm7ePY9YwbQ+kv1oo+GkXZIPj4qD3Nh"
            + "0BeVkgudsuNJ2aMhMB8wHQYDVR0OBBYEFIFSgLfCliRJ2QmLooQ7Guf3R+vCMAoG"
            + "CCqGSM49BAMDA0gAMEUCIQD2dSmZ+j1JvsS7/BlxdcTs/ftxO9Nj2jVwi82Ij7gz"
            + "qwIga/njuOmiHUkCMYvZRUVnnzqLLehdKUsYCGfEU7ILztU=";

    private static X509Certificate loadCert() throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(CERT_B64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] digest(String alg, byte[] a, byte[] salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance(alg);
        md.update(a);
        if (salt != null) {
            md.update(salt);
        }
        return md.digest();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static BasicRealm realmWith(String user, String stored) {
        BasicRealm realm = new BasicRealm();
        realm.passwords.put(user, stored);
        return realm;
    }

    @Test
    public void sha1Hash() throws Exception {
        byte[] pw = "hunter2".getBytes(StandardCharsets.UTF_8);
        String stored = "{SHA}" + Base64.getEncoder().encodeToString(digest("SHA-1", pw, null));
        BasicRealm realm = realmWith("u", stored);
        assertTrue(realm.passwordMatch("u", "hunter2"));
        assertFalse(realm.passwordMatch("u", "hunter3"));
        assertFalse(realm.passwordMatch("u", ""));
    }

    @Test
    public void sha1HashWrongLength() {
        BasicRealm realm = realmWith("u", "{SHA}" + Base64.getEncoder().encodeToString(new byte[5]));
        assertFalse(realm.passwordMatch("u", "x"));
    }

    @Test
    public void sha256Hash() throws Exception {
        byte[] pw = "hunter2".getBytes(StandardCharsets.UTF_8);
        String stored = "{SHA256}" + Base64.getEncoder().encodeToString(digest("SHA-256", pw, null));
        BasicRealm realm = realmWith("u", stored);
        assertTrue(realm.passwordMatch("u", "hunter2"));
        assertFalse(realm.passwordMatch("u", "nope"));
        BasicRealm bad = realmWith("u", "{SHA256}" + Base64.getEncoder().encodeToString(new byte[3]));
        assertFalse(bad.passwordMatch("u", "x"));
    }

    @Test
    public void saltedSha1Hash() throws Exception {
        byte[] pw = "hunter2".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[] {1, 2, 3, 4};
        byte[] d = digest("SHA-1", pw, salt);
        String stored = "{SSHA}" + Base64.getEncoder().encodeToString(concat(d, salt));
        BasicRealm realm = realmWith("u", stored);
        assertTrue(realm.passwordMatch("u", "hunter2"));
        assertFalse(realm.passwordMatch("u", "other"));
        BasicRealm shortRealm = realmWith("u", "{SSHA}" + Base64.getEncoder().encodeToString(new byte[20]));
        assertFalse(shortRealm.passwordMatch("u", "x"));
    }

    @Test
    public void saltedSha256Hash() throws Exception {
        byte[] pw = "hunter2".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[] {9, 8, 7};
        byte[] d = digest("SHA-256", pw, salt);
        String stored = "{SSHA256}" + Base64.getEncoder().encodeToString(concat(d, salt));
        BasicRealm realm = realmWith("u", stored);
        assertTrue(realm.passwordMatch("u", "hunter2"));
        assertFalse(realm.passwordMatch("u", "other"));
        BasicRealm shortRealm = realmWith("u", "{SSHA256}" + Base64.getEncoder().encodeToString(new byte[32]));
        assertFalse(shortRealm.passwordMatch("u", "x"));
    }

    @Test
    public void invalidBase64InHashIsRejected() {
        String[] schemes = new String[] {"{SHA}", "{SHA256}", "{SSHA}", "{SSHA256}"};
        for (int i = 0; i < schemes.length; i++) {
            BasicRealm realm = realmWith("u", schemes[i] + "!!!not base64!!!");
            assertFalse(schemes[i], realm.passwordMatch("u", "x"));
        }
    }

    @Test
    public void pbkdf2VerifyViaRealm() {
        byte[] salt = new byte[] {5, 5, 5, 5, 5, 5, 5, 5};
        String stored = BasicRealm.createPbkdf2Hash("pw", salt, 10);
        BasicRealm realm = realmWith("u", stored);
        assertTrue(realm.passwordMatch("u", "pw"));
        assertFalse(realm.passwordMatch("u", "px"));
    }

    @Test
    public void pbkdf2MalformedSpecs() {
        String[] specs = new String[] {
            "{PBKDF2}nodollars",
            "{PBKDF2}10$onlyone",
            "{PBKDF2}abc$AAAA$AAAA",
            "{PBKDF2}0$AAAA$AAAA",
            "{PBKDF2}10$!!$AAAA"
        };
        for (int i = 0; i < specs.length; i++) {
            BasicRealm realm = realmWith("u", specs[i]);
            assertFalse(specs[i], realm.passwordMatch("u", "x"));
        }
    }

    @Test
    public void publicPbkdf2HashHasExpectedShape() {
        String h = BasicRealm.createPbkdf2Hash("pw");
        assertTrue(h.startsWith("{PBKDF2}210000$"));
        BasicRealm realm = realmWith("u", h);
        assertTrue(realm.passwordMatch("u", "pw"));
    }

    @Test
    public void hashedPasswordsHideSecretsFromPlaintextMechanisms() {
        BasicRealm realm = realmWith("u", "{SHA}AAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        assertNull(realm.getDigestHA1("u", "r"));
        assertNull(realm.getCramMD5Response("u", "<c>"));
        assertNull(realm.getApopResponse("u", "<t>"));
        assertNull(realm.getScramCredentials("u"));
        assertEquals("{SHA}AAAAAAAAAAAAAAAAAAAAAAAAAAA=", realm.getPassword("u"));
    }

    @Test
    public void certificateAuthentication() throws Exception {
        X509Certificate cert = loadCert();
        BasicRealm empty = new BasicRealm();
        assertNull(empty.authenticateCertificate(cert));
        assertFalse(empty.userExists("carol"));

        String fp = BasicRealm.computeSHA256Fingerprint(cert);
        assertNotNull(fp);
        assertEquals(95, fp.length());

        BasicRealm realm = new BasicRealm();
        realm.certFingerprints.put(fp, "carol");
        Realm.CertificateAuthenticationResult ok = realm.authenticateCertificate(cert);
        assertTrue(ok.valid);
        assertEquals("carol", ok.username);
        assertTrue(realm.userExists("carol"));

        BasicRealm other = new BasicRealm();
        other.certFingerprints.put("00:11", "dave");
        Realm.CertificateAuthenticationResult bad = other.authenticateCertificate(cert);
        assertFalse(bad.valid);
    }

    @Test
    public void xmlConfigurationLoadsUsersGroupsAndFingerprints() throws Exception {
        String xml = "<realm>\n"
            + "  <group id='g-admin' name='admin'/>\n"
            + "  <group id='g-only'/>\n"
            + "  <user name='alice' password='pw1' groups='g-admin g-only'/>\n"
            + "  <user name='bob' password='pw2' groups='undefined-group'/>\n"
            + "  <user name='cert' cert-fingerprint='SHA-256:AB:CD'/>\n"
            + "  <user name='blank' password='pw3' groups='   '/>\n"
            + "  <group name='legacy'>\n"
            + "    <user name='carl' password='pw4'/>\n"
            + "    <member name='alice'/>\n"
            + "  </group>\n"
            + "</realm>\n";
        Path file = Files.createTempFile("basicrealm", ".xml");
        try {
            Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
            BasicRealm realm = new BasicRealm();
            realm.setHref(file);
            assertTrue(realm.passwordMatch("alice", "pw1"));
            assertTrue(realm.isUserInRole("alice", "admin"));
            assertTrue(realm.isUserInRole("alice", "g-only"));
            assertTrue(realm.isUserInRole("alice", "legacy"));
            assertTrue(realm.isUserInRole("bob", "undefined-group"));
            assertTrue(realm.isUserInRole("carl", "legacy"));
            assertFalse(realm.isUserInRole("blank", "legacy"));
            assertTrue(realm.userExists("cert"));
            assertTrue(realm.certFingerprints.containsKey("ab:cd"));
            Set<SaslMechanism> mechs = realm.getSupportedSASLMechanisms();
            assertTrue(mechs.contains(SaslMechanism.EXTERNAL));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void xmlConfigurationViaStringHref() throws Exception {
        String xml = "<realm><user name='zed' password='{SHA}AAAAAAAAAAAAAAAAAAAAAAAAAAA='/></realm>";
        Path file = Files.createTempFile("basicrealm", ".xml");
        try {
            Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
            BasicRealm realm = new BasicRealm();
            realm.setHref(file.toUri().toString());
            assertTrue(realm.userExists("zed"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void missingConfigurationFileFails() throws IOException {
        Path file = Files.createTempFile("basicrealm", ".xml");
        Files.delete(file);
        BasicRealm realm = new BasicRealm();
        try {
            realm.setHref(file);
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        try {
            realm.setHref(file.toUri().toString());
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void malformedConfigurationFails() throws IOException {
        Path file = Files.createTempFile("basicrealm", ".xml");
        try {
            Files.write(file, "<realm><user".getBytes(StandardCharsets.UTF_8));
            BasicRealm realm = new BasicRealm();
            try {
                realm.setHref(file);
                fail("expected RuntimeException");
            } catch (RuntimeException expected) {
                assertNotNull(expected.getCause());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
