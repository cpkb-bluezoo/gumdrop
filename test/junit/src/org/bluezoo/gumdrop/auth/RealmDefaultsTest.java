/*
 * RealmDefaultsTest.java
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

import org.bluezoo.gumdrop.SelectorLoop;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for the default methods and result types of {@link Realm}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RealmDefaultsTest {

    private static final class BareRealm implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return false;
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return null;
        }

        @Override
        public String getPassword(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }
    }

    @Test
    public void optionalFeaturesDefaultToUnsupported() {
        Realm realm = new BareRealm();
        assertFalse(realm.userExists("x"));
        assertNull(realm.validateBearerToken("t"));
        assertNull(realm.validateOAuthToken("t"));
        assertNull(realm.authenticateCertificate(null));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void cramMd5UnsupportedByDefault() {
        new BareRealm().getCramMD5Response("u", "c");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void apopUnsupportedByDefault() {
        new BareRealm().getApopResponse("u", "t");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void scramUnsupportedByDefault() {
        new BareRealm().getScramCredentials("u");
    }

    @Test
    public void authorizeAsRequiresIdentity() {
        Realm realm = new BareRealm();
        assertTrue(realm.authorizeAs("alice", "alice"));
        assertFalse(realm.authorizeAs("alice", "bob"));
    }

    @Test
    public void kerberosPrincipalMapping() {
        Realm realm = new BareRealm();
        assertNull(realm.mapKerberosPrincipal(null));
        assertEquals("alice", realm.mapKerberosPrincipal("alice@EXAMPLE.COM"));
        assertEquals("alice", realm.mapKerberosPrincipal("alice"));
        assertEquals("@EXAMPLE.COM", realm.mapKerberosPrincipal("@EXAMPLE.COM"));
    }

    @Test
    public void tokenValidationResults() {
        String[] scopes = { "read", "write" };
        Realm.TokenValidationResult ok = Realm.TokenValidationResult.success("alice", scopes, "Bearer");
        assertTrue(ok.valid);
        assertEquals("alice", ok.username);
        assertEquals("Bearer", ok.tokenType);
        assertEquals(0L, ok.expirationTime);
        assertFalse(ok.isExpired());
        assertTrue(ok.hasScope("read"));
        assertFalse(ok.hasScope("admin"));
        assertTrue(ok.toString().contains("username='alice'"));

        Realm.TokenValidationResult expired =
            Realm.TokenValidationResult.success("alice", null, "JWT", 1L);
        assertTrue(expired.isExpired());
        assertFalse(expired.hasScope("read"));

        Realm.TokenValidationResult future =
            Realm.TokenValidationResult.success("alice", scopes, "JWT", Long.MAX_VALUE / 2000L);
        assertFalse(future.isExpired());

        Realm.TokenValidationResult bad = Realm.TokenValidationResult.failure();
        assertFalse(bad.valid);
        assertNull(bad.username);
        assertEquals("TokenValidationResult{valid=false}", bad.toString());
    }

    @Test
    public void certificateAuthenticationResults() {
        Realm.CertificateAuthenticationResult ok = Realm.CertificateAuthenticationResult.success("bob");
        assertTrue(ok.valid);
        assertEquals("bob", ok.username);
        assertTrue(ok.toString().contains("username='bob'"));
        Realm.CertificateAuthenticationResult bad = Realm.CertificateAuthenticationResult.failure();
        assertFalse(bad.valid);
        assertNull(bad.username);
        assertTrue(bad.toString().contains("valid=false"));
    }

    @Test
    public void scramDerivationIsDeterministicAndSized() {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        Realm.ScramCredentials a = Realm.ScramCredentials.derive("pencil", salt, 4096, "SHA-256");
        Realm.ScramCredentials b = Realm.ScramCredentials.derive("pencil", salt, 4096, "SHA-256");
        assertEquals(Base64.getEncoder().encodeToString(salt), a.salt);
        assertEquals(4096, a.iterations);
        assertEquals(32, a.storedKey.length);
        assertEquals(32, a.serverKey.length);
        assertArrayEquals(a.storedKey, b.storedKey);
        assertArrayEquals(a.serverKey, b.serverKey);
        Realm.ScramCredentials other = Realm.ScramCredentials.derive("pencil2", salt, 4096, "SHA-256");
        assertFalse(Arrays.equals(a.storedKey, other.storedKey));
    }

    @Test(expected = RuntimeException.class)
    public void scramDerivationRejectsUnknownAlgorithm() {
        Realm.ScramCredentials.derive("p", new byte[8], 1, "NOPE-1");
    }
}
