/*
 * Dtls13HandshakeConfigTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class Dtls13HandshakeConfigTest {

    @Test
    public void copyBaseForEnginePreservesCertificateCompressionPolicy() {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.CLIENT);
        base.setCertificateCompressionEnabled(false);
        base.setCertificateCompressionAlgorithms(
                Collections.singletonList(CertificateCompressionAlgorithm.ZLIB));
        base.setMaxDecompressedCertificateSize(8192);

        Dtls13HandshakeConfig wrapped = new Dtls13HandshakeConfig(base);
        HandshakeConfig copy = wrapped.copyBaseForEngine();

        assertFalse(copy.isCertificateCompressionEnabled());
        assertEquals(1, copy.getCertificateCompressionAlgorithms().size());
        assertEquals(CertificateCompressionAlgorithm.ZLIB,
                copy.getCertificateCompressionAlgorithms().get(0));
        assertEquals(8192, copy.getMaxDecompressedCertificateSize());
    }
}
