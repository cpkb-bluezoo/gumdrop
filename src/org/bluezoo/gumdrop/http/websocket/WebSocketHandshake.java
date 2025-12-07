/*
 * WebSocketHandshake.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.websocket;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * WebSocket handshake utilities implementing RFC 6455.
 * Handles the WebSocket upgrade handshake including key validation,
 * accept value calculation, and response header generation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 */
public class WebSocketHandshake {

    private static final Logger LOGGER = Logger.getLogger(WebSocketHandshake.class.getName());

    /**
     * WebSocket Protocol GUID as defined in RFC 6455 Section 1.3.
     * This constant is concatenated with the client's key to generate the accept value.
     */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * Required WebSocket version as per RFC 6455.
     */
    private static final String WEBSOCKET_VERSION = "13";

    /**
     * Calculates the Sec-WebSocket-Accept value for the given client key.
     * 
     * <p>Implementation follows RFC 6455 Section 1.3:
     * <ol>
     * <li>Concatenate the client key with the WebSocket GUID</li>
     * <li>Calculate SHA-1 hash of the concatenated string</li>
     * <li>Base64 encode the hash</li>
     * </ol>
     *
     * @param key the Sec-WebSocket-Key value from the client request
     * @return the calculated Sec-WebSocket-Accept value
     * @throws IllegalArgumentException if the key is null or invalid
     */
    public static String calculateAccept(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("WebSocket key cannot be null or empty");
        }

        try {
            // RFC 6455: key + GUID
            String combined = key + WEBSOCKET_GUID;
            
            // Calculate SHA-1 hash
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(combined.getBytes("UTF-8"));
            
            // Base64 encode the hash
            return Base64.getEncoder().encodeToString(hash);
            
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 should always be available
            throw new RuntimeException("SHA-1 algorithm not available", e);
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 should always be available
            throw new RuntimeException("UTF-8 encoding not available", e);
        }
    }

    /**
     * Validates whether the request headers represent a valid WebSocket upgrade request.
     * 
     * <p>Checks for the presence and correct values of required headers:
     * <ul>
     * <li><code>Upgrade: websocket</code> (case-insensitive)</li>
     * <li><code>Connection: Upgrade</code> (case-insensitive, may contain other values)</li>
     * <li><code>Sec-WebSocket-Key</code> (present and non-empty)</li>
     * <li><code>Sec-WebSocket-Version: 13</code></li>
     * </ul>
     *
     * @param headers the HTTP request headers
     * @return true if this is a valid WebSocket upgrade request
     */
    public static boolean isValidWebSocketUpgrade(Headers headers) {
        // Check Upgrade header contains "websocket"
        String upgradeValue = headers.getCombinedValue("Upgrade");
        boolean hasUpgradeWebSocket = containsIgnoreCase(upgradeValue, "websocket");

        // Check Connection header contains "Upgrade"
        String connectionValue = headers.getCombinedValue("Connection");
        boolean hasConnectionUpgrade = containsIgnoreCase(connectionValue, "Upgrade");

        // Check Sec-WebSocket-Key is present and non-empty
        String key = headers.getValue("Sec-WebSocket-Key");
        boolean hasWebSocketKey = (key != null && !key.trim().isEmpty());

        // Check Sec-WebSocket-Version is "13"
        String version = headers.getValue("Sec-WebSocket-Version");
        boolean hasValidVersion = (version != null && WEBSOCKET_VERSION.equals(version.trim()));

        boolean isValid = hasUpgradeWebSocket && hasConnectionUpgrade && hasWebSocketKey && hasValidVersion;
        
        if (!isValid) {
            LOGGER.fine(String.format("Invalid WebSocket upgrade request: " +
                "Upgrade=websocket:%s, Connection=Upgrade:%s, Sec-WebSocket-Key:%s, Version=13:%s",
                hasUpgradeWebSocket, hasConnectionUpgrade, hasWebSocketKey, hasValidVersion));
        }

        return isValid;
    }

    /**
     * Checks if a comma-separated header value contains the specified token (case-insensitive).
     */
    private static boolean containsIgnoreCase(String headerValue, String token) {
        if (headerValue == null) {
            return false;
        }
        for (String v : headerValue.split(",")) {
            if (token.equalsIgnoreCase(v.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the HTTP response headers for a successful WebSocket upgrade.
     * 
     * <p>Generates the required headers for a 101 Switching Protocols response:
     * <ul>
     * <li><code>Upgrade: websocket</code></li>
     * <li><code>Connection: Upgrade</code></li>
     * <li><code>Sec-WebSocket-Accept</code> (calculated from client key)</li>
     * <li><code>Sec-WebSocket-Protocol</code> (if protocol negotiated)</li>
     * </ul>
     *
     * @param key the Sec-WebSocket-Key from the client request
     * @param protocol the negotiated subprotocol (may be null)
     * @return headers for the WebSocket upgrade response
     * @throws IllegalArgumentException if the key is invalid
     */
    public static Headers createWebSocketResponse(String key, String protocol) {
        Headers responseHeaders = new Headers();
        
        // Required upgrade headers
        responseHeaders.add("Upgrade", "websocket");
        responseHeaders.add("Connection", "Upgrade");
        
        // Calculate and add accept header
        String accept = calculateAccept(key);
        responseHeaders.add("Sec-WebSocket-Accept", accept);
        
        // Add protocol if negotiated
        if (protocol != null && !protocol.trim().isEmpty()) {
            responseHeaders.add("Sec-WebSocket-Protocol", protocol.trim());
        }
        
        return responseHeaders;
    }

    /**
     * Validates that a WebSocket key conforms to RFC 6455 requirements.
     * The key must be a base64-encoded value that decodes to exactly 16 bytes.
     *
     * @param key the Sec-WebSocket-Key value to validate
     * @return true if the key is valid
     */
    public static boolean isValidWebSocketKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            return decoded.length == 16; // RFC 6455: key must be 16 bytes when decoded
        } catch (IllegalArgumentException e) {
            return false; // Invalid base64
        }
    }
}
