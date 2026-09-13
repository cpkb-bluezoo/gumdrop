/*
 * TlsConfigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * {@link TlsConfig} factory validation.
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

}
