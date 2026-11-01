/*
 * AsyncRealmDefaultsTest.java
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

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;

import static org.junit.Assert.*;

/**
 * The default methods of the asynchronous {@link Realm} interface itself,
 * against a realm that supplies only the abstract ones.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AsyncRealmDefaultsTest {

    private static final class Bare implements Realm {
        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return Collections.emptySet();
        }

        @Override
        public void passwordMatch(String username, String password, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }

        @Override
        public void getDigestHA1(String username, String realmName, RealmCallback<String> callback) {
            callback.completed(null);
        }

        @Override
        public void isUserInRole(String username, String role, RealmCallback<Boolean> callback) {
            callback.completed(Boolean.FALSE);
        }
    }

    @Test
    public void unknownUserAndNoDigestByDefault() {
        final Realm realm = new Bare();
        CapturedCallback<Boolean> exists = new CapturedCallback<Boolean>();
        realm.userExists("u", exists);
        assertFalse(exists.get().booleanValue());
        assertFalse(realm.supportsDigestHA1());
    }

    @Test
    public void scramIsReportedAsUnsupportedFailure() {
        CapturedCallback<Realm.ScramCredentials> cb = new CapturedCallback<Realm.ScramCredentials>();
        new Bare().getScramCredentials("u", cb);
        assertTrue(cb.failure() instanceof UnsupportedOperationException);
        assertNull(cb.value());
    }

    @Test
    public void tokensAndCertificatesAreNotRecognisedByDefault() {
        Realm realm = new Bare();
        CapturedCallback<Realm.TokenValidationResult> bearer = new CapturedCallback<Realm.TokenValidationResult>();
        realm.validateBearerToken("t", bearer);
        assertTrue(bearer.isDone());
        assertNull(bearer.value());
        CapturedCallback<Realm.TokenValidationResult> oauth = new CapturedCallback<Realm.TokenValidationResult>();
        realm.validateOAuthToken("t", oauth);
        assertTrue(oauth.isDone());
        assertNull(oauth.value());
        CapturedCallback<Realm.CertificateAuthenticationResult> cert =
                new CapturedCallback<Realm.CertificateAuthenticationResult>();
        realm.authenticateCertificate(null, cert);
        assertTrue(cert.isDone());
        assertNull(cert.value());
    }

    @Test
    public void authorizeAsRequiresIdentity() {
        Realm realm = new Bare();
        CapturedCallback<Boolean> same = new CapturedCallback<Boolean>();
        realm.authorizeAs("alice", "alice", same);
        assertTrue(same.get().booleanValue());
        CapturedCallback<Boolean> other = new CapturedCallback<Boolean>();
        realm.authorizeAs("alice", "bob", other);
        assertFalse(other.get().booleanValue());
    }

    @Test
    public void kerberosPrincipalMappingStripsTheRealm() {
        Realm realm = new Bare();
        CapturedCallback<String> none = new CapturedCallback<String>();
        realm.mapKerberosPrincipal(null, none);
        assertTrue(none.isDone());
        assertNull(none.value());
        CapturedCallback<String> qualified = new CapturedCallback<String>();
        realm.mapKerberosPrincipal("alice@EXAMPLE.COM", qualified);
        assertEquals("alice", qualified.get());
        CapturedCallback<String> bare = new CapturedCallback<String>();
        realm.mapKerberosPrincipal("alice", bare);
        assertEquals("alice", bare.get());
        CapturedCallback<String> leadingAt = new CapturedCallback<String>();
        realm.mapKerberosPrincipal("@EXAMPLE.COM", leadingAt);
        assertEquals("@EXAMPLE.COM", leadingAt.get());
    }

    @Test
    public void scramDerivationWithBadParametersIsWrapped() {
        try {
            Realm.ScramCredentials.derive("p", new byte[8], 0, "SHA-256");
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("SCRAM"));
            assertNotNull(expected.getCause());
        }
    }
}
