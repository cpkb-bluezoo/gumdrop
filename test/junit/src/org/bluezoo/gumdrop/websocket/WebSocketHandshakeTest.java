/*
 * WebSocketHandshakeTest.java
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

package org.bluezoo.gumdrop.websocket;

import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WebSocketHandshake} — RFC 6455 §4.
 * Covers accept value calculation (§1.3), key generation and validation,
 * upgrade request validation, and client-side response validation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketHandshakeTest {

    // ── calculateAccept (RFC 6455 §1.3) ──

    @Test
    public void testCalculateAcceptKnownVector() {
        // RFC 6455 §4.2.2 example: key "dGhlIHNhbXBsZSBub25jZQ=="
        // produces accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        String accept = WebSocketHandshake.calculateAccept("dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept);
    }

    @Test
    public void testCalculateAcceptDeterministic() {
        String key = WebSocketHandshake.generateKey();
        String accept1 = WebSocketHandshake.calculateAccept(key);
        String accept2 = WebSocketHandshake.calculateAccept(key);
        assertEquals(accept1, accept2);
    }

    @Test
    public void testCalculateAcceptDifferentKeysProduceDifferentValues() {
        String accept1 = WebSocketHandshake.calculateAccept(WebSocketHandshake.generateKey());
        String accept2 = WebSocketHandshake.calculateAccept(WebSocketHandshake.generateKey());
        assertNotEquals(accept1, accept2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCalculateAcceptNullKey() {
        WebSocketHandshake.calculateAccept(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCalculateAcceptEmptyKey() {
        WebSocketHandshake.calculateAccept("  ");
    }

    // ── generateKey (RFC 6455 §4.1 step 7) ──

    @Test
    public void testGenerateKeyIsBase64() {
        String key = WebSocketHandshake.generateKey();
        byte[] decoded = Base64.getDecoder().decode(key);
        assertEquals(16, decoded.length);
    }

    @Test
    public void testGenerateKeyIsRandom() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            keys.add(WebSocketHandshake.generateKey());
        }
        assertEquals("Keys should be unique", 50, keys.size());
    }

    // ── isValidWebSocketKey (RFC 6455 §4.2.1) ──

    @Test
    public void testValidKey() {
        String key = WebSocketHandshake.generateKey();
        assertTrue(WebSocketHandshake.isValidWebSocketKey(key));
    }

    @Test
    public void testValidKeyRfcExample() {
        assertTrue(WebSocketHandshake.isValidWebSocketKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void testInvalidKeyNull() {
        assertFalse(WebSocketHandshake.isValidWebSocketKey(null));
    }

    @Test
    public void testInvalidKeyEmpty() {
        assertFalse(WebSocketHandshake.isValidWebSocketKey(""));
        assertFalse(WebSocketHandshake.isValidWebSocketKey("  "));
    }

    @Test
    public void testInvalidKeyNotBase64() {
        assertFalse(WebSocketHandshake.isValidWebSocketKey("not!valid!base64!!!"));
    }

    @Test
    public void testInvalidKeyWrongLength() {
        // 8 bytes instead of 16
        String shortKey = Base64.getEncoder().encodeToString(new byte[8]);
        assertFalse(WebSocketHandshake.isValidWebSocketKey(shortKey));
    }

    @Test
    public void testInvalidKeyTooLong() {
        String longKey = Base64.getEncoder().encodeToString(new byte[32]);
        assertFalse(WebSocketHandshake.isValidWebSocketKey(longKey));
    }

    // ── isValidWebSocketUpgrade (RFC 6455 §4.2.1) ──

    @Test
    public void testValidUpgradeRequest() {
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", WebSocketHandshake.generateKey(), "13"));
    }

    @Test
    public void testUpgradeMissingUpgradeHeader() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                null, "Upgrade", WebSocketHandshake.generateKey(), "13"));
    }

    @Test
    public void testUpgradeMissingConnectionHeader() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", null, WebSocketHandshake.generateKey(), "13"));
    }

    @Test
    public void testUpgradeMissingKey() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", null, "13"));
    }

    @Test
    public void testUpgradeEmptyKey() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", "  ", "13"));
    }

    @Test
    public void testUpgradeMissingVersion() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", WebSocketHandshake.generateKey(), null));
    }

    @Test
    public void testUpgradeWrongVersion() {
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", WebSocketHandshake.generateKey(), "8"));
    }

    @Test
    public void testUpgradeCaseInsensitiveValues() {
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(
                "WebSocket", "upgrade", WebSocketHandshake.generateKey(), "13"));
    }

    @Test
    public void testUpgradeConnectionWithMultipleValues() {
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "keep-alive, Upgrade", WebSocketHandshake.generateKey(), "13"));
    }

    @Test
    public void testUpgradeTokenListWithOtherProtocols() {
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(
                "h2c, websocket", "Upgrade", WebSocketHandshake.generateKey(), " 13 "));
    }

    // ── validateUpgradeResponse (RFC 6455 §4.1 step 5) ──

    @Test
    public void testValidateValidResponse() {
        String key = WebSocketHandshake.generateKey();
        assertTrue(WebSocketHandshake.validateUpgradeResponse(key, "websocket", "Upgrade",
                WebSocketHandshake.calculateAccept(key)));
    }

    @Test
    public void testValidateResponseMissingUpgrade() {
        String key = WebSocketHandshake.generateKey();
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, null, "Upgrade",
                WebSocketHandshake.calculateAccept(key)));
    }

    @Test
    public void testValidateResponseMissingConnection() {
        String key = WebSocketHandshake.generateKey();
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, "websocket", null,
                WebSocketHandshake.calculateAccept(key)));
    }

    @Test
    public void testValidateResponseMissingAccept() {
        String key = WebSocketHandshake.generateKey();
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, "websocket", "Upgrade", null));
    }

    @Test
    public void testValidateResponseWrongAccept() {
        String key = WebSocketHandshake.generateKey();
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, "websocket", "Upgrade",
                "wrongvalue"));
    }

    @Test
    public void testValidateResponseAcceptMismatch() {
        String key1 = WebSocketHandshake.generateKey();
        String key2 = WebSocketHandshake.generateKey();
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key1, "websocket", "Upgrade",
                WebSocketHandshake.calculateAccept(key2)));
    }

    // ── Full handshake round-trip ──

    @Test
    public void testFullHandshakeRoundTrip() {
        // Client generates key; the server validates the upgrade it sends
        String clientKey = WebSocketHandshake.generateKey();
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(
                "websocket", "Upgrade", clientKey, "13"));

        // Server answers with the accept value for that key; client validates
        String accept = WebSocketHandshake.calculateAccept(clientKey);
        assertTrue(WebSocketHandshake.validateUpgradeResponse(
                clientKey, "websocket", "Upgrade", accept));
    }
}
