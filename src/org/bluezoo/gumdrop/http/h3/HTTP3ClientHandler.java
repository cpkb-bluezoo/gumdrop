/*
 * HTTP3ClientHandler.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.ArrayList;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPMethodSafety;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;

/**
 * Client-side HTTP/3 handler for one QUIC connection.
 *
 * <p>HTTP/3 (RFC 9114) maps HTTP semantics onto QUIC (RFC 9000)
 * transport. The client negotiates "h3" via ALPN (RFC 9114 section 3.1)
 * during the QUIC handshake, then exchanges SETTINGS frames
 * (RFC 9114 section 7.2.4). This class opens the connection's control
 * stream and sends SETTINGS in its constructor, and registers via
 * {@link H3ControlStream} to receive the peer's control stream events.
 *
 * <p>This class provides
 * {@link #sendRequest(Headers, HTTPResponseHandler)} to initiate
 * HTTP/3 requests: each request opens a new bidirectional stream
 * handled by its own {@link H3ClientStream}, which owns its own
 * {@link H3Parser} and translates response frames into
 * {@link HTTPResponseHandler} callbacks directly -- unlike the previous
 * quiche-backed implementation, this class does not poll for events.
 * Public send entry points marshal onto the connection's
 * {@code SelectorLoop} so callers need not share that thread affinity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3ClientStream
 * @see QuicConnection
 */
public final class HTTP3ClientHandler implements H3ControlStream.Listener {

    private static final Logger LOGGER = Logger.getLogger(HTTP3ClientHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    // RFC 9204 section 3.2.1: matches HPACK's own well-known
    // SETTINGS_HEADER_TABLE_SIZE default (RFC 7541 section 6.5.2) --
    // generous for real header sets, still bounded per connection.
    private static final int DEFAULT_QPACK_TABLE_CAPACITY = 4096;

    private final QuicConnection quicConnection;

    // Our own declared receive-side ceiling, fixed for the connection's
    // lifetime (Decoder enforces this itself, RFC 9204 section 3.2.3).
    // Our send-side Encoder starts at capacity 0 (falls back to
    // literal-only encoding) until the peer's own SETTINGS arrives and
    // tells us the ceiling it's willing to accept -- see settingsReceived.
    private final Encoder qpackEncoder = new Encoder(0);
    private final Decoder qpackDecoder = new Decoder(DEFAULT_QPACK_TABLE_CAPACITY);
    private Endpoint qpackEncoderStream;
    private Endpoint qpackDecoderStream;

    private final Map<Long, H3ClientStream> streams = new HashMap<Long, H3ClientStream>();

    private boolean goaway;

    /** Callback invoked when the h3 connection is ready for requests. */
    private Runnable readyCallback;

    // Requests deferred because their method isn't 0-RTT-eligible and the
    // connection isn't yet established -- see isSafeToSendNow/deferUntilEstablished.
    // Only ever touched from the QuicConnection's own SelectorLoop thread
    // (both deferUntilEstablished and runDeferredRequests are always called
    // from within a h3Handler.execute(...) task), so no synchronization needed.
    private final List<Runnable> deferredRequests = new ArrayList<Runnable>();

    // RFC 9220 section 3 / RFC 8441 section 3: whether the peer's control
    // stream SETTINGS frame (received asynchronously, independent of this
    // connection's own readiness) has arrived yet, and, once it has,
    // whether it advertised SETTINGS_ENABLE_CONNECT_PROTOCOL = 1 -- see
    // whenConnectProtocolKnown/connectWebSocket.
    private boolean initialSettingsReceived;
    private boolean peerEnablesConnectProtocol;
    private final List<Runnable> connectProtocolCallbacks = new ArrayList<Runnable>();

    // RFC 9114 section 4.2.2 / 7.2.4.1: peer's advertised
    // SETTINGS_MAX_FIELD_SECTION_SIZE. Unlimited (the RFC default)
    // until the peer's SETTINGS arrives.
    private long peerMaxFieldSectionSize = Long.MAX_VALUE;

    // RFC 9297 section 2.1.1: peer advertised SETTINGS_H3_DATAGRAM=1.
    private boolean peerH3Datagram;

    /**
     * Creates a new HTTP/3 client handler on top of an existing
     * QUIC connection.
     *
     * @param quicConnection the underlying QUIC connection
     */
    public HTTP3ClientHandler(QuicConnection quicConnection) {
        this.quicConnection = quicConnection;

        quicConnection.setUnidirectionalStreamAcceptHandler(new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(Endpoint stream) {
                return new H3ControlStream(quicConnection, HTTP3ClientHandler.this, qpackEncoder, qpackDecoder,
                        true);
            }
        });
        openControlStream();
        openQpackStreams();
        quicConnection.setDatagramHandler(new ProtocolHandler() {
            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void connected(Endpoint endpoint) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void error(Exception cause) {
            }

            @Override
            public void datagramReceived(ByteBuffer data) {
                onHttpDatagram(data);
            }
        });

        if (readyCallback != null) {
            Runnable cb = readyCallback;
            readyCallback = null;
            cb.run();
        }
    }

    // RFC 9114 section 6.2.1 / 7.2.4: open our own control stream and
    // send SETTINGS as its first frame. The send lives in connected()
    // so a queued open (no uni-stream credit yet; RFC 9000 section 4.6)
    // still emits SETTINGS once the peer grants the stream.
    private void openControlStream() {
        final long[] settings = {
            H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1,
            H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY, DEFAULT_QPACK_TABLE_CAPACITY,
            H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE, H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE,
            H3FrameHandler.SETTINGS_H3_DATAGRAM, 1
        };
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                int length = H3Writer.streamTypeLength(0x00) + H3Writer.settingsLength(settings);
                ByteBuffer out = ByteBuffer.allocate(length);
                H3Writer.writeStreamType(out, 0x00);
                H3Writer.writeSettings(out, settings);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
    }

    // RFC 9204 section 4.2: open our own QPACK encoder and decoder
    // streams, kept open for the connection's lifetime -- neither is
    // ever closed, matching the control stream.
    private void openQpackStreams() {
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                qpackEncoderStream = endpoint;
                ByteBuffer out = ByteBuffer.allocate(H3Writer.streamTypeLength(0x02));
                H3Writer.writeStreamType(out, 0x02);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                qpackDecoderStream = endpoint;
                ByteBuffer out = ByteBuffer.allocate(H3Writer.streamTypeLength(0x03));
                H3Writer.writeStreamType(out, 0x03);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
    }

    /**
     * Sends any QPACK encoder-stream instructions {@code
     * Encoder#encode} wrote to {@code instructions} (often nothing) on
     * this connection's own QPACK encoder stream.
     *
     * @param instructions the (possibly empty) instructions buffer,
     *                     positioned for reading
     */
    void flushQpackEncoderInstructions(ByteBuffer instructions) {
        if (instructions.hasRemaining() && qpackEncoderStream != null) {
            qpackEncoderStream.send(instructions);
        }
    }

    /**
     * Sends any QPACK decoder-stream instructions queued by {@link
     * Decoder#decode} (Section Acknowledgment) or by mirroring an
     * encoder-stream insertion (Insert Count Increment) on this
     * connection's own QPACK decoder stream.
     */
    void flushQpackDecoderInstructions() {
        byte[] bytes = qpackDecoder.takePendingInstructions();
        if (bytes.length > 0 && qpackDecoderStream != null) {
            qpackDecoderStream.send(ByteBuffer.wrap(bytes));
        }
    }

    /**
     * RFC 9204 section 4.4.2: notifies the peer encoder that {@code
     * streamId} was abandoned before its (possibly still in-flight)
     * field section was decoded, so it can release its table
     * references for it -- otherwise they leak for the rest of the
     * connection (a reference-counted entry can never be evicted).
     *
     * @param streamId the stream that was abandoned
     */
    void cancelQpackStream(long streamId) {
        qpackDecoder.cancelStream(streamId);
        flushQpackDecoderInstructions();
    }

    /**
     * Sets a callback that is invoked once the h3 connection is ready
     * to send requests.
     *
     * @param callback the ready callback
     */
    public void setReadyCallback(Runnable callback) {
        this.readyCallback = callback;
    }

    /**
     * Dispatches a task to run on the underlying {@link QuicConnection}'s
     * own {@code SelectorLoop} thread -- the only thread that may safely
     * touch its state (see {@code QuicConnection}'s class documentation).
     * {@link H3Request} uses this so that application callers of
     * {@link org.bluezoo.gumdrop.http.client.HTTPRequest} on an arbitrary
     * thread don't race the connection's own I/O thread.
     *
     * @param task the task to run
     */
    void execute(Runnable task) {
        quicConnection.getSelectorLoop().invokeLater(task);
    }

    /**
     * Returns whether a request using the given method may be sent right
     * now -- either because the connection is already fully established
     * (RFC 9001 section 4.6.1: only established connections may send
     * arbitrary application data), or because the method is safe and
     * idempotent (RFC 9110 section 9.3.1/9.3.2/9.3.7/9.3.8) and so may
     * ride 0-RTT early data if it's available.
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param method the HTTP method of the pending request
     * @return true if the request may be sent immediately
     */
    boolean isSafeToSendNow(String method) {
        return quicConnection.isEstablished() || HTTPMethodSafety.isEarlyDataEligible(method);
    }

    /**
     * Queues a task to run once the connection is fully established,
     * for a request whose method is not 0-RTT-eligible and that was not
     * safe to send immediately (see {@link #isSafeToSendNow}).
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param task the deferred send task
     */
    void deferUntilEstablished(Runnable task) {
        deferredRequests.add(task);
    }

    /**
     * Runs and clears every task queued via {@link #deferUntilEstablished},
     * called once the connection is established.
     */
    public void runDeferredRequests() {
        List<Runnable> pending = new ArrayList<Runnable>(deferredRequests);
        deferredRequests.clear();
        for (Runnable task : pending) {
            task.run();
        }
    }

    /**
     * Runs {@code task} once the peer's initial SETTINGS frame has been
     * received -- and so whether it advertised
     * {@code SETTINGS_ENABLE_CONNECT_PROTOCOL = 1} (RFC 9220 section 3 /
     * RFC 8441 section 3) is known -- running immediately if that has
     * already happened. Used by {@link #connectWebSocket} so it never
     * sends an Extended CONNECT request before knowing the peer accepts
     * one; RFC 8441 section 4 warns that doing so risks "a non-supporting
     * peer... detect[ing] a malformed request and generat[ing] a stream
     * error" instead of a clean rejection.
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param task the task to run once the peer's support is known
     */
    void whenConnectProtocolKnown(Runnable task) {
        if (initialSettingsReceived) {
            task.run();
        } else {
            connectProtocolCallbacks.add(task);
        }
    }

    /**
     * Returns whether this handler has received a GOAWAY frame.
     *
     * @return true if GOAWAY was received
     */
    public boolean isGoaway() {
        return goaway;
    }

    // ── Request sending ──

    /**
     * Sends an HTTP/3 request on a new stream.
     *
     * <p>The headers must include the HTTP/3 pseudo-headers:
     * {@code :method}, {@code :scheme}, {@code :authority}, {@code :path}.
     *
     * <p>Safe to call from any thread: the open is marshaled onto the
     * connection's {@code SelectorLoop} (see {@link #execute}).
     *
     * @param headers the request headers
     * @param handler the handler to receive response events
     * @return the stream ID, or -1 on failure
     */
    public long sendRequest(Headers headers, HTTPResponseHandler handler) {
        return sendRequest(headers, handler, true);
    }

    /**
     * Sends an HTTP/3 request on a new stream (RFC 9114 section 4.1).
     *
     * <p>The headers must include the pseudo-headers defined in
     * RFC 9114 section 4.3.1: {@code :method}, {@code :scheme},
     * {@code :authority}, and {@code :path}.
     *
     * <p>If {@code fin} is false, the caller must send the request body
     * via {@link #sendRequestBody(H3ClientStream, ByteBuffer, boolean)}
     * (or {@link #sendRequestBody(long, ByteBuffer, boolean)} once the
     * stream ID is known).
     *
     * <p>Safe to call from any thread: the open is marshaled onto the
     * connection's {@code SelectorLoop} (see {@link #execute}). When
     * already on that thread the work runs immediately; otherwise this
     * method blocks until the loop has opened the stream so the returned
     * ID is usable by a following {@link #sendRequestBody} call.
     *
     * @param headers the request headers
     * @param handler the handler to receive response events
     * @param fin true if no request body will follow
     * @return the stream ID, or -1 on failure or if the open is still
     *         queued behind peer MAX_STREAMS credit
     */
    public long sendRequest(final Headers headers, final HTTPResponseHandler handler,
            final boolean fin) {
        final long[] streamId = new long[] { -1L };
        final CountDownLatch done = new CountDownLatch(1);
        execute(new Runnable() {
            @Override
            public void run() {
                try {
                    streamId[0] = startRequest(headers, handler, fin).getStreamId();
                } catch (RuntimeException e) {
                    handler.failed(e);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.failed(e);
            return -1L;
        }
        return streamId[0];
    }

    /**
     * Opens an HTTP/3 request stream, queuing the HEADERS send until the
     * QUIC layer grants a stream ID. Used by {@link H3Request} so body
     * data can be buffered on the same object if MAX_STREAMS credit is
     * not yet available.
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param headers the request headers
     * @param handler the handler to receive response events
     * @param fin true if no request body will follow
     * @return the stream object; {@link H3ClientStream#getStreamId} is
     *         {@code -1} until the open completes
     */
    H3ClientStream startRequest(Headers headers, HTTPResponseHandler handler, boolean fin) {
        H3ClientStream clientStream = new H3ClientStream(this, qpackDecoder, handler);
        if (goaway) {
            handler.failed(new IOException("Connection received GOAWAY"));
            return clientStream;
        }
        clientStream.prepareRequest(headers, fin);
        quicConnection.openStream(clientStream);
        return clientStream;
    }

    /**
     * Encodes and sends a request that was armed via
     * {@link H3ClientStream#prepareRequest} once the QUIC stream actually
     * exists. Invoked from {@link H3ClientStream#connected}, including
     * when the open was queued behind peer MAX_STREAMS credit.
     *
     * @param clientStream the stream whose pending request is now writable
     */
    void completePreparedRequest(H3ClientStream clientStream) {
        long streamId = clientStream.getStreamId();
        streams.put(Long.valueOf(streamId), clientStream);
        Headers headers = clientStream.takePendingRequestHeaders();
        boolean fin = clientStream.takePendingRequestFin();
        Endpoint endpoint = clientStream.getEndpoint();
        if (headers == null || endpoint == null) {
            return;
        }
        if (exceedsPeerFieldSectionLimit(headers)) {
            // RFC 9114 section 4.2.2: SHOULD NOT send a field section
            // over the peer's advertised ceiling.
            clientStream.abortExcessiveLoad(
                    "request field section exceeds peer SETTINGS_MAX_FIELD_SECTION_SIZE");
            return;
        }

        ByteBuffer fieldSection = ByteBuffer.allocate(estimateFieldSectionCapacity(headers));
        ByteBuffer encoderInstructions = ByteBuffer.allocate(estimateFieldSectionCapacity(headers));
        qpackEncoder.encode(fieldSection, encoderInstructions, streamId, headers);
        fieldSection.flip();
        byte[] encoded = new byte[fieldSection.remaining()];
        fieldSection.get(encoded);
        encoderInstructions.flip();
        flushQpackEncoderInstructions(encoderInstructions);

        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(out, encoded);
        out.flip();
        endpoint.send(out);
        List<byte[]> body = clientStream.takePendingBody();
        boolean bodyFin = clientStream.takePendingBodyFin();
        for (int i = 0; i < body.size(); i++) {
            sendRequestBodyOnLoop(streamId, body.get(i), false);
        }
        if (fin || bodyFin) {
            endpoint.close();
        }
    }

    /**
     * Sends request body data on the specified stream (RFC 9114
     * section 4.1 -- DATA frames carry the message body).
     *
     * <p>Safe to call from any thread: remaining bytes are snapshotted
     * immediately so the caller may reuse {@code data}, then the send is
     * marshaled onto the connection's {@code SelectorLoop}
     * (see {@link #execute}).
     *
     * @param streamId the stream ID returned by
     *                 {@link #sendRequest(Headers, HTTPResponseHandler, boolean)}
     * @param data the body data
     * @param fin true if this is the last body data
     */
    public void sendRequestBody(final long streamId, ByteBuffer data, final boolean fin) {
        final byte[] snapshot = new byte[data.remaining()];
        data.get(snapshot);
        execute(new Runnable() {
            @Override
            public void run() {
                sendRequestBodyOnLoop(streamId, snapshot, fin);
            }
        });
    }

    /**
     * Sends request body data, buffering it on {@code clientStream} if
     * the QUIC stream has not been granted yet.
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param clientStream the request stream returned by {@link #startRequest}
     * @param data the body data
     * @param fin true if this is the last body data
     */
    void sendRequestBody(H3ClientStream clientStream, ByteBuffer data, boolean fin) {
        byte[] snapshot = new byte[data.remaining()];
        data.get(snapshot);
        if (clientStream.getEndpoint() == null) {
            clientStream.queueRequestBody(snapshot, fin);
            return;
        }
        sendRequestBodyOnLoop(clientStream.getStreamId(), snapshot, fin);
    }

    /**
     * Writes a DATA frame (and optionally closes the stream) on the
     * connection's {@code SelectorLoop} thread.
     */
    private void sendRequestBodyOnLoop(long streamId, byte[] bytes, boolean fin) {
        H3ClientStream clientStream = streams.get(Long.valueOf(streamId));
        if (clientStream == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String formatted = MessageFormat.format(
                        L10N.getString("fine.request_body_unknown_stream"),
                        Long.valueOf(streamId));
                LOGGER.fine(formatted);
            }
            return;
        }
        Endpoint endpoint = clientStream.getEndpoint();
        if (bytes.length > 0) {
            ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(bytes.length));
            H3Writer.writeData(out, bytes);
            out.flip();
            endpoint.send(out);
        }
        if (fin) {
            endpoint.close();
        }
    }

    /**
     * Initiates a WebSocket-over-H3 connection via Extended CONNECT
     * (RFC 9220 section 3): opens a new bidirectional stream, sends a
     * {@code CONNECT} request with {@code :protocol: websocket} and the
     * requested subprotocol/extensions, and leaves the stream open in
     * both directions for the WebSocket tunnel -- unlike
     * {@link #sendRequest}, this never closes the stream after sending
     * headers. Once the server responds with {@code 200}, {@code wsHandler}
     * starts receiving {@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler}
     * callbacks; any other response, or a connection-level failure before
     * then, is reported via {@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler#error}.
     *
     * @param authority the {@code :authority} pseudo-header value
     * @param path the {@code :path} pseudo-header value
     * @param subprotocol the WebSocket subprotocol to request, or null
     * @param extensions the extensions to offer, or null/empty for none
     * @param wsHandler the handler to receive WebSocket events
     * @return the stream ID, or -1 if sending failed, was rejected, or
     *         (the peer's support not yet being known) was deferred
     */
    public long connectWebSocket(String authority, String path, String subprotocol,
            List<WebSocketExtension> extensions, WebSocketEventHandler wsHandler) {
        if (goaway) {
            wsHandler.error(new IOException("Connection received GOAWAY"));
            return -1;
        }
        if (!initialSettingsReceived) {
            whenConnectProtocolKnown(new Runnable() {
                @Override
                public void run() {
                    connectWebSocket(authority, path, subprotocol, extensions, wsHandler);
                }
            });
            return -1;
        }
        if (!peerEnablesConnectProtocol) {
            wsHandler.error(new IOException("Server does not support Extended CONNECT "
                    + "(RFC 9220/RFC 8441): SETTINGS_ENABLE_CONNECT_PROTOCOL was not advertised"));
            return -1;
        }

        H3ClientStream clientStream = H3ClientStream.forWebSocket(this, qpackDecoder, wsHandler, extensions);
        Headers headers = new Headers();
        headers.add(new Header(":method", "CONNECT"));
        headers.add(new Header(":protocol", "websocket"));
        headers.add(new Header(":scheme", "https"));
        headers.add(new Header(":authority", authority));
        headers.add(new Header(":path", path));
        if (subprotocol != null && !subprotocol.isEmpty()) {
            headers.add(new Header("sec-websocket-protocol", subprotocol));
        }
        if (extensions != null && !extensions.isEmpty()) {
            headers.add(new Header("sec-websocket-extensions", WebSocketHandshake.formatOffers(extensions)));
        }
        clientStream.prepareRequest(headers, false);
        quicConnection.openStream(clientStream);
        return clientStream.getStreamId();
    }

    // ── H3ControlStream.Listener (peer's control stream) ──

    @Override
    public void settingsReceived(long[] settings) {
        // RFC 9204 section 3.2.1/4.3.1: our own Encoder may not use more
        // dynamic-table capacity than the peer's declared receive-side
        // ceiling permits, whichever is smaller against our own default.
        for (int i = 0; i + 1 < settings.length; i += 2) {
            if (settings[i] == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
                int capacity = (int) Math.min(DEFAULT_QPACK_TABLE_CAPACITY, settings[i + 1]);
                ByteBuffer instructions = ByteBuffer.allocate(16);
                qpackEncoder.setCapacity(instructions, capacity);
                instructions.flip();
                flushQpackEncoderInstructions(instructions);
            } else if (settings[i] == H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL) {
                peerEnablesConnectProtocol = settings[i + 1] == 1;
            } else if (settings[i] == H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE) {
                peerMaxFieldSectionSize = settings[i + 1];
            } else if (settings[i] == H3FrameHandler.SETTINGS_H3_DATAGRAM) {
                long value = settings[i + 1];
                if (value > 1) {
                    closeWithApplicationError(H3ErrorCode.H3_SETTINGS_ERROR,
                            "SETTINGS_H3_DATAGRAM must be 0 or 1");
                    return;
                }
                if (value == 1 && quicConnection.getPeerMaxDatagramFrameSize() <= 0) {
                    closeWithApplicationError(H3ErrorCode.H3_SETTINGS_ERROR,
                            "SETTINGS_H3_DATAGRAM=1 without QUIC DATAGRAM");
                    return;
                }
                peerH3Datagram = value == 1;
            }
        }
        if (!initialSettingsReceived) {
            initialSettingsReceived = true;
            List<Runnable> pending = new ArrayList<Runnable>(connectProtocolCallbacks);
            connectProtocolCallbacks.clear();
            for (Runnable task : pending) {
                task.run();
            }
        }
    }

    /**
     * Returns this endpoint's advertised {@code SETTINGS_MAX_FIELD_SECTION_SIZE}
     * (RFC 9114 section 4.2.2), enforced on inbound HEADERS.
     *
     * @return the local receive ceiling in octets
     */
    long getLocalMaxFieldSectionSize() {
        return H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE;
    }

    /**
     * Returns whether {@code fields} exceeds the peer's advertised
     * {@code SETTINGS_MAX_FIELD_SECTION_SIZE}. The RFC default when the
     * peer omitted the parameter is unlimited.
     *
     * @param fields the field section about to be sent
     * @return true if this section must not be sent
     */
    boolean exceedsPeerFieldSectionLimit(List<Header> fields) {
        return H3Writer.fieldSectionSize(fields) > peerMaxFieldSectionSize;
    }

    boolean peerH3Datagram() {
        return peerH3Datagram;
    }

    /**
     * Sends an HTTP Datagram (RFC 9297 section 2.1) associated with
     * {@code streamId}. The peer must have advertised
     * {@code SETTINGS_H3_DATAGRAM=1}.
     *
     * @param streamId the client-initiated bidirectional stream ID
     * @param payload the HTTP Datagram payload
     * @return true if queued
     */
    public boolean sendDatagram(long streamId, ByteBuffer payload) {
        if (payload == null) {
            return false;
        }
        byte[] copy = new byte[payload.remaining()];
        payload.get(copy);
        return sendHttpDatagram(streamId, copy);
    }

    boolean sendHttpDatagram(long streamId, byte[] payload) {
        if (!peerH3Datagram) {
            return false;
        }
        byte[] encoded = H3Datagram.encode(streamId, payload);
        if (encoded == null) {
            return false;
        }
        return quicConnection.sendDatagram(ByteBuffer.wrap(encoded));
    }

    private void onHttpDatagram(ByteBuffer data) {
        if (!peerH3Datagram) {
            closeWithApplicationError(H3ErrorCode.H3_DATAGRAM_ERROR,
                    "HTTP Datagram before SETTINGS_H3_DATAGRAM=1");
            return;
        }
        H3Datagram datagram = H3Datagram.decode(data);
        if (datagram == null) {
            closeWithApplicationError(H3ErrorCode.H3_DATAGRAM_ERROR,
                    "malformed HTTP Datagram");
            return;
        }
        H3ClientStream stream = streams.get(Long.valueOf(datagram.getStreamId()));
        if (stream == null) {
            return;
        }
        stream.httpDatagramReceived(ByteBuffer.wrap(datagram.getPayload()));
    }

    // RFC 9114 section 5.2: GOAWAY for graceful shutdown. The server
    // sends GOAWAY with the stream ID of the last request it will
    // process; the client should not send new requests and may retry
    // unprocessed requests on a new connection.
    @Override
    public void goawayReceived(long lastStreamId) {
        goaway = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            String formatted = MessageFormat.format(
                    L10N.getString("fine.goaway_received"), Long.valueOf(lastStreamId));
            LOGGER.fine(formatted);
        }

        // RFC 9114 section 5.2: fail all streams with IDs above
        // the server's last-stream-ID so the caller can retry them
        // on a new connection
        IOException retryable = new IOException("Server GOAWAY: stream not processed (retryable)");
        for (Iterator<Map.Entry<Long, H3ClientStream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, H3ClientStream> entry = it.next();
            if (entry.getKey().longValue() > lastStreamId) {
                entry.getValue().onGoawayFailed(retryable);
                it.remove();
            }
        }
    }

    @Override
    public void priorityUpdateReceived(long streamId, String fieldValue) {
        // Servers MUST NOT send PRIORITY_UPDATE; H3ControlStream already
        // treats that as H3_FRAME_UNEXPECTED before this is called.
    }

    private static int estimateFieldSectionCapacity(List<Header> fields) {
        int estimate = 16;
        for (Header field : fields) {
            estimate += 8 + 2 * (field.getName().length() + field.getValue().length());
        }
        return estimate;
    }

    /**
     * Closes this HTTP/3 client handler, failing any outstanding
     * requests. Actual QUIC connection teardown is the caller's
     * responsibility.
     */
    public void close() {
        IOException closed = new IOException("Connection closed");
        for (H3ClientStream stream : streams.values()) {
            stream.error(closed);
        }
        streams.clear();
    }

    /**
     * Closes the underlying QUIC connection with an HTTP/3 application
     * error (RFC 9114 section 8.1).
     *
     * @param errorCode an {@link H3ErrorCode} value
     * @param reason a human-readable reason phrase
     */
    void closeWithApplicationError(long errorCode, String reason) {
        quicConnection.closeWithApplicationError(errorCode, reason);
    }
}
