/*
 * QuicCipherSuitesTest.java
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

package org.bluezoo.gumdrop.quic.tls;

import org.junit.Test;

import java.util.List;

import tech.kwik.agent15.TlsConstants;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuicCipherSuites#resolve}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicCipherSuitesTest {

    @Test
    public void testNullResolvesToDefault() {
        assertEquals(QuicCipherSuites.DEFAULT, QuicCipherSuites.resolve(null));
    }

    @Test
    public void testEmptyResolvesToDefault() {
        assertEquals(QuicCipherSuites.DEFAULT, QuicCipherSuites.resolve(""));
    }

    @Test
    public void testSingleSupportedCipherResolves() {
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256),
                QuicCipherSuites.resolve("TLS_CHACHA20_POLY1305_SHA256"));
    }

    @Test
    public void testMultipleSupportedCiphersPreserveConfiguredOrder() {
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                        TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256),
                QuicCipherSuites.resolve("TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256"));
    }

    @Test
    public void testUnimplementedCcmSuiteSkipped() {
        // TLS_AES_128_CCM_SHA256 is a real Agent15 cipher (RFC 9001
        // section 5.3 permits CCM) but gumdrop's AEAD layer has no
        // algorithm for it -- must be dropped, not offered.
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_AES_256_GCM_SHA384),
                QuicCipherSuites.resolve("TLS_AES_128_CCM_SHA256:TLS_AES_256_GCM_SHA384"));
    }

    @Test
    public void testUnknownNameSkipped() {
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256),
                QuicCipherSuites.resolve("NOT_A_REAL_CIPHER:TLS_AES_128_GCM_SHA256"));
    }

    @Test
    public void testAllUnusableFallsBackToDefault() {
        assertEquals(QuicCipherSuites.DEFAULT,
                QuicCipherSuites.resolve("NOT_A_REAL_CIPHER:TLS_AES_128_CCM_8_SHA256"));
    }

    @Test
    public void testDuplicatesCollapsed() {
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256),
                QuicCipherSuites.resolve("TLS_AES_128_GCM_SHA256:TLS_AES_128_GCM_SHA256"));
    }

    @Test
    public void testBlankTokensIgnored() {
        assertEquals(List.of(TlsConstants.CipherSuite.TLS_AES_128_GCM_SHA256),
                QuicCipherSuites.resolve(":: TLS_AES_128_GCM_SHA256 :"));
    }

}
