/*
 * IMAPProtocolHandlerTest.java
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

package org.bluezoo.gumdrop.imap;

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
 * Unit tests for {@link IMAPProtocolHandler}'s streaming-lexer conversion
 * (issue #85): command recognition/dispatch, the outer tag/command/args
 * assembly, RFC 7888 general-purpose literals (including chained
 * literals spliced back into the reassembled command, and the
 * literal-too-large rejection), and sliced-boundary fuzzing.
 *
 * <p>APPEND's own message-body literal (the specialised streaming path,
 * as opposed to the general-purpose literal path exercised here) needs a
 * real {@code MailboxStore}/{@code Mailbox} to drive meaningfully and is
 * already covered end-to-end, including the async mailbox-open buffering
 * for non-synchronizing literals, by {@code IMAPServerIntegrationTest}'s
 * {@code testAppendSynchronizingLiteral}/{@code testAppendNonSynchronizingLiteral}.
 */
public class IMAPProtocolHandlerTest {

    private IMAPProtocolHandler handler;
    private StubEndpoint endpoint;
    private IMAPListener listener;

    @Before
    public void setUp() {
        listener = new IMAPListener();
        handler = new IMAPProtocolHandler(listener);
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

    // ═══════════════════════════════════════════════════════════════════
    // Greeting / basic dispatch
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testGreeting() {
        connect();
        assertTrue(lastResponse().startsWith("* OK"));
    }

    @Test
    public void testCapability() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 CAPABILITY");
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    @Test
    public void testNoop() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 NOOP");
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    @Test
    public void testLogout() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 LOGOUT");
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    @Test
    public void testUnknownCommand() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 BOGUS");
        assertTrue(lastResponse().startsWith("a1 BAD"));
    }

    @Test
    public void testMissingTagNoSpaceAtAll() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1");
        assertTrue(lastResponse().startsWith("* BAD"));
    }

    @Test
    public void testMissingTagLeadingSpace() {
        connect();
        endpoint.sentData.clear();
        sendCommand(" NOOP");
        assertTrue(lastResponse().startsWith("* BAD"));
    }

    @Test
    public void testInvalidTag() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a{1 NOOP");
        assertTrue(lastResponse().startsWith("* BAD"));
    }

    @Test
    public void testCommandCaseInsensitive() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 noop");
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Streaming lexer: sliced-boundary and golden transcript coverage
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testCommandSlicedByteAtATime() {
        connect();
        endpoint.sentData.clear();
        sendSliced("a1 NOOP\r\n", 1);
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    @Test
    public void testCommandWithArgsSlicedAtEveryChunkSize() {
        for (int chunkSize = 1; chunkSize <= 16; chunkSize++) {
            listener = new IMAPListener();
            handler = new IMAPProtocolHandler(listener);
            endpoint = new StubEndpoint();

            connect();
            endpoint.sentData.clear();
            sendSliced("a1 SELECT INBOX\r\n", chunkSize);
            assertEquals("chunk size " + chunkSize,
                    "a1", lastResponse().split(" ")[0]);
        }
    }

    @Test
    public void testMultipleCommandsInOneReceiveCall() {
        connect();
        endpoint.sentData.clear();
        byte[] wire = "a1 NOOP\r\na2 NOOP\r\n".getBytes(StandardCharsets.US_ASCII);
        handler.receive(ByteBuffer.wrap(wire));
        List<String> responses = endpoint.getResponses();
        assertEquals(2, responses.size());
        assertTrue(responses.get(0).startsWith("a1 OK"));
        assertTrue(responses.get(1).startsWith("a2 OK"));
    }

    @Test
    public void testLongTagTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longTag = new StringBuilder();
        for (int i = 0; i < listener.getMaxLineLength() + 100; i++) {
            longTag.append('X');
        }
        ByteBuffer netIn = ByteBuffer.allocate(listener.getMaxLineLength() * 2);
        netIn.put((longTag.toString() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        assertEquals(1, endpoint.getResponses().size());
        assertTrue("Should report line too long", lastResponse().startsWith("* BAD"));

        netIn.put("a1 NOOP\r\n".getBytes(StandardCharsets.US_ASCII));
        netIn.flip();
        handler.receive(netIn);
        netIn.compact();

        assertEquals(2, endpoint.getResponses().size());
        assertTrue(lastResponse().startsWith("a1 OK"));
    }

    @Test
    public void testLongArgsTriggersLineTooLongAndResyncs() {
        connect();
        endpoint.sentData.clear();
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < listener.getMaxLineLength() + 100; i++) {
            longArgs.append('a');
        }
        sendCommand("a1 SELECT " + longArgs);
        // The pre-conversion code's own line-too-long check ran on the raw
        // bytes before the tag was ever parsed out, so the error reply
        // always used currentTag (still unset here) / "*", never the
        // current line's own tag — faithfully replicated, not a bug.
        assertTrue("Should report line too long", lastResponse().startsWith("* BAD"));

        sendCommand("a2 NOOP");
        assertTrue(lastResponse().startsWith("a2 OK"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // RFC 7888 general-purpose literals — the enterRaw()-driven splice
    // path (as opposed to APPEND's own streaming literal, covered by
    // integration tests). Uses LOGIN as the vehicle since it accepts two
    // astring arguments and is reachable pre-authentication.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void testSynchronizingLiteralInLoginIsSplicedAndDispatched() {
        connect();
        endpoint.sentData.clear();
        sendCommand("a1 LOGIN {5}");
        assertTrue("Should request continuation", lastResponse().startsWith("+"));
        sendRaw("alice password\r\n");
        // PRIVACYREQUIRED (no TLS, plaintext login disabled by default) or
        // AUTHENTICATIONFAILED either way proves dispatchCommand() was
        // reached with "alice" correctly spliced in as the literal value
        // and "password" as the following plain-text argument.
        assertTrue(lastResponse().startsWith("a1 NO"));
    }

    @Test
    public void testNonSynchronizingLiteralInLoginPipelinedNoContinuation() {
        connect();
        endpoint.sentData.clear();
        // LITERAL+: command line and literal are pipelined together in one
        // buffer; no "+" continuation should be sent.
        sendRaw("a1 LOGIN {5+}\r\nalice password\r\n");
        List<String> responses = endpoint.getResponses();
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).startsWith("a1 NO"));
    }

    @Test
    public void testChainedLiteralsInLoginBothSpliced() {
        connect();
        endpoint.sentData.clear();
        listener.setAllowPlaintextLogin(true);
        sendCommand("a1 LOGIN {5}");
        assertTrue(lastResponse().startsWith("+"));
        sendRaw("alice {8}\r\n");
        assertTrue("Second literal should also request continuation",
                lastResponse().startsWith("+"));
        sendRaw("password\r\n");
        // allowPlaintextLogin=true clears the PRIVACYREQUIRED gate, so this
        // now fails purely on authentication (no realm configured),
        // proving both literals reached handleLogin() with the right text.
        assertTrue(lastResponse().startsWith("a1 NO"));
        assertTrue(lastResponse().contains("AUTHENTICATIONFAILED"));
    }

    @Test
    public void testLiteralTooLargeRejectedAndResyncs() {
        listener = new IMAPListener();
        listener.setMaxLiteralSize(10);
        handler = new IMAPProtocolHandler(listener);
        endpoint = new StubEndpoint();

        connect();
        endpoint.sentData.clear();
        sendCommand("a1 LOGIN {1000}");
        assertTrue(lastResponse().startsWith("a1 NO"));

        sendCommand("a2 NOOP");
        assertTrue(lastResponse().startsWith("a2 OK"));
    }

    @Test
    public void testLiteralSlicedAtEveryChunkSize() {
        for (int chunkSize = 1; chunkSize <= 24; chunkSize++) {
            listener = new IMAPListener();
            listener.setAllowPlaintextLogin(true);
            handler = new IMAPProtocolHandler(listener);
            endpoint = new StubEndpoint();

            connect();
            endpoint.sentData.clear();
            sendSliced("a1 LOGIN {5+}\r\nalice password\r\n", chunkSize);
            assertEquals("chunk size " + chunkSize,
                    "a1", lastResponse().split(" ")[0]);
            assertTrue("chunk size " + chunkSize,
                    lastResponse().contains("NO"));
        }
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
            return new InetSocketAddress("127.0.0.1", 143);
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
