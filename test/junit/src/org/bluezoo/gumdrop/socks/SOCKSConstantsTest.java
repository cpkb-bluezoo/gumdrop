/*
 * SOCKSConstantsTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SocksConstants.*;

/**
 * Unit tests for {@link SocksConstants}.
 * Verifies protocol constant values match the specifications.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SOCKSConstantsTest {

    @Test
    public void testVersionBytes() {
        assertEquals(0x04, SOCKS4_VERSION);
        assertEquals(0x05, SOCKS5_VERSION);
    }

    @Test
    public void testSOCKS4Commands() {
        assertEquals(0x01, SOCKS4_CMD_CONNECT);
        assertEquals(0x02, SOCKS4_CMD_BIND);
    }

    @Test
    public void testSOCKS4Replies() {
        assertEquals(0x5a, SOCKS4_REPLY_GRANTED & 0xFF);
        assertEquals(0x5b, SOCKS4_REPLY_REJECTED & 0xFF);
        assertEquals(0x5c, SOCKS4_REPLY_IDENTD_UNREACHABLE & 0xFF);
        assertEquals(0x5d, SOCKS4_REPLY_IDENTD_MISMATCH & 0xFF);
    }

    @Test
    public void testSOCKS5AuthMethods() {
        assertEquals(0x00, SOCKS5_AUTH_NONE);
        assertEquals(0x01, SOCKS5_AUTH_GSSAPI);
        assertEquals(0x02, SOCKS5_AUTH_USERNAME_PASSWORD);
        assertEquals(0xFF, SOCKS5_AUTH_NO_ACCEPTABLE & 0xFF);
    }

    @Test
    public void testSOCKS5UsernamePasswordSubnegotiation() {
        assertEquals(0x01, SOCKS5_AUTH_USERPASS_VERSION);
        assertEquals(0x00, SOCKS5_AUTH_USERPASS_SUCCESS);
        assertEquals(0x01, SOCKS5_AUTH_USERPASS_FAILURE);
    }

    @Test
    public void testSOCKS5Commands() {
        assertEquals(0x01, SOCKS5_CMD_CONNECT);
        assertEquals(0x02, SOCKS5_CMD_BIND);
        assertEquals(0x03, SOCKS5_CMD_UDP_ASSOCIATE);
    }

    @Test
    public void testSOCKS5AddressTypes() {
        assertEquals(0x01, SOCKS5_ATYP_IPV4);
        assertEquals(0x03, SOCKS5_ATYP_DOMAINNAME);
        assertEquals(0x04, SOCKS5_ATYP_IPV6);
    }

    @Test
    public void testSOCKS5ReplyCodes() {
        assertEquals(0x00, SOCKS5_REPLY_SUCCEEDED);
        assertEquals(0x01, SOCKS5_REPLY_GENERAL_FAILURE);
        assertEquals(0x02, SOCKS5_REPLY_NOT_ALLOWED);
        assertEquals(0x03, SOCKS5_REPLY_NETWORK_UNREACHABLE);
        assertEquals(0x04, SOCKS5_REPLY_HOST_UNREACHABLE);
        assertEquals(0x05, SOCKS5_REPLY_CONNECTION_REFUSED);
        assertEquals(0x06, SOCKS5_REPLY_TTL_EXPIRED);
        assertEquals(0x07, SOCKS5_REPLY_COMMAND_NOT_SUPPORTED);
        assertEquals(0x08, SOCKS5_REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
    }

    @Test
    public void testSOCKS5UDPHeaderConstants() {
        assertEquals(0x0000, SOCKS5_UDP_RSV);
        assertEquals(0x00, SOCKS5_UDP_FRAG_STANDALONE);
    }

    @Test
    public void testDefaultPorts() {
        assertEquals(1080, SOCKS_DEFAULT_PORT);
        assertEquals(1081, SOCKSS_DEFAULT_PORT);
    }

    @Test
    public void testGSSAPIConstants() {
        assertEquals(0x01, SOCKS5_GSSAPI_VERSION);
        assertEquals(0x01, SOCKS5_GSSAPI_MSG_AUTH);
        assertEquals(0x02, SOCKS5_GSSAPI_MSG_ENCAPSULATION);
        assertEquals(0x01, SOCKS5_GSSAPI_STATUS_SUCCESS);
        assertEquals(0xFF, SOCKS5_GSSAPI_STATUS_FAILURE & 0xFF);
    }

    @Test
    public void testSOCKS4BindMatchesSOCKS5Bind() {
        assertEquals(SOCKS4_CMD_BIND, SOCKS5_CMD_BIND);
    }
}
