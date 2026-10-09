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

import static org.junit.Assert.assertEquals;
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
    public void dtlsCookieSettingsDefaultToOff() {
        TlsConfig tls = new TlsConfig();
        assertFalse(tls.isRequireCookie());
        assertNull(tls.getCookieSecret());
        assertEquals(0, tls.getMaxFragmentSize());
    }

    @Test
    public void dtlsCookieSecretIsCopiedInAndOut() {
        byte[] secret = new byte[] {1, 2, 3, 4};
        TlsConfig tls = new TlsConfig().requireCookie(true).cookieSecret(secret).maxFragmentSize(1200);
        secret[0] = 99;
        byte[] out = tls.getCookieSecret();
        assertEquals(1, out[0]);
        out[1] = 99;
        assertEquals(2, tls.getCookieSecret()[1]);
        assertTrue(tls.isRequireCookie());
        assertEquals(1200, tls.getMaxFragmentSize());
    }

    @Test
    public void dtlsCookieSettingsSurviveCopyAndMerge() {
        TlsConfig source = new TlsConfig().requireCookie(true)
                .cookieSecret(new byte[] {7}).maxFragmentSize(900);
        TlsConfig copy = new TlsConfig().copyFrom(source);
        assertTrue(copy.isRequireCookie());
        assertEquals(7, copy.getCookieSecret()[0]);
        assertEquals(900, copy.getMaxFragmentSize());

        TlsConfig merged = TlsConfig.effective(new TlsConfig(), source);
        assertTrue(merged.isRequireCookie());
        assertEquals(7, merged.getCookieSecret()[0]);
        assertEquals(900, merged.getMaxFragmentSize());

        TlsConfig overridden = TlsConfig.effective(new TlsConfig().maxFragmentSize(500), source);
        assertEquals(500, overridden.getMaxFragmentSize());
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


    @Test
    public void clientEchRequiredIsOffByDefaultAndSurvivesMergeAndCopy() {
        assertFalse(new TlsConfig().isClientEchRequired());
        TlsConfig required = new TlsConfig().clientEchRequired(true);
        assertTrue(required.isClientEchRequired());
        assertTrue(TlsConfig.effective(required, null).isClientEchRequired());
        assertTrue(TlsConfig.effective(new TlsConfig(), required).isClientEchRequired());
        assertTrue(new TlsConfig().copyFrom(required).isClientEchRequired());
    }

    @Test
    public void listenerAndHandshakeSettingsDefaultToUnset() {
        TlsConfig tls = new TlsConfig();
        assertNull(tls.getCipherSuites());
        assertNull(tls.getNamedGroups());
        assertNull(tls.getTlsVersion());
        assertNull(tls.getDtlsVersion());
        assertTrue(tls.getSniHostnames().isEmpty());
        assertNull(tls.getSniDefaultAlias());
        assertFalse(tls.isClientAuthRequired());
        assertFalse(tls.isEarlyDataEnabled());
    }

    @Test
    public void copyFromCopiesListenerAndHandshakeSettings() {
        TlsConfig source = new TlsConfig().cipherSuites("TLS_AES_128_GCM_SHA256")
                .namedGroups("X25519").tlsVersion(TlsVersion.TLS_1_2)
                .dtlsVersion(DtlsVersion.DTLS_1_3).sni("a.example", "a")
                .sniDefaultAlias("d").requireClientAuth(true).earlyData(true);
        TlsConfig copy = new TlsConfig().copyFrom(source);
        assertEquals("TLS_AES_128_GCM_SHA256", copy.getCipherSuites());
        assertEquals("X25519", copy.getNamedGroups());
        assertEquals(TlsVersion.TLS_1_2, copy.getTlsVersion());
        assertEquals(DtlsVersion.DTLS_1_3, copy.getDtlsVersion());
        assertEquals("a", copy.getSniHostnames().get("a.example"));
        assertEquals("d", copy.getSniDefaultAlias());
        assertTrue(copy.isClientAuthRequired());
        assertTrue(copy.isEarlyDataEnabled());
        source.sni("b.example", "b");
        assertNull("the copy is independent", copy.getSniHostnames().get("b.example"));
    }

    @Test
    public void effectiveMergesSniAndPrefersLocalSettings() {
        TlsConfig local = new TlsConfig().cipherSuites("L").sni("a.example", "local");
        TlsConfig fallback = new TlsConfig().cipherSuites("F").namedGroups("G")
                .sni("a.example", "fallback").sni("b.example", "b").requireClientAuth(true);
        TlsConfig out = TlsConfig.effective(local, fallback);
        assertEquals("L", out.getCipherSuites());
        assertEquals("G", out.getNamedGroups());
        assertEquals("local", out.getSniHostnames().get("a.example"));
        assertEquals("b", out.getSniHostnames().get("b.example"));
        assertTrue(out.isClientAuthRequired());
    }

    @Test(expected = NullPointerException.class)
    public void sniRejectsNullAlias() {
        new TlsConfig().sni("a.example", null);
    }
}
