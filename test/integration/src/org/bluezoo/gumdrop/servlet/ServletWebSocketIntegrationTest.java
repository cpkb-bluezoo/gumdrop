/*
 * ServletWebSocketIntegrationTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;

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
 * End-to-end integration tests for WebSocket upgrade through the servlet
 * container ({@link HttpUpgradeHandler} / {@link ServletWebConnection}).
 */
public class ServletWebSocketIntegrationTest extends AbstractServerIntegrationTest {

    private static final String CONNECT_HOST = "::1";
    /** RFC 9110 Host field; bracketed IPv6 literals are rejected by {@code HTTPUtils.isValidHost}. */
    private static final String HTTP_HOST = "localhost";
    private static final int PORT = 19080;
    private static final String WS_PATH = "/test/ws";

    @Rule
    public Timeout globalTimeout = Timeout.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withLookingForStuckThread(true)
            .build();

    @Override
    protected File getTestConfigFile() {
        return new File("test/integration/config/servlet-server-test.xml");
    }

    @Test
    public void testServletWebSocketUpgradeHandshake() throws Exception {
        try (Socket socket = connect()) {
            String key = sendUpgradeRequest(socket, WS_PATH);
            String responseHeaders = readResponseHeaders(socket);
            assertTrue("Expected 101, got: " + responseHeaders.trim(),
                    responseHeaders.startsWith("HTTP/1.1 101"));
            assertTrue("Should have Upgrade: websocket",
                    responseHeaders.toLowerCase().contains("upgrade: websocket"));
            assertTrue("Should have correct Sec-WebSocket-Accept",
                    responseHeaders.contains(WebSocketHandshake.calculateAccept(key)));
        }
    }

    @Test
    public void testServletWebSocketTextEchoRoundTrip() throws Exception {
        try (Socket socket = connect()) {
            sendUpgradeRequest(socket, WS_PATH);
            readResponseHeaders(socket);

            OutputStream out = socket.getOutputStream();
            out.write(maskedTextFrame("ping"));
            out.flush();

            assertEquals("Echo: ping", readTextFrame(socket));
        }
    }

    /**
     * A text frame sent in the same TCP write as the upgrade request must
     * still reach the servlet handler after {@code init()} is dispatched.
     */
    @Test
    public void testServletWebSocketMessagePipelinedWithUpgrade() throws Exception {
        try (Socket socket = connect()) {
            byte[] request = upgradeRequestBytes(WS_PATH);
            byte[] frame = maskedTextFrame("early");
            byte[] combined = new byte[request.length + frame.length];
            System.arraycopy(request, 0, combined, 0, request.length);
            System.arraycopy(frame, 0, combined, request.length, frame.length);

            OutputStream out = socket.getOutputStream();
            out.write(combined);
            out.flush();

            String responseHeaders = readResponseHeaders(socket);
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));
            assertEquals("Echo: early", readTextFrame(socket));
        }
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(CONNECT_HOST, PORT), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private byte[] upgradeRequestBytes(String path) {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + HTTP_HOST + ":" + PORT + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private String sendUpgradeRequest(Socket socket, String path) throws Exception {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + HTTP_HOST + ":" + PORT + "\r\n"
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

    private byte[] maskedFrame(int opcode, byte[] payload) throws Exception {
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | opcode);
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

    private String readTextFrame(Socket socket) throws Exception {
        return new String(readFramePayload(socket), StandardCharsets.UTF_8);
    }

    private byte[] readFramePayload(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        in.read();
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
