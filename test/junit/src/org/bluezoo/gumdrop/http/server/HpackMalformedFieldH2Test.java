/*
 * HpackMalformedFieldH2Test.java
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


package org.bluezoo.gumdrop.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Before;
import org.junit.Test;

/**
 * What an HTTP/2 server does with a request whose header field is not valid
 * field syntax, and with octets above 0x7F.
 *
 * <p>RFC 9113 section 8.1.1 makes a malformed request a stream error, not a
 * connection error, and section 4.3 requires the HPACK state to stay in step
 * with the peer's, which means the whole block must be decoded even though one
 * field in it is refused. So the stream is reset with PROTOCOL_ERROR, no
 * GOAWAY is sent, and a later request on the connection that depends on
 * entries inserted by the refused block still decodes.
 *
 * <p>Octets above 0x7F in a value are opaque (RFC 9110 section 5.5), not
 * malformed, and reach the handler one character per octet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HpackMalformedFieldH2Test {

    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int PROTOCOL_ERROR = 0x1;

    /** One frame written by the server. */
    private static final class Frame {
        final int type;
        final int streamId;
        final byte[] payload;

        Frame(int type, int streamId, byte[] payload) {
            this.type = type;
            this.streamId = streamId;
            this.payload = payload;
        }
    }

    private static final class CapturingEndpoint implements Endpoint {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        @Override public void send(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            sent.write(b, 0, b.length);
        }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
        @Override public void onWriteReady(Runnable callback) { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public boolean isTelemetryEnabled() { return false; }
        @Override public TelemetryConfig getTelemetryConfig() { return null; }

        /** Parses everything written so far into frames (RFC 9113 section 4.1). */
        List<Frame> frames() {
            byte[] all = sent.toByteArray();
            List<Frame> out = new ArrayList<Frame>();
            int p = 0;
            while (p + 9 <= all.length) {
                int length = ((all[p] & 0xFF) << 16) | ((all[p + 1] & 0xFF) << 8) | (all[p + 2] & 0xFF);
                int type = all[p + 3] & 0xFF;
                int stream = ((all[p + 5] & 0x7F) << 24) | ((all[p + 6] & 0xFF) << 16)
                        | ((all[p + 7] & 0xFF) << 8) | (all[p + 8] & 0xFF);
                if (p + 9 + length > all.length) {
                    break;
                }
                byte[] payload = new byte[length];
                System.arraycopy(all, p + 9, payload, 0, length);
                out.add(new Frame(type, stream, payload));
                p += 9 + length;
            }
            return out;
        }
    }

    private static final class StubSecurityInfo implements SecurityInfo {
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
        @Override public int getKeySize() { return 256; }
        @Override public Certificate[] getPeerCertificates() { return null; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public String getApplicationProtocol() { return "h2"; }
        @Override public long getHandshakeDurationMs() { return 0; }
        @Override public boolean isSessionResumed() { return false; }
    }

    /** Remembers what the application was shown. */
    private static final class Seen extends CollectingRequestHandler {
        static final List<String> requests = new ArrayList<String>();

        @Override
        public void headers(HttpResponse state, Headers headers) {
            requests.add(headers.getValue(":path") + "|" + headers.getValue("x-raw")
                    + "|" + headers.getValue("x-good"));
        }
    }

    private CapturingEndpoint endpoint;
    private HttpProtocolHandler connection;

    @Before
    public void setUp() {
        Seen.requests.clear();
        Http2Listener listener = new Http2Listener();
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse state) {
                return CollectingRequestHandler.bind(new Seen(), state);
            }
        });
        endpoint = new CapturingEndpoint();
        connection = new HttpProtocolHandler(listener);
        connection.connected(endpoint);
        connection.securityEstablished(new StubSecurityInfo());
        connection.settingsFrameReceived(false, Collections.emptyMap());
    }

    private static void literal(ByteArrayOutputStream out, int opcode, String name, byte[] value) {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        out.write(opcode);
        out.write(n.length);
        out.write(n, 0, n.length);
        out.write(value.length);
        out.write(value, 0, value.length);
    }

    /** :method GET, :scheme https, :path /, :authority example.test (RFC 7541 appendix A). */
    private static ByteArrayOutputStream getRequest() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x82);   // :method GET
        out.write(0x87);   // :scheme https
        out.write(0x84);   // :path /
        byte[] host = "example.test".getBytes(StandardCharsets.US_ASCII);
        out.write(0x01);   // literal without indexing, name = static index 1 (:authority)
        out.write(host.length);
        out.write(host, 0, host.length);
        return out;
    }

    private void send(int streamId, ByteArrayOutputStream block) throws Exception {
        connection.headersFrameReceived(streamId, true, true, 0, false, 16,
                ByteBuffer.wrap(block.toByteArray()));
    }

    private List<Frame> framesOfType(int type) {
        List<Frame> out = new ArrayList<Frame>();
        for (Frame f : endpoint.frames()) {
            if (f.type == type) {
                out.add(f);
            }
        }
        return out;
    }

    @Test
    public void octetsAboveAsciiReachTheHandlerOneCharacterPerOctet() throws Exception {
        ByteArrayOutputStream block = getRequest();
        literal(block, 0x00, "x-raw", new byte[] {'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9});
        send(3, block);

        assertEquals(1, Seen.requests.size());
        // The two octets of UTF-8 e-acute arrive as two characters: nothing is
        // decoded on the application's behalf.
        assertEquals("/|cafÃ©|null", Seen.requests.get(0));
        assertTrue(framesOfType(TYPE_RST_STREAM).isEmpty());
        assertTrue(framesOfType(TYPE_GOAWAY).isEmpty());
    }

    @Test
    public void malformedFieldResetsTheStreamNotTheConnection() throws Exception {
        ByteArrayOutputStream block = getRequest();
        literal(block, 0x00, "x-raw", new byte[] {'a', '\r', '\n', 'b'});
        send(3, block);

        assertTrue("the application never saw the request", Seen.requests.isEmpty());
        List<Frame> resets = framesOfType(TYPE_RST_STREAM);
        assertEquals(1, resets.size());
        assertEquals(3, resets.get(0).streamId);
        assertEquals(PROTOCOL_ERROR, resets.get(0).payload[3] & 0xFF);
        assertTrue("not a connection error", framesOfType(TYPE_GOAWAY).isEmpty());
    }

    @Test
    public void tableStaysInStepSoALaterRequestUsingTheRefusedBlocksEntriesDecodes() throws Exception {
        // Request 1: a bad field, then a good one inserted into the dynamic
        // table. If the decoder had stopped at the bad field, the good entry
        // would never have been inserted.
        ByteArrayOutputStream first = getRequest();
        literal(first, 0x40, "x-bad", new byte[] {'a', '\r', 'b'});
        literal(first, 0x40, "x-good", "ok".getBytes(StandardCharsets.US_ASCII));
        send(3, first);
        assertTrue(Seen.requests.isEmpty());
        assertEquals(1, framesOfType(TYPE_RST_STREAM).size());

        // Request 2 refers to x-good by its dynamic index: newest entry = 62.
        ByteArrayOutputStream second = getRequest();
        second.write(0x80 | 62);
        send(5, second);

        assertEquals(1, Seen.requests.size());
        assertEquals("/|null|ok", Seen.requests.get(0));
        assertEquals("still only the one reset", 1, framesOfType(TYPE_RST_STREAM).size());
        assertTrue(framesOfType(TYPE_GOAWAY).isEmpty());
    }
}
