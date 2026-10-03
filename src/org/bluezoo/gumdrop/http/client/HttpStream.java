/*
 * HttpStream.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.http.HeaderFields;
import java.util.List;
import java.util.ArrayList;
import org.bluezoo.gumdrop.http.Header;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.HttpDateFormat;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.HttpMessageRecorder;
import org.bluezoo.gumdrop.http.PriorityParams;

/**
 * Internal implementation of {@link HttpRequest} representing an HTTP stream.
 *
 * <p>In HTTP/2 (RFC 9113 section 5.1), each request/response exchange occurs
 * on a separate stream identified by a client-initiated odd stream ID.
 * In HTTP/1.1, there is logically one stream per request on the connection.
 * This class encapsulates the request state and delegates actual I/O to
 * the owning {@link HttpClientProtocolHandler}.
 *
 * <p>Instances are created internally via factory methods like
 * {@link HttpClient#get(String)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class HttpStream implements HttpRequest {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");

    private final HttpClientConnectionOps connection;
    private final String method;
    private final String path;
    private final List<Header> headers;
    
    // HTTP/2 stream ID (assigned when sent)
    int streamId;
    
    // HTTP/2 priority settings (RFC 9113 section 5.3: deprecated)
    private int priority = 16;  // Default weight
    private HttpRequest dependency;
    private boolean exclusive;
    
    // State
    private HttpResponseHandler handler;
    private boolean headersSent;
    private boolean bodySent;
    // The first piece of body, held back until it is known whether it is the
    // only one
    private ByteBuffer firstChunk;
    // Trailer fields: fields given once the body has begun
    private List<Header> trailers;
    private boolean bodyStarted;
    private boolean cancelled;

    private ContentEncoding.Encoder requestContentEncoder;

    private ContentEncoding.Decoder inboundResponseDecoder;

    // HTTP/2 authentication retry (RFC 9110 section 11.6.1): the
    // Authorization value to retry with once this stream's challenge
    // response has been fully received and discarded.
    private String pendingAuthorization;
    private boolean pendingAuthorizationProxy;
    private boolean authRetry;

    /**
     * Creates a new HTTP stream.
     *
     * @param connection the connection this stream belongs to
     * @param method the HTTP method
     * @param path the request path
     */
    HttpStream(HttpClientConnectionOps connection, String method, String path) {
        this.connection = connection;
        this.method = method;
        this.path = path;
        this.headers = new ArrayList<Header>();
    }

    HttpStream(HttpClientConnectionOps connection, String method, String path,
            HttpResponseHandler handler) {
        this(connection, method, path);
        this.handler = handler;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method
     */
    String getMethod() {
        return method;
    }

    /**
     * Returns the request path.
     *
     * @return the path
     */
    String getPath() {
        return path;
    }

    /**
     * Returns the request headers.
     *
     * @return the headers
     */
    List<Header> getHeaders() {
        return headers;
    }

    /**
     * Returns the response handler.
     *
     * @return the handler
     */
    HttpResponseHandler getHandler() {
        return handler;
    }

    /**
     * Returns the priority weight.
     *
     * @return the priority (1-256)
     */
    int getPriorityWeight() {
        return priority;
    }

    /**
     * Returns the dependency request.
     *
     * @return the dependency, or null
     */
    HttpRequest getDependency() {
        return dependency;
    }

    /**
     * Returns whether this is an exclusive dependency.
     *
     * @return true if exclusive
     */
    boolean isExclusive() {
        return exclusive;
    }

    ContentEncoding.Coding getRequestContentCoding() {
        if (!connection.isEncodeRequestBodyContentCoding()) {
            return null;
        }
        return ContentEncoding.parseContentEncoding(
                HeaderFields.getCombinedValue(headers, "Content-Encoding"));
    }

    ContentEncoding.Encoder getOrCreateRequestContentEncoder()
            throws ContentEncoding.ContentEncodingException {
        ContentEncoding.Coding coding = getRequestContentCoding();
        if (coding == null) {
            String raw = HeaderFields.getCombinedValue(headers, "Content-Encoding");
            if (connection.isEncodeRequestBodyContentCoding()
                    && raw != null && !raw.trim().isEmpty()) {
                throw new ContentEncoding.ContentEncodingException(
                        MessageFormat.format(
                                L10N.getString("err.unsupported_request_content_encoding"),
                                raw.trim()));
            }
            return null;
        }
        if (requestContentEncoder == null) {
            requestContentEncoder = ContentEncoding.createEncoder(coding);
        }
        return requestContentEncoder;
    }

    void closeRequestContentEncoder() {
        if (requestContentEncoder != null) {
            requestContentEncoder.close();
            requestContentEncoder = null;
        }
    }

    ContentEncoding.Decoder getInboundResponseDecoder() {
        return inboundResponseDecoder;
    }

    void setInboundResponseDecoder(ContentEncoding.Coding coding) {
        if (coding == null) {
            if (inboundResponseDecoder != null) {
                inboundResponseDecoder.close();
                inboundResponseDecoder = null;
            }
            return;
        }
        if (inboundResponseDecoder != null) {
            inboundResponseDecoder.close();
        }
        inboundResponseDecoder = ContentEncoding.createDecoder(coding,
                ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
    }

    void closeInboundResponseDecoder() {
        if (inboundResponseDecoder != null) {
            inboundResponseDecoder.close();
            inboundResponseDecoder = null;
        }
    }

    // The events of the response field section being decoded, held until the
    // client has decided what to do with the response (see the HTTP/2 path in
    // HttpClientProtocolHandler), and the state that goes with them.
    private final HttpMessageRecorder responseEvents = new HttpMessageRecorder();
    private boolean responseHeadersReceived;
    private boolean messageEvents;

    HttpMessageRecorder responseEvents() {
        return responseEvents;
    }

    /** Whether the final response's header section has been seen (later sections are trailers). */
    boolean isResponseHeadersReceived() {
        return responseHeadersReceived;
    }

    void setResponseHeadersReceived() {
        responseHeadersReceived = true;
    }

    /** Whether the handler has been given the start of the response as events. */
    boolean isMessageEvents() {
        return messageEvents;
    }

    void setMessageEvents() {
        messageEvents = true;
    }

    /** The response has failed: nothing more is given to the handler as message events. */
    void clearMessageEvents() {
        messageEvents = false;
    }

    void drainInboundResponseDecoded(HttpResponseHandler responseHandler)
            throws ContentEncoding.ContentEncodingException {
        if (inboundResponseDecoder == null || responseHandler == null) {
            return;
        }
        ByteBuffer decoded;
        while ((decoded = inboundResponseDecoder.readDecoded()) != null) {
            if (messageEvents) {
                responseHandler.bodyContent(decoded.asReadOnlyBuffer());
            }
        }
    }

    void finishInboundResponseDecoded(HttpResponseHandler responseHandler)
            throws ContentEncoding.ContentEncodingException {
        if (inboundResponseDecoder == null) {
            return;
        }
        inboundResponseDecoder.write(ByteBuffer.allocate(0), true);
        drainInboundResponseDecoded(responseHandler);
        closeInboundResponseDecoder();
    }

    @Override
    public void header(String name, ByteBuffer value) {
        byte[] octets = new byte[value.remaining()];
        value.duplicate().get(octets);
        addField(name, new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private void addField(String name, String value) {
        if (bodySent) {
            throw new IllegalStateException(L10N.getString("err.headers_already_sent"));
        }
        if (headersSent || bodyStarted) {
            // the header section is sent, or the body has begun, so this is
            // a trailer field (RFC 9110 section 6.5)
            addTrailer(name, value);
            return;
        }
        HeaderFields.add(headers, name, value);
    }

    private void addTrailer(String name, String value) {
        String lower = name.toLowerCase();
        if (lower.startsWith(":") || lower.equals("content-length")
                || lower.equals("transfer-encoding") || lower.equals("host")
                || lower.equals("trailer")) {
            throw new IllegalArgumentException(MessageFormat.format(
                    L10N.getString("err.trailer_not_allowed"), name));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException(L10N.getString("err.invalid_trailer_value"));
            }
        }
        if (HeaderFields.containsName(headers, "Content-Length") && !HeaderFields.containsName(headers, "Transfer-Encoding")) {
            // a body with a declared length has no room for trailers on
            // HTTP/1.x, and the framing was already chosen
            throw new IllegalStateException(L10N.getString("err.trailers_need_chunked"));
        }
        if (trailers == null) {
            trailers = new ArrayList<Header>();
        }
        HeaderFields.add(trailers, name, value);
    }

    @Override
    public void longHeader(String name, long value) {
        addField(name, Long.toString(value));
    }

    @Override
    public void dateHeader(String name, Instant value) {
        addField(name, new HttpDateFormat().format(value.toEpochMilli()));
    }

    @Override
    public void contentType(ContentType contentType) {
        addField("Content-Type", contentType.toHeaderValue());
    }

    @Override
    public void contentDisposition(ContentDisposition contentDisposition) {
        addField("Content-Disposition", contentDisposition.toHeaderValue());
    }

    @Override
    public void priority(int weight) {
        this.priority = weight;
        int urgency = PriorityParams.urgencyFromWeight(weight);
        HeaderFields.add(headers, PriorityParams.PRIORITY_HEADER, "u=" + urgency);
    }

    @Override
    public void dependency(HttpRequest parent) {
        this.dependency = parent;
    }

    @Override
    public void exclusive(boolean exclusive) {
        this.exclusive = exclusive;
    }

    /**
     * Records that this stream's response was a 401/407 challenge to be
     * answered by retrying with the given credentials value.
     */
    void setPendingAuthorization(String authorization, boolean proxy) {
        this.pendingAuthorization = authorization;
        this.pendingAuthorizationProxy = proxy;
    }

    String getPendingAuthorization() {
        return pendingAuthorization;
    }

    boolean isPendingAuthorizationProxy() {
        return pendingAuthorizationProxy;
    }

    /** Returns whether this stream is itself a credentials retry. */
    boolean isAuthRetry() {
        return authRetry;
    }

    void markAuthRetry() {
        this.authRetry = true;
    }

    /**
     * Binds the response handler of a server-pushed stream. The request
     * was made by the server, so nothing is sent for it.
     */
    void attachPushedResponseHandler(HttpResponseHandler responseHandler) {
        this.handler = responseHandler;
        this.headersSent = true;
        this.bodySent = true;
    }

    /**
     * Sends a request that has no body: used for retries, which are built
     * from a request that was already sent.
     */
    void sendWithoutBody() {
        headersSent = true;
        bodySent = true;
        connection.sendRequest(this, false);
    }

    @Override
    public void endHeaders() {
        if (headersSent) {
            throw new IllegalStateException(L10N.getString("err.headers_already_sent"));
        }
        if (cancelled) {
            return;
        }
        headersSent = true;
        connection.sendRequest(this, true);
        if (firstChunk != null) {
            ByteBuffer held = firstChunk;
            firstChunk = null;
            sendBody(held);
        }
    }

    @Override
    public int bodyContent(ByteBuffer data) {
        if (data == null) {
            return 0;
        }
        if (bodySent) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        if (cancelled) {
            return 0;
        }
        int length = data.remaining();
        if (length == 0) {
            return 0;
        }
        bodyStarted = true;
        if (!headersSent && firstChunk == null) {
            // Held back until it is known whether it is also the last piece
            // (see the class description of HttpRequest).
            ByteBuffer copy = ByteBuffer.allocate(length);
            copy.put(data);
            copy.flip();
            firstChunk = copy;
            return length;
        }
        if (!headersSent) {
            headersSent = true;
            connection.sendRequest(this, true);
        }
        if (firstChunk != null) {
            ByteBuffer held = firstChunk;
            firstChunk = null;
            sendBody(held);
        }
        return sendBody(data);
    }

    private int sendBody(ByteBuffer data) {
        if (getRequestContentCoding() != null) {
            return connection.sendRequestBodyEncoded(this, data, false);
        }
        return connection.sendRequestBody(this, data);
    }

    @Override
    public void endMessage() {
        if (bodySent) {
            throw new IllegalStateException(L10N.getString("err.body_already_complete"));
        }
        bodySent = true;
        if (cancelled) {
            return;
        }
        if (headersSent) {
            if (trailers != null) {
                connection.endRequestWithTrailers(this, trailers);
            } else {
                connection.endRequestBody(this);
            }
            return;
        }
        headersSent = true;
        if (firstChunk == null) {
            connection.sendRequest(this, false);
            return;
        }
        ByteBuffer only = firstChunk;
        firstChunk = null;
        if (trailers != null) {
            // trailers need a chunked body: the length is not declared
            connection.sendRequest(this, true);
            sendBody(only);
            connection.endRequestWithTrailers(this, trailers);
            return;
        }
        if (getRequestContentCoding() == null
                && !HeaderFields.containsName(headers, "Content-Length")
                && !HeaderFields.containsName(headers, "Transfer-Encoding")) {
            // The one piece of body is the whole body: say how long it is
            // rather than chunking it.
            HeaderFields.add(headers, "Content-Length", Integer.toString(only.remaining()));
        }
        connection.sendRequest(this, true);
        connection.sendLastRequestBody(this, only);
    }

    @Override
    public void cancel() {
        if (!cancelled) {
            cancelled = true;
            connection.cancelRequest(this);
        }
    }

    @Override
    public String toString() {
        return method + " " + path;
    }
}
