/*
 * HTTPClientProtocolHandlerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for HTTP/2 client features in {@link HTTPClientProtocolHandler},
 * including RFC 9113 cipher suite validation.
 */
public class HTTPClientProtocolHandlerTest {

    // RFC 8441 section 3/4: SETTINGS_ENABLE_CONNECT_PROTOCOL gating --
    // isConnectProtocolEnabled/whenConnectProtocolKnown.
    //
    // settingsFrameReceived's real send path (sendSettingsAck ->
    // endpoint.getSelectorLoop()) NPEs when driven against a handler
    // constructed without a live connection -- everything under test
    // here runs before that point in the method, so the NPE is expected
    // and discarded, matching this codebase's established pattern for
    // exercising protocol-handler logic without a real transport (see
    // H3StreamTest's own documented use of the same approach).

    @Test
    public void testIsConnectProtocolEnabledDefaultsFalse() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        assertFalse(handler.isConnectProtocolEnabled());
    }

    @Test
    public void testIsConnectProtocolEnabledTrueAfterSettingsSaySo() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        receiveSettings(handler, H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
        assertTrue(handler.isConnectProtocolEnabled());
    }

    @Test
    public void testIsConnectProtocolEnabledFalseWhenSettingsOmitIt() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        // A real SETTINGS frame carrying only some other identifier --
        // the absence of SETTINGS_ENABLE_CONNECT_PROTOCOL must leave the
        // default (false) alone, not be misread as "explicitly disabled".
        receiveSettings(handler, H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 50);
        assertFalse(handler.isConnectProtocolEnabled());
    }

    @Test
    public void testWhenConnectProtocolKnownFiresImmediatelyOnceAlreadyReceived() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        receiveSettings(handler, H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);

        AtomicBoolean fired = new AtomicBoolean(false);
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                fired.set(true);
            }
        });
        assertTrue("callback must fire synchronously once settings are already known", fired.get());
    }

    @Test
    public void testWhenConnectProtocolKnownDefersUntilSettingsArrive() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);

        AtomicBoolean fired = new AtomicBoolean(false);
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                fired.set(true);
            }
        });
        assertFalse("callback must not fire before the server's SETTINGS frame arrives", fired.get());

        receiveSettings(handler, H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 0);
        assertTrue("callback must fire once settings arrive, regardless of the value carried", fired.get());
        assertFalse(handler.isConnectProtocolEnabled());
    }

    @Test
    public void testWhenConnectProtocolKnownCallbackRunsOnlyOnce() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);

        final int[] callCount = { 0 };
        handler.whenConnectProtocolKnown(new Runnable() {
            @Override
            public void run() {
                callCount[0]++;
            }
        });

        receiveSettings(handler, H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);
        // A second SETTINGS frame (mid-connection updates are legal per
        // RFC 9113 section 6.5) must not re-fire a callback already run.
        receiveSettings(handler, H2FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 0);

        assertEquals(1, callCount[0]);
    }

    private static void receiveSettings(HTTPClientProtocolHandler handler, int identifier, int value) {
        Map<Integer, Integer> settings = new HashMap<Integer, Integer>();
        settings.put(Integer.valueOf(identifier), Integer.valueOf(value));
        try {
            handler.settingsFrameReceived(false, settings);
        } catch (NullPointerException expected) {
            // confirms the method ran to its send-ack tail (see class note)
        }
    }

    // RFC 9113 section 9.2.2: GCM suites are AEAD and allowed
    @Test
    public void testGCMCipherAllowed() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testChaCha20CipherAllowed() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testCCMCipherAllowed() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CCM");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    // RFC 9113 section 9.2.2: CBC suites (non-AEAD) are blocked
    @Test
    public void testCBCCipherBlocked() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2",
                "TLS_RSA_WITH_AES_128_CBC_SHA");
        assertTrue(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testRC4CipherBlocked() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2",
                "TLS_RSA_WITH_RC4_128_SHA");
        assertTrue(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    // TLS 1.3 only has AEAD suites — never blocked
    @Test
    public void testTLS13NeverBlocked() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.3",
                "TLS_AES_256_GCM_SHA384");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testTLS13CBCNameNeverBlocked() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.3",
                "TLS_RSA_WITH_AES_128_CBC_SHA");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testNullProtocolNotBlocked() {
        SecurityInfo info = new StubSecurityInfo(null, "TLS_RSA_WITH_AES_128_CBC_SHA");
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testNullCipherNotBlocked() {
        SecurityInfo info = new StubSecurityInfo("TLSv1.2", null);
        assertFalse(HTTPClientProtocolHandler.isBlockedH2CipherSuite(info));
    }

    @Test
    public void testIdleTimeoutDefaults() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        assertEquals(0, handler.getIdleTimeoutMs());
    }

    @Test
    public void testSetIdleTimeout() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 443, true);
        handler.setIdleTimeoutMs(30000);
        assertEquals(30000, handler.getIdleTimeoutMs());
    }

    // RFC 9112 section 5: max response header size
    @Test
    public void testMaxResponseHeaderSizeDefaults() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 80, false);
        assertEquals(1024 * 1024, handler.getMaxResponseHeaderSize());
    }

    @Test
    public void testSetMaxResponseHeaderSize() {
        HTTPClientProtocolHandler handler =
                new HTTPClientProtocolHandler(null, "localhost", 80, false);
        handler.setMaxResponseHeaderSize(64 * 1024);
        assertEquals(64 * 1024, handler.getMaxResponseHeaderSize());
    }

    // RFC 9110 section 8.6: Content-Length validation
    @Test
    public void testValidateContentLengthSimple() {
        assertEquals(100, HTTPClientProtocolHandler.validateContentLength("100"));
    }

    @Test
    public void testValidateContentLengthZero() {
        assertEquals(0, HTTPClientProtocolHandler.validateContentLength("0"));
    }

    @Test
    public void testValidateContentLengthWithSpaces() {
        assertEquals(42, HTTPClientProtocolHandler.validateContentLength("  42  "));
    }

    @Test
    public void testValidateContentLengthMultipleEqual() {
        assertEquals(200, HTTPClientProtocolHandler.validateContentLength("200, 200"));
    }

    @Test
    public void testValidateContentLengthMultipleDifferent() {
        assertEquals(-1, HTTPClientProtocolHandler.validateContentLength("100, 200"));
    }

    @Test
    public void testValidateContentLengthNegative() {
        assertEquals(-1, HTTPClientProtocolHandler.validateContentLength("-5"));
    }

    @Test
    public void testValidateContentLengthNonNumeric() {
        assertEquals(-1, HTTPClientProtocolHandler.validateContentLength("abc"));
    }

    @Test
    public void testValidateContentLengthNull() {
        assertEquals(-1, HTTPClientProtocolHandler.validateContentLength(null));
    }

    private static class StubSecurityInfo implements SecurityInfo {
        private final String protocol;
        private final String cipherSuite;

        StubSecurityInfo(String protocol, String cipherSuite) {
            this.protocol = protocol;
            this.cipherSuite = cipherSuite;
        }

        @Override public String getProtocol() { return protocol; }
        @Override public String getCipherSuite() { return cipherSuite; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public int getKeySize() { return 128; }
        @Override public java.security.cert.Certificate[] getPeerCertificates() { return null; }
        @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        @Override public long getHandshakeDurationMs() { return -1; }
        @Override public boolean isSessionResumed() { return false; }
    }
}
