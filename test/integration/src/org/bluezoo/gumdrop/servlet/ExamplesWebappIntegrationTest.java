/*
 * ExamplesWebappIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HTTPClientHelper;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.servlet.server.ServletRequestHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.File;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises servlet examples compiled into
 * {@code test/integration/webapp-examples} under context path {@code /examples}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ExamplesWebappIntegrationTest extends AbstractServerIntegrationTest {

    private static final int TEST_PORT = 19081;
    private static final String HOST = "::1";
    private static final String CONTEXT = "/examples";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        Container container = new Container();
        container.addContext(new Context(container, CONTEXT,
                new File("test/integration/webapp-examples")));

        HttpServer server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(TEST_PORT)
                        .addresses(InetAddress.getByName(HOST)))
                .streamHandler(new ServletRequestHandler(container))
                .server();
        return Collections.singletonList(server);
    }

    @Test
    public void testStaticWelcomePage() throws Exception {
        HTTPClientHelper.HttpResponse response = get(CONTEXT + "/index.html");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("Gumdrop servlet examples webapp"));
    }

    @Test
    public void testTrailerFieldsServlet() throws Exception {
        HTTPClientHelper.HttpResponse response =
                get(CONTEXT + "/trailer-fields?demo=basic");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("Trailer Fields")
                || response.body.contains("trailer"));
    }

    @Test
    public void testHttp2PriorityServlet() throws Exception {
        HTTPClientHelper.HttpResponse response =
                get(CONTEXT + "/http2-priority?resource=index");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("HTTP/2")
                || response.body.contains("priority")
                || response.body.contains("Priority"));
    }

    @Test
    public void testServerPushServletIndex() throws Exception {
        HTTPClientHelper.HttpResponse response =
                get(CONTEXT + "/server-push?demo=basic");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("Server Push")
                || response.body.contains("push"));
    }

    @Test
    public void testAsyncTimeoutDemoPage() throws Exception {
        HTTPClientHelper.HttpResponse response =
                get(CONTEXT + "/async-timeout?action=demo");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("Async")
                || response.body.contains("timeout"));
    }

    @Test
    public void testJspHello() throws Exception {
        HTTPClientHelper.HttpResponse response = get(CONTEXT + "/hello.jsp");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("examples-webapp-jsp-ok"));
    }

    private static HTTPClientHelper.HttpResponse get(String path) throws Exception {
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        return HTTPClientHelper.sendRequest(HOST, TEST_PORT, request);
    }
}
