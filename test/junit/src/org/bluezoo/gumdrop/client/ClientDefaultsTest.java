/*
 * ClientDefaultsTest.java
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

package org.bluezoo.gumdrop.client;

import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientDefaultsTest {

    @After
    public void tearDown() {
        ClientDefaults.setDefaultTls(null);
    }

    @Test
    public void effectiveTlsEmptyWhenNothingConfigured() {
        TlsConfig effective = ClientDefaults.effectiveTls(new TlsConfig());
        assertNull(effective.getServerCredentials());
        assertTrue(effective.isVerifyPeer());
    }

    @Test
    public void processDefaultFillsMissingClientMaterial() {
        ServerCredentials credentials = new ServerCredentials(null, null);
        ClientDefaults.setDefaultTls(new TlsConfig().serverCredentials(credentials));
        TlsConfig effective = ClientDefaults.effectiveTls(new TlsConfig());
        assertSame(credentials, effective.getServerCredentials());
    }

    @Test
    public void perClientWinsOverProcessDefault() {
        ServerCredentials defaultCredentials = new ServerCredentials(null, null);
        ServerCredentials clientCredentials = new ServerCredentials(null, null);
        ClientDefaults.setDefaultTls(new TlsConfig().serverCredentials(defaultCredentials));
        TlsConfig effective = ClientDefaults.effectiveTls(
                new TlsConfig().serverCredentials(clientCredentials));
        assertSame(clientCredentials, effective.getServerCredentials());
    }

    @Test
    public void perClientVerifyPeerWinsWhenClientHasMaterial() {
        ClientDefaults.setDefaultTls(new TlsConfig().verifyPeer(true));
        TlsConfig effective = ClientDefaults.effectiveTls(
                new TlsConfig().verifyPeer(false)
                        .serverCredentials(new ServerCredentials(null, null)));
        assertFalse(effective.isVerifyPeer());
    }

}
