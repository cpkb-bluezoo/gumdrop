/*
 * WebSocketHandshake.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.http.websocket;

import org.bluezoo.gumdrop.http.Header;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
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
    public static boolean isValidWebSocketUpgrade(Collection<Header> headers) {
        boolean hasUpgradeWebSocket = false;
        boolean hasConnectionUpgrade = false;
        boolean hasWebSocketKey = false;
        boolean hasValidVersion = false;

        for (Header header : headers) {
            String name = header.getName();
            String value = header.getValue();

            if ("Upgrade".equalsIgnoreCase(name)) {
                // Check if websocket is in the upgrade values (case-insensitive)
                for (String v : value.split(",")) {
                    if ("websocket".equalsIgnoreCase(v.trim())) {
                        hasUpgradeWebSocket = true;
                        break;
                    }
                }
            } else if ("Connection".equalsIgnoreCase(name)) {
                // Check if Upgrade is in the connection values (case-insensitive)
                for (String v : value.split(",")) {
                    if ("Upgrade".equalsIgnoreCase(v.trim())) {
                        hasConnectionUpgrade = true;
                        break;
                    }
                }
            } else if ("Sec-WebSocket-Key".equalsIgnoreCase(name)) {
                hasWebSocketKey = (value != null && !value.trim().isEmpty());
            } else if ("Sec-WebSocket-Version".equalsIgnoreCase(name)) {
                hasValidVersion = WEBSOCKET_VERSION.equals(value.trim());
            }
        }

        boolean isValid = hasUpgradeWebSocket && hasConnectionUpgrade && hasWebSocketKey && hasValidVersion;
        
        if (!isValid) {
            LOGGER.fine(String.format("Invalid WebSocket upgrade request: " +
                "Upgrade=websocket:%s, Connection=Upgrade:%s, Sec-WebSocket-Key:%s, Version=13:%s",
                hasUpgradeWebSocket, hasConnectionUpgrade, hasWebSocketKey, hasValidVersion));
        }

        return isValid;
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
     * @return list of response headers for the WebSocket upgrade
     * @throws IllegalArgumentException if the key is invalid
     */
    public static List<Header> createWebSocketResponse(String key, String protocol) {
        List<Header> responseHeaders = new ArrayList<>();
        
        // Required upgrade headers
        responseHeaders.add(new Header("Upgrade", "websocket"));
        responseHeaders.add(new Header("Connection", "Upgrade"));
        
        // Calculate and add accept header
        String accept = calculateAccept(key);
        responseHeaders.add(new Header("Sec-WebSocket-Accept", accept));
        
        // Add protocol if negotiated
        if (protocol != null && !protocol.trim().isEmpty()) {
            responseHeaders.add(new Header("Sec-WebSocket-Protocol", protocol.trim()));
        }
        
        return responseHeaders;
    }

    /**
     * Extracts the value of the specified header from the header collection.
     * Header name matching is case-insensitive.
     *
     * @param headers the header collection
     * @param headerName the name of the header to find
     * @return the header value, or null if not found
     */
    public static String getHeaderValue(Collection<Header> headers, String headerName) {
        for (Header header : headers) {
            if (headerName.equalsIgnoreCase(header.getName())) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * Extracts all values of the specified header from the header collection.
     * For headers that can appear multiple times or contain comma-separated values.
     * Header name matching is case-insensitive.
     *
     * @param headers the header collection
     * @param headerName the name of the header to find
     * @return list of header values (may be empty)
     */
    public static List<String> getHeaderValues(Collection<Header> headers, String headerName) {
        List<String> values = new ArrayList<>();
        
        for (Header header : headers) {
            if (headerName.equalsIgnoreCase(header.getName())) {
                String value = header.getValue();
                if (value != null) {
                    // Split comma-separated values and trim whitespace
                    for (String v : value.split(",")) {
                        String trimmed = v.trim();
                        if (!trimmed.isEmpty()) {
                            values.add(trimmed);
                        }
                    }
                }
            }
        }
        
        return values;
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
