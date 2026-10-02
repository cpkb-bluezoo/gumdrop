/*
 * DtlsHandshakeConfigWrapperTest.java
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

import org.bluezoo.gumdrop.testsupport.memfs.MemoryTemp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.bluezoo.gumdrop.testsupport.TestCertificates;

/**
 * Unit tests for {@link Dtls12HandshakeConfig}, {@link Dtls13HandshakeConfig}
 * and {@link EchClientBootstrap}: delegation to the wrapped config, copying
 * for an engine, and ECH config selection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsHandshakeConfigWrapperTest {

    private static final String PK_RM = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d";

    private static byte[] hex(String h) {
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return r;
    }

    @Test
    public void dtls12WrapperDelegatesEverySetting() throws Exception {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        Dtls12HandshakeConfig w = new Dtls12HandshakeConfig(base);
        assertSame(base, w.getBase());
        assertEquals(HandshakeRole.SERVER, w.getRole());
        assertFalse(w.isRequireCookie());
        w.setRequireCookie(true);
        assertTrue(w.isRequireCookie());
        byte[] secret = new byte[] { 1, 2, 3 };
        w.setCookieSecret(secret);
        assertArrayEquals(secret, w.getCookieSecret());
        w.setMaxFragmentSize(777);
        assertEquals(777, w.getMaxFragmentSize());

        List<java.security.cert.X509Certificate> chain = TestCertificates.ec256().getChain();
        ServerCredentials creds = new ServerCredentials(chain, TestCertificates.ec256().getPrivateKey());
        w.setServerCredentials(creds);
        assertSame(creds, w.getServerCredentials());
        w.setClientCredentials(creds);
        assertSame(creds, w.getClientCredentials());
        ServerCredentialsResolver resolver = null;
        w.setServerCredentialsResolver(resolver);
        assertNull(w.getServerCredentialsResolver());
        w.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        assertEquals(ClientAuthPolicy.REQUIRE, w.getClientAuthPolicy());
        javax.net.ssl.X509TrustManager tm = TestCertificates.ec256().trustManager();
        w.setClientTrustManager(tm);
        assertSame(tm, w.getClientTrustManager());
        w.setTrustManager(tm);
        assertSame(tm, w.getTrustManager());
        w.setServerName("example.org");
        assertEquals("example.org", w.getServerName());
        w.setApplicationProtocols(Collections.singletonList("h2"));
        assertEquals(Collections.singletonList("h2"), w.getApplicationProtocols());
        List<Tls12CipherSuite> suites = Collections.singletonList(
                Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256);
        w.setCipherSuites(suites);
        assertEquals(suites, w.getCipherSuites());
        TicketKeys keys = new TicketKeys(new byte[16]);
        w.setTicketKeys(keys);
        assertSame(keys, w.getTicketKeys());
        w.setClientTicketStore(null);
        assertNull(w.getClientTicketStore());

        Tls12HandshakeConfig copy = w.copyBaseForEngine();
        assertEquals(HandshakeRole.SERVER, copy.getRole());
        assertSame(creds, copy.getServerCredentials());
        assertEquals("example.org", copy.getServerName());
        assertEquals(suites, copy.getCipherSuites());
        assertSame(keys, copy.getTicketKeys());
        assertEquals(ClientAuthPolicy.REQUIRE, copy.getClientAuthPolicy());
    }

    @Test
    public void dtls13WrapperDelegatesEverySetting() throws Exception {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.CLIENT);
        Dtls13HandshakeConfig w = new Dtls13HandshakeConfig(base);
        assertSame(base, w.getBase());
        assertEquals(HandshakeRole.CLIENT, w.getRole());
        w.setRequireCookie(true);
        assertTrue(w.isRequireCookie());
        w.setCookieSecret(new byte[] { 9 });
        assertArrayEquals(new byte[] { 9 }, w.getCookieSecret());
        w.setMaxFragmentSize(555);
        assertEquals(555, w.getMaxFragmentSize());
        ServerCredentials creds = new ServerCredentials(TestCertificates.ec256().getChain(),
                TestCertificates.ec256().getPrivateKey());
        w.setServerCredentials(creds);
        assertSame(creds, w.getServerCredentials());
        w.setClientCredentials(creds);
        assertSame(creds, w.getClientCredentials());
        w.setServerCredentialsResolver(null);
        assertNull(w.getServerCredentialsResolver());
        w.setClientAuthPolicy(ClientAuthPolicy.REQUEST);
        assertEquals(ClientAuthPolicy.REQUEST, w.getClientAuthPolicy());
        javax.net.ssl.X509TrustManager tm = CertificateVerifier.trustManagerFromCertificates(
                TestCertificates.ec256().getChain());
        w.setClientTrustManager(tm);
        assertSame(tm, w.getClientTrustManager());
        w.setTrustManager(tm);
        assertSame(tm, w.getTrustManager());
        w.setServerName("example.org");
        assertEquals("example.org", w.getServerName());
        w.setApplicationProtocols(Collections.singletonList("h3"));
        assertEquals(Collections.singletonList("h3"), w.getApplicationProtocols());
        w.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_256_GCM_SHA384));
        assertEquals(Collections.singletonList(CipherSuite.TLS_AES_256_GCM_SHA384), w.getCipherSuites());
        w.setNamedGroups(Collections.singletonList(NamedGroup.SECP256R1));
        assertEquals(Collections.singletonList(NamedGroup.SECP256R1), w.getNamedGroups());
        TicketKeys keys = new TicketKeys(new byte[16]);
        w.setTicketKeys(keys);
        assertSame(keys, w.getTicketKeys());
        w.setSessionTicket(null);
        assertNull(w.getSessionTicket());

        HandshakeConfig copy = w.copyBaseForEngine();
        assertEquals(HandshakeMode.DTLS, copy.getMode());
        assertEquals(HandshakeRole.CLIENT, copy.getRole());
        assertSame(creds, copy.getServerCredentials());
        assertEquals("example.org", copy.getServerName());
        assertEquals(Collections.singletonList(NamedGroup.SECP256R1), copy.getNamedGroups());
        assertSame(keys, copy.getTicketKeys());
    }

    @Test
    public void dtls13CopyKeepsEchServerKeys() {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.SERVER);
        EchConfig ech = EchConfig.createV13(7, hex(PK_RM), "public.example", 32);
        base.addEchServerKey(ech, new byte[32]);
        base.setEchServerRequired(true);
        Dtls13HandshakeConfig w = new Dtls13HandshakeConfig(base);
        HandshakeConfig copy = w.copyBaseForEngine();
        assertEquals(1, copy.getEchServerKeys().size());
        assertTrue(copy.isEchServerRequired());
    }

    @Test
    public void echClientBootstrapSelectsFromDnsThenFile() throws Exception {
        EchConfig ech = EchConfig.createV13(3, hex(PK_RM), "public.example", 32);
        byte[] list = EchConfig.encodeList(new EchConfig[] { ech });
        EchConfig fromDns = EchClientBootstrap.selectConfig(list, null);
        assertNotNull(fromDns);
        assertEquals(3, fromDns.getConfigId());
        assertNull(EchClientBootstrap.selectConfig(null, null));
        assertNull(EchClientBootstrap.selectConfig(new byte[0], null));

        Path file = MemoryTemp.createTempFile("ech-list", ".bin");
        try {
            Files.write(file, list);
            EchConfig fromFile = EchClientBootstrap.selectConfig(null, file);
            assertNotNull(fromFile);
            assertEquals("public.example", fromFile.getPublicName());
        } finally {
            Files.delete(file);
        }
        Path missing = file.resolveSibling("ech-list-does-not-exist.bin");
        assertNull(EchClientBootstrap.selectConfig(null, missing));
    }

    @Test
    public void echClientBootstrapAppliesFlags() {
        EchConfig ech = EchConfig.createV13(3, hex(PK_RM), "public.example", 32);
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        EchClientBootstrap.applyToHandshakeConfig(c, null, false, false);
        assertFalse(c.isEchEnabled());
        assertFalse(c.isEchRequired());
        assertFalse(c.isEchGreaseEnabled());
        EchClientBootstrap.applyToHandshakeConfig(c, ech, true, true);
        assertTrue(c.isEchEnabled());
        assertSame(ech, c.getEchConfig());
        assertTrue(c.isEchRequired());
        assertTrue(c.isEchGreaseEnabled());
    }
}
