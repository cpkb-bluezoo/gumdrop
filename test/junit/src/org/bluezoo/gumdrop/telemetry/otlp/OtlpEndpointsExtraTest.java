/*
 * OtlpEndpointsExtraTest.java
 * Copyright (C) 2025 Chris Burdess
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.junit.Test;

/**
 * Additional tests for the OTLP HTTP and gRPC endpoint classes that need no
 * network: URL parsing, failing connection set-up, and string forms.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpEndpointsExtraTest {

    private static TelemetryConfig tlsConfig() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        File f = File.createTempFile("missing-truststore", ".p12");
        f.delete();
        config.setTruststoreFile(f.toPath());
        config.setTruststorePass("changeit");
        return config;
    }

    @Test
    public void testHttpEndpointWithConfigAndFailingConnect() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Test", "1");
        OtlpEndpoint e = OtlpEndpoint.create(null, "traces", "https://localhost:4318",
                "/v1/traces", headers, tlsConfig());
        assertNotNull(e);
        assertEquals("traces", e.getName());
        assertTrue(e.isSecure());
        assertFalse(e.isConnected());
        assertFalse(e.isConnecting());
        assertTrue(e.toString().contains("https://localhost:4318/v1/traces"));
        assertNull(e.getClient());
        assertFalse(e.connectAndWait(10L));
        OtlpExporter exporter = new OtlpExporter(new TelemetryConfig());
        try {
            OtlpResponseHandler h = new OtlpResponseHandler("traces", exporter);
            e.send(ByteBuffer.allocate(4), h);
            assertTrue(h.isComplete());
            OtlpResponseHandler h2 = new OtlpResponseHandler("traces", exporter);
            assertNull(e.openStream(h2));
            assertTrue(h2.isComplete());
        } finally {
            exporter.shutdown();
        }
        e.close();
    }

    @Test
    public void testHttpEndpointInvalid() {
        Map<String, String> none = Collections.<String, String>emptyMap();
        assertNull(OtlpEndpoint.create(null, "t", "no-host", "/p", none, null));
        assertNull(OtlpEndpoint.create(null, "t", "http://bad host/x", "/p", none, null));
    }

    @Test
    public void testGrpcEndpoint() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Test", "1");
        OtlpGrpcEndpoint e = OtlpGrpcEndpoint.create(null, "traces", "https://localhost",
                "/svc/Export", headers, tlsConfig());
        assertNotNull(e);
        assertEquals("traces", e.getName());
        assertEquals("localhost", e.getHost());
        assertEquals(4317, e.getPort());
        assertEquals("/svc/Export", e.getPath());
        assertTrue(e.isSecure());
        assertFalse(e.isConnected());
        assertFalse(e.isConnecting());
        assertTrue(e.toString().contains("gRPC"));
        assertNull(e.getClient());
        assertFalse(e.connectAndWait(10L));
        OtlpGrpcExporter exporter = new OtlpGrpcExporter(new TelemetryConfig());
        try {
            OtlpGrpcResponseHandler h = new OtlpGrpcResponseHandler("traces", exporter);
            e.send(ByteBuffer.allocate(4), h);
            assertTrue(h.isComplete());
        } finally {
            exporter.shutdown();
        }
        e.close();
    }

    @Test
    public void testGrpcEndpointCreateEdgeCases() {
        Map<String, String> none = Collections.<String, String>emptyMap();
        assertNull(OtlpGrpcEndpoint.create(null, "t", null, "/p", none, null));
        assertNull(OtlpGrpcEndpoint.create(null, "t", "", "/p", none, null));
        assertNull(OtlpGrpcEndpoint.create(null, "t", "no-host", "/p", none, null));
        assertNull(OtlpGrpcEndpoint.create(null, "t", "http://bad host/x", "/p", none, null));
        OtlpGrpcEndpoint plain = OtlpGrpcEndpoint.create(null, "t", "http://h:9999/x", "/p", none, null);
        assertNotNull(plain);
        assertEquals(9999, plain.getPort());
        assertFalse(plain.isSecure());
    }
}
