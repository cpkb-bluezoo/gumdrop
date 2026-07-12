/*
 * WebSocketServerIntegrationTest.java
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

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link WebSocketListener} + {@link WebSocketService}
 * with real network connections.
 *
 * <p>Written to close the test-coverage gap noted while root-causing issue
 * #107 (streaming protocol handler project, issue #85 Phase 6): prior to
 * this class, no unit or integration test exercised
 * {@code WebSocketListener} at all, which is how two pre-existing bugs in
 * the post-upgrade data path went undetected:
 * <ul>
 *   <li>{@code HTTPProtocolHandler.processHeaderLine()}'s bodyless-request
 *       handling unconditionally reset connection state back to
 *       {@code REQUEST_LINE} after the upgrade callback returned,
 *       clobbering the {@code WEBSOCKET} state {@code switchToWebSocketMode()}
 *       had just set synchronously — see {@link #testTextMessagePipelinedWithUpgrade}.</li>
 *   <li>{@code Stream.appendRequestBody}/{@code receiveRequestBody} copied
 *       WebSocket bytes into an ephemeral buffer each call and
 *       force-consumed it, discarding any bytes a partial trailing frame
 *       needed to survive to the next {@code receive()} call — see
 *       {@link #testFrameSplitAcrossTwoWrites}.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketServerIntegrationTest extends AbstractServerIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18110;

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/websocket-server-test.xml");
    }

    @Test
    public void testServerStartsAndAcceptsConnections() throws Exception {
        assertTrue("WebSocket port should be listening", isPortListening(HOST, PORT));
    }

    @Test
    public void testUpgradeHandshake() throws Exception {
        try (Socket socket = connect()) {
            String key = sendUpgradeRequest(socket, "/chat");
            String responseHeaders = readResponseHeaders(socket);
            assertTrue("Should receive 101 Switching Protocols",
                    responseHeaders.startsWith("HTTP/1.1 101"));
            assertTrue("Should have Upgrade: websocket",
                    responseHeaders.toLowerCase().contains("upgrade: websocket"));
            assertTrue("Should have correct Sec-WebSocket-Accept",
                    responseHeaders.contains(WebSocketHandshake.calculateAccept(key)));
        }
    }

    /**
     * Regression test for the connection-state-clobber bug: a masked text
     * frame pipelined into the SAME write as the upgrade request must be
     * delivered to the application, not silently swallowed while the
     * lexer waits forever for a CRLF that will never come.
     */
    @Test
    public void testTextMessagePipelinedWithUpgrade() throws Exception {
        try (Socket socket = connect()) {
            byte[] request = upgradeRequestBytes("/chat");
            byte[] frame = maskedTextFrame("hello");
            byte[] combined = new byte[request.length + frame.length];
            System.arraycopy(request, 0, combined, 0, request.length);
            System.arraycopy(frame, 0, combined, request.length, frame.length);

            OutputStream out = socket.getOutputStream();
            out.write(combined);
            out.flush();

            String responseHeaders = readResponseHeaders(socket);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));

            assertEquals("echo:hello", readTextFrame(socket));
        }
    }

    @Test
    public void testTextMessageSeparateWrite() throws Exception {
        try (Socket socket = connect()) {
            sendUpgradeRequest(socket, "/chat");
            String responseHeaders = readResponseHeaders(socket);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));

            OutputStream out = socket.getOutputStream();
            out.write(maskedTextFrame("world"));
            out.flush();

            assertEquals("echo:world", readTextFrame(socket));
        }
    }

    @Test
    public void testBinaryMessageRoundTrip() throws Exception {
        try (Socket socket = connect()) {
            sendUpgradeRequest(socket, "/chat");
            String responseHeaders = readResponseHeaders(socket);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));

            byte[] payload = new byte[]{1, 2, 3, 4, 5, (byte) 0xff, 0, 42};
            OutputStream out = socket.getOutputStream();
            out.write(maskedBinaryFrame(payload));
            out.flush();

            byte[] echoed = readBinaryFrame(socket);
            assertArrayEquals(payload, echoed);
        }
    }

    /**
     * Regression test for the request-body buffer copy+force-consume bug:
     * splits a single WebSocket frame's bytes across two separate TCP
     * writes (a partial header/payload write, then the remainder after a
     * short delay) so the server must receive it across two distinct
     * {@code receive()} calls. Before the fix, the incomplete trailing
     * bytes from the first call were discarded rather than preserved,
     * corrupting the frame.
     */
    @Test
    public void testFrameSplitAcrossTwoWrites() throws Exception {
        try (Socket socket = connect()) {
            sendUpgradeRequest(socket, "/chat");
            String responseHeaders = readResponseHeaders(socket);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));

            byte[] frame = maskedTextFrame("split-across-packets");
            int splitAt = frame.length / 2;

            OutputStream out = socket.getOutputStream();
            out.write(frame, 0, splitAt);
            out.flush();
            Thread.sleep(200); // force two distinct receive() calls
            out.write(frame, splitAt, frame.length - splitAt);
            out.flush();

            assertEquals("echo:split-across-packets", readTextFrame(socket));
        }
    }

    // ── Helpers ──

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private byte[] upgradeRequestBytes(String path) {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + HOST + ":" + PORT + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    /** Sends the upgrade request and returns the Sec-WebSocket-Key used. */
    private String sendUpgradeRequest(Socket socket, String path) throws Exception {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + HOST + ":" + PORT + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return key;
    }

    private String readResponseHeaders(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int b;
        int crlfcrlf = 0;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if ((crlfcrlf == 0 && b == '\r') || (crlfcrlf == 1 && b == '\n')
                    || (crlfcrlf == 2 && b == '\r') || (crlfcrlf == 3 && b == '\n')) {
                crlfcrlf++;
            } else {
                crlfcrlf = (b == '\r') ? 1 : 0;
            }
            if (crlfcrlf == 4) {
                break;
            }
        }
        return headerBuf.toString(StandardCharsets.US_ASCII.name());
    }

    private byte[] maskedTextFrame(String text) throws Exception {
        return maskedFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] maskedBinaryFrame(byte[] payload) throws Exception {
        return maskedFrame(0x2, payload);
    }

    /** RFC 6455 section 5.2 — builds a single-frame, client-masked message. */
    private byte[] maskedFrame(int opcode, byte[] payload) throws Exception {
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | opcode); // FIN + opcode
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            throw new IllegalArgumentException("payload too large for this test helper");
        }
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        return out.toByteArray();
    }

    /** Reads one unmasked server-to-client text frame and returns its payload. */
    private String readTextFrame(Socket socket) throws Exception {
        return new String(readFramePayload(socket), StandardCharsets.UTF_8);
    }

    private byte[] readBinaryFrame(Socket socket) throws Exception {
        return readFramePayload(socket);
    }

    private byte[] readFramePayload(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        int first = in.read();
        int second = in.read();
        int len = second & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            throw new IllegalStateException("64-bit length not used by this test helper");
        }
        byte[] payload = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(payload, off, len - off);
            if (n < 0) {
                throw new IllegalStateException("Connection closed before full frame received");
            }
            off += n;
        }
        return payload;
    }
}
