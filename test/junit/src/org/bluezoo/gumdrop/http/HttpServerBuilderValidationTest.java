/*
 * HttpServerBuilderValidationTest.java
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

import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Composer validation for {@link HttpServer#compose()}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpServerBuilderValidationTest {

    @Test
    public void testBuildWithoutHandlerUsesDefaultNotFound() {
        HttpServer server = HttpServer.compose()
                .listener(new Http2Listener().port(9999))
                .server();
        assertNotNull(server);
    }

    @Test
    public void testSecureEndpointWiresTcpAndQuicListeners() {
        TlsConfig tls = TlsConfig.pem("cert.pem", "key.pem");
        HttpServer server = HttpServer.compose()
                .secureEndpoint(8443, tls)
                .server();
        assertEquals(2, server.getListeners().size());
        assertTrue(server.getListeners().get(0) instanceof Http2Listener);
        assertTrue(server.getListeners().get(1) instanceof Http3Listener);
        assertEquals(8443, ((Http2Listener) server.getListeners().get(0)).getPort());
        assertEquals(8443, ((Http3Listener) server.getListeners().get(1)).getPort());
    }

    @Test
    public void testSecureEndpointSharesOneTlsConfigWithDifferentImmediacy() {
        // The same TlsConfig instance is handed to both listeners: TCP
        // starts encrypted immediately (secure(true)), QUIC never gets
        // secure(true) called (HTTP/3 is TLS-only at the transport level
        // regardless of the flag) -- this is exactly why TlsConfig cannot
        // carry a "secure" property of its own.
        TlsConfig tls = TlsConfig.pem("cert.pem", "key.pem");
        HttpServer server = HttpServer.compose()
                .secureEndpoint(8443, tls)
                .server();
        Http2Listener tcpListener = (Http2Listener) server.getListeners().get(0);
        Http3Listener quicListener = (Http3Listener) server.getListeners().get(1);
        assertTrue(tcpListener.isSecure());
        assertFalse(quicListener.isSecure());
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildRequiresListener() {
        HttpServer.compose()
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(HttpResponseState stream) {
                        return new DefaultHttpRequestHandler();
                    }
                })
                .server();
    }

}
