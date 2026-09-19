/*
 * TlsConfigTest.java
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
