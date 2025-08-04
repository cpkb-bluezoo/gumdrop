/*
 * AbstractHTTPConnection.java
 * Copyright (C) 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.Connection;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.mail.internet.MimeUtility;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.bluezoo.gumdrop.http.hpack.HPACKHeaders;
import org.bluezoo.gumdrop.util.LineInput;

import gnu.inet.http.HTTPDateFormat;

/**
 * Abstract connection handler for the HTTP protocol.
 * This manages potentially multiple requests within a single TCP
 * connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7230
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public abstract class AbstractHTTPConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(AbstractHTTPConnection.class.getName());

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();
    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    static final CharsetDecoder ISO_8859_1_DECODER = ISO_8859_1.newDecoder();

    private static final Pattern METHOD_PATTERN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9a-zA-Z]+$"); // token
    private static final Pattern REQUEST_TARGET_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-._~!$&'()*+,;=:@/?#\\[\\]%]+$");

    /**
     * HTTP version being used by the connection.
     */
    protected HTTPVersion version = HTTPVersion.HTTP_1_0;

    private static final int STATE_REQUEST_LINE = 0;
    private static final int STATE_HEADER = 1;
    private static final int STATE_BODY = 2;
    private static final int STATE_PRI = 4;
    private static final int STATE_HTTP2 = 5;
    private static final int STATE_HTTP2_CONTINUATION = 6;

    private int state = STATE_REQUEST_LINE;
    private ByteBuffer in; // input buffer
    private LineReader lineReader;

    class LineReader implements LineInput {

        private CharBuffer sink; // character buffer to receive decoded characters

        @Override public ByteBuffer getLineInputBuffer() {
            return in;
        }

        @Override public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (sink == null || sink.capacity() < capacity) {
                sink = CharBuffer.allocate(capacity);
            }
            return sink;
        }

    }

    // HTTP/1
    private String headerName;
    private CharBuffer headerValue;
    private ByteBuffer response;

    // HTTP/2 settings
    int headerTableSize = 4096;
    boolean enablePush = true;
    int maxConcurrentStreams = Integer.MAX_VALUE;
    int initialWindowSize = 65535;
    int maxFrameSize = 16384;
    int maxHeaderListSize = Integer.MAX_VALUE;

    private int clientStreamId; // synthesized stream ID for HTTP/1
    private Map<Integer,Stream> streams;
    private int continuationStream;
    private boolean continuationEndStream; // if the continuation should end stream after end of headers
    private Set<Integer> activeStreams = new TreeSet<>();
    // TODO stream priority

    protected AbstractHTTPConnection(SocketChannel channel, SSLEngine engine, boolean secure) {
        super(engine, secure);
        in = ByteBuffer.allocate(4096);
        /*if (engine != null) {
            // Configure this engine with ALPN
            SSLParameters sp = engine.getSSLParameters();
            sp.setApplicationProtocols(new String[] { "h2", "http/1.1"}); // h2 and http/1.1
            engine.setSSLParameters(sp);
        }*/
        streams = new TreeMap<>();
        clientStreamId = 1;
        lineReader = this.new LineReader();
    }

    protected void handshakeComplete(String protocol) {
        // Whether we negotiated h2 or not, the client will still send a PRI
        // so we will change state to HTTP2 based on that, not here
    }

    /**
     * Returns the URI scheme used by this connection.
     */
    public String getScheme() {
        return secure ? "https" : "http";
    }

    /**
     * Returns the version of the HTTP protocol this connection is currently
     * using.
     */
    public HTTPVersion getVersion() {
        return version;
    }

    /**
     * Read a frame (HTTP/2).
     * Returns null if there was not enough input data for a frame,
     * and the input buffer position will be unchanged.
     */
    private Frame readFrame() {
        // Must have at least 9-byte header
        if (in.remaining() < 9) {
            return null;
        }
        int pos = in.position();
        byte[] header = new byte[9];
        in.get(header);
        int length = ((int) header[0] & 0xff) << 16
            | ((int) header[1] & 0xff) << 8
            | ((int) header[2] & 0xff);
        int type = ((int) header[3] & 0xff);
        int flags = ((int) header[4] & 0xff);
        int stream = ((int) header[5] & 0x7f) << 24 // NB mask out reserved bit
            | ((int) header[6] & 0xff) << 16
            | ((int) header[7] & 0xff) << 8
            | ((int) header[8] & 0xff);
        if (in.remaining() < length) { // not enough data yet for frame
            in.position(pos);
            return null;
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            in.get(payload);
        }
        try {
            switch (type) {
                case Frame.TYPE_DATA:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    // TODO STREAM_CLOSED
                    in.compact();
                    return new DataFrame(flags, stream, payload);
                case Frame.TYPE_HEADERS:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new HeadersFrame(flags, stream, payload);
                case Frame.TYPE_PRIORITY:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 5) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new PriorityFrame(stream, payload);
                case Frame.TYPE_RST_STREAM:
                    if (stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 4) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    // TODO stream in idle state
                    in.compact();
                    return new RstStreamFrame(stream, payload);
                case Frame.TYPE_SETTINGS:
                    if (stream != 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length % 6 != 0) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new SettingsFrame(flags, payload);
                case Frame.TYPE_PUSH_PROMISE:
                    if (!enablePush || stream == 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    // TODO STREAM_CLOSED
                    in.compact();
                    return new PushPromiseFrame(flags, stream, payload);
                case Frame.TYPE_PING:
                    if (stream != 0) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    if (length != 0) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new PingFrame(flags);
                case Frame.TYPE_GOAWAY:
                    if (stream != 0 || length < 8) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new GoawayFrame(payload);
                case Frame.TYPE_WINDOW_UPDATE:
                    if (length != 4) {
                        sendErrorFrame(Frame.ERROR_FRAME_SIZE_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new WindowUpdateFrame(stream, payload);
                case Frame.TYPE_CONTINUATION:
                    if (stream != continuationStream) {
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
                        return null;
                    }
                    in.compact();
                    return new ContinuationFrame(flags, stream, payload);
                default:
                    sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
            }
        } catch (ProtocolException e) {
            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, stream);
        }
        close();
        return null;
    }

    /**
     * Received data from the client.
     * This method is called from a thread in the connector's thread pool.
     * @param buf the receive buffer
     */
    protected synchronized void received(ByteBuffer buf) {
        // in is ready for put
        System.err.println("AbstractHTTPConnection.received buf="+toString(buf));
        System.err.println("\tin="+toString(in));
        int len = buf.remaining();
        if (in.remaining() < len) {
            // need to reallocate in
            ByteBuffer tmp = ByteBuffer.allocate(in.capacity() + Math.max(4096, len));
            in.flip();
            tmp.put(in);
            in = tmp;
        }
        in.put(buf);
        in.flip();
        // in is ready for get

        Stream stream;
        while (in.hasRemaining()) {
            System.err.println("AbstractHTTPConnection.received loop: state="+state+" in="+toASCIIString(in));
            String line;
            Frame frame;
            switch (state) {
                case STATE_REQUEST_LINE:
                    stream = getStream(clientStreamId);
                    try {
                        line = lineReader.readLine(US_ASCII_DECODER);
                        System.err.println("STATE_REQUEST_LINE: line="+line);
                    } catch (IOException e) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    if (line == null) {
                        in.compact();
                        return; // not enough data for request-line
                    }
                    int mi = line.indexOf(' ', 1);
                    int ui = line.indexOf(' ', mi + 2);
                    if (mi == -1 || ui == -1) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    String method = line.substring(0, mi);
                    String requestTarget = line.substring(mi + 1, ui);
                    String version = line.substring(ui + 1);
                    this.version = HTTPVersion.fromString(version);
                    // Validate input
                    if (!METHOD_PATTERN.matcher(method).matches()) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    if (!REQUEST_TARGET_PATTERN.matcher(requestTarget).matches()) {
                        // does not match characters in absolute-URI / authority / origin-form / asterisk-form
                        // we will not do full parsing of the request-target structure here
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    switch (this.version) {
                        case HTTP_1_0:
                            stream.closeConnection = true;
                            break;
                        case UNKNOWN:
                            sendStreamError(stream, 505); // HTTP Version Not Supported
                            in.compact();
                            return;
                    }
                    stream.addHeader(new Header(":method", method));
                    stream.addHeader(new Header(":path", requestTarget));
                    stream.addHeader(new Header(":scheme", secure ? "https" : "http"));
                    headerName = null;
                    headerValue = CharBuffer.allocate(4096);
                    state = STATE_HEADER;
                    System.err.println("end of STATE_REQUEST_LINE: remaining="+in.remaining());
                    break;
                case STATE_HEADER:
                    stream = getStream(clientStreamId);
                    try {
                        // Old clients may still sometimes send iso-latin-1
                        // data in headers. We have to accept this even
                        // though we won't issue any non-ASCII header data
                        // outrselves.
                        line = lineReader.readLine(ISO_8859_1_DECODER);
                        System.err.println("STATE_HEADER: line="+line);
                    } catch (IOException e) {
                        sendStreamError(stream, 400); // Bad Request
                        in.compact();
                        return;
                    }
                    if (line == null) {
                        in.compact();
                        return; // not enough data for header-line
                    }
                    if (line.length() == 0) { // end of headers
                        if (headerName != null) { // add last header
                            headerValue.flip();
                            String v = headerValue.toString();
                            stream.addHeader(new Header(headerName, v));
                            headerName = null;
                            headerValue = null;
                        }
                        try {
                            stream.streamEndHeaders(0); // 0 signals HTTP/1
                        } catch (IOException e) {
                            // This should only happen with malformed HPACK headers
                            LOGGER.log(Level.SEVERE, e.getMessage(), e);
                        }
                        if (stream.upgrade != null && stream.upgrade.contains("h2c") && stream.settingsFrame != null) {
                            System.err.println("Upgrading to HTTP/2");
                            // 3.2 Starting HTTP/2
                            Collection<Header> responseHeaders = new ArrayList<>();
                            responseHeaders.add(new Header("Connection", "Upgrade"));
                            responseHeaders.add(new Header("Upgrade", "h2c"));
                            sendResponseHeaders(clientStreamId, 101, responseHeaders, true); // Switching Protocols
                            // We now start the server side of the
                            // connection preface
                            SettingsFrame settingsFrame = new SettingsFrame(false,
                                    headerTableSize,
                                    enablePush,
                                    maxConcurrentStreams,
                                    initialWindowSize,
                                    maxFrameSize,
                                    maxHeaderListSize);
                            sendFrame(settingsFrame);
                            // We now await the client connection preface
                            // (PRI * HTTP/2.0 request line)
                            state = STATE_REQUEST_LINE;
                            // client stream ID is not updated
                        } else if ("PRI".equals(stream.method) && "*".equals(stream.requestTarget) && this.version == HTTPVersion.HTTP_2_0) {
                            state = STATE_PRI;
                        } else {
                            state = STATE_BODY;
                            if (stream.getContentLength() == 0L) { // end of request
                                stream.streamEndRequest();
                                if (stream.isCloseConnection()) {
                                    stream.streamClose();
                                    close();
                                    in.compact();
                                    return;
                                }
                                state = STATE_REQUEST_LINE;
                                clientStreamId += 2;
                            }
                        }
                    } else { // header line
                        char c0 = line.charAt(0);
                        if (headerName != null && (c0 == ' ' || c0 == '\t')) { // folded continuation
                            if (c0 == '\t') { // RFC 7230: convert HT to SP
                                line = new StringBuilder().append(' ').append(line.substring(1)).toString();
                            }
                            appendHeaderValue(line);
                        } else { // new header
                            int ci = line.indexOf(':');
                            if (ci < 1) {
                                sendStreamError(stream, 400);
                                in.compact();
                                return;
                            }
                            String h = line.substring(0, ci);
                            if (headerName != null) {
                                headerValue.flip();
                                String v = headerValue.toString();
                                try {
                                    stream.addHeader(new Header(headerName, v));
                                } catch (IllegalArgumentException e) {
                                    sendStreamError(stream, 400);
                                    in.compact();
                                    return;
                                }
                            }
                            headerName = h;
                            headerValue.clear();
                            appendHeaderValue(line.substring(ci + 1));
                        }
                    }
                    System.err.println("end of STATE_HEADER: remaining="+in.remaining());
                    break;
                case STATE_BODY:
                    stream = getStream(clientStreamId);
                    long contentLength = stream.getContentLength();
                    if (contentLength == -1L) {
                        sendStreamError(stream, 411); // Length Required
                        in.compact();
                        return;
                    }
                    int available = in.remaining();
                    if (available < 1) {
                        in.compact();
                        return; // underflow
                    }
                    long max = stream.getRequestBodyBytesNeeded();
                    if ((long) available > max) {
                        available = (int) max;
                    }
                    int ip = in.position();
                    int il = in.limit();
                    in.limit(ip + available);
                    stream.appendRequestBody(in);
                    in.limit(il);
                    in.compact();
                    if (stream.getRequestBodyBytesNeeded() == 0L) {
                        // body is complete
                        stream.streamEndRequest();
                        if (stream.closeConnection) {
                            stream.streamClose();
                            close();
                            return;
                        }
                        state = STATE_REQUEST_LINE;
                        clientStreamId += 2;
                    }
                    break;
                case STATE_PRI:
                    stream = getStream(clientStreamId);
                    // SM\r\n\r\n + SETTINGS frame
                    if (in.remaining() < 16) {
                        System.err.println("*** Not enough data for client preface");
                        in.compact();
                        return; // underflow
                    }
                    System.err.println("*** Expecting client preface, in is: "+toASCIIString(in));
                    byte[] smt = new byte[6];
                    in.get(smt);
                    if (smt[0] != 'S' || smt[1] != 'M' || smt[2] != '\r' || smt[3] != '\n' || smt[4] != '\r' || smt[5] != '\n') {
                        sendStreamError(stream, 400);
                        in.compact();
                        return;
                    }
                    frame = readFrame();
                    System.err.println("*** Frame is: "+frame.toString());
                    if (frame == null) {
                        return; // not enough data for settings frame
                    }
                    in.compact(); // remove frame from in
                    /*if (frame.getType() != Frame.TYPE_SETTINGS) {
                        sendStreamError(stream, 400);
                        return;
                    }*/
                    state = STATE_HTTP2;
                    try {
                        receiveFrame(frame); // handle the frame
                    } catch (IOException e) { // HPACK headers malformed
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
                    }
                    break;
                case STATE_HTTP2:
                case STATE_HTTP2_CONTINUATION:
                    frame = readFrame();
                    if (frame == null) {
                        in.compact();
                        return; // underflow
                    }
                    in.compact();
                    int streamId = frame.getStream();
                    if (streamId != 0 && !streams.containsKey(streamId)) {
                        // Check max concurrent streams (5.1.2)
                        int numConcurrentStreams = activeStreams.size();
                        if (numConcurrentStreams >= maxConcurrentStreams) {
                            sendErrorFrame(Frame.ERROR_REFUSED_STREAM, streamId);
                            in.compact();
                            continue;
                        }
                    }
                    // If we are in a continuation then we can only get
                    // continuation frames until an endHeaders
                    if (state == STATE_HTTP2_CONTINUATION) {
                        int type = frame.getType();
                        if (type != Frame.TYPE_CONTINUATION || streamId != continuationStream) {
                            sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, streamId);
                            in.compact();
                            continue;
                        }
                    }
                    try {
                        receiveFrame(frame); // handle the frame
                    } catch (IOException e) { // HPACK headers malformed
                        sendErrorFrame(Frame.ERROR_PROTOCOL_ERROR, clientStreamId);
                        in.compact();
                    }
                    break;
            }
        }
        in.compact();
    }

    /**
     * Return the stream for the given stream ID.
     * If the stream does not exist yet it will be created.
     * Stream ID 0 always results in a null stream.
     */
    Stream getStream(int streamId) {
        Stream stream = (streamId == 0) ? null : streams.get(streamId);
        if (streamId != 0 && stream == null) {
            stream = newStream(this, streamId);
            streams.put(streamId, stream);
        }
        return stream;
    }

    void sendStreamError(Stream stream, int statusCode) {
        try {
            stream.sendError(statusCode);
        } catch (ProtocolException e) {
            String message = AbstractHTTPConnector.L10N.getString("err.send_headers");
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    /**
     * Returns a new stream implementation for this connection.
     * The stream will be notified of events relating to request reception.
     * @param connection the connection the stream is associated with
     * @param streamId the connection-unique stream identifier
     */
    protected abstract Stream newStream(AbstractHTTPConnection connection, int streamId);

    /**
     * Received a frame from the client.
     */
    void receiveFrame(Frame frame) throws IOException {
        int streamId = frame.getStream();
        Stream stream = getStream(streamId);
        switch (frame.getType()) {
            case Frame.TYPE_DATA:
                DataFrame df = (DataFrame) frame;
                stream.appendRequestBody(df.data);
                if (df.endStream) {
                    stream.streamEndRequest();
                    if (stream.isActive()) {
                        activeStreams.add(streamId);
                    }
                }
                break;
            case Frame.TYPE_HEADERS:
                HeadersFrame hf = (HeadersFrame) frame;
                stream.appendHeaderBlockFragment(hf.headerBlockFragment);
                if (hf.endHeaders) {
                    stream.streamEndHeaders(headerTableSize);
                    if (hf.endStream) {
                        stream.streamEndRequest();
                    }
                    if (stream.isActive()) {
                        activeStreams.add(streamId);
                    }
                } else {
                    state = STATE_HTTP2_CONTINUATION;
                    continuationStream = streamId;
                    continuationEndStream = hf.endStream;
                }
                break;
            case Frame.TYPE_PUSH_PROMISE:
                PushPromiseFrame ppf = (PushPromiseFrame) frame;
                stream.setPushPromise();
                stream.appendHeaderBlockFragment(ppf.headerBlockFragment);
                if (ppf.endHeaders) {
                    stream.streamEndHeaders(headerTableSize);
                    stream.streamEndRequest();
                } else {
                    state = STATE_HTTP2_CONTINUATION;
                    continuationStream = streamId;
                    continuationEndStream = true;
                }
                break;
            case Frame.TYPE_CONTINUATION:
                ContinuationFrame cf = (ContinuationFrame) frame;
                stream.appendHeaderBlockFragment(cf.headerBlockFragment);
                if (cf.endHeaders) {
                    stream.streamEndHeaders(headerTableSize);
                    state = STATE_HTTP2;
                    continuationStream = 0;
                    if (continuationEndStream) {
                        stream.streamEndRequest();
                    }
                    if (stream.isActive()) {
                        activeStreams.add(streamId);
                    }
                }
                break;
            case Frame.TYPE_RST_STREAM:
                stream.streamClose();
                activeStreams.remove(streamId);
                // TODO evict the stream from streams after some reasonable delay
                break;
            case Frame.TYPE_GOAWAY:
                close();
                break;
            case Frame.TYPE_SETTINGS:
                ((SettingsFrame) frame).apply(this);
                SettingsFrame result = new SettingsFrame(true,
                        headerTableSize,
                        enablePush,
                        maxConcurrentStreams,
                        initialWindowSize,
                        maxFrameSize,
                        maxHeaderListSize);
                sendFrame(result);
                break;
        }
    }

    void sendErrorFrame(int errorType, int stream) {
        sendFrame(new RstStreamFrame(errorType, stream));
    }

    void sendFrame(Frame frame) {
        ByteBuffer buf = ByteBuffer.allocate(9 + frame.getLength());
        frame.write(buf);
        send(buf);
    }

    /**
     * Append the specified header line to the current header value.
     * This will decode any quoted-strings and RFC 2047 encoded-words in the
     * header. All linear whitespace will be replaced by a single space.
     * It will not decode comments.
     */
    private void appendHeaderValue(String l) {
        int len = l.length();
        StringBuilder quoteBuf = null;
        boolean escaped = false, text = false;
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = l.charAt(i);
            if (quoteBuf != null) {
                if (escaped) {
                    quoteBuf.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    String word = quoteBuf.toString();
                    appendHeaderWord(word, 0, word.length());
                    quoteBuf = null;
                } else {
                    quoteBuf.append(c);
                }
            } else if (c == ' ' || c == '\t') {
                if (text) {
                    appendHeaderWord(l, start, i);
                    text = false;
                }
                start = i + 1;
            } else if (c == '"') {
                quoteBuf = new StringBuilder();
            } else {
                text = true;
            }
        }
        if (text) {
            appendHeaderWord(l, start, len);
        }
    }

    private void appendHeaderWord(String l, int start, int end) {
        if (end - start > 6 && l.charAt(start) == '=' && l.charAt(start + 1) == '?') {
            // RFC 2047 encoded-words
            String text = l.substring(start, end);
            try {
                text = MimeUtility.decodeWord(text);
                l = text;
                start = 0;
                end = text.length();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, text, e);
            }
        }
        int required = (end - start) + 1;
        if (headerValue.remaining() < required) {
            CharBuffer tmp = CharBuffer.allocate(headerValue.remaining() + Math.max(4096, required));
            headerValue.flip();
            tmp.put(headerValue);
            headerValue = tmp;
        }
        if (headerValue.position() > 0) {
            headerValue.append(' ');
        }
        headerValue.append(l, start, end);
    }

    protected void disconnected() throws IOException {
        // System.err.println("Peer closed connection");
    }

    /**
     * Write a status response: status code and header fields.
     * @param streamId the stream identifier
     * @param statusCode the status code of the response
     * @param headers the headers to be sent to the client
     * @param endStream if no response body data will be sent
     */
    void sendResponseHeaders(int streamId, int statusCode, Collection<Header> headers, boolean endStream) {
        System.err.println("sendResponseHeaders headers="+headers+" endStream="+endStream);
        ByteBuffer buf;
        boolean success = false;
        switch (state) {
            case STATE_HTTP2:
                // send headers frame(s)
                headers.add(new Header(":status", Integer.toString(statusCode)));
                int streamDependency = 0; // TODO
                boolean streamDependencyExclusive = false; // TODO
                int weight = 0; // TODO
                int padLength = 0; // TODO
                // determine headers payload
                HPACKHeaders hheaders = new HPACKHeaders(headers);
                headers = hheaders;
                buf = ByteBuffer.allocate(headerTableSize);
                while (!success) {
                    try {
                        hheaders.write(buf, headerTableSize); 
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    }
                }
                buf.flip();
                // do we need to split into multiple frames
                int length = buf.remaining();
                if (length <= headerTableSize) {
                    // single HEADERS frame
                    byte[] headerBlockFragment = new byte[length];
                    buf.get(headerBlockFragment);
                    sendFrame(new HeadersFrame(streamId, padLength != 0, endStream, true, streamDependency != 0, padLength, streamDependency, streamDependencyExclusive, weight, headerBlockFragment));
                } else {
                    // sequence of HEADERS and CONTINUATION+
                    byte[] headerBlockFragment = new byte[headerTableSize];
                    buf.get(headerBlockFragment);
                    sendFrame(new HeadersFrame(streamId, padLength != 0, endStream, false, streamDependency != 0, padLength, streamDependency, streamDependencyExclusive, weight, headerBlockFragment));
                    length -= headerTableSize;
                    while (length > headerTableSize) {
                        buf.get(headerBlockFragment);
                        sendFrame(new ContinuationFrame(streamId, false, headerBlockFragment));
                        length -= headerTableSize;
                    }
                    headerBlockFragment = new byte[length];
                    buf.get(headerBlockFragment);
                    sendFrame(new ContinuationFrame(streamId, true, headerBlockFragment));
                }
                break;
            default:
                // HTTP/1
                buf = ByteBuffer.allocate(headerTableSize); // sensible default
                while (!success) {
                    try {
                        writeStatusLineAndHeaders(buf, statusCode, headers);
                        success = true;
                    } catch (BufferOverflowException e) {
                        buf = ByteBuffer.allocate(buf.capacity() + headerTableSize);
                    }
                }
                buf.flip();
                System.err.println("sendStatusLineAndHeaders: buf="+toASCIIString(buf));
                send(buf);
        }
    }

    private void writeStatusLineAndHeaders(ByteBuffer buf, int statusCode, Collection<Header> headers) {
        try {
            String statusLine = String.format("%s %03d %s\r\n", version.toString(), statusCode, HTTPConstants.getMessage(statusCode));
            buf.put(statusLine.getBytes(US_ASCII));
            for (Header header : headers) {
                String name = header.getName();
                if (name.charAt(0) == ':') {
                    continue;
                }
                String value = header.getValue();
                int cflags = getCharsetFlags(value);
                if ((cflags & CHARSET_UNICODE) != 0) {
                    String enc = ((cflags & CHARSET_Q_ENCODING) != 0) ? "Q" : "B";
                    value = MimeUtility.encodeText(value, "UTF-8", enc);
                }
                String headerLine = String.format("%s: %s\r\n", name, value);
                buf.put(headerLine.getBytes(US_ASCII));
            }
            buf.put(new byte[] { (byte) 0x0d, (byte) 0x0a }); // CRLF empty line for end headers
        } catch (IOException e) {
            // non-ASCII characters. This shouldn't happen
            LOGGER.log(Level.SEVERE, "Non-ASCII characters in headers", e);
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    private static final int CHARSET_UNICODE = 1;
    private static final int CHARSET_Q_ENCODING = 2;

    /*
     * This will tell us 2 things:
     * - whether we can use ASCII or need UTF-8
     * - whether to use B or Q encoding
     */
    private static int getCharsetFlags(String text) {
        int asciiCount = 0, nonAsciiCount = 0, len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 32 && c < 127) || c == '\n' || c == '\r' || c == '\t') {
                asciiCount++;
            } else {
                nonAsciiCount++;
            }
        }
        int ret = (nonAsciiCount == 0) ? 0 : CHARSET_UNICODE;
        if (nonAsciiCount > asciiCount) {
            ret |= CHARSET_Q_ENCODING;
        }
        return ret;
    }

    /**
     * Write response body data.
     * @param streamId the stream identifier
     * @param data the response data
     * @param endStream if this is the last response data that will be sent
     */
    void sendResponseBody(int streamId, byte[] data, boolean endStream) {
        switch (state) {
            case STATE_HTTP2:
                // Use DATA frame
                int padLength = 0; // TODO
                sendFrame(new DataFrame(streamId, padLength > 0, endStream, padLength, data));
                break;
            default:
                // send directly
                ByteBuffer buf = ByteBuffer.wrap(data);
                send(buf);
        }
    }

    /**
     * Write response body data.
     * @param streamId the stream identifier
     * @param buf the response data buffer
     * @param endStream if this is the last response data that will be sent
     */
    void sendResponseBody(int streamId, ByteBuffer buf, boolean endStream) {
        switch (state) {
            case STATE_HTTP2:
                // Use DATA frame
                int padLength = 0; // TODO
                int length = buf.remaining();
                byte[] data = new byte[length];
                buf.get(data);
                sendFrame(new DataFrame(streamId, padLength > 0, endStream, padLength, data));
                break;
            default:
                // send directly
                send(buf);
        }
    }

}
