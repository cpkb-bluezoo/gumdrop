/*
 * DoHClientTransport.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.http.client.HTTPClient;
import org.bluezoo.gumdrop.http.client.HTTPClientHandler;
import org.bluezoo.gumdrop.http.client.HTTPRequest;
import org.bluezoo.gumdrop.http.client.HTTPResponse;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.PushPromise;

/**
 * DNS-over-HTTPS (DoH) transport for DNS client queries.
 * RFC 8484: DNS Queries over HTTPS (DoH).
 *
 * <p>RFC 8484 section 4.1: queries are sent as HTTP POST requests with
 * content type {@code application/dns-message}. The request body is the
 * raw DNS wire-format query. The response body is the raw DNS
 * wire-format response.
 *
 * <p>RFC 8484 section 4.1: the Accept header MUST include
 * {@code application/dns-message}.
 *
 * <p>RFC 8484 section 4.1: the URI template path defaults to
 * {@code /dns-query} but is configurable.
 *
 * <p>RFC 8484 section 5.1: the default port is 443 (standard HTTPS).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8484">RFC 8484</a>
 */
public class DoHClientTransport implements DNSClientTransport {

    // RFC 8484 section 6
    static final String DNS_MESSAGE_CONTENT_TYPE = "application/dns-message";

    // RFC 8484 section 5.1
    private static final int DEFAULT_DOH_PORT = 443;

    // RFC 8484 section 4.1: default URI template path
    private static final String DEFAULT_PATH = "/dns-query";

    private static final ScheduledExecutorService TIMER =
            createTimerExecutor();

    private HTTPClient httpClient;
    private DNSClientTransportHandler handler;
    private volatile boolean connected;

    private String path = DEFAULT_PATH;

    private static ScheduledExecutorService createTimerExecutor() {
        ScheduledThreadPoolExecutor exec =
                new ScheduledThreadPoolExecutor(1);
        exec.setKeepAliveTime(60, TimeUnit.SECONDS);
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    /**
     * Sets the URI path for DoH queries.
     * RFC 8484 section 4.1: the path component of the URI template.
     *
     * @param path the path (default {@code /dns-query})
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns the URI path used for DoH queries.
     *
     * @return the path
     */
    public String getPath() {
        return path;
    }

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        this.handler = handler;
        if (port <= 0) {
            port = DEFAULT_DOH_PORT;
        }
        httpClient = new HTTPClient(server.getHostAddress(), port);
        httpClient.setSecure(true);
        httpClient.connect(new HTTPClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                connected = true;
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                // TLS details not needed for DoH transport
            }

            @Override
            public void onError(Exception cause) {
                DoHClientTransport.this.handler.onError(cause);
            }

            @Override
            public void onDisconnected() {
                connected = false;
            }
        });
    }

    // RFC 8484 section 4.1: send DNS query as HTTP POST with
    // Content-Type: application/dns-message
    @Override
    public void send(ByteBuffer data) {
        if (!connected) {
            handler.onError(new IOException(
                    "DoH connection not yet established"));
            return;
        }
        byte[] queryBytes = new byte[data.remaining()];
        data.get(queryBytes);

        HTTPRequest request = httpClient.post(path);
        // RFC 8484 section 4.1
        request.header("Content-Type", DNS_MESSAGE_CONTENT_TYPE);
        request.header("Accept", DNS_MESSAGE_CONTENT_TYPE);

        DoHResponseHandler responseHandler =
                new DoHResponseHandler(handler);
        request.startRequestBody(responseHandler);
        request.requestBodyContent(ByteBuffer.wrap(queryBytes));
        request.endRequestBody();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        final ScheduledFuture<?> future = TIMER.schedule(
                callback, delayMs, TimeUnit.MILLISECONDS);
        return new TimerHandle() {
            @Override
            public void cancel() {
                future.cancel(false);
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }
        };
    }

    @Override
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /**
     * Accumulates the HTTP response body (the DNS wire-format response)
     * and delivers it to the transport handler on completion.
     */
    private static class DoHResponseHandler implements HTTPResponseHandler {

        private final DNSClientTransportHandler handler;
        private final ByteArrayOutputStream accumulator =
                new ByteArrayOutputStream(512);
        private boolean success;

        DoHResponseHandler(DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        // RFC 8484 section 4.2.1: a successful response uses HTTP 200
        @Override
        public void ok(HTTPResponse response) {
            success = true;
        }

        @Override
        public void error(HTTPResponse response) {
            handler.onError(new IOException(
                    "DoH server returned HTTP error: " + response));
        }

        @Override
        public void header(String name, String value) {
            // RFC 8484 section 4.2.1: could validate Content-Type here
        }

        @Override
        public void startResponseBody() {
            // Body accumulation handled in responseBodyContent
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (!success) {
                return;
            }
            byte[] buf = new byte[data.remaining()];
            data.get(buf);
            accumulator.write(buf, 0, buf.length);
        }

        @Override
        public void endResponseBody() {
            if (success && accumulator.size() > 0) {
                handler.onReceive(
                        ByteBuffer.wrap(accumulator.toByteArray()));
            }
        }

        @Override
        public void pushPromise(PushPromise promise) {
            promise.reject();
        }

        @Override
        public void close() {
            // Response complete; DNS message already delivered
        }

        @Override
        public void failed(Exception ex) {
            handler.onError(ex);
        }
    }
}
