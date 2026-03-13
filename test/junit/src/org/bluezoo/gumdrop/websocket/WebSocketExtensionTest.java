/*
 * WebSocketExtensionTest.java
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
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the WebSocket extension framework (RFC 6455 §9)
 * and permessage-deflate (RFC 7692).
 */
public class WebSocketExtensionTest {

    // ── Extension header parsing (RFC 6455 §9.1) ──

    @Test
    public void testParseEmptyExtensions() {
        List<WebSocketHandshake.ExtensionOffer> offers =
                WebSocketHandshake.parseExtensions(null);
        assertTrue(offers.isEmpty());
    }

    @Test
    public void testParseSimpleExtension() {
        List<WebSocketHandshake.ExtensionOffer> offers =
                WebSocketHandshake.parseExtensions("permessage-deflate");
        assertEquals(1, offers.size());
        assertEquals("permessage-deflate", offers.get(0).getName());
        assertTrue(offers.get(0).getParams().isEmpty());
    }

    @Test
    public void testParseExtensionWithParams() {
        List<WebSocketHandshake.ExtensionOffer> offers =
                WebSocketHandshake.parseExtensions(
                        "permessage-deflate; server_no_context_takeover; client_max_window_bits=12");
        assertEquals(1, offers.size());
        assertEquals("permessage-deflate", offers.get(0).getName());
        Map<String, String> params = offers.get(0).getParams();
        assertEquals(2, params.size());
        assertTrue(params.containsKey("server_no_context_takeover"));
        assertEquals("12", params.get("client_max_window_bits"));
    }

    @Test
    public void testParseMultipleExtensions() {
        List<WebSocketHandshake.ExtensionOffer> offers =
                WebSocketHandshake.parseExtensions(
                        "permessage-deflate, x-custom-ext; mode=fast");
        assertEquals(2, offers.size());
        assertEquals("permessage-deflate", offers.get(0).getName());
        assertEquals("x-custom-ext", offers.get(1).getName());
        assertEquals("fast", offers.get(1).getParams().get("mode"));
    }

    @Test
    public void testParseExtensionWithValuelessParam() {
        List<WebSocketHandshake.ExtensionOffer> offers =
                WebSocketHandshake.parseExtensions(
                        "permessage-deflate; client_max_window_bits");
        assertEquals(1, offers.size());
        Map<String, String> params = offers.get(0).getParams();
        assertTrue(params.containsKey("client_max_window_bits"));
        assertEquals("", params.get("client_max_window_bits"));
    }

    // ── Extension formatting ──

    @Test
    public void testFormatExtensionSimple() {
        String result = WebSocketHandshake.formatExtension("permessage-deflate", null);
        assertEquals("permessage-deflate", result);
    }

    @Test
    public void testFormatExtensionWithParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("server_no_context_takeover", null);
        params.put("client_max_window_bits", "12");
        String result = WebSocketHandshake.formatExtension("permessage-deflate", params);
        assertEquals("permessage-deflate; server_no_context_takeover; client_max_window_bits=12", result);
    }

    @Test
    public void testFormatOffersNull() {
        assertNull(WebSocketHandshake.formatOffers(null));
    }

    @Test
    public void testFormatOffersEmpty() {
        assertNull(WebSocketHandshake.formatOffers(Collections.emptyList()));
    }

    // ── Server-side extension negotiation ──

    @Test
    public void testNegotiateNoOffersReturnsEmpty() {
        List<WebSocketExtension> supported = new ArrayList<>();
        supported.add(new PerMessageDeflateExtension());
        List<WebSocketExtension> result =
                WebSocketHandshake.negotiateExtensions(null, supported);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNegotiateMatchingExtension() {
        List<WebSocketExtension> supported = new ArrayList<>();
        supported.add(new PerMessageDeflateExtension());
        List<WebSocketExtension> result =
                WebSocketHandshake.negotiateExtensions("permessage-deflate", supported);
        assertEquals(1, result.size());
        assertEquals("permessage-deflate", result.get(0).getName());
    }

    @Test
    public void testNegotiateNoMatchReturnsEmpty() {
        List<WebSocketExtension> supported = new ArrayList<>();
        supported.add(new PerMessageDeflateExtension());
        List<WebSocketExtension> result =
                WebSocketHandshake.negotiateExtensions("x-unknown", supported);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testNegotiateNoSupportedReturnsEmpty() {
        List<WebSocketExtension> result =
                WebSocketHandshake.negotiateExtensions("permessage-deflate", null);
        assertTrue(result.isEmpty());
    }

    // ── PerMessageDeflateExtension (RFC 7692) ──

    @Test
    public void testPerMessageDeflateName() {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        assertEquals("permessage-deflate", ext.getName());
    }

    @Test
    public void testPerMessageDeflateUsesRsv1() {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        assertTrue(ext.usesRsv1());
        assertFalse(ext.usesRsv2());
        assertFalse(ext.usesRsv3());
    }

    @Test
    public void testPerMessageDeflateCompressDecompressRoundTrip() throws Exception {
        PerMessageDeflateExtension serverExt = new PerMessageDeflateExtension();
        serverExt.acceptOffer(new LinkedHashMap<>());

        PerMessageDeflateExtension clientExt = new PerMessageDeflateExtension();
        clientExt.acceptResponse(new LinkedHashMap<>());

        String original = "Hello, this is a test message for compression!";
        byte[] payload = original.getBytes(StandardCharsets.UTF_8);

        // Client encodes, server decodes
        byte[] compressed = clientExt.encode(payload);
        byte[] decompressed = serverExt.decode(compressed);
        assertEquals(original, new String(decompressed, StandardCharsets.UTF_8));

        // Server encodes, client decodes
        byte[] compressed2 = serverExt.encode(payload);
        byte[] decompressed2 = clientExt.decode(compressed2);
        assertEquals(original, new String(decompressed2, StandardCharsets.UTF_8));
    }

    @Test
    public void testPerMessageDeflateCompressesData() throws Exception {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.acceptOffer(new LinkedHashMap<>());

        // Highly compressible data
        byte[] payload = new byte[1000];
        Arrays.fill(payload, (byte) 'a');

        byte[] compressed = ext.encode(payload);
        assertTrue("Should compress data", compressed.length < payload.length);
    }

    @Test
    public void testPerMessageDeflateEmptyPayload() throws Exception {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.acceptOffer(new LinkedHashMap<>());

        byte[] compressed = ext.encode(new byte[0]);
        byte[] decompressed = ext.decode(compressed);
        assertEquals(0, decompressed.length);
    }

    @Test
    public void testPerMessageDeflateAcceptOfferWithParams() {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        Map<String, String> offer = new LinkedHashMap<>();
        offer.put("server_no_context_takeover", "");
        offer.put("client_max_window_bits", "12");

        Map<String, String> accepted = ext.acceptOffer(offer);
        assertNotNull(accepted);
        assertTrue(accepted.containsKey("server_no_context_takeover"));
        assertEquals("12", accepted.get("client_max_window_bits"));
    }

    @Test
    public void testPerMessageDeflateClientGenerateOffer() {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        Map<String, String> offer = ext.generateOffer();
        assertNotNull(offer);
        assertTrue(offer.containsKey("client_max_window_bits"));
    }

    @Test
    public void testPerMessageDeflateClose() {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.acceptOffer(new LinkedHashMap<>());

        // Should not throw
        ext.close();
        ext.close(); // double close should be safe
    }

    @Test
    public void testPerMessageDeflateMultipleMessages() throws Exception {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        ext.acceptOffer(new LinkedHashMap<>());

        for (int i = 0; i < 10; i++) {
            String msg = "Message number " + i;
            byte[] payload = msg.getBytes(StandardCharsets.UTF_8);
            byte[] compressed = ext.encode(payload);
            byte[] decompressed = ext.decode(compressed);
            assertEquals(msg, new String(decompressed, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testPerMessageDeflateNoContextTakeover() throws Exception {
        PerMessageDeflateExtension ext = new PerMessageDeflateExtension();
        Map<String, String> offer = new LinkedHashMap<>();
        offer.put("server_no_context_takeover", "");
        offer.put("client_no_context_takeover", "");
        ext.acceptOffer(offer);

        // Each message should compress/decompress independently
        String msg1 = "First message with some content";
        String msg2 = "Second message with different content";

        byte[] comp1 = ext.encode(msg1.getBytes(StandardCharsets.UTF_8));
        byte[] decomp1 = ext.decode(comp1);
        assertEquals(msg1, new String(decomp1, StandardCharsets.UTF_8));

        byte[] comp2 = ext.encode(msg2.getBytes(StandardCharsets.UTF_8));
        byte[] decomp2 = ext.decode(comp2);
        assertEquals(msg2, new String(decomp2, StandardCharsets.UTF_8));
    }

    // ── Handshake extension header integration ──

    @Test
    public void testCreateWebSocketResponseWithExtensions() {
        org.bluezoo.gumdrop.http.Headers headers =
                WebSocketHandshake.createWebSocketResponse(
                        WebSocketHandshake.generateKey(), null, "permessage-deflate");
        String extHeader = headers.getValue("Sec-WebSocket-Extensions");
        assertEquals("permessage-deflate", extHeader);
    }

    @Test
    public void testCreateWebSocketResponseWithoutExtensions() {
        org.bluezoo.gumdrop.http.Headers headers =
                WebSocketHandshake.createWebSocketResponse(
                        WebSocketHandshake.generateKey(), null, null);
        assertNull(headers.getValue("Sec-WebSocket-Extensions"));
    }

    @Test
    public void testCreateUpgradeRequestWithExtensions() {
        org.bluezoo.gumdrop.http.Headers headers =
                WebSocketHandshake.createUpgradeRequest(
                        WebSocketHandshake.generateKey(), null, "permessage-deflate");
        String extHeader = headers.getValue("Sec-WebSocket-Extensions");
        assertEquals("permessage-deflate", extHeader);
    }

    @Test
    public void testCreateUpgradeRequestWithoutExtensions() {
        org.bluezoo.gumdrop.http.Headers headers =
                WebSocketHandshake.createUpgradeRequest(
                        WebSocketHandshake.generateKey(), null, null);
        assertNull(headers.getValue("Sec-WebSocket-Extensions"));
    }
}
