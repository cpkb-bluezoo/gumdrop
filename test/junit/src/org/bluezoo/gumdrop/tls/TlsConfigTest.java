/*
 * TlsConfigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.junit.Test;

import java.nio.file.Paths;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link TlsConfig} factory and material-application behaviour validation.
 */
public class TlsConfigTest {

    @Test(expected = NullPointerException.class)
    public void testPemRejectsNullCert() {
        TlsConfig.pem(null, Paths.get("key.pem"));
    }

    @Test(expected = NullPointerException.class)
    public void testKeystoreRejectsNullPass() {
        TlsConfig.keystore(Paths.get("server.p12"), null);
    }

    @Test
    public void secureIsAConsumerDecisionNotTlsConfigMaterial() {
        // secure() lives on the client/listener, not TlsConfig -- an empty
        // TlsConfig carries no opinion on immediacy either way.
        TlsConfig tls = new TlsConfig();
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientConnect.prepareTls(true, tls, factory);
        assertTrue(factory.isSecure());
    }

    @Test
    public void verifyPeerDefaultsToTrue() {
        TlsConfig tls = new TlsConfig();
        assertTrue(tls.isVerifyPeer());
    }

    @Test
    public void verifyPeerFalseAppliesEmptyTrustManagerToFactory() {
        TlsConfig tls = new TlsConfig().verifyPeer(false);
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientConnect.applyToTcpFactory(tls, factory);
        assertNotNull(factory.getTrustManager());
    }

    @Test
    public void trustManagerMaterialAppliedToFactory() {
        X509TrustManager custom = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }
            public void checkServerTrusted(X509Certificate[] c, String a) {
            }
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        TlsConfig tls = new TlsConfig().trustManager(custom);
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientConnect.applyToTcpFactory(tls, factory);
        assertSame(custom, factory.getTrustManager());
    }

    @Test
    public void trustJvmClearsCustomTrustManager() {
        TlsConfig tls = new TlsConfig().verifyPeer(false).trustJvm();
        assertTrue(tls.isVerifyPeer());
        assertNull(tls.getTrustManager());
    }

    @Test
    public void copyFromDuplicatesMaterial() {
        TlsConfig source = new TlsConfig()
                .trustJvm()
                .verifyPeer(false);
        TlsConfig copy = new TlsConfig().copyFrom(source);
        assertFalse(copy.isVerifyPeer());
    }

}
