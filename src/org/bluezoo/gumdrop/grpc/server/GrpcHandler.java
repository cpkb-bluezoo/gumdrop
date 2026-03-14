/*
 * GrpcHandler.java
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

package org.bluezoo.gumdrop.grpc.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.http.DefaultHTTPRequestHandler;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPStatus;

/**
 * HTTPRequestHandler that processes gRPC requests.
 *
 * <p>Accumulates the request body, parses gRPC framing, dispatches to the
 * GrpcService, and sends the framed response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcHandler extends DefaultHTTPRequestHandler {

    private static final Logger LOGGER = Logger.getLogger(GrpcHandler.class.getName());
    private static final String CONTENT_TYPE_GRPC = "application/grpc";

    private final ProtoFile protoFile;
    private final GrpcService service;
    private final String path;
    private HTTPResponseState state;
    private final List<ByteBuffer> bodyChunks = new ArrayList<>();
    private boolean bodyStarted;

    GrpcHandler(ProtoFile protoFile, GrpcService service, String path) {
        this.protoFile = protoFile;
        this.service = service;
        this.path = path;
    }

    @Override
    public void headers(HTTPResponseState state, Headers headers) {
        this.state = state;
    }

    @Override
    public void startRequestBody(HTTPResponseState state) {
        bodyStarted = true;
    }

    @Override
    public void requestBodyContent(HTTPResponseState state, ByteBuffer data) {
        if (data != null && data.hasRemaining()) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            bodyChunks.add(copy);
        }
    }

    @Override
    public void endRequestBody(HTTPResponseState state) {
        if (!bodyStarted) {
            sendError(state, HTTPStatus.BAD_REQUEST, "Missing request body");
            return;
        }

        int total = 0;
        for (ByteBuffer b : bodyChunks) {
            total += b.remaining();
        }
        ByteBuffer combined = ByteBuffer.allocate(total);
        for (ByteBuffer b : bodyChunks) {
            combined.put(b);
        }
        combined.flip();

        ByteBuffer requestMessage;
        try {
            int length = GrpcFraming.readHeader(combined);
            if (length < 0 || combined.remaining() < length) {
                sendError(state, HTTPStatus.BAD_REQUEST, "Invalid gRPC frame");
                return;
            }
            requestMessage = combined.slice();
            requestMessage.limit(length);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse gRPC frame", e);
            sendError(state, HTTPStatus.BAD_REQUEST, "Invalid gRPC frame");
            return;
        }

        GrpcResponseSenderImpl sender = new GrpcResponseSenderImpl(state);
        try {
            service.unaryCall(path, requestMessage, sender);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "gRPC call failed", e);
            sender.sendError(e);
        }
    }

    private void sendError(HTTPResponseState state, HTTPStatus status, String message) {
        Headers response = new Headers();
        response.status(status);
        response.add("content-type", "text/plain");
        state.headers(response);
        state.startResponseBody();
        state.responseBodyContent(ByteBuffer.wrap(message.getBytes()));
        state.endResponseBody();
        state.complete();
    }

    private static class GrpcResponseSenderImpl implements GrpcResponseSender {

        private final HTTPResponseState state;
        private boolean sent;

        GrpcResponseSenderImpl(HTTPResponseState state) {
            this.state = state;
        }

        @Override
        public void send(ByteBuffer message) {
            if (sent) return;
            sent = true;

            ByteBuffer framed = GrpcFraming.frame(message);
            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add("content-type", CONTENT_TYPE_GRPC);
            state.headers(response);
            state.startResponseBody();
            state.responseBodyContent(framed);
            state.endResponseBody();
            state.complete();
        }

        @Override
        public void sendError(int status, String message) {
            if (sent) return;
            sent = true;

            Headers response = new Headers();
            response.status(HTTPStatus.OK);
            response.add("content-type", "application/grpc");
            response.add("grpc-status", String.valueOf(status));
            response.add("grpc-message", message != null ? message : "");
            state.headers(response);
            state.complete();
        }

        @Override
        public void sendError(Throwable cause) {
            if (sent) return;
            sendError(13, cause != null ? cause.getMessage() : "Internal error"); // INTERNAL
        }
    }
}
