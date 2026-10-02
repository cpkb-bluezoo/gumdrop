/*
 * WebSocketServletStreamsTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketSession;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives the WebSocket servlet input and output streams directly: blocking
 * and listener-mode reads, skipping, listener failure containment, output
 * buffer growth, closure and write-listener notification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketServletStreamsTest {

    private static final class Session implements WebSocketSession {
        final List<String> texts = new ArrayList<String>();
        final List<Integer> binarySizes = new ArrayList<Integer>();

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void sendText(String message) {
            texts.add(message);
        }

        @Override
        public void sendBinary(ByteBuffer data) {
            binarySizes.add(Integer.valueOf(data.remaining()));
        }

        @Override
        public void sendPing(ByteBuffer payload) {
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int statusCode, String reason) {
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }
    }

    private static class State implements HttpResponseState {
        volatile int pending;

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }

        @Override
        public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public SecurityInfo getSecurityInfo() {
            return null;
        }

        @Override
        public HttpVersion getVersion() {
            return HttpVersion.HTTP_2_0;
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public void headers(Headers headers) {
        }

        @Override
        public void startResponseBody() {
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
        }

        @Override
        public void endResponseBody() {
        }

        @Override
        public void complete() {
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void onWritable(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void pauseRequestBody() {
        }

        @Override
        public void resumeRequestBody() {
        }

        @Override
        public boolean pushPromise(Headers headers) {
            return false;
        }

        @Override
        public void upgradeToWebSocket(String protocol, WebSocketEventHandler handler) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public int pendingResponseBytes() {
            return pending;
        }
    }

    private static final class Handler extends ServletHandler {
        private final HttpResponseState state;

        Handler(Container container, HttpResponseState state) {
            super(container, 8192);
            this.state = state;
        }

        @Override
        HttpResponseState getState() {
            return state;
        }
    }

    private static final class NoOp implements HttpUpgradeHandler {
        @Override
        public void init(WebConnection wc) {
        }

        @Override
        public void destroy() {
        }
    }

    private State state;
    private Session session;
    private ServletWebConnection connection;
    private WebSocketServletInputStream in;
    private WebSocketServletOutputStream out;

    @Before
    public void setUp() throws Exception {
        state = new State();
        Container container = new Container();
        Handler handler = new Handler(container, state);
        connection = new ServletWebConnection(new NoOp(), state, handler);
        session = new Session();
        connection.getEventHandler().opened(session);
        in = (WebSocketServletInputStream) connection.getInputStream();
        out = (WebSocketServletOutputStream) connection.getOutputStream();
    }

    private void message(String text) {
        connection.getEventHandler().textMessageReceived(null, text);
    }

    private static final class Counting implements ReadListener {
        int available;
        int allRead;
        int errors;
        boolean failAvailable;
        boolean failAllRead;
        boolean failError;

        @Override
        public void onDataAvailable() throws IOException {
            available++;
            if (failAvailable) {
                throw new IOException("available");
            }
        }

        @Override
        public void onAllDataRead() throws IOException {
            allRead++;
            if (failAllRead) {
                throw new IOException("all read");
            }
        }

        @Override
        public void onError(Throwable t) {
            errors++;
            if (failError) {
                throw new IllegalStateException("onError");
            }
        }
    }

    @Test
    public void testBlockingReadsOfEveryShape() throws Exception {
        message("abcdefghij");
        assertEquals(10, in.available());
        assertTrue(in.isReady());
        assertFalse(in.isFinished());
        assertEquals('a', in.read());
        byte[] two = new byte[2];
        assertEquals(2, in.read(two));
        assertEquals("bc", new String(two, StandardCharsets.US_ASCII));
        ByteBuffer heap = ByteBuffer.allocate(3);
        assertEquals(3, in.read(heap));
        assertEquals(3, heap.position());
        ByteBuffer direct = ByteBuffer.allocateDirect(2);
        assertEquals(2, in.read(direct));
        assertEquals(0, in.read(ByteBuffer.allocate(0)));
        assertEquals(2, in.skip(2));
        in.close();
        assertTrue(in.isFinished());
        assertFalse(in.isReady());
    }

    @Test
    public void testListenerModeReadsAndNotReadyErrors() throws Exception {
        Counting listener = new Counting();
        in.setReadListener(listener);
        try {
            in.setReadListener(listener);
            fail("second listener");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        try {
            in.read();
            fail("no data ready");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        try {
            in.read(new byte[4], 0, 4);
            fail("no data ready");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        assertEquals(0, in.read(new byte[0], 0, 0));
        try {
            in.skip(1);
            fail("no data ready");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        assertEquals(0, in.skip(0));
        message("0123456789");
        assertTrue(listener.available >= 1);
        assertEquals('0', in.read());
        assertEquals(2, in.read(new byte[2], 0, 2));
        assertEquals(3, in.skip(3));
        ByteBuffer dst = ByteBuffer.allocate(2);
        assertEquals(2, in.read(dst));
        assertEquals(2, in.skip(2));
        connection.getEventHandler().closed(1000, "bye");
        assertTrue(in.isFinished());
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4], 0, 4));
        assertEquals(0, in.skip(5));
        assertEquals(1, listener.allRead);
    }

    @Test
    public void testNullListenerRejected() {
        try {
            in.setReadListener(null);
            fail("null listener");
        } catch (NullPointerException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testFailingListenersAreContained() {
        Counting listener = new Counting();
        listener.failAvailable = true;
        listener.failAllRead = true;
        listener.failError = true;
        in.setReadListener(listener);
        message("x");
        assertTrue(listener.errors >= 1);
        connection.getEventHandler().closed(1000, "bye");
        in.notifyAllDataRead();
        in.notifyAllDataRead();
        assertEquals(1, listener.allRead);
        in.notifyError(new IOException("direct"));
        assertTrue(listener.errors >= 2);
    }

    @Test
    public void testDispatchWithoutListenerIsIgnored() {
        in.dispatchDataAvailable();
        in.notifyDataAvailable();
        in.notifyAllDataRead();
        in.notifyError(new IOException("none"));
        assertFalse(in.isFinished());
    }

    @Test
    public void testOutputGrowsAndFlushesAsOneMessage() throws Exception {
        byte[] big = new byte[20000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) 'q';
        }
        out.write(big, 0, big.length);
        out.write('!');
        out.write(ByteBuffer.wrap(new byte[] { 'a', 'b' }));
        out.write(ByteBuffer.allocate(0));
        out.flush();
        out.flush();
        assertEquals(1, session.texts.size());
        assertEquals(20003, session.texts.get(0).length());
        assertTrue(out.isReady());
        out.close();
        out.close();
        assertFalse(out.isReady());
        try {
            out.write('x');
            fail("closed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        try {
            out.write(new byte[1], 0, 1);
            fail("closed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        try {
            out.write(ByteBuffer.allocate(1));
            fail("closed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        try {
            out.flush();
            fail("closed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testWritingAfterTheConnectionClosedFails() throws Exception {
        connection.close();
        assertTrue(connection.isClosed());
        assertFalse(out.isReady());
        try {
            out.write('x');
            fail("connection closed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testWriteListenerRulesAndFailureContainment() throws Exception {
        final List<String> events = new ArrayList<String>();
        WriteListener listener = new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {
                events.add("possible");
                throw new IOException("write");
            }

            @Override
            public void onError(Throwable t) {
                events.add("error");
                throw new IllegalStateException("onError fails too");
            }
        };
        out.setWriteListener(listener);
        assertTrue(out.hasWriteListener());
        assertEquals("possible", events.get(0));
        assertEquals("error", events.get(1));
        try {
            out.setWriteListener(listener);
            fail("second listener");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        out.write('a');
        out.flush();
        state.pending = 10 * 1024 * 1024;
        assertFalse(out.isReady());
        try {
            out.write('b');
            fail("not ready");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        try {
            out.flush();
            fail("not ready");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
        state.pending = 0;
        out.notifyWritePossible();
        assertTrue(events.size() >= 4);
    }

    @Test
    public void testNullWriteListenerRejected() {
        try {
            out.setWriteListener(null);
            fail("null listener");
        } catch (NullPointerException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testSendMessageGuards() throws Exception {
        Container container = new Container();
        ServletWebConnection bare = new ServletWebConnection(new NoOp(), state, new Handler(container, state));
        try {
            bare.sendMessage(new byte[] { 1 });
            fail("no session yet");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        bare.sendMessage(new byte[0]);
        connection.sendMessage(ByteBuffer.allocate(0), false);
        connection.sendMessage("copy".getBytes(StandardCharsets.UTF_8));
        connection.sendMessage(ByteBuffer.wrap(new byte[] { (byte) 0xC0, (byte) 0xC0 }), false);
        assertEquals(1, session.texts.size());
        assertEquals(1, session.binarySizes.size());
        assertEquals("copy", session.texts.get(0));
    }

    @Test
    public void testWebSocketErrorFailsPendingReadsAndCloses() throws Exception {
        connection.getEventHandler().error(new IOException("wire"));
        assertTrue(connection.isClosed());
        try {
            in.read();
            fail("stream failed");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        connection.getEventHandler().textMessageReceived(null, "late");
        connection.getEventHandler().textMessageReceived(null, "");
        connection.close();
    }

    @Test
    public void testEmptyMessagesAreDropped() throws Exception {
        connection.getEventHandler().textMessageReceived(null, "");
        connection.getEventHandler().binaryMessageReceived(null, ByteBuffer.allocate(0));
        assertEquals(0, in.available());
        connection.getEventHandler().binaryMessageReceived(null, ByteBuffer.wrap(new byte[] { 5, 6 }));
        assertEquals(2, in.available());
    }
}
