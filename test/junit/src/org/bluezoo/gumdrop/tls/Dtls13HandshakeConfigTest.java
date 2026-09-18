/*
 * Dtls13HandshakeConfigTest.java
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

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Unit tests for {@link Dtls13HandshakeConfig} certificate-compression wiring.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
