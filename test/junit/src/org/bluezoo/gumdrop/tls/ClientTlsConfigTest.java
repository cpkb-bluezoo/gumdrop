/*
 * ClientTlsConfigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.bluezoo.gumdrop.TcpTransportFactory;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientTlsConfigTest {

    @Test
    public void secureAloneDoesNotEnableTls() {
        ClientTlsConfig tls = new ClientTlsConfig().secure(true);
        assertFalse(tls.isConfigured());
        assertFalse(tls.useImplicitTls());
        assertFalse(tls.allowsTlsUpgrade());

        TcpTransportFactory factory = new TcpTransportFactory();
        tls.applyTo(factory);
        assertFalse(factory.isSecure());
    }

    @Test
    public void trustJvmEnablesImplicitTlsWhenSecure() {
        ClientTlsConfig tls = new ClientTlsConfig().secure(true).trustJvm();
        assertTrue(tls.isConfigured());
        assertTrue(tls.useImplicitTls());
        assertTrue(tls.allowsTlsUpgrade());

        TcpTransportFactory factory = new TcpTransportFactory();
        tls.applyTo(factory);
        assertTrue(factory.isSecure());
    }

    @Test
    public void verifyPeerAloneMarksConfigured() {
        ClientTlsConfig tls = new ClientTlsConfig().verifyPeer(false);
        assertTrue(tls.isConfigured());
        assertFalse(tls.useImplicitTls());
        assertTrue(tls.allowsTlsUpgrade());
    }

    @Test
    public void copyFromDuplicatesMaterial() {
        ClientTlsConfig source = new ClientTlsConfig()
                .secure(true)
                .trustJvm()
                .verifyPeer(true);
        ClientTlsConfig copy = new ClientTlsConfig().copyFrom(source);
        assertTrue(copy.useImplicitTls());
        assertTrue(copy.isVerifyPeer());
    }

}
