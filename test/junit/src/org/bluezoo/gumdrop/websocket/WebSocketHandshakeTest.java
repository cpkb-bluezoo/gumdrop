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

import java.util.ArrayList;
import java.util.List;
import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.Header;
import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WebSocketHandshake} — RFC 6455 §4.
 * Covers accept value calculation (§1.3), key generation and validation,
 * upgrade request/response creation, and client-side response validation.
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
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Connection", "Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingUpgradeHeader() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Connection", "Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingConnectionHeader() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingKey() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Connection", "Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingVersion() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Connection", "Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeWrongVersion() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Connection", "Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "8");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeCaseInsensitiveHeaders() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "WebSocket");
        HeaderFields.add(headers, "Connection", "upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeConnectionWithMultipleValues() {
        List<Header> headers = new ArrayList<Header>();
        HeaderFields.add(headers, "Upgrade", "websocket");
        HeaderFields.add(headers, "Connection", "keep-alive, Upgrade");
        HeaderFields.add(headers, "Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        HeaderFields.add(headers, "Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    // ── createWebSocketResponse (RFC 6455 §4.2.2) ──

    @Test
    public void testCreateResponse() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = WebSocketHandshake.createWebSocketResponse(key, null);
        assertEquals("websocket", HeaderFields.getValue(response, "Upgrade"));
        assertEquals("Upgrade", HeaderFields.getValue(response, "Connection"));
        assertNotNull(HeaderFields.getValue(response, "Sec-WebSocket-Accept"));
        assertNull(HeaderFields.getValue(response, "Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateResponseWithProtocol() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = WebSocketHandshake.createWebSocketResponse(key, "graphql-ws");
        assertEquals("graphql-ws", HeaderFields.getValue(response, "Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateResponseAcceptMatchesCalculation() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = WebSocketHandshake.createWebSocketResponse(key, null);
        String expected = WebSocketHandshake.calculateAccept(key);
        assertEquals(expected, HeaderFields.getValue(response, "Sec-WebSocket-Accept"));
    }

    // ── createUpgradeRequest (RFC 6455 §4.1) ──

    @Test
    public void testCreateUpgradeRequest() {
        String key = WebSocketHandshake.generateKey();
        List<Header> request = WebSocketHandshake.createUpgradeRequest(key, null);
        assertEquals("websocket", HeaderFields.getValue(request, "Upgrade"));
        assertEquals("Upgrade", HeaderFields.getValue(request, "Connection"));
        assertEquals("13", HeaderFields.getValue(request, "Sec-WebSocket-Version"));
        assertEquals(key, HeaderFields.getValue(request, "Sec-WebSocket-Key"));
        assertNull(HeaderFields.getValue(request, "Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateUpgradeRequestWithProtocol() {
        String key = WebSocketHandshake.generateKey();
        List<Header> request = WebSocketHandshake.createUpgradeRequest(key, "chat");
        assertEquals("chat", HeaderFields.getValue(request, "Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateUpgradeRequestWithExtensions() {
        String key = WebSocketHandshake.generateKey();
        List<Header> request = WebSocketHandshake.createUpgradeRequest(key, null, "permessage-deflate");
        assertEquals("permessage-deflate", HeaderFields.getValue(request, "Sec-WebSocket-Extensions"));
    }

    // ── validateUpgradeResponse (RFC 6455 §4.1 step 5) ──

    @Test
    public void testValidateValidResponse() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = WebSocketHandshake.createWebSocketResponse(key, null);
        assertTrue(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingUpgrade() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = new ArrayList<Header>();
        HeaderFields.add(response, "Connection", "Upgrade");
        HeaderFields.add(response, "Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key));
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingConnection() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = new ArrayList<Header>();
        HeaderFields.add(response, "Upgrade", "websocket");
        HeaderFields.add(response, "Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key));
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingAccept() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = new ArrayList<Header>();
        HeaderFields.add(response, "Upgrade", "websocket");
        HeaderFields.add(response, "Connection", "Upgrade");
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseWrongAccept() {
        String key = WebSocketHandshake.generateKey();
        List<Header> response = new ArrayList<Header>();
        HeaderFields.add(response, "Upgrade", "websocket");
        HeaderFields.add(response, "Connection", "Upgrade");
        HeaderFields.add(response, "Sec-WebSocket-Accept", "wrongvalue");
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseAcceptMismatch() {
        String key1 = WebSocketHandshake.generateKey();
        String key2 = WebSocketHandshake.generateKey();
        List<Header> response = WebSocketHandshake.createWebSocketResponse(key2, null);
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key1, response));
    }

    // ── Full handshake round-trip ──

    @Test
    public void testFullHandshakeRoundTrip() {
        // Client generates key and creates upgrade request
        String clientKey = WebSocketHandshake.generateKey();
        List<Header> clientRequest = WebSocketHandshake.createUpgradeRequest(clientKey, "chat");

        // Server validates the upgrade
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(clientRequest));

        // Server creates response
        String serverKey = HeaderFields.getValue(clientRequest, "Sec-WebSocket-Key");
        String protocol = HeaderFields.getValue(clientRequest, "Sec-WebSocket-Protocol");
        List<Header> serverResponse = WebSocketHandshake.createWebSocketResponse(serverKey, protocol);

        // Client validates response
        assertTrue(WebSocketHandshake.validateUpgradeResponse(clientKey, serverResponse));

        // Protocol should be echoed back
        assertEquals("chat", HeaderFields.getValue(serverResponse, "Sec-WebSocket-Protocol"));
    }

    @Test
    public void testFullHandshakeWithoutProtocol() {
        String clientKey = WebSocketHandshake.generateKey();
        List<Header> clientRequest = WebSocketHandshake.createUpgradeRequest(clientKey, null);

        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(clientRequest));

        String serverKey = HeaderFields.getValue(clientRequest, "Sec-WebSocket-Key");
        List<Header> serverResponse = WebSocketHandshake.createWebSocketResponse(serverKey, null);

        assertTrue(WebSocketHandshake.validateUpgradeResponse(clientKey, serverResponse));
        assertNull(HeaderFields.getValue(serverResponse, "Sec-WebSocket-Protocol"));
    }
}
