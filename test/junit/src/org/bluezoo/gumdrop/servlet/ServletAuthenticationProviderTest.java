/*
 * ServletAuthenticationProviderTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks that {@link ServletAuthenticationProvider} delegates credential
 * checks and token validation to the context's realm, and answers
 * conservatively when the realm is not configured.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletAuthenticationProviderTest {

    /** Realm with configurable digest support and token handling. */
    private static final class TestRealm implements Realm {
        final Set<SaslMechanism> mechanisms;

        TestRealm(Set<SaslMechanism> mechanisms) {
            this.mechanisms = mechanisms;
        }

        @Override
        public Realm forSelectorLoop(SelectorLoop loop) {
            return this;
        }

        @Override
        public Set<SaslMechanism> getSupportedSASLMechanisms() {
            return mechanisms;
        }

        @Override
        public boolean passwordMatch(String username, String password) {
            return "u".equals(username) && "p".equals(password);
        }

        @Override
        public String getDigestHA1(String username, String realmName) {
            return "ha1-" + username;
        }

        @Override
        public String getPassword(String username) {
            return "pw";
        }

        @Override
        public boolean isUserInRole(String username, String role) {
            return false;
        }

        @Override
        public Realm.TokenValidationResult validateBearerToken(String token) {
            return Realm.TokenValidationResult.success("bearer-" + token, new String[0], "Bearer");
        }

        @Override
        public Realm.TokenValidationResult validateOAuthToken(String token) {
            return Realm.TokenValidationResult.success("oauth-" + token, new String[0], "OAuth");
        }
    }

    public MemoryFolder tmp = new MemoryFolder();

    private Context context;
    private ServletAuthenticationProvider provider;

    @Before
    public void setUp() throws Exception {
        context = new Context(new Container(), "/auth", tmp.newFolder("auth"));
        provider = new ServletAuthenticationProvider(context);
    }

    private void login(String method, String realm) {
        LoginConfig config = new LoginConfig();
        config.authMethod = method;
        config.realmName = realm;
        context.setLoginConfig(config);
    }

    @Test
    public void testLoginConfigIsExposed() {
        login("BASIC", "main");
        assertEquals("BASIC", provider.getAuthMethod());
        assertEquals("main", provider.getRealmName());
    }

    @Test
    public void testCredentialChecksDelegateToRealm() {
        context.addRealm("main", new TestRealm(Collections.<SaslMechanism>emptySet()));
        assertTrue(provider.passwordMatch("main", "u", "p"));
        assertFalse(provider.passwordMatch("main", "u", "bad"));
        assertEquals("ha1-u", provider.getDigestHA1("main", "u"));
        assertFalse(provider.passwordMatch("absent", "u", "p"));
    }

    @Test
    public void testUnconfiguredRealmAnswersConservatively() {
        assertFalse(provider.supportsDigestAuth());
        assertNull(provider.validateBearerToken("t"));
        assertNull(provider.validateOAuthToken("t"));
        login("DIGEST", "ghost");
        assertFalse(provider.supportsDigestAuth());
        assertNull(provider.validateBearerToken("t"));
        assertNull(provider.validateOAuthToken("t"));
    }

    @Test
    public void testDigestSupportFollowsRealmMechanisms() {
        login("DIGEST", "main");
        context.addRealm("main", new TestRealm(Collections.<SaslMechanism>emptySet()));
        assertFalse(provider.supportsDigestAuth());
        context.addRealm("main", new TestRealm(EnumSet.of(SaslMechanism.DIGEST_MD5)));
        assertTrue(provider.supportsDigestAuth());
    }

    @Test
    public void testTokensAreValidatedByTheRealm() {
        login("BEARER", "main");
        context.addRealm("main", new TestRealm(Collections.<SaslMechanism>emptySet()));
        assertEquals("bearer-abc", provider.validateBearerToken("abc").username);
        assertEquals("oauth-xyz", provider.validateOAuthToken("xyz").username);
    }
}
