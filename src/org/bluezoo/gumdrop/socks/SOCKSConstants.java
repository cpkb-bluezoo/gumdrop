/*
 * SOCKSConstants.java
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

package org.bluezoo.gumdrop.socks;

/**
 * Protocol constants for SOCKS4, SOCKS4a, and SOCKS5.
 *
 * <p>SOCKS4 and SOCKS4a are de facto standards defined by the original
 * SOCKS reference implementation; SOCKS5 is specified by RFC 1928 with
 * authentication extensions in RFC 1929 (username/password) and
 * RFC 1961 (GSS-API).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.openssh.com/txt/socks4.protocol">SOCKS4
 *      Protocol</a>
 * @see <a href="https://www.openssh.com/txt/socks4a.protocol">SOCKS4a
 *      Extension</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928
 *      — SOCKS Protocol Version 5</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929
 *      — Username/Password Authentication for SOCKS V5</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc1961">RFC 1961
 *      — GSS-API Authentication Method for SOCKS Version 5</a>
 */
public final class SOCKSConstants {

    private SOCKSConstants() {
    }

    // ═══════════════════════════════════════════════════════════════════
    // Version bytes
    // ═══════════════════════════════════════════════════════════════════

    /** SOCKS4 version identifier (0x04). SOCKS4 protocol §Request. */
    public static final byte SOCKS4_VERSION = 0x04;

    /** SOCKS5 version identifier (0x05). RFC 1928 §3, §4. */
    public static final byte SOCKS5_VERSION = 0x05;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4/4a commands (SOCKS4 protocol §Request: CD field)
    // ═══════════════════════════════════════════════════════════════════

    /** SOCKS4 CONNECT command (CD=1). SOCKS4 protocol §Request. */
    public static final byte SOCKS4_CMD_CONNECT = 0x01;

    /** SOCKS4 BIND command (CD=2). SOCKS4 protocol §Request. */
    public static final byte SOCKS4_CMD_BIND = 0x02;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS4/4a reply codes (SOCKS4 protocol §Reply: CD field)
    // ═══════════════════════════════════════════════════════════════════

    /** Request granted (90). SOCKS4 protocol §Reply. */
    public static final byte SOCKS4_REPLY_GRANTED = 0x5a;

    /** Request rejected or failed (91). SOCKS4 protocol §Reply. */
    public static final byte SOCKS4_REPLY_REJECTED = 0x5b;

    /**
     * Request rejected: identd not reachable (92).
     * SOCKS4 protocol §Reply.
     */
    public static final byte SOCKS4_REPLY_IDENTD_UNREACHABLE = 0x5c;

    /**
     * Request rejected: identd userid mismatch (93).
     * SOCKS4 protocol §Reply.
     */
    public static final byte SOCKS4_REPLY_IDENTD_MISMATCH = 0x5d;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 authentication methods (RFC 1928 §3: METHOD selection)
    // ═══════════════════════════════════════════════════════════════════

    /** No authentication required (0x00). RFC 1928 §3. */
    public static final byte SOCKS5_AUTH_NONE = 0x00;

    /** GSSAPI authentication (0x01). RFC 1928 §3, RFC 1961. */
    public static final byte SOCKS5_AUTH_GSSAPI = 0x01;

    /** Username/password authentication (0x02). RFC 1928 §3, RFC 1929. */
    public static final byte SOCKS5_AUTH_USERNAME_PASSWORD = 0x02;

    /**
     * No acceptable methods (0xFF). Sent by server when none of the
     * client's offered methods are acceptable. RFC 1928 §3.
     */
    public static final byte SOCKS5_AUTH_NO_ACCEPTABLE = (byte) 0xFF;

    // ── RFC 1929 username/password sub-negotiation ──

    /**
     * Username/password sub-negotiation version (0x01).
     * RFC 1929 §2: "current version of the subnegotiation".
     */
    public static final byte SOCKS5_AUTH_USERPASS_VERSION = 0x01;

    /** Authentication succeeded (STATUS=0x00). RFC 1929 §2. */
    public static final byte SOCKS5_AUTH_USERPASS_SUCCESS = 0x00;

    /**
     * Authentication failed (STATUS!=0x00). RFC 1929 §2: "A STATUS
     * field of X'00' indicates success. If the server returns a
     * `failure' (STATUS value other than X'00') status, it MUST close
     * the connection."
     */
    public static final byte SOCKS5_AUTH_USERPASS_FAILURE = 0x01;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 GSSAPI sub-negotiation (RFC 1961 §3, §4, §5)
    // ═══════════════════════════════════════════════════════════════════

    /** GSSAPI sub-negotiation version (0x01). RFC 1961 §3. */
    public static final byte SOCKS5_GSSAPI_VERSION = 0x01;

    /** GSSAPI authentication message type (MTYP=0x01). RFC 1961 §3. */
    public static final byte SOCKS5_GSSAPI_MSG_AUTH = 0x01;

    /**
     * GSSAPI per-message encapsulation type (MTYP=0x02).
     * RFC 1961 §5: used after context establishment for
     * integrity/confidentiality.
     *
     * <p>Defined for protocol completeness. Gumdrop does not support
     * per-message encapsulation — the server negotiates
     * {@code SECURITY_LAYER_NONE} during GSSAPI setup, deferring
     * confidentiality and integrity to TLS at the transport layer.
     */
    public static final byte SOCKS5_GSSAPI_MSG_ENCAPSULATION = 0x02;

    /**
     * GSSAPI context establishment success. RFC 1961 §4: server
     * sends this after the security context is fully established.
     */
    public static final byte SOCKS5_GSSAPI_STATUS_SUCCESS = 0x01;

    /** GSSAPI context establishment failure. RFC 1961 §4. */
    public static final byte SOCKS5_GSSAPI_STATUS_FAILURE = (byte) 0xFF;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 commands (RFC 1928 §4: CMD field)
    // ═══════════════════════════════════════════════════════════════════

    /** CONNECT command (0x01). RFC 1928 §4. */
    public static final byte SOCKS5_CMD_CONNECT = 0x01;

    /** BIND command (0x02). RFC 1928 §4. */
    public static final byte SOCKS5_CMD_BIND = 0x02;

    /** UDP ASSOCIATE command (0x03). RFC 1928 §4, §7. */
    public static final byte SOCKS5_CMD_UDP_ASSOCIATE = 0x03;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 address types (RFC 1928 §4: ATYP field, §5)
    // ═══════════════════════════════════════════════════════════════════

    /** IPv4 address (0x01): 4-octet address. RFC 1928 §5. */
    public static final byte SOCKS5_ATYP_IPV4 = 0x01;

    /**
     * Fully-qualified domain name (0x03): 1-octet length + name.
     * RFC 1928 §5.
     */
    public static final byte SOCKS5_ATYP_DOMAINNAME = 0x03;

    /** IPv6 address (0x04): 16-octet address. RFC 1928 §5. */
    public static final byte SOCKS5_ATYP_IPV6 = 0x04;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 reply codes (RFC 1928 §6: REP field)
    // ═══════════════════════════════════════════════════════════════════

    /** Succeeded (0x00). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_SUCCEEDED = 0x00;

    /** General SOCKS server failure (0x01). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_GENERAL_FAILURE = 0x01;

    /** Connection not allowed by ruleset (0x02). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_NOT_ALLOWED = 0x02;

    /** Network unreachable (0x03). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_NETWORK_UNREACHABLE = 0x03;

    /** Host unreachable (0x04). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_HOST_UNREACHABLE = 0x04;

    /** Connection refused (0x05). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_CONNECTION_REFUSED = 0x05;

    /** TTL expired (0x06). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_TTL_EXPIRED = 0x06;

    /** Command not supported (0x07). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_COMMAND_NOT_SUPPORTED = 0x07;

    /** Address type not supported (0x08). RFC 1928 §6. */
    public static final byte SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    // ═══════════════════════════════════════════════════════════════════
    // SOCKS5 UDP relay header (RFC 1928 §7)
    // ═══════════════════════════════════════════════════════════════════

    /** UDP request header reserved field (0x0000). RFC 1928 §7. */
    public static final short SOCKS5_UDP_RSV = 0x0000;

    /**
     * Standalone datagram — no fragmentation (FRAG=0x00).
     * RFC 1928 §7: "An implementation that does not support
     * fragmentation MUST drop any datagram whose FRAG field is
     * other than X'00'."
     */
    public static final byte SOCKS5_UDP_FRAG_STANDALONE = 0x00;

    // ═══════════════════════════════════════════════════════════════════
    // Default ports
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Default SOCKS port (1080). This is the IANA-assigned port for
     * SOCKS services.
     */
    public static final int SOCKS_DEFAULT_PORT = 1080;

    /** Default SOCKS-over-TLS port (1081). */
    public static final int SOCKSS_DEFAULT_PORT = 1081;

}
