/*
 * TCPTransportFactorySecurityTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import static org.junit.Assert.*;

public class TCPTransportFactorySecurityTest {

    @Test
    public void testClientEngineHasHostnameVerification() throws Exception {
        TCPTransportFactory factory = new TCPTransportFactory();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        SSLEngine engine = ctx.createSSLEngine("example.com", 443);
        factory.configureClientSSLEngine(engine);
        SSLParameters params = engine.getSSLParameters();
        assertEquals("HTTPS", params.getEndpointIdentificationAlgorithm());
    }
}
