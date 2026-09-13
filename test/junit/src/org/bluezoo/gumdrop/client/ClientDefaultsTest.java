/*
 * ClientDefaultsTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import org.bluezoo.gumdrop.tls.ClientTlsConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientDefaultsTest {

    @After
    public void tearDown() {
        ClientDefaults.setDefaultTls(null);
    }

    @Test
    public void effectiveTlsPlaintextWhenNothingConfigured() {
        ClientTlsConfig effective = ClientDefaults.effectiveTls(new ClientTlsConfig());
        assertFalse(effective.isConfigured());
        assertFalse(effective.useImplicitTls());
    }

    @Test
    public void processDefaultFillsMissingClientMaterial() {
        ClientDefaults.setDefaultTls(new ClientTlsConfig().secure(true).trustJvm());
        ClientTlsConfig effective = ClientDefaults.effectiveTls(
                new ClientTlsConfig().secure(true));
        assertTrue(effective.useImplicitTls());
    }

    @Test
    public void perClientWinsOverProcessDefault() {
        ClientDefaults.setDefaultTls(new ClientTlsConfig().secure(false).trustJvm());
        ClientTlsConfig effective = ClientDefaults.effectiveTls(
                new ClientTlsConfig().secure(true).trustJvm());
        assertTrue(effective.useImplicitTls());
    }

}
