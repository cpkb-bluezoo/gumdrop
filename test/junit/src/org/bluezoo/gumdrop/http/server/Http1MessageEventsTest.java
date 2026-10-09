/*
 * Http1MessageEventsTest.java
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
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.RecordingMessageHandler;
import org.junit.Before;
import org.junit.Test;

/**
 * An HTTP/2 request reaches an application handler written for the message
 * events ({@link org.bluezoo.gumdrop.http.HttpMessageHandler}) as those events,
 * whole and in order: start events, fields, {@code endHeaders}, body, and
 * {@code endMessage}. The server holds the header section until it has
 * decided what to do with the request, then replays it to the handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http1MessageEventsTest {

    /** A handler that implements only the message events and records them. */
    private static final class EventHandler extends DefaultHttpRequestHandler {
        static final RecordingMessageHandler seen = new RecordingMessageHandler();

        @Override public void method(HttpMethod m) { seen.method(m); }
        @Override public void target(ByteBuffer t) { seen.target(t); }
        @Override public void scheme(ByteBuffer v) { seen.scheme(v); }
        @Override public void authority(ByteBuffer v) { seen.authority(v); }
        @Override public void version(HttpVersion v) { seen.version(v); }
        @Override public void contentType(ContentType c) { seen.contentType(c); }
        @Override public void contentDisposition(ContentDisposition d) { seen.contentDisposition(d); }
        @Override public void longHeader(String n, long v) { seen.longHeader(n, v); }
        @Override public void dateHeader(String n, java.time.Instant v) { seen.dateHeader(n, v); }
        @Override public void header(String n, ByteBuffer v) { seen.header(n, v); }
        @Override public void endHeaders() { seen.endHeaders(); }
        @Override public void bodyContent(ByteBuffer d) { seen.bodyContent(d); }
        @Override public void endMessage() { seen.endMessage(); }
        @Override public void error(HttpError e, String detail) { seen.error(e, detail); }
    }

    private static final class NoopEndpoint implements Endpoint {
        @Override public void send(ByteBuffer data) { }
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
        @Override public TelemetryConfig getTelemetryConfig() { return org.bluezoo.gumdrop.testsupport.StubTelemetry.CONFIG; }
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

    private HttpProtocolHandler connection;

    @Before
    public void setUp() {
        EventHandler.seen.events.clear();
        Http2Listener listener = new Http2Listener();
        listener.streamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse response) {
                return new EventHandler();
            }
        });
        connection = new HttpProtocolHandler(listener);
        connection.connected(new NoopEndpoint());
    }

    private void feed(String request, int chunk) {
        byte[] bytes = request.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer pending = ByteBuffer.allocate(bytes.length + 16);
        pending.flip();
        for (int i = 0; i < bytes.length; i += chunk) {
            pending.compact();
            pending.put(bytes, i, Math.min(chunk, bytes.length - i));
            pending.flip();
            connection.receive(pending);
        }
    }

    private static final String CHUNKED = "POST /up HTTP/1.1\r\nHost: h.test\r\n"
            + "Transfer-Encoding: chunked\r\nTrailer: x-sum\r\n\r\n"
            + "3\r\nabc\r\n2\r\nde\r\n0\r\nx-sum: 7\r\n\r\n";

    private static final List<String> CHUNKED_EVENTS = Arrays.asList("method POST",
            "target /up", "version HTTP/1.1", "scheme https", "authority h.test",
            "header transfer-encoding chunked", "header trailer x-sum",
            "endHeaders", "body abcde", "header x-sum 7", "endMessage");

    @Test
    public void aChunkedBodyAndItsTrailersArriveInOrder() {
        feed(CHUNKED, Integer.MAX_VALUE);
        assertEquals(CHUNKED_EVENTS, EventHandler.seen.events);
    }

    @Test
    public void theSameEventsWhateverTheSplit() {
        for (int chunk : new int[] {1, 2, 3, 7}) {
            EventHandler.seen.events.clear();
            setUp();
            feed(CHUNKED, chunk);
            assertEquals("chunk " + chunk, CHUNKED_EVENTS, EventHandler.seen.events);
        }
    }

    @Test
    public void aContentLengthBodyEndsAtTheEndOfTheMessage() {
        feed("POST /up HTTP/1.1\r\nHost: h.test\r\nContent-Length: 5\r\n\r\nhello", 2);
        assertEquals(Arrays.asList("method POST", "target /up", "version HTTP/1.1",
                "scheme https", "authority h.test", "long content-length 5", "endHeaders", "body hello", "endMessage"),
                EventHandler.seen.events);
    }

    @Test
    public void aRequestWithoutABodyHasNoBodyEvent() {
        feed("GET /x HTTP/1.1\r\nHost: h.test\r\n\r\n", Integer.MAX_VALUE);
        assertEquals(Arrays.asList("method GET", "target /x", "version HTTP/1.1",
                "scheme https", "authority h.test", "endHeaders", "endMessage"), EventHandler.seen.events);
    }
}
