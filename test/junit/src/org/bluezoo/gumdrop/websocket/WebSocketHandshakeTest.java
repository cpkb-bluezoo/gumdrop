package org.bluezoo.gumdrop.websocket;

import org.bluezoo.gumdrop.http.Headers;
import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WebSocketHandshake} — RFC 6455 §4.
 * Covers accept value calculation (§1.3), key generation and validation,
 * upgrade request/response creation, and client-side response validation.
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
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Connection", "Upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingUpgradeHeader() {
        Headers headers = new Headers();
        headers.add("Connection", "Upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingConnectionHeader() {
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingKey() {
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Connection", "Upgrade");
        headers.add("Sec-WebSocket-Version", "13");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeMissingVersion() {
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Connection", "Upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeWrongVersion() {
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Connection", "Upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "8");
        assertFalse(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeCaseInsensitiveHeaders() {
        Headers headers = new Headers();
        headers.add("Upgrade", "WebSocket");
        headers.add("Connection", "upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    @Test
    public void testUpgradeConnectionWithMultipleValues() {
        Headers headers = new Headers();
        headers.add("Upgrade", "websocket");
        headers.add("Connection", "keep-alive, Upgrade");
        headers.add("Sec-WebSocket-Key", WebSocketHandshake.generateKey());
        headers.add("Sec-WebSocket-Version", "13");
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(headers));
    }

    // ── createWebSocketResponse (RFC 6455 §4.2.2) ──

    @Test
    public void testCreateResponse() {
        String key = WebSocketHandshake.generateKey();
        Headers response = WebSocketHandshake.createWebSocketResponse(key, null);
        assertEquals("websocket", response.getValue("Upgrade"));
        assertEquals("Upgrade", response.getValue("Connection"));
        assertNotNull(response.getValue("Sec-WebSocket-Accept"));
        assertNull(response.getValue("Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateResponseWithProtocol() {
        String key = WebSocketHandshake.generateKey();
        Headers response = WebSocketHandshake.createWebSocketResponse(key, "graphql-ws");
        assertEquals("graphql-ws", response.getValue("Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateResponseAcceptMatchesCalculation() {
        String key = WebSocketHandshake.generateKey();
        Headers response = WebSocketHandshake.createWebSocketResponse(key, null);
        String expected = WebSocketHandshake.calculateAccept(key);
        assertEquals(expected, response.getValue("Sec-WebSocket-Accept"));
    }

    // ── createUpgradeRequest (RFC 6455 §4.1) ──

    @Test
    public void testCreateUpgradeRequest() {
        String key = WebSocketHandshake.generateKey();
        Headers request = WebSocketHandshake.createUpgradeRequest(key, null);
        assertEquals("websocket", request.getValue("Upgrade"));
        assertEquals("Upgrade", request.getValue("Connection"));
        assertEquals("13", request.getValue("Sec-WebSocket-Version"));
        assertEquals(key, request.getValue("Sec-WebSocket-Key"));
        assertNull(request.getValue("Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateUpgradeRequestWithProtocol() {
        String key = WebSocketHandshake.generateKey();
        Headers request = WebSocketHandshake.createUpgradeRequest(key, "chat");
        assertEquals("chat", request.getValue("Sec-WebSocket-Protocol"));
    }

    @Test
    public void testCreateUpgradeRequestWithExtensions() {
        String key = WebSocketHandshake.generateKey();
        Headers request = WebSocketHandshake.createUpgradeRequest(key, null, "permessage-deflate");
        assertEquals("permessage-deflate", request.getValue("Sec-WebSocket-Extensions"));
    }

    // ── validateUpgradeResponse (RFC 6455 §4.1 step 5) ──

    @Test
    public void testValidateValidResponse() {
        String key = WebSocketHandshake.generateKey();
        Headers response = WebSocketHandshake.createWebSocketResponse(key, null);
        assertTrue(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingUpgrade() {
        String key = WebSocketHandshake.generateKey();
        Headers response = new Headers();
        response.add("Connection", "Upgrade");
        response.add("Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key));
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingConnection() {
        String key = WebSocketHandshake.generateKey();
        Headers response = new Headers();
        response.add("Upgrade", "websocket");
        response.add("Sec-WebSocket-Accept", WebSocketHandshake.calculateAccept(key));
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseMissingAccept() {
        String key = WebSocketHandshake.generateKey();
        Headers response = new Headers();
        response.add("Upgrade", "websocket");
        response.add("Connection", "Upgrade");
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseWrongAccept() {
        String key = WebSocketHandshake.generateKey();
        Headers response = new Headers();
        response.add("Upgrade", "websocket");
        response.add("Connection", "Upgrade");
        response.add("Sec-WebSocket-Accept", "wrongvalue");
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key, response));
    }

    @Test
    public void testValidateResponseAcceptMismatch() {
        String key1 = WebSocketHandshake.generateKey();
        String key2 = WebSocketHandshake.generateKey();
        Headers response = WebSocketHandshake.createWebSocketResponse(key2, null);
        assertFalse(WebSocketHandshake.validateUpgradeResponse(key1, response));
    }

    // ── Full handshake round-trip ──

    @Test
    public void testFullHandshakeRoundTrip() {
        // Client generates key and creates upgrade request
        String clientKey = WebSocketHandshake.generateKey();
        Headers clientRequest = WebSocketHandshake.createUpgradeRequest(clientKey, "chat");

        // Server validates the upgrade
        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(clientRequest));

        // Server creates response
        String serverKey = clientRequest.getValue("Sec-WebSocket-Key");
        String protocol = clientRequest.getValue("Sec-WebSocket-Protocol");
        Headers serverResponse = WebSocketHandshake.createWebSocketResponse(serverKey, protocol);

        // Client validates response
        assertTrue(WebSocketHandshake.validateUpgradeResponse(clientKey, serverResponse));

        // Protocol should be echoed back
        assertEquals("chat", serverResponse.getValue("Sec-WebSocket-Protocol"));
    }

    @Test
    public void testFullHandshakeWithoutProtocol() {
        String clientKey = WebSocketHandshake.generateKey();
        Headers clientRequest = WebSocketHandshake.createUpgradeRequest(clientKey, null);

        assertTrue(WebSocketHandshake.isValidWebSocketUpgrade(clientRequest));

        String serverKey = clientRequest.getValue("Sec-WebSocket-Key");
        Headers serverResponse = WebSocketHandshake.createWebSocketResponse(serverKey, null);

        assertTrue(WebSocketHandshake.validateUpgradeResponse(clientKey, serverResponse));
        assertNull(serverResponse.getValue("Sec-WebSocket-Protocol"));
    }
}
