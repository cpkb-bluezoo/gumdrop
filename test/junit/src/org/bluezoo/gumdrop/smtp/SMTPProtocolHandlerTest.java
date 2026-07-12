/*
 * SMTPProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.smtp;

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
 * Unit tests for {@link SMTPProtocolHandler}'s streaming-lexer conversion
 * (issue #85): command recognition/dispatch, DATA dot-unstuffing, BDAT
 * chunked transfer (including the zero-chunk synchronous-state-revert
 * edge case), and sliced-boundary fuzzing, using a stub {@link Endpoint}
 * with {@code handler == null} (business logic beyond command sequencing
 * is out of scope for this conversion).
 */
public class SMTPProtocolHandlerTest {

    private SMTPProtocolHandler handler;
    private StubEndpoint endpoint;
    private SMTPListener listener;

    @Before
    public void setUp() {
        listener = new SMTPListener();
        handler = new SMTPProtocolHandler(listener, null);
        endpoint = new StubEndpoint();
    }

    private void connect() {
        handler.connected(endpoint);
    }

    private void sendCommand(String command) {
        byte[] data = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(data));
    }

    private void sendRaw(String data) {
        handler.receive(ByteBuffer.wrap(data.getBytes(StandardCharsets.US_ASCII)));
    }

    // Mirrors the real transport contract (TCPEndpoint.processInbound()):
    // a single persistent buffer, compacted between receive() calls so
    // unconsumed bytes from a partial token are preserved and physically
    // moved forward, not a fresh isolated buffer per chunk.
    private void sendSliced(String data, int chunkSize) {
        byte[] wire = data.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer netIn = ByteBuffer.allocate(65536);
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

    private void heloReady() {
        connect();
        endpoint.sentData.clear();
        sendCommand("HELO client.example.com");
        assertTrue(lastResponse().startsWith("250"));
        endpoint.sentData.clear();
    }

    // Uses EHLO (not HELO) so extendedSMTP is set, required by BDAT.
    private void ehloReady() {
        connect();
        endpoint.sentData.clear();
        sendCommand("EHLO client.example.com");
        assertTrue(lastResponse().startsWith("250"));
        endpoint.sentData.clear();
    }

    private void mailRcptReady() {
        heloReady();
        sendCommand("MAIL FROM:<sender@example.com>");
        assertTrue(lastResponse().startsWith("250"));
        sendCommand("RCPT TO:<recipient@example.com>");
        assertTrue(lastResponse().startsWith("250"));
        endpoint.sentData.clear();
    }

    private void ehloMailRcptReady() {
        ehloReady();
        sendCommand("MAIL FROM:<sender@example.com>");
        assertTrue(lastResponse().startsWith("250"));
        sendCommand("RCPT TO:<recipient@example.com>");
        assertTrue(lastResponse().startsWith("250"));
        endpoint.sentData.clear();
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
        assertTrue(lastResponse().startsWith("250"));
    }

    @Test
    public void testNoopLowercase() {
        connect();
        endpoint.sentData.clear();
        sendCommand("noop");
        assertTrue(lastResponse().startsWith("250"));
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
    public void testHeloRequiresArgument() {
        connect();
        endpoint.sentData.clear();
        sendCommand("HELO");
        assertTrue(lastResponse().startsWith("501"));
    }

    @Test
    public void testMailWithoutHeloRejected() {
        connect();
        endpoint.sentData.clear();
        sendCommand("MAIL FROM:<a@example.com>");
        assertTrue(lastResponse().startsWith("503"));
    }

    @Test
    public void testQuitClosesConnection() {
        connect();
        endpoint.sentData.clear();
        sendCommand("QUIT");
        assertTrue(lastResponse().startsWith("221"));
        assertFalse(endpoint.open);
    }

    @Test
    public void testEhloAdvertisesExtensions() {
        connect();
        endpoint.sentData.clear();
        sendCommand("EHLO client.example.com");
        List<String> responses = endpoint.getResponses();
        assertTrue(responses.get(0).startsWith("250"));
        boolean sawPipelining = false;
        for (String r : responses) {
            if (r.contains("PIPELINING")) {
                sawPipelining = true;
            }
        }
        assertTrue(sawPipelining);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Streaming lexer: sliced-boundary and golden transcript coverage
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testCommandSlicedByteAtATime() {
        connect();
        endpoint.sentData.clear();
        sendSliced("NOOP\r\n", 1);
        assertTrue(lastResponse().startsWith("250"));
    }

    @Test
    public void testMailFromSlicedAtEveryChunkSize() {
        for (int chunkSize = 1; chunkSize <= 16; chunkSize++) {
            listener = new SMTPListener();
            handler = new SMTPProtocolHandler(listener, null);
            endpoint = new StubEndpoint();

            heloReady();
            sendSliced("MAIL FROM:<a@example.com>\r\n", chunkSize);
            assertEquals("chunk size " + chunkSize,
                    "250", lastResponse().split(" ")[0]);
        }
    }

    @Test
    public void testMultipleCommandsInOneReceiveCall() {
        heloReady();
        byte[] wire = "MAIL FROM:<a@example.com>\r\nRCPT TO:<b@example.com>\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(wire));
        List<String> responses = endpoint.getResponses();
        assertEquals(2, responses.size());
        assertTrue(responses.get(0).startsWith("250"));
        assertTrue(responses.get(1).startsWith("250"));
    }

    @Test
    public void testLongKeywordTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longWord.append('X');
        }
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
        assertTrue(lastResponse().startsWith("250"));
    }

    @Test
    public void testLongArgsTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longArgs.append('a');
        }
        sendCommand("MAIL FROM:<" + longArgs + "@example.com>");
        assertTrue("Should report line too long", lastResponse().startsWith("500"));

        sendCommand("NOOP");
        assertTrue(lastResponse().startsWith("250"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATA (RFC 5321 §4.5.2 dot-unstuffing) — verifies the requestStop()
    // handoff from the lexer to the existing (unchanged) processDataBuffer
    // state machine at the right moment.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testDataAcceptsDotUnstuffedContent() {
        mailRcptReady();
        sendCommand("DATA");
        assertTrue(lastResponse().startsWith("354"));

        // A doubled leading dot must be unstuffed; the terminator
        // "\r\n.\r\n" must be recognised even though it arrives in the
        // same receive() call as the preceding command line (pipelined
        // DATA + content), and dispatch must not try to re-tokenise the
        // binary content as more command lines.
        sendRaw("Subject: test\r\n..dot-stuffed line\r\nplain line\r\n.\r\n");
        assertTrue(lastResponse().startsWith("250"));
    }

    @Test
    public void testPipelinedCommandAfterDataCompletes() {
        mailRcptReady();
        // DATA and its content pipelined into a single receive() call.
        ByteBuffer netIn = ByteBuffer.allocate(4096);
        netIn.put("DATA\r\nHello\r\n.\r\nNOOP\r\n".getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        List<String> responses = endpoint.getResponses();
        assertEquals(3, responses.size());
        assertTrue(responses.get(0).startsWith("354"));
        assertTrue(responses.get(1).startsWith("250")); // message accepted
        assertTrue(responses.get(2).startsWith("250")); // NOOP
    }

    // ═══════════════════════════════════════════════════════════════════
    // BDAT (RFC 3030) — the requestStop()-timing edge case: a zero-size
    // BDAT synchronously completes and reverts state back to RCPT within
    // dispatchCommand() itself, so the lexer must NOT stop, and a
    // pipelined next command in the same line must be processed normally.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testBdatWithContent() {
        ehloMailRcptReady();
        ByteBuffer netIn = ByteBuffer.allocate(4096);
        byte[] chunk = "Hello, world!".getBytes(StandardCharsets.US_ASCII);
        netIn.put(("BDAT " + chunk.length + " LAST\r\n").getBytes(StandardCharsets.US_ASCII));
        netIn.put(chunk);
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();
        assertTrue(lastResponse().startsWith("250"));
    }

    @Test
    public void testZeroChunkBdatDoesNotStopLexerAndPipelinedCommandRuns() {
        ehloMailRcptReady();
        // BDAT 0 synchronously completes (chunkSize == 0), reverting state
        // to RCPT within dispatchCommand() itself — the lexer must not
        // have been told to stop, so the pipelined NOOP on the same
        // receive() call is processed normally rather than being
        // misrouted as BDAT content.
        ByteBuffer netIn = ByteBuffer.allocate(4096);
        netIn.put("BDAT 0\r\nNOOP\r\n".getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        List<String> responses = endpoint.getResponses();
        assertEquals(2, responses.size());
        assertTrue(responses.get(1).startsWith("250"));
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
            return new InetSocketAddress("127.0.0.1", 25);
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
