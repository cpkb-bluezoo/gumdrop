/*
 * PyGrpcServerIntegrationTest.java
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

import org.bluezoo.gumdrop.AbstractServerIntegrationTest;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.grpc.GrpcMetadata;
import org.bluezoo.gumdrop.grpc.GrpcStatus;
import org.bluezoo.gumdrop.grpc.proto.ProtoDefaultHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelSerializer;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.server.GrpcCall;
import org.bluezoo.gumdrop.grpc.server.GrpcCallHandler;
import org.bluezoo.gumdrop.grpc.server.GrpcRequestHandler;
import org.bluezoo.gumdrop.grpc.server.GrpcResponseMessage;
import org.bluezoo.gumdrop.grpc.server.GrpcServer;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.protobuf.ProtobufWriter;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Gumdrop's gRPC server, streaming RPCs, metadata and deadlines included,
 * called by a real Python (grpcio) client, {@code client.py}, run in a
 * container -- not run in CI, see {@link PyGrpcTestSupport}.
 *
 * <p>The server listens on all addresses, on the port of the system property
 * {@code grpc.test.server.port} (15052 by default), and the client reaches it
 * from the container as {@code host.containers.internal}, which is what a
 * podman machine calls the host. The image is the one that
 * {@code test/integration/docker/grpc/Dockerfile} builds, named by
 * {@code grpc.test.image} ({@code gumdrop-grpc-test} by default). The test is
 * skipped if podman or the image is not there.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PyGrpcServerIntegrationTest extends AbstractServerIntegrationTest {

    private static final int PORT = Integer.getInteger("grpc.test.server.port", 15052);
    private static final String IMAGE = System.getProperty("grpc.test.image", "gumdrop-grpc-test");
    private static final String HOST_NAME = System.getProperty(
            "grpc.test.server.host", "host.containers.internal");
    private static final long TIMEOUT_SECONDS = 60;

    private final EchoService service = new EchoService();

    @Override
    protected Collection<? extends TcpListener> buildListeners() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PyGrpcTestSupport.ECHO_PROTO);
        service.protoFile = protoFile;
        Http2Listener listener = new Http2Listener();
        listener.port(PORT);
        listener.streamHandler(new GrpcRequestHandler(protoFile, service));
        return Collections.singletonList(listener);
    }

    private static boolean imageExists() {
        try {
            Process p = new ProcessBuilder("podman", "image", "exists", IMAGE)
                    .redirectErrorStream(true).start();
            p.getInputStream().close();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    public void thePythonClientCanCallEveryKindOfRpc() throws Exception {
        assumeTrue("podman and the image " + IMAGE + " are needed", imageExists());
        Process p = new ProcessBuilder("podman", "run", "--rm", IMAGE,
                "python3", "client.py", HOST_NAME + ":" + PORT)
                .redirectErrorStream(true).start();
        List<String> lines = new ArrayList<String>();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            lines.add(line);
        }
        assertTrue("client.py did not finish: " + lines,
                p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i), lines.get(i).startsWith("ok "));
        }
        assertEquals(lines.toString(), 0, p.exitValue());
        assertEquals(lines.toString(), 12, lines.size());
        // the Slow call was cut short by the client's deadline and then
        // cancelled, and the handler was told
        assertTrue("the slow call was not cancelled",
                service.slowCancelled.await(10, TimeUnit.SECONDS));
    }

    /** The Echo service of echo.proto, as a gumdrop gRPC server. */
    private static final class EchoService implements GrpcServer {

        ProtoFile protoFile;
        final CountDownLatch slowCancelled = new CountDownLatch(1);

        @Override
        public boolean isStreamingSupported() {
            return true;
        }

        @Override
        public ProtoMessageHandler startCall(String path, final GrpcCall call) {
            String method = path.substring(path.lastIndexOf('/') + 1);
            if ("SayEcho".equals(method)) {
                return new Requests(call) {
                    @Override
                    void message(String message, int repeat) throws Exception {
                        StringBuilder out = new StringBuilder();
                        for (int i = 0; i < Math.max(1, repeat); i++) {
                            out.append(message);
                        }
                        reply(call, out.toString());
                    }
                };
            }
            if ("Watch".equals(method)) {
                return new Requests(call) {
                    @Override
                    void message(String message, int repeat) throws Exception {
                        if ("fail".equals(message)) {
                            reply(call, "first");
                            call.close(GrpcStatus.NOT_FOUND, "gone");
                            return;
                        }
                        for (int i = 1; i <= Math.max(1, repeat); i++) {
                            reply(call, message + i);
                        }
                        call.close();
                    }
                };
            }
            if ("Collect".equals(method)) {
                return new Requests(call) {
                    private final StringBuilder all = new StringBuilder();

                    @Override
                    void message(String message, int repeat) {
                        all.append(message);
                    }

                    @Override
                    public void endRequests() {
                        try {
                            reply(call, all.toString());
                        } catch (Exception e) {
                            call.sendError(e);
                        }
                    }
                };
            }
            if ("Chat".equals(method)) {
                return new Requests(call) {
                    @Override
                    void message(String message, int repeat) throws Exception {
                        reply(call, "re " + message);
                    }

                    @Override
                    public void endRequests() {
                        call.close();
                    }
                };
            }
            if ("Metadata".equals(method)) {
                return new Requests(call) {
                    @Override
                    void message(String message, int repeat) throws Exception {
                        GrpcMetadata in = call.getRequestMetadata();
                        String value = in.get("x-test");
                        call.getResponseMetadata().add("x-echo", value);
                        call.getResponseMetadata().addBinary("x-echo-bin",
                                in.getBinary("x-test-bin"));
                        call.getTrailingMetadata().add("x-trailer", "done");
                        reply(call, value);
                    }
                };
            }
            if ("Slow".equals(method)) {
                // never answers: ended by the client's deadline or cancellation
                return new Requests(call) {
                    @Override
                    void message(String message, int repeat) {
                    }

                    @Override
                    public void cancelled() {
                        slowCancelled.countDown();
                    }
                };
            }
            return null;
        }

        private void reply(GrpcCall call, String text) throws Exception {
            GrpcResponseMessage msg = call.openMessage(null);
            ProtoModelSerializer ser = msg.getSerializer();
            ProtobufWriter w = msg.getWriter();
            ser.field(w, "message", text);
            ser.field(w, "length", text.length());
            msg.complete();
        }

        /** Collects each request message and passes it on. */
        private abstract class Requests extends ProtoDefaultHandler implements GrpcCallHandler {

            private final GrpcCall call;
            private String message = "";
            private int repeat;

            Requests(GrpcCall call) {
                this.call = call;
            }

            abstract void message(String message, int repeat) throws Exception;

            @Override
            public void startMessage(String typeName) {
                message = "";
                repeat = 0;
            }

            @Override
            public void field(String name, Object value) {
                if ("message".equals(name)) {
                    message = (String) value;
                } else if ("repeat_count".equals(name)) {
                    repeat = ((Number) value).intValue();
                }
            }

            @Override
            public void endMessage() throws ProtoParseException {
                try {
                    message(message, repeat);
                } catch (Exception e) {
                    throw new ProtoParseException(e.getMessage(), e);
                }
            }
        }
    }
}
