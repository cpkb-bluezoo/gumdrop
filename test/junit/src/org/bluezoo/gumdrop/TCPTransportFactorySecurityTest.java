/*
 * TCPTransportFactorySecurityTest.java
 * Copyright (C) 2026 Chris Burdess
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
 * SSLEngine} built by {@code TCPTransportFactory.configureClientSSLEngine}
 * (removed with JSSE); the in-tree {@link HandshakeEngine} performs the
 * equivalent check itself, driven by {@link HandshakeConfig#isVerifyHostname()},
 * which {@link org.bluezoo.gumdrop.TCPTransportFactory} never overrides
 * when building a client config, so its default here is the property that
 * actually matters.
 */
public class TCPTransportFactorySecurityTest {

    @Test
    public void testClientConfigVerifiesHostnameByDefault() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        assertTrue(config.isVerifyHostname());
    }
}
