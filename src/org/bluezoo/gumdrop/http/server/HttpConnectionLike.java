/*
 * HttpConnectionLike.java
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

import java.util.List;
import org.bluezoo.gumdrop.http.Header;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.hpack.Decoder;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Abstract base for HTTP connection abstractions used by {@link Stream}.
 *
 * <p>{@link HttpProtocolHandler} extends this class so that
 * {@link Stream} can interact with the HTTP protocol handler uniformly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
abstract class HttpConnectionLike {

    abstract String getScheme();
    abstract HttpVersion getVersion();
    abstract SocketAddress getRemoteSocketAddress();
    abstract SocketAddress getLocalSocketAddress();
    abstract SecurityInfo getSecurityInfoForStream();
    abstract HttpStreamHandler getStreamHandler();

    abstract void sendResponseHeaders(int streamId, int statusCode, List<Header> headers, boolean endStream);
    abstract void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream);

    /**
     * Sends trailer fields as the final HEADERS frame of an HTTP/2 response,
     * ending the stream, after any data still queued for it. HTTP/1.x
     * trailers are part of the chunked body and are written by the stream.
     */
    abstract void sendResponseTrailers(int streamId, List<Header> trailers);
    abstract void send(ByteBuffer buf);
    abstract void sendRstStream(int streamId, int errorCode);
    abstract void sendGoaway(int errorCode);
    abstract void switchToWebSocketMode(int streamId);
    abstract void switchToStreamTunnelMode(int streamId);
    abstract Decoder getHpackDecoder();
    abstract boolean isSecure();
    abstract TelemetryConfig getTelemetryConfig();
    abstract Trace getTrace();
    abstract void setTrace(Trace trace);
    abstract HttpServerMetrics getServerMetrics();
    abstract boolean isEnablePush();
    abstract Stream newStream(HttpConnectionLike connection, int streamId);
    abstract int getNextServerStreamId();
    abstract byte[] encodeHeaders(List<Header> headers);
    abstract void sendPushPromise(int streamId, int promisedStreamId, ByteBuffer headerBlock, boolean endHeaders);
    abstract Stream createPushedStream(int streamId, String method, String uri, List<Header> headers);
    abstract SelectorLoop getSelectorLoop();
    abstract TimerHandle scheduleTimer(long delayMs, Runnable callback);
    abstract int getMaxHeaderListSize();
    abstract long getMaxRequestBodySize();

    /**
     * Returns the authentication provider configured for this connection's
     * listener/service, or {@code null} if none is configured.
     *
     * @return the authentication provider, or null
     */
    abstract HttpAuthenticationProvider getAuthenticationProvider();

    /**
     * Whether this request asks to switch the connection to HTTP/2 (RFC 9113
     * section 3.1, {@code Upgrade: h2c}) and the connection will do so. The
     * request is then answered as HTTP/2 stream 1, so it must not reach the
     * application until the switch is complete: anything it wrote sooner would
     * be an HTTP/1.1 response sent before the {@code 101}.
     *
     * @param stream the request
     * @return true if the connection will become HTTP/2 for this request;
     *         false by default
     */
    boolean upgradesToHttp2(Stream stream) {
        return false;
    }

    /**
     * Runs {@code release} once this connection has become HTTP/2, or not at
     * all if the connection ends first.
     *
     * @param release what to run
     */
    void whenHttp2Established(Runnable release) {
        release.run();
    }

    /**
     * Whether this client is locked out of authenticating after too many
     * failed attempts (see {@code Listener#maxAuthFailures}). A request that
     * carries credentials is then refused without consulting the realm.
     *
     * @return true if the client is locked out; false by default
     */
    boolean isAuthLockedOut() {
        return false;
    }

    /** Counts a failed authentication towards this client's lockout. */
    void recordAuthFailure() {
    }

    /**
     * Records a successful authentication, clearing this client's failure count.
     *
     * @param username the authenticated user
     */
    void recordAuthSuccess(String username) {
    }

    /**
     * Registers a one-shot callback invoked when the transport is ready
     * for more data on the given stream.
     *
     * <p>For HTTP/1.1 this registers on the TCP endpoint's write-complete
     * callback.  For HTTP/2 this is tracked per-stream; the callback fires
     * when the stream's flow-control send window opens (WINDOW_UPDATE
     * received) or when the TCP write buffer drains.  For HTTP/3 this
     * delegates to the underlying QUIC stream's own write-readiness
     * callback, firing once its congestion/flow-control send window opens.
     *
     * @param streamId the stream requesting write-readiness notification
     * @param callback the callback, or null to clear
     */
    abstract void onWritable(int streamId, Runnable callback);

    /**
     * Pauses delivery of request body data for the given stream.
     *
     * <p>For HTTP/1.1, this removes {@code OP_READ} from the
     * connection's {@code SelectionKey}, causing TCP backpressure.
     *
     * <p>For HTTP/2, this withholds WINDOW_UPDATE frames for the
     * stream.  The peer's send window will eventually fill and it
     * will stop sending DATA on this stream, without affecting other
     * streams on the same connection.
     *
     * <p>For HTTP/3, this pauses reading on the underlying QUIC stream.
     * The peer's flow-control window fills naturally and it stops
     * sending, without affecting other streams.
     *
     * @param streamId the stream to pause
     */
    abstract void pauseRead(int streamId);

    /**
     * Resumes delivery of request body data for the given stream
     * after a previous {@link #pauseRead(int)}.
     *
     * <p>For HTTP/1.1, this restores {@code OP_READ} on the
     * connection's {@code SelectionKey}.
     *
     * <p>For HTTP/2, this sends the accumulated WINDOW_UPDATE
     * increment that was withheld while the stream was paused,
     * allowing the peer to resume sending DATA.
     *
     * <p>For HTTP/3, this resumes reading on the underlying QUIC stream,
     * which sends MAX_STREAM_DATA as data is consumed, re-opening the
     * peer's flow-control window.
     *
     * @param streamId the stream to resume
     */
    abstract void resumeRead(int streamId);

    /**
     * Returns the number of response body bytes currently buffered for
     * the given stream because the flow-control send window is closed
     * (HTTP/2) or the transport write buffer is full.
     *
     * <p>Callers use this to apply backpressure to a producer (e.g. a
     * servlet worker thread) before queuing more data, rather than
     * buffering an unbounded amount of unsent response body.
     *
     * @param streamId the stream to query
     * @return the number of buffered, unsent bytes; 0 if none or not
     *      applicable to this transport
     */
    abstract int pendingResponseBytes(int streamId);

    /**
     * Applies RFC 9218 priority from a request's {@code Priority} header.
     * Default: ignore (HTTP/1.1 stubs).
     *
     * @param streamId the HTTP/2 stream identifier
     * @param headers the decoded request headers
     */
    void applyRfc9218Priority(int streamId, List<Header> headers) {
    }
}
