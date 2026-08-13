/*
 * PyGrpcTestSupport.java
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

package org.bluezoo.gumdrop.grpc.pygrpc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Connection settings shared by the {@code grpc.pygrpc} integration
 * tests, which exercise the real gRPC client ({@code
 * org.bluezoo.gumdrop.grpc.client}) against a real Python (grpcio)
 * gRPC server -- not run in CI, only locally against a container you
 * already have running. See {@link
 * org.bluezoo.gumdrop.smtp.postfix.PostfixTestSupport} for the sibling
 * of this class and the shared rationale.
 *
 * <p>Unlike the other client integration test suites, the server here
 * is a small custom one (not an off-the-shelf broker) because
 * gumdrop's gRPC client is schema-driven (it decodes/encodes protobuf
 * messages from a parsed {@code .proto} file rather than generated
 * stubs), so the test needs a server implementing a known, specific
 * schema -- see {@code test/integration/docker/grpc/echo.proto}. What
 * still makes this a meaningful independent-implementation test is
 * that the server is plain Python grpcio (the C-core gRPC/HTTP2/
 * protobuf implementation), wholly unrelated to gumdrop's own HTTP/2
 * and protobuf code.
 *
 * <p>All settings are overridable via system properties so this isn't
 * tied to one machine's setup; the defaults match a server built and
 * started from {@code test/integration/docker/grpc} as:
 * <pre>{@code
 * podman build -t gumdrop-grpc-test -f test/integration/docker/grpc/Dockerfile \
 *     test/integration/docker/grpc
 * podman run -d --name grpc-test -p 15051:50051 gumdrop-grpc-test
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class PyGrpcTestSupport {

    static final String HOST = System.getProperty("grpc.test.host", "127.0.0.1");
    static final int PORT = Integer.getInteger("grpc.test.port", 15051);

    private static final int PROBE_TIMEOUT_MS = 500;

    private PyGrpcTestSupport() {
    }

    static final String NOT_REACHABLE_MESSAGE =
            "no gRPC test server reachable at " + HOST + ":" + PORT
                    + " -- build and start the container locally to run these tests"
                    + " (see PyGrpcTestSupport's class Javadoc)";

    static boolean isReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The schema for the Echo service, matching test/integration/docker/grpc/echo.proto. */
    static final String ECHO_PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package gumdroptest;\n"
            + "message EchoRequest {\n"
            + "  string message = 1;\n"
            + "  int32 repeat_count = 2;\n"
            + "}\n"
            + "message EchoResponse {\n"
            + "  string message = 1;\n"
            + "  int32 length = 2;\n"
            + "}\n"
            + "message FailRequest {\n"
            + "  string reason = 1;\n"
            + "}\n"
            + "service Echo {\n"
            + "  rpc SayEcho(EchoRequest) returns (EchoResponse);\n"
            + "  rpc AlwaysFail(FailRequest) returns (EchoResponse);\n"
            + "}\n";
}
