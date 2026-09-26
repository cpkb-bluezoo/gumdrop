/*
 * TlsVersionPickTest.java
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

import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link TlsVersionPick}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsVersionPickTest {

    @Test
    public void picksTls13WhenClientOffers0304() throws Exception {
        HandshakeConfig defaults = new HandshakeConfig(HandshakeRole.CLIENT);
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = new byte[32];
        params.cipherSuites = defaults.getCipherSuites();
        params.groups = defaults.getNamedGroups();
        params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        NamedGroup shareGroup = params.groups.get(0);
        params.keyShares.put(shareGroup, KeyExchange.generate(shareGroup).getShareBytes());
        params.signatureAlgorithms = Collections.singletonList(
                SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.emptyList();
        params.offerTls12Fallback = true;
        byte[] ch = HandshakeMessages.buildClientHelloWithBinder(params, null);
        byte[] body = HandshakeMessages.extractClientHelloContent(ch);
        assertEquals(TlsVersionPick.Picked.V13, TlsVersionPick.pickServerVersion(body));
    }

    @Test
    public void negotiatePolicyAllowsBothPicks() {
        assertEquals(true, TlsVersion.NEGOTIATE.allowsServerPick(TlsVersionPick.Picked.V13));
        assertEquals(true, TlsVersion.NEGOTIATE.allowsServerPick(TlsVersionPick.Picked.V12));
        assertEquals(false, TlsVersion.TLS_1_3.allowsServerPick(TlsVersionPick.Picked.V12));
        assertEquals(false, TlsVersion.TLS_1_2.allowsServerPick(TlsVersionPick.Picked.V13));
    }
}
