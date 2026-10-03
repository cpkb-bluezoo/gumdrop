/*
 * MockHttpConnection.java
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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

/**
 * Hand-written mock of the connection side seen by {@link Stream}.
 * Everything the stream sends is recorded in public fields so unit tests
 * can assert on it; behaviour is configurable through the other public
 * fields. No real I/O, no threads, no timers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MockHttpConnection implements HttpConnectionLike {

    final SelectorLoop loop = new InlineSelectorLoop();
    HttpVersion version = HttpVersion.HTTP_2_0;
    boolean secure = true;
    SecurityInfo securityInfo;
    HttpStreamHandler streamHandler;
    Decoder decoder = new Decoder(4096, 65536);
    int maxHeaderListSize = 65536;
    long maxRequestBodySize;
    HttpAuthenticationProvider authenticationProvider;
    boolean enablePush = true;
    boolean telemetryEnabled;
    TelemetryConfig telemetryConfig;
    Trace trace;
    HttpServerMetrics metrics;
    int nextServerStreamId = 2;
    boolean pushedStreamCreated = true;
    boolean failEncodeHeaders;
    boolean failSendPush;
    boolean failSendResponseHeaders;
    boolean failSendResponseBody;

    final List<Integer> statuses = new ArrayList<Integer>();
    final List<Headers> sentHeaders = new ArrayList<Headers>();
    final List<Boolean> headerEndStreams = new ArrayList<Boolean>();
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    final List<Boolean> bodyEndStreams = new ArrayList<Boolean>();
    final List<Integer> rstCodes = new ArrayList<Integer>();
    final List<Integer> goawayCodes = new ArrayList<Integer>();
    final List<ByteBuffer> raw = new ArrayList<ByteBuffer>();
    int nullSends;
    int webSocketSwitches;
    int tunnelSwitches;
    int pushPromises;
    int pausedCount;
    int resumedCount;
    int pendingBytes;
    final Map<Integer, Runnable> writable = new HashMap<Integer, Runnable>();
    final List<Stream> pushedStreams = new ArrayList<Stream>();
    /** Header sets given to encodeHeaders (the promised request of each push). */
    final List<Headers> encodedHeaders = new ArrayList<Headers>();

    @Override public String getScheme() { return secure ? "https" : "http"; }
    @Override public HttpVersion getVersion() { return version; }
    @Override public SocketAddress getRemoteSocketAddress() {
        return new InetSocketAddress("127.0.0.1", 40000);
    }
    @Override public SocketAddress getLocalSocketAddress() {
        return new InetSocketAddress("127.0.0.1", 443);
    }
    @Override public SecurityInfo getSecurityInfoForStream() { return securityInfo; }
    @Override public HttpStreamHandler getStreamHandler() { return streamHandler; }

    @Override
    public void sendResponseHeaders(int streamId, int statusCode, Headers headers,
            boolean endStream) {
        if (failSendResponseHeaders) {
            throw new IllegalStateException("mock header failure");
        }
        statuses.add(Integer.valueOf(statusCode));
        sentHeaders.add(headers);
        headerEndStreams.add(Boolean.valueOf(endStream));
    }

    @Override
    public void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
        if (failSendResponseBody) {
            throw new IllegalStateException("mock body failure");
        }
        if (buf != null) {
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            body.write(b, 0, b.length);
        }
        bodyEndStreams.add(Boolean.valueOf(endStream));
    }

    /** The trailer sections sent, one per call. */
    final List<Headers> sentTrailers = new ArrayList<Headers>();

    @Override
    public void sendResponseTrailers(int streamId, Headers trailers) {
        sentTrailers.add(trailers);
    }

    @Override
    public void send(ByteBuffer buf) {
        if (buf == null) {
            nullSends++;
        } else {
            raw.add(buf);
        }
    }

    @Override public void sendRstStream(int streamId, int errorCode) {
        rstCodes.add(Integer.valueOf(errorCode));
    }
    @Override public void sendGoaway(int errorCode) {
        goawayCodes.add(Integer.valueOf(errorCode));
    }
    @Override public void switchToWebSocketMode(int streamId) { webSocketSwitches++; }
    @Override public void switchToStreamTunnelMode(int streamId) { tunnelSwitches++; }
    @Override public Decoder getHpackDecoder() { return decoder; }
    @Override public boolean isSecure() { return secure; }
    @Override public TelemetryConfig getTelemetryConfig() { return telemetryConfig; }
    @Override public Trace getTrace() { return trace; }
    @Override public void setTrace(Trace t) { trace = t; }
    @Override public boolean isTelemetryEnabled() { return telemetryEnabled; }
    @Override public HttpServerMetrics getServerMetrics() { return metrics; }
    @Override public boolean isEnablePush() { return enablePush; }

    @Override
    public Stream newStream(HttpConnectionLike connection, int streamId) {
        return new Stream(connection, streamId);
    }

    @Override
    public int getNextServerStreamId() {
        int id = nextServerStreamId;
        nextServerStreamId += 2;
        return id;
    }

    @Override
    public byte[] encodeHeaders(Headers headers) {
        if (failEncodeHeaders) {
            throw new IllegalStateException("mock encode failure");
        }
        encodedHeaders.add(headers);
        return new byte[] { (byte) 0x82 };
    }

    @Override
    public void sendPushPromise(int streamId, int promisedStreamId,
            ByteBuffer headerBlock, boolean endHeaders) {
        if (failSendPush) {
            throw new IllegalStateException("mock push failure");
        }
        pushPromises++;
    }

    @Override
    public Stream createPushedStream(int streamId, String method, String uri,
            Headers headers) {
        if (!pushedStreamCreated) {
            return null;
        }
        Stream s = new Stream(this, streamId);
        pushedStreams.add(s);
        return s;
    }

    @Override public SelectorLoop getSelectorLoop() { return loop; }
    @Override public TimerHandle scheduleTimer(long delayMs, Runnable callback) { return null; }
    @Override public int getMaxHeaderListSize() { return maxHeaderListSize; }
    @Override public long getMaxRequestBodySize() { return maxRequestBodySize; }
    @Override public HttpAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    @Override
    public void onWritable(int streamId, Runnable callback) {
        if (callback == null) {
            writable.remove(Integer.valueOf(streamId));
        } else {
            writable.put(Integer.valueOf(streamId), callback);
        }
    }

    @Override public void pauseRead(int streamId) { pausedCount++; }
    @Override public void resumeRead(int streamId) { resumedCount++; }
    @Override public int pendingResponseBytes(int streamId) { return pendingBytes; }

    String bodyString() {
        return new String(body.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
