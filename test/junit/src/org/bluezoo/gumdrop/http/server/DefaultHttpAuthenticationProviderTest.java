/*
 * DefaultHttpAuthenticationProviderTest.java
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


package org.bluezoo.gumdrop.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.junit.Test;

/**
 * {@link DefaultHttpAuthenticationProvider} adapts a {@link Realm}: the
 * authentication method is derived from the realm's SASL mechanisms.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultHttpAuthenticationProviderTest {

    private static final class StubRealm implements Realm {
        final Set<SaslMechanism> mechanisms;

        StubRealm(Set<SaslMechanism> mechanisms) {
            this.mechanisms = mechanisms;
        }

        @Override public Realm forSelectorLoop(SelectorLoop loop) { return this; }
        @Override public Set<SaslMechanism> getSupportedSASLMechanisms() { return mechanisms; }
        @Override public boolean passwordMatch(String username, String password) {
            return "u".equals(username) && "p".equals(password);
        }
        @Override public String getDigestHA1(String username, String realmName) {
            return "ha1-" + username + "-" + realmName;
        }
        @Override public String getPassword(String username) { return null; }
        @Override public boolean isUserInRole(String username, String role) { return false; }
        @Override public Realm.TokenValidationResult validateBearerToken(String token) {
            if ("good".equals(token)) {
                return Realm.TokenValidationResult.success("tokuser", null, "Bearer");
            }
            return Realm.TokenValidationResult.failure();
        }
        @Override public Realm.TokenValidationResult validateOAuthToken(String token) {
            return Realm.TokenValidationResult.failure();
        }
    }

    @Test
    public void testBasicWhenNoSpecialMechanisms() {
        Set<SaslMechanism> none = EnumSet.noneOf(SaslMechanism.class);
        DefaultHttpAuthenticationProvider p = new DefaultHttpAuthenticationProvider(new StubRealm(none));
        assertEquals("Basic realm=\"gumdrop\"", p.generateChallenge());
        String creds = Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.US_ASCII));
        assertTrue(p.authenticate("Basic " + creds).success);
        String bad = Base64.getEncoder().encodeToString("u:x".getBytes(StandardCharsets.US_ASCII));
        assertFalse(p.authenticate("Basic " + bad).success);
    }

    @Test
    public void testBearerWhenOauthBearerSupported() {
        Set<SaslMechanism> m = EnumSet.of(SaslMechanism.OAUTHBEARER);
        DefaultHttpAuthenticationProvider p = new DefaultHttpAuthenticationProvider(new StubRealm(m), "myrealm");
        assertEquals("Bearer realm=\"myrealm\"", p.generateChallenge());
        HttpAuthenticationProvider.AuthenticationResult ok = p.authenticate("Bearer good");
        assertTrue(ok.success);
        assertEquals("tokuser", ok.username);
        assertFalse(p.authenticate("Bearer bad").success);
    }

    @Test
    public void testDigestWhenDigestMd5Supported() {
        Set<SaslMechanism> m = EnumSet.of(SaslMechanism.DIGEST_MD5);
        DefaultHttpAuthenticationProvider p = new DefaultHttpAuthenticationProvider(new StubRealm(m), "dr");
        String challenge = p.generateChallenge();
        assertNotNull(challenge);
        assertTrue(challenge, challenge.startsWith("Digest realm=\"dr\""));
        assertTrue(p.supportsScheme("Digest"));
    }
}
