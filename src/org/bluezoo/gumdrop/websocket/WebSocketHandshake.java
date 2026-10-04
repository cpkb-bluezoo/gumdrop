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

package org.bluezoo.gumdrop.websocket;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * WebSocket handshake utilities implementing RFC 6455 §4.
 * Handles the opening handshake for both server (§4.2) and client (§4.1)
 * sides, including key validation, accept value calculation, response
 * validation, and extension negotiation (§9).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 * @see WebSocketExtension
 * @see PerMessageDeflateExtension
 */
public class WebSocketHandshake {

    private static final Logger LOGGER = Logger.getLogger(WebSocketHandshake.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle(
        "org.bluezoo.gumdrop.websocket.L10N");

    /** RFC 6455 §1.3 — WebSocket Protocol GUID for accept value calculation. */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** RFC 6455 §4.1 — required WebSocket protocol version. */
    private static final String WEBSOCKET_VERSION = "13";

    /**
     * RFC 6455 §4.2.2 step 4 — calculates the Sec-WebSocket-Accept value:
     * concatenate key with GUID, SHA-1 hash, Base64 encode.
     *
     * @param key the Sec-WebSocket-Key value from the client request
     * @return the calculated Sec-WebSocket-Accept value
     * @throws IllegalArgumentException if the key is null or invalid
     */
    public static String calculateAccept(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(L10N.getString("err.key_null_or_empty"));
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
        } catch (UnsupportedEncodingException e) {
            // UTF-8 should always be available
            throw new RuntimeException("UTF-8 encoding not available", e);
        }
    }

    /**
     * RFC 6455 §4.2.1 — validates a server-side WebSocket upgrade request
     * from the values of its fields. Checks that the upgrade token list
     * contains {@code websocket}, the connection token list contains
     * {@code Upgrade}, the key is present and the version is {@code 13}.
     *
     * @param upgrade the combined value of the {@code Upgrade} fields (may be null)
     * @param connection the combined value of the {@code Connection} fields (may be null)
     * @param key the {@code Sec-WebSocket-Key} value (may be null)
     * @param version the {@code Sec-WebSocket-Version} value (may be null)
     * @return true if this is a valid WebSocket upgrade request
     */
    public static boolean isValidWebSocketUpgrade(String upgrade, String connection,
                                                  String key, String version) {
        boolean hasUpgradeWebSocket = containsIgnoreCase(upgrade, "websocket");
        boolean hasConnectionUpgrade = containsIgnoreCase(connection, "Upgrade");
        boolean hasWebSocketKey = (key != null && !key.trim().isEmpty());
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
        int start = 0;
        int length = headerValue.length();
        while (start <= length) {
            int end = headerValue.indexOf(',', start);
            if (end < 0) {
                end = length;
            }
            String v = headerValue.substring(start, end).trim();
            if (token.equalsIgnoreCase(v)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    /**
     * RFC 6455 §4.2.1 — validates that a Sec-WebSocket-Key is a
     * base64-encoded 16-byte nonce.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Client-side handshake utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RFC 6455 §4.1 step 7 — generates a 16-byte SecureRandom nonce,
     * Base64-encoded, for the Sec-WebSocket-Key header.
     *
     * @return a Base64-encoded 16-byte random key
     */
    public static String generateKey() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * RFC 6455 §4.1 step 5 — client-side validation of the server's 101
     * response from the values of its fields: checks Upgrade, Connection,
     * and Sec-WebSocket-Accept.
     *
     * @param sentKey the Sec-WebSocket-Key that was sent in the request
     * @param upgrade the combined value of the response's {@code Upgrade} fields (may be null)
     * @param connection the combined value of the response's {@code Connection} fields (may be null)
     * @param accept the response's {@code Sec-WebSocket-Accept} value (may be null)
     * @return true if the response is a valid WebSocket upgrade
     */
    public static boolean validateUpgradeResponse(String sentKey, String upgrade,
                                                  String connection, String accept) {
        if (!containsIgnoreCase(upgrade, "websocket")) {
            LOGGER.fine(L10N.getString("fine.upgrade_missing_upgrade"));
            return false;
        }

        if (!containsIgnoreCase(connection, "Upgrade")) {
            LOGGER.fine(L10N.getString("fine.upgrade_missing_connection"));
            return false;
        }

        if (accept == null) {
            LOGGER.fine(L10N.getString("fine.upgrade_missing_accept"));
            return false;
        }

        String expected = calculateAccept(sentKey);
        if (!expected.equals(accept.trim())) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("fine.upgrade_accept_mismatch"), expected, accept));
            return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension negotiation — RFC 6455 §9.1
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RFC 6455 §9.1 — an extension offer parsed from the
     * {@code Sec-WebSocket-Extensions} header.
     */
    public static class ExtensionOffer {
        private final String name;
        private final Map<String, String> params;

        ExtensionOffer(String name, Map<String, String> params) {
            this.name = name;
            this.params = params;
        }

        public String getName() { return name; }
        public Map<String, String> getParams() { return params; }
    }

    /**
     * RFC 6455 §9.1 — parses the {@code Sec-WebSocket-Extensions} header
     * into a list of offers. Each offer contains an extension name and
     * its parameters.
     *
     * <p>Grammar: {@code extension = token *( ";" extension-param )}
     *
     * @param headerValue the header value (may be null)
     * @return parsed offers (empty if none)
     */
    public static List<ExtensionOffer> parseExtensions(String headerValue) {
        List<ExtensionOffer> offers = new ArrayList<>();
        if (headerValue == null || headerValue.trim().isEmpty()) {
            return offers;
        }

        for (String offer : headerValue.split(",")) {
            offer = offer.trim();
            if (offer.isEmpty()) {
                continue;
            }
            String[] parts = offer.split(";");
            String name = parts[0].trim();
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                int eq = param.indexOf('=');
                if (eq > 0) {
                    params.put(param.substring(0, eq).trim(),
                               param.substring(eq + 1).trim());
                } else {
                    params.put(param, "");
                }
            }
            offers.add(new ExtensionOffer(name, params));
        }
        return offers;
    }

    /**
     * RFC 6455 §9.1 — client-side extension reconciliation. Parses a
     * server response's {@code Sec-WebSocket-Extensions} header and
     * matches each accepted offer, by name, against the extensions this
     * client requested, letting each matched extension confirm the
     * server's response parameters via
     * {@link WebSocketExtension#acceptResponse(Map)}.
     *
     * @param headerValue the response's {@code Sec-WebSocket-Extensions}
     *                     header value (may be null)
     * @param requested the extensions this client offered
     * @return the extensions actually active on this connection
     */
    public static List<WebSocketExtension> reconcileExtensions(
            String headerValue, List<WebSocketExtension> requested) {
        List<WebSocketExtension> active = new ArrayList<>();
        if (headerValue == null || headerValue.trim().isEmpty() || requested == null) {
            return active;
        }
        List<ExtensionOffer> accepted = parseExtensions(headerValue);
        for (ExtensionOffer offer : accepted) {
            for (WebSocketExtension ext : requested) {
                if (ext.getName().equals(offer.getName())) {
                    if (ext.acceptResponse(offer.getParams())) {
                        active.add(ext);
                    }
                    break;
                }
            }
        }
        return active;
    }

    /**
     * RFC 6455 §9.1 — server-side extension negotiation. Evaluates
     * each client offer against the list of supported extensions and
     * returns those that were accepted.
     *
     * @param offeredHeader the {@code Sec-WebSocket-Extensions} request header
     * @param supported the extensions this server supports
     * @return the negotiated (active) extensions
     */
    public static List<WebSocketExtension> negotiateExtensions(
            String offeredHeader, List<WebSocketExtension> supported) {
        List<WebSocketExtension> negotiated = new ArrayList<>();
        if (supported == null || supported.isEmpty()) {
            return negotiated;
        }
        List<ExtensionOffer> offers = parseExtensions(offeredHeader);
        for (ExtensionOffer offer : offers) {
            for (WebSocketExtension ext : supported) {
                if (ext.getName().equals(offer.getName())) {
                    Map<String, String> accepted = ext.acceptOffer(offer.getParams());
                    if (accepted != null) {
                        negotiated.add(ext);
                    }
                    break;
                }
            }
        }
        return negotiated;
    }

    /**
     * RFC 6455 §9.1 — formats a list of negotiated extensions for the
     * {@code Sec-WebSocket-Extensions} response header.
     *
     * @param extensions the negotiated extensions
     * @return the formatted header value, or null if none
     */
    public static String formatExtensions(List<WebSocketExtension> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WebSocketExtension ext : extensions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ext.getName());
        }
        return sb.toString();
    }

    /**
     * RFC 6455 §9.1 — formats a single extension with parameters.
     *
     * @param name the extension name
     * @param params the extension parameters (values may be null for valueless params)
     * @return the formatted extension string
     */
    public static String formatExtension(String name, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(name);
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append("; ").append(entry.getKey());
                if (entry.getValue() != null) {
                    sb.append('=').append(entry.getValue());
                }
            }
        }
        return sb.toString();
    }

    /**
     * RFC 6455 §9.1 — formats client extension offers for the request header.
     *
     * @param extensions the extensions to offer
     * @return the formatted header value, or null if none
     */
    public static String formatOffers(List<WebSocketExtension> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WebSocketExtension ext : extensions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(formatExtension(ext.getName(), ext.generateOffer()));
        }
        return sb.toString();
    }
}
