/*
 * TCPTransportFactorySecurityTest.java
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

package org.bluezoo.gumdrop;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;

import static org.junit.Assert.*;

/**
 * Guards against accidentally disabling server hostname verification for
 * client-role TLS connections -- formerly checked via JSSE's {@code
 * SSLParameters.getEndpointIdentificationAlgorithm()} on an {@code
 * SSLEngine} built by {@code TcpTransportFactory.configureClientSSLEngine}
 * (removed with JSSE); the in-tree {@link HandshakeEngine} performs the
 * equivalent check itself, driven by {@link HandshakeConfig#isVerifyHostname()},
 * which {@link org.bluezoo.gumdrop.TcpTransportFactory} never overrides
 * when building a client config, so its default here is the property that
 * actually matters.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TCPTransportFactorySecurityTest {

    @Test
    public void testClientConfigVerifiesHostnameByDefault() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        assertTrue(config.isVerifyHostname());
    }
}
