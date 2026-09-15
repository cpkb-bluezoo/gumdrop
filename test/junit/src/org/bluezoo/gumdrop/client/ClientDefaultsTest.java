/*
 * ClientDefaultsTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientDefaultsTest {

    @After
    public void tearDown() {
        ClientDefaults.setDefaultTls(null);
    }

    @Test
    public void effectiveTlsEmptyWhenNothingConfigured() {
        TlsConfig effective = ClientDefaults.effectiveTls(new TlsConfig());
        assertNull(effective.getServerCredentials());
        assertTrue(effective.isVerifyPeer());
    }

    @Test
    public void processDefaultFillsMissingClientMaterial() {
        ServerCredentials credentials = new ServerCredentials(null, null);
        ClientDefaults.setDefaultTls(new TlsConfig().serverCredentials(credentials));
        TlsConfig effective = ClientDefaults.effectiveTls(new TlsConfig());
        assertSame(credentials, effective.getServerCredentials());
    }

    @Test
    public void perClientWinsOverProcessDefault() {
        ServerCredentials defaultCredentials = new ServerCredentials(null, null);
        ServerCredentials clientCredentials = new ServerCredentials(null, null);
        ClientDefaults.setDefaultTls(new TlsConfig().serverCredentials(defaultCredentials));
        TlsConfig effective = ClientDefaults.effectiveTls(
                new TlsConfig().serverCredentials(clientCredentials));
        assertSame(clientCredentials, effective.getServerCredentials());
    }

    @Test
    public void perClientVerifyPeerWinsWhenClientHasMaterial() {
        ClientDefaults.setDefaultTls(new TlsConfig().verifyPeer(true));
        TlsConfig effective = ClientDefaults.effectiveTls(
                new TlsConfig().verifyPeer(false)
                        .serverCredentials(new ServerCredentials(null, null)));
        assertFalse(effective.isVerifyPeer());
    }

}
