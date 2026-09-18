/*
 * HttpTlsConfigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.http.server;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * {@link HttpTlsConfig} deprecated wrapper validation.
 */
public class HttpTlsConfigTest {

    @Test(expected = NullPointerException.class)
    public void testPemRejectsNullCert() {
        HttpTlsConfig.pem(null, Paths.get("key.pem"));
    }

    @Test(expected = NullPointerException.class)
    public void testKeystoreRejectsNullPass() {
        HttpTlsConfig.keystore(Paths.get("server.p12"), null);
    }

}
