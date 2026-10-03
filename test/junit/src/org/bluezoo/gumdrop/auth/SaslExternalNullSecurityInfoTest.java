/*
 * SaslExternalNullSecurityInfoTest.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.testsupport.RecordingStubEndpoint;

import static org.junit.Assert.*;

/**
 * SASL EXTERNAL must fail cleanly (not throw) when a secure endpoint has no
 * TLS session details.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SaslExternalNullSecurityInfoTest {

    @Test
    public void nullSecurityInfoFailsAuthentication() {
        RecordingStubEndpoint endpoint = new RecordingStubEndpoint(143);
        endpoint.setSecure(true);
        Realm realm = new Realm() {
            @Override
            public Realm forSelectorLoop(org.bluezoo.gumdrop.SelectorLoop loop) {
                return this;
            }

            @Override
            public java.util.Set<SaslMechanism> getSupportedSASLMechanisms() {
                return java.util.Collections.emptySet();
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
            @SuppressWarnings("deprecation")
            public String getPassword(String username) {
                return null;
            }

            @Override
            public boolean isUserInRole(String username, String role) {
                return false;
            }
        };
        Realm.CertificateAuthenticationResult result =
                SaslUtils.authenticateExternal(endpoint, realm, null);
        assertFalse(result.valid);
    }
}
