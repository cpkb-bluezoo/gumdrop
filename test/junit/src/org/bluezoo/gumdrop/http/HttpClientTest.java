/*
 * HttpClientTest.java
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

package org.bluezoo.gumdrop.http;

import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.http.client.AltSvcCache;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HttpClient} configuration and Alt-Svc caching without I/O.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientTest {

    @After
    public void clearCache() {
        AltSvcCache.clear();
    }

    @Test
    public void fluentConfigurationReturnsSameInstance() {
        HttpClient client = new HttpClient("example.com", 443);
        assertSame(client, client.secure(true).h2Enabled(true).h3Enabled(false));
    }

    @Test
    public void notOpenBeforeConnect() {
        assertFalse(new HttpClient("example.com", 80).isOpen());
    }

    @Test
    public void connectWithoutHostReportsError() {
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        HttpClient client = new HttpClient();
        client.connect(null, new HttpClientHandler() {
            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                err.set(cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
        assertNotNull(err.get());
        assertTrue(err.get() instanceof IllegalStateException);
    }

    @Test
    public void h3OverUnixSocketIsRejected() {
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        HttpClient client = new HttpClient("/tmp/http.sock");
        client.setH3Enabled(true);
        client.connect(null, new HttpClientHandler() {
            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                err.set(cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
        assertNotNull(err.get());
        assertTrue(err.get() instanceof java.io.IOException);
    }

    @Test
    public void altSvcReceivedPopulatesCache() {
        HttpClient client = new HttpClient("origin.test", 443);
        client.altSvcReceived("h3=\"h3.origin.test:4433\"; ma=600");

        AltSvcCache.Entry entry = AltSvcCache.get("origin.test", 443);
        assertNotNull(entry);
        assertEquals("h3.origin.test", entry.getH3Host());
        assertEquals(4433, entry.getH3Port());
    }

    @Test
    public void altSvcIgnoredForUnixSocketClient() {
        HttpClient client = new HttpClient("/var/run/gumdrop.sock");
        client.altSvcReceived("h3=\":443\"; ma=3600");
        assertNull(AltSvcCache.get("origin.test", 443));
    }
}
