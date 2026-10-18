/*
 * ClientHelloPskModesTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.Collections;
import java.util.LinkedHashMap;

import org.bluezoo.gumdrop.crypto.CryptoServices;
import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A ClientHello must always carry {@code psk_key_exchange_modes}: servers
 * such as picoquic and NSS only issue a {@code NewSessionTicket} to a
 * client that said it can use one, so a first connection that omits the
 * extension never gets a ticket to resume with.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientHelloPskModesTest {

    private static final int EXT_PSK_KEY_EXCHANGE_MODES = 0x002d;
    private static final byte PSK_DHE_KE = 1;

    @Test
    public void firstConnectionClientHelloOffersPskDheKe() throws Exception {
        HandshakeConfig defaults = new HandshakeConfig(HandshakeRole.CLIENT, new CryptoServices());
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = new byte[32];
        params.cipherSuites = defaults.getCipherSuites();
        params.groups = defaults.getNamedGroups();
        params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        NamedGroup shareGroup = params.groups.get(0);
        params.keyShares.put(shareGroup, KeyExchange.generate(shareGroup, new CryptoServices()).getShareBytes());
        params.signatureAlgorithms = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.emptyList();
        // no pskIdentity: this is a first connection, nothing to resume
        byte[] ch = HandshakeMessages.buildClientHelloWithBinder(params, null);
        byte[] body = HandshakeMessages.extractClientHelloContent(ch);
        byte[] modes = extension(body, EXT_PSK_KEY_EXCHANGE_MODES);
        assertNotNull("psk_key_exchange_modes missing", modes);
        assertArrayEquals(new byte[] { 1, PSK_DHE_KE }, modes);
    }

    /** Returns the data of the first extension of the given type, or null. */
    private static byte[] extension(byte[] body, int type) {
        int p = 2 + 32;                          // legacy_version, random
        p += 1 + (body[p] & 0xff);               // legacy_session_id
        p += 2 + (((body[p] & 0xff) << 8) | (body[p + 1] & 0xff)); // cipher_suites
        p += 1 + (body[p] & 0xff);               // compression_methods
        int end = p + 2 + (((body[p] & 0xff) << 8) | (body[p + 1] & 0xff));
        p += 2;
        while (p + 4 <= end) {
            int t = ((body[p] & 0xff) << 8) | (body[p + 1] & 0xff);
            int len = ((body[p + 2] & 0xff) << 8) | (body[p + 3] & 0xff);
            if (t == type) {
                byte[] data = new byte[len];
                System.arraycopy(body, p + 4, data, 0, len);
                return data;
            }
            p += 4 + len;
        }
        return null;
    }
}
