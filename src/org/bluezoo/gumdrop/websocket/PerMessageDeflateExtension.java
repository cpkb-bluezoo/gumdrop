/*
 * PerMessageDeflateExtension.java
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

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * RFC 7692 — permessage-deflate WebSocket extension.
 *
 * <p>Compresses WebSocket message payloads using the DEFLATE algorithm.
 * The RSV1 bit is set on the first frame of each compressed message.
 * Compression uses raw DEFLATE (no zlib wrapper) and the trailing
 * empty-block marker ({@code 0x00 0x00 0xFF 0xFF}) is stripped from
 * compressed output and re-appended before decompression, as required
 * by RFC 7692 §7.2.1.
 *
 * <h4>Negotiation Parameters</h4>
 * <ul>
 * <li>{@code server_no_context_takeover} — server resets LZ77 window per message</li>
 * <li>{@code client_no_context_takeover} — client resets LZ77 window per message</li>
 * <li>{@code server_max_window_bits} — max server LZ77 window size (8–15)</li>
 * <li>{@code client_max_window_bits} — max client LZ77 window size (8–15)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc7692">RFC 7692</a>
 */
public class PerMessageDeflateExtension implements WebSocketExtension {

    /** RFC 7692 — registered extension token. */
    public static final String EXTENSION_NAME = "permessage-deflate";

    static final String PARAM_SERVER_NO_CONTEXT_TAKEOVER = "server_no_context_takeover";
    static final String PARAM_CLIENT_NO_CONTEXT_TAKEOVER = "client_no_context_takeover";
    static final String PARAM_SERVER_MAX_WINDOW_BITS = "server_max_window_bits";
    static final String PARAM_CLIENT_MAX_WINDOW_BITS = "client_max_window_bits";

    /** RFC 7692 §7.2.1 — trailing bytes removed from compressed output. */
    private static final byte[] TAIL_BYTES = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    private static final int DEFAULT_WINDOW_BITS = 15;
    private static final int MIN_WINDOW_BITS = 8;
    private static final int BUFFER_SIZE = 1024;

    private boolean serverNoContextTakeover;
    private boolean clientNoContextTakeover;
    private int serverMaxWindowBits = DEFAULT_WINDOW_BITS;
    private int clientMaxWindowBits = DEFAULT_WINDOW_BITS;

    private boolean isClient;
    private Deflater deflater;
    private Inflater inflater;

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    /** RFC 7692 §8 — permessage-deflate uses RSV1 to indicate compression. */
    @Override
    public boolean usesRsv1() {
        return true;
    }

    @Override
    public boolean usesRsv2() {
        return false;
    }

    @Override
    public boolean usesRsv3() {
        return false;
    }

    /**
     * RFC 7692 §7.1 — server-side negotiation of a permessage-deflate offer.
     */
    @Override
    public Map<String, String> acceptOffer(Map<String, String> offeredParams) {
        Map<String, String> accepted = new LinkedHashMap<>();

        if (offeredParams.containsKey(PARAM_SERVER_NO_CONTEXT_TAKEOVER)) {
            serverNoContextTakeover = true;
            accepted.put(PARAM_SERVER_NO_CONTEXT_TAKEOVER, null);
        }
        if (offeredParams.containsKey(PARAM_CLIENT_NO_CONTEXT_TAKEOVER)) {
            clientNoContextTakeover = true;
            accepted.put(PARAM_CLIENT_NO_CONTEXT_TAKEOVER, null);
        }

        String smwb = offeredParams.get(PARAM_SERVER_MAX_WINDOW_BITS);
        if (smwb != null) {
            int bits = parseWindowBits(smwb);
            if (bits >= 0) {
                serverMaxWindowBits = bits;
                accepted.put(PARAM_SERVER_MAX_WINDOW_BITS, String.valueOf(bits));
            }
        }

        String cmwb = offeredParams.get(PARAM_CLIENT_MAX_WINDOW_BITS);
        if (cmwb != null) {
            if (cmwb.isEmpty()) {
                accepted.put(PARAM_CLIENT_MAX_WINDOW_BITS, null);
            } else {
                int bits = parseWindowBits(cmwb);
                if (bits >= 0) {
                    clientMaxWindowBits = bits;
                    accepted.put(PARAM_CLIENT_MAX_WINDOW_BITS, String.valueOf(bits));
                }
            }
        }

        isClient = false;
        initCompression();
        return accepted;
    }

    /** RFC 7692 §7.1 — client-side offer generation. */
    @Override
    public Map<String, String> generateOffer() {
        Map<String, String> offer = new LinkedHashMap<>();
        if (serverNoContextTakeover) {
            offer.put(PARAM_SERVER_NO_CONTEXT_TAKEOVER, null);
        }
        if (clientNoContextTakeover) {
            offer.put(PARAM_CLIENT_NO_CONTEXT_TAKEOVER, null);
        }
        if (serverMaxWindowBits != DEFAULT_WINDOW_BITS) {
            offer.put(PARAM_SERVER_MAX_WINDOW_BITS, String.valueOf(serverMaxWindowBits));
        }
        // RFC 7692 §7.1.2.2: offer without value to let server choose
        offer.put(PARAM_CLIENT_MAX_WINDOW_BITS, null);
        return offer;
    }

    /** RFC 7692 §7.1 — client-side acceptance of server response. */
    @Override
    public boolean acceptResponse(Map<String, String> params) {
        if (params.containsKey(PARAM_SERVER_NO_CONTEXT_TAKEOVER)) {
            serverNoContextTakeover = true;
        }
        if (params.containsKey(PARAM_CLIENT_NO_CONTEXT_TAKEOVER)) {
            clientNoContextTakeover = true;
        }

        String smwb = params.get(PARAM_SERVER_MAX_WINDOW_BITS);
        if (smwb != null) {
            int bits = parseWindowBits(smwb);
            if (bits < 0) {
                return false;
            }
            serverMaxWindowBits = bits;
        }

        String cmwb = params.get(PARAM_CLIENT_MAX_WINDOW_BITS);
        if (cmwb != null && !cmwb.isEmpty()) {
            int bits = parseWindowBits(cmwb);
            if (bits < 0) {
                return false;
            }
            clientMaxWindowBits = bits;
        }

        isClient = true;
        initCompression();
        return true;
    }

    /**
     * RFC 7692 §7.2.1 — compresses a message payload using DEFLATE.
     * Removes the trailing empty uncompressed block marker.
     */
    @Override
    public byte[] encode(byte[] payload) {
        if (mustResetDeflater()) {
            deflater.reset();
        }

        deflater.setInput(payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        byte[] buf = new byte[BUFFER_SIZE];
        // RFC 7692 §7.2.1: flush with SYNC_FLUSH to produce a complete
        // block boundary. Do NOT use finish() as that ends the stream and
        // prevents context reuse across messages.
        int len;
        do {
            len = deflater.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH);
            if (len > 0) {
                out.write(buf, 0, len);
            }
        } while (len > 0);

        byte[] compressed = out.toByteArray();
        // RFC 7692 §7.2.1: remove the trailing 0x00 0x00 0xFF 0xFF marker
        if (compressed.length >= 4 && endsWith(compressed, TAIL_BYTES)) {
            byte[] trimmed = new byte[compressed.length - 4];
            System.arraycopy(compressed, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return compressed;
    }

    /**
     * RFC 7692 §7.2.2 — decompresses a message payload.
     * Appends the trailing marker before inflating.
     */
    @Override
    public byte[] decode(byte[] payload) throws java.io.IOException {
        if (mustResetInflater()) {
            inflater.reset();
        }

        byte[] input = new byte[payload.length + TAIL_BYTES.length];
        System.arraycopy(payload, 0, input, 0, payload.length);
        System.arraycopy(TAIL_BYTES, 0, input, payload.length, TAIL_BYTES.length);

        inflater.setInput(input);

        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length * 2);
        byte[] buf = new byte[BUFFER_SIZE];
        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                int len = inflater.inflate(buf);
                if (len > 0) {
                    out.write(buf, 0, len);
                }
            }
        } catch (DataFormatException e) {
            throw new WebSocketProtocolException(
                    "permessage-deflate: decompression failed", e);
        }

        return out.toByteArray();
    }

    @Override
    public void close() {
        if (deflater != null) {
            deflater.end();
            deflater = null;
        }
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
    }

    // ── Configuration (call before negotiation to set preferences) ──

    public void setServerNoContextTakeover(boolean value) {
        this.serverNoContextTakeover = value;
    }

    public void setClientNoContextTakeover(boolean value) {
        this.clientNoContextTakeover = value;
    }

    public void setServerMaxWindowBits(int bits) {
        this.serverMaxWindowBits = Math.max(MIN_WINDOW_BITS,
                Math.min(DEFAULT_WINDOW_BITS, bits));
    }

    public void setClientMaxWindowBits(int bits) {
        this.clientMaxWindowBits = Math.max(MIN_WINDOW_BITS,
                Math.min(DEFAULT_WINDOW_BITS, bits));
    }

    // ── Internal ──

    private void initCompression() {
        deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        inflater = new Inflater(true);
    }

    private boolean mustResetDeflater() {
        return isClient ? clientNoContextTakeover : serverNoContextTakeover;
    }

    private boolean mustResetInflater() {
        return isClient ? serverNoContextTakeover : clientNoContextTakeover;
    }

    private static boolean endsWith(byte[] data, byte[] suffix) {
        if (data.length < suffix.length) {
            return false;
        }
        int offset = data.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (data[offset + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int parseWindowBits(String value) {
        try {
            int bits = Integer.parseInt(value.trim());
            if (bits >= MIN_WINDOW_BITS && bits <= DEFAULT_WINDOW_BITS) {
                return bits;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return -1;
    }
}
