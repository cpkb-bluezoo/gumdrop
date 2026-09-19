/*
 * WebSocketClientIntegrationTest.java
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

package org.bluezoo.gumdrop.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.websocket.client.WebSocketClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * End-to-end tests for {@link WebSocketClient} over HTTP/1.1 against the
 * in-tree WebSocket server, exercising the real socket connect, upgrade
 * handshake, masked frame exchange and close handshake.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketClientIntegrationTest extends AbstractServerIntegrationTest {

    private static final int PORT = 18121;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    private final EchoWebSocketService echo = new EchoWebSocketService();

    @Override
    protected Collection<? extends Server> buildServers() throws Exception {
        HttpServer server = HttpServer.compose()
                .listener(new Http2Listener()
                        .port(PORT)
                        .bindWildcard())
                .streamHandler(echo.toHandler())
                .server();
        return Collections.singletonList(server);
    }

    /** Client-side collector with latches for each expected event. */
    private static final class Collector implements WebSocketEventHandler {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch text = new CountDownLatch(1);
        final CountDownLatch binary = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicReference<WebSocketSession> session =
                new AtomicReference<WebSocketSession>();
        final AtomicReference<String> textMessage =
                new AtomicReference<String>();
        final AtomicReference<byte[]> binaryMessage =
                new AtomicReference<byte[]>();
        final AtomicReference<Integer> closeCode =
                new AtomicReference<Integer>();
        final AtomicReference<Throwable> error =
                new AtomicReference<Throwable>();

        @Override
        public void opened(WebSocketSession s) {
            session.set(s);
            opened.countDown();
        }

        @Override
        public void textMessageReceived(WebSocketSession s, String m) {
            textMessage.set(m);
            text.countDown();
        }

        @Override
        public void binaryMessageReceived(WebSocketSession s, ByteBuffer d) {
            byte[] b = new byte[d.remaining()];
            d.get(b);
            binaryMessage.set(b);
            binary.countDown();
        }

        @Override
        public void closed(int code, String reason) {
            closeCode.set(code);
            closed.countDown();
        }

        @Override
        public void error(Throwable cause) {
            error.set(cause);
            failed.countDown();
        }
    }

    private WebSocketClient newClient() {
        WebSocketClient c = new WebSocketClient("localhost", PORT);
        c.setH2Enabled(false);
        c.setDnsHttpsRecordEnabled(false);
        return c;
    }

    @Test
    public void connectUpgradesAndEchoesText() throws Exception {
        WebSocketClient client = newClient();
        Collector c = new Collector();
        client.connect(gumdrop, "/chat", c);
        assertTrue("open", c.opened.await(10, TimeUnit.SECONDS));
        assertTrue(client.isOpen());

        c.session.get().sendText("hello");
        assertTrue("echo", c.text.await(10, TimeUnit.SECONDS));
        assertEquals("echo:hello", c.textMessage.get());
        assertEquals("/chat", echo.handlers.get(0).path);
        assertEquals(Arrays.asList("hello"), echo.handlers.get(0).textMessages);
        client.close();
    }

    @Test
    public void binaryMessagesRoundTrip() throws Exception {
        WebSocketClient client = newClient();
        Collector c = new Collector();
        client.connect(gumdrop, "/bin", c);
        assertTrue(c.opened.await(10, TimeUnit.SECONDS));
        byte[] payload = new byte[3000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        c.session.get().sendBinary(ByteBuffer.wrap(payload));
        assertTrue(c.binary.await(10, TimeUnit.SECONDS));
        assertTrue(Arrays.equals(payload, c.binaryMessage.get()));
        client.close();
    }

    @Test
    public void clientCloseIsSeenByServer() throws Exception {
        WebSocketClient client = newClient();
        Collector c = new Collector();
        client.connect(gumdrop, "/close", c);
        assertTrue(c.opened.await(10, TimeUnit.SECONDS));
        c.session.get().close(1000, "done");
        assertTrue("client close", c.closed.await(10, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1000), c.closeCode.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (echo.handlers.get(0).closeCode < 0
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1000, echo.handlers.get(0).closeCode);
    }

    @Test
    public void subprotocolAndExtensionsNegotiationDoesNotBreakEcho()
            throws Exception {
        WebSocketClient client = newClient();
        client.setSubprotocol("chat");
        client.setDeflateEnabled(true);
        Collector c = new Collector();
        client.connect(gumdrop, "/deflate", c);
        assertTrue(c.opened.await(10, TimeUnit.SECONDS));
        c.session.get().sendText("compress me compress me compress me");
        assertTrue(c.text.await(10, TimeUnit.SECONDS));
        assertEquals("echo:compress me compress me compress me",
                c.textMessage.get());
        client.close();
    }

    @Test
    public void connectionRefusedReportsError() throws Exception {
        WebSocketClient client = new WebSocketClient("localhost", 1);
        client.setH2Enabled(false);
        client.setDnsHttpsRecordEnabled(false);
        Collector c = new Collector();
        client.connect(gumdrop, "/", c);
        assertTrue("error", c.failed.await(10, TimeUnit.SECONDS));
        assertNotNull(c.error.get());
        assertFalse(client.isOpen());
    }
}
