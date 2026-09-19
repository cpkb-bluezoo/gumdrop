/*
 * OtlpEndpointTest.java
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

package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.Test;

/**
 * URL interpretation in {@link OtlpEndpoint#create}: host, port and path
 * extraction, scheme-dependent default ports, and IPv6 literals.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpEndpointTest {

    private static OtlpEndpoint create(String url) {
        return OtlpEndpoint.create(null, "traces", url, "/v1/traces",
                Collections.<String, String>emptyMap(), null);
    }

    @Test
    public void hostPortAndPathAreExtracted() {
        OtlpEndpoint e = create("https://collector.example:4318/custom/traces");
        assertNotNull(e);
        assertEquals("collector.example", e.getHost());
        assertEquals(4318, e.getPort());
        assertEquals("/custom/traces", e.getPath());
    }

    @Test
    public void defaultPortsFollowScheme() {
        assertEquals(443, create("https://c.example/v1/traces").getPort());
        assertEquals(80, create("http://c.example/v1/traces").getPort());
    }

    @Test
    public void missingPathUsesDefault() {
        assertEquals("/v1/traces", create("http://c.example:4318").getPath());
    }

    @Test
    public void ipv6LiteralHostHasNoBrackets() {
        OtlpEndpoint e = create("https://[::1]:24318/v1/traces");
        assertNotNull(e);
        assertEquals("::1", e.getHost());
        assertEquals(24318, e.getPort());
    }

    @Test
    public void unbracketedIpv6IsInvalid() {
        assertNull(create("https://::1:24318/v1/traces"));
    }

    @Test
    public void emptyOrNullUrlYieldsNoEndpoint() {
        assertNull(create(null));
        assertNull(create(""));
    }
}
