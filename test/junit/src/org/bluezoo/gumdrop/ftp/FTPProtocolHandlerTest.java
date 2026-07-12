/*
 * FTPProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.ftp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FTPProtocolHandler}.
 *
 * <p>There was no pre-existing unit coverage for the FTP control channel
 * protocol handler prior to its streaming-lexer conversion (issue #85) —
 * this suite fills that gap as part of the conversion's validation gate,
 * following the same pattern used for POP3's {@code POP3ProtocolHandlerTest}:
 * simulate client commands through a stub {@link Endpoint} with {@code
 * handler == null} (business logic — authentication, filesystem access —
 * is out of scope; only command recognition and dispatch, which this
 * conversion changes, is under test) and verify the reply codes sent back.
 */
public class FTPProtocolHandlerTest {

    private FTPProtocolHandler handler;
    private StubEndpoint endpoint;
    private FTPListener listener;

    @Before
    public void setUp() {
        listener = new FTPListener();
        handler = new FTPProtocolHandler(listener, null);
        endpoint = new StubEndpoint();
    }

    private void connect() {
        handler.connected(endpoint);
    }

    private void sendCommand(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    // Mirrors the real transport contract (TCPEndpoint.processInbound()):
    // a single persistent buffer, compacted between receive() calls so
    // unconsumed bytes from a partial token are preserved and physically
    // moved forward, not a fresh isolated buffer per chunk.
    private void sendCommandSliced(String command, int chunkSize) {
        byte[] wire = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(2048);
        int offset = 0;
        while (offset < wire.length) {
            int len = Math.min(chunkSize, wire.length - offset);
            netIn.put(wire, offset, len);
            offset += len;
            netIn.flip();
            handler.receive(netIn);
            netIn.compact();
        }
    }

    private String lastResponse() {
        List<String> responses = endpoint.getResponses();
        assertFalse("No responses sent", responses.isEmpty());
        return responses.get(responses.size() - 1);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Greeting / basic dispatch
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testGreeting() {
        connect();
        assertTrue(lastResponse().startsWith("220"));
    }

    @Test
    public void testNoop() {
        connect();
        endpoint.sentData.clear();
        sendCommand("NOOP");
        assertTrue(lastResponse().startsWith("200"));
    }

    @Test
    public void testNoopLowercase() {
        // RFC 959 command verbs are case-insensitive.
        connect();
        endpoint.sentData.clear();
        sendCommand("noop");
        assertTrue(lastResponse().startsWith("200"));
    }

    @Test
    public void testUnknownCommand() {
        connect();
        endpoint.sentData.clear();
        sendCommand("BOGUS");
        assertTrue(lastResponse().startsWith("500"));
        assertTrue(lastResponse().contains("BOGUS"));
    }

    @Test
    public void testUserWithoutHandlerPromptsForPassword() {
        connect();
        endpoint.sentData.clear();
        sendCommand("USER alice");
        assertTrue(lastResponse().startsWith("331"));
    }

    @Test
    public void testUserRequiresArgument() {
        connect();
        endpoint.sentData.clear();
        sendCommand("USER");
        assertTrue(lastResponse().startsWith("501"));
    }

    @Test
    public void testPwdWithoutLoginRejected() {
        connect();
        endpoint.sentData.clear();
        sendCommand("PWD");
        assertTrue(lastResponse().startsWith("530"));
    }

    @Test
    public void testQuitClosesConnection() {
        connect();
        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue(lastResponse().startsWith("221"));
        assertFalse(endpoint.open);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Streaming lexer tests (issue #85) — sliced-boundary and golden
    // transcript coverage, proving the FTPServerLexer conversion from
    // buffered-line parsing preserves identical semantic dispatch.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testCommandSlicedByteAtATime() {
        connect();
        endpoint.sentData.clear();
        sendCommandSliced("NOOP", 1);
        assertTrue(lastResponse().startsWith("200"));
    }

    @Test
    public void testCommandWithArgsSlicedAtEveryChunkSize() {
        for (int chunkSize = 1; chunkSize <= 12; chunkSize++) {
            listener = new FTPListener();
            handler = new FTPProtocolHandler(listener, null);
            endpoint = new StubEndpoint();

            connect();
            endpoint.sentData.clear();
            sendCommandSliced("USER alice", chunkSize);
            assertEquals("chunk size " + chunkSize,
                    "331", lastResponse().split(" ")[0]);
        }
    }

    @Test
    public void testPathnameWithSpacesSlicedAtEveryChunkSize() {
        // PWD without login always replies 530 regardless of args, but the
        // reply only comes at all if the whole line (including a
        // multi-space pathname arg) was correctly lexed through to CRLF.
        for (int chunkSize = 1; chunkSize <= 20; chunkSize++) {
            listener = new FTPListener();
            handler = new FTPProtocolHandler(listener, null);
            endpoint = new StubEndpoint();

            connect();
            endpoint.sentData.clear();
            sendCommandSliced("DELE my file with spaces.txt", chunkSize);
            assertEquals("chunk size " + chunkSize,
                    "530", lastResponse().split(" ")[0]);
        }
    }

    @Test
    public void testMultipleCommandsInOneReceiveCall() {
        connect();
        endpoint.sentData.clear();
        byte[] wire = "NOOP\r\nNOOP\r\n".getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(wire));
        List<String> responses = endpoint.getResponses();
        assertEquals(2, responses.size());
        assertTrue(responses.get(0).startsWith("200"));
        assertTrue(responses.get(1).startsWith("200"));
    }

    @Test
    public void testLongKeywordTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longWord.append('X');
        }
        // The lexer's own cap fires mid-buffer, leaving the tail of this
        // line (including its CRLF) genuinely unconsumed — see the
        // equivalent POP3 test/plan-doc note for why this needs two
        // separate receive() calls to correctly model the transport
        // contract rather than one combined buffer.
        ByteBuffer netIn = ByteBuffer.allocate(4096);
        netIn.put((longWord.toString() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        assertEquals(1, endpoint.getResponses().size());
        assertTrue("Should report line too long", lastResponse().startsWith("500"));

        netIn.put("NOOP\r\n".getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        assertEquals(2, endpoint.getResponses().size());
        assertTrue(lastResponse().startsWith("200"));
    }

    @Test
    public void testLongArgsTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longArgs.append('a');
        }
        sendCommand("DELE " + longArgs);
        assertTrue("Should report line too long", lastResponse().startsWith("500"));

        // Parser-tracked (not lexer-capped) overflow must not desync the
        // lexer: the next command, in the SAME receive() model, parses
        // normally.
        sendCommand("NOOP");
        assertTrue(lastResponse().startsWith("200"));
    }

    @Test
    public void testEmptyLineDoesNotCrash() {
        connect();
        endpoint.sentData.clear();
        sendCommand("");
        assertTrue(lastResponse().startsWith("500"));
        sendCommand("NOOP");
        assertTrue(lastResponse().startsWith("200"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stub Endpoint
    // ═══════════════════════════════════════════════════════════════════

    static class StubEndpoint implements Endpoint {
        final List<byte[]> sentData = new ArrayList<byte[]>();
        boolean open = true;
        boolean secure;

        @Override
        public void send(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sentData.add(bytes);
        }

        List<String> getResponses() {
            List<String> result = new ArrayList<String>();
            for (byte[] data : sentData) {
                String s = new String(data, StandardCharsets.US_ASCII);
                String[] lines = s.split("\r\n", -1);
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
            }
            return result;
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 21);
        }
        @Override public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }
        @Override public boolean isSecure() { return secure; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() {}
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) {}
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }
        @Override public void pauseRead() {}
        @Override public void resumeRead() {}
        @Override public void onWriteReady(Runnable callback) {
            if (callback != null) {
                callback.run();
            }
        }
    }
}
