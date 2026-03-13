/*
 * DNSCookie.java
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

package org.bluezoo.gumdrop.dns;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * DNS cookie support.
 * RFC 7873: DNS cookies provide a lightweight security mechanism for
 * verifying DNS source addresses. A client cookie is 8 bytes of opaque
 * data; the server echoes it and appends a server cookie (8-32 bytes).
 *
 * <p>EDNS0 option code 10 (COOKIE) carries both cookies in a single
 * EDNS0 option: option-code=10, option-length=8..40, option-data =
 * client-cookie (8 bytes) + optional server-cookie (8-32 bytes).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7873">RFC 7873</a>
 */
public final class DNSCookie {

    /** RFC 7873 section 4: EDNS0 option code for DNS cookies. */
    public static final int EDNS_OPTION_COOKIE = 10;

    /** RFC 7873 section 4: client cookie is exactly 8 octets. */
    public static final int CLIENT_COOKIE_LENGTH = 8;

    /** RFC 7873 section 4: minimum server cookie is 8 octets. */
    public static final int MIN_SERVER_COOKIE_LENGTH = 8;

    /** RFC 7873 section 4: maximum server cookie is 32 octets. */
    public static final int MAX_SERVER_COOKIE_LENGTH = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();
    private final byte[] serverSecret;

    /** Per-server cookie cache: maps server address string to last
     *  received server cookie bytes. */
    private final ConcurrentHashMap<String, byte[]> serverCookies =
            new ConcurrentHashMap<>();

    /** The client's own cookie (regenerated periodically). */
    private volatile byte[] clientCookie;

    /**
     * Creates a DNS cookie manager.
     * RFC 7873 section 5.2: the server secret is used to generate
     * server cookies via HMAC.
     */
    public DNSCookie() {
        this.serverSecret = new byte[16];
        random.nextBytes(serverSecret);
        regenerateClientCookie();
    }

    /**
     * Creates a DNS cookie manager with a specific server secret.
     *
     * @param serverSecret the secret key for server cookie generation
     */
    public DNSCookie(byte[] serverSecret) {
        this.serverSecret = serverSecret.clone();
        regenerateClientCookie();
    }

    /**
     * Regenerates the client cookie with fresh random bytes.
     * RFC 7873 section 5.1: the client cookie SHOULD be regenerated
     * periodically.
     */
    public void regenerateClientCookie() {
        byte[] cookie = new byte[CLIENT_COOKIE_LENGTH];
        random.nextBytes(cookie);
        this.clientCookie = cookie;
    }

    /**
     * Returns the current client cookie.
     *
     * @return the 8-byte client cookie
     */
    public byte[] getClientCookie() {
        return clientCookie.clone();
    }

    /**
     * Builds the EDNS0 cookie option data for an outgoing query.
     * RFC 7873 section 5.1: the client includes its 8-byte cookie and,
     * if available, the last server cookie received from this server.
     *
     * @param serverAddress the upstream server address (for cache lookup)
     * @return the EDNS0 option data (client cookie + optional server cookie)
     */
    public byte[] buildCookieOptionData(String serverAddress) {
        byte[] cc = clientCookie;
        byte[] sc = serverCookies.get(serverAddress);
        int len = CLIENT_COOKIE_LENGTH + (sc != null ? sc.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(len);
        buf.put(cc);
        if (sc != null) {
            buf.put(sc);
        }
        return buf.array();
    }

    /**
     * Builds a complete EDNS0 cookie option (option-code + option-length + data)
     * for inclusion in OPT record RDATA.
     *
     * @param serverAddress the upstream server address
     * @return the encoded EDNS0 option bytes
     */
    public byte[] buildCookieOption(String serverAddress) {
        byte[] data = buildCookieOptionData(serverAddress);
        ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
        buf.putShort((short) EDNS_OPTION_COOKIE);
        buf.putShort((short) data.length);
        buf.put(data);
        return buf.array();
    }

    /**
     * Stores the server cookie from a response.
     * RFC 7873 section 5.1: the client caches the server cookie and
     * echoes it in subsequent queries to the same server.
     *
     * @param serverAddress the server address
     * @param optionData the cookie option data from the response
     */
    public void processResponseCookie(String serverAddress,
                                       byte[] optionData) {
        if (optionData.length > CLIENT_COOKIE_LENGTH) {
            byte[] sc = Arrays.copyOfRange(optionData,
                    CLIENT_COOKIE_LENGTH, optionData.length);
            serverCookies.put(serverAddress, sc);
        }
    }

    /**
     * Generates a server cookie for a client request.
     * RFC 7873 section 5.2: the server cookie is an HMAC of the
     * client IP address and client cookie, using the server secret.
     *
     * @param clientAddress the client IP address
     * @param clientCookie the client's 8-byte cookie
     * @return the server cookie (truncated to 8 bytes)
     */
    public byte[] generateServerCookie(byte[] clientAddress,
                                        byte[] clientCookie) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(serverSecret, HMAC_ALGORITHM));
            mac.update(clientAddress);
            mac.update(clientCookie);
            byte[] full = mac.doFinal();
            return Arrays.copyOf(full, MIN_SERVER_COOKIE_LENGTH);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate server cookie", e);
        }
    }

    /**
     * Validates a server cookie from a client request.
     * RFC 7873 section 5.2: the server recomputes the expected cookie
     * and compares it against the one received.
     *
     * @param clientAddress the client IP address
     * @param clientCookie the client's 8-byte cookie from the request
     * @param serverCookie the server cookie from the request
     * @return true if the server cookie is valid
     */
    public boolean validateServerCookie(byte[] clientAddress,
                                         byte[] clientCookie,
                                         byte[] serverCookie) {
        byte[] expected = generateServerCookie(clientAddress, clientCookie);
        return Arrays.equals(expected, serverCookie);
    }

    /**
     * Parses EDNS0 options from OPT record RDATA.
     * RFC 6891 section 6.1.2: options are encoded as
     * option-code(2) + option-length(2) + option-data.
     *
     * @param rdata the OPT record RDATA
     * @param optionCode the option code to find
     * @return the option data, or null if not found
     */
    public static byte[] findEdnsOption(byte[] rdata, int optionCode) {
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        while (buf.remaining() >= 4) {
            int code = buf.getShort() & 0xFFFF;
            int length = buf.getShort() & 0xFFFF;
            if (buf.remaining() < length) {
                break;
            }
            if (code == optionCode) {
                byte[] data = new byte[length];
                buf.get(data);
                return data;
            }
            buf.position(buf.position() + length);
        }
        return null;
    }
}
