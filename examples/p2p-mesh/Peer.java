/*
 * Peer.java
 * A mesh node: serves HTTP and calls another node, on one Gumdrop.
 */

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One node of a two-node mesh.
 *
 * <p>Every node is both a server and a client: it answers {@code GET /hello}
 * with its own name, and after a short delay (so the other node has time to
 * start listening) it calls the peer's {@code /hello} and prints the reply. Both roles share one {@link Gumdrop}
 * runtime and its SelectorLoops, and neither is a "library" in any sense
 * the other is not.
 *
 * <p>Run two nodes from the gumdrop tree after {@code ant build}, each
 * pointing at the other's port:
 * <pre>{@code
 * java -cp "build/*:lib/*" examples.p2p-mesh.Peer alice 9001 localhost 9002
 * java -cp "build/*:lib/*" examples.p2p-mesh.Peer bob   9002 localhost 9001
 * }</pre>
 *
 * <p>Cleartext HTTP keeps the example short. A real mesh would use
 * {@code secureEndpoint(port, TlsConfig.pem(cert, key))} on the server
 * side and {@code client.setSecure(true)} on the client side; see
 * web/tls.html.
 */
public final class Peer {

    private static final long DIAL_DELAY_MS = 2000L;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println(
                    "usage: Peer <name> <listen-port> <peer-host> <peer-port>");
            System.exit(2);
        }
        final String name = args[0];
        int listenPort = Integer.parseInt(args[1]);
        String peerHost = args[2];
        int peerPort = Integer.parseInt(args[3]);

        Gumdrop gumdrop = Gumdrop.boot();

        // Server role: one handler per request stream.
        HttpServer server = HttpServer.compose()
                .plaintextListener(listenPort)
                .streamHandler(new HelloStreamHandler(name))
                .server();
        gumdrop.addServer(server);
        System.out.println(name + " listening on port " + listenPort);

        // Client role: dial the peer on the same runtime, once the peer has
        // had time to start listening.
        final HttpClient client = new HttpClient(peerHost, peerPort);
        final Gumdrop runtime = gumdrop;
        gumdrop.scheduleTimer(null, DIAL_DELAY_MS, new Runnable() {
            @Override
            public void run() {
                dial(runtime, client, name);
            }
        });

        gumdrop.join();
    }

    private static void dial(Gumdrop gumdrop, final HttpClient client,
                             final String name) {
        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
                HttpRequest request = client.get("/hello", new DefaultHttpResponseHandler() {
                    private final StringBuilder body = new StringBuilder();

                    @Override
                    public void bodyContent(ByteBuffer data) {
                        body.append(StandardCharsets.UTF_8.decode(data));
                    }

                    @Override
                    public void endMessage() {
                        System.out.println(name + " heard: " + body.toString().trim());
                        client.close();
                    }

                    @Override
                    public void failed(Exception ex) {
                        System.err.println(name + " request failed: " + ex);
                        client.close();
                    }
                });
                request.endMessage();
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                System.err.println(name + " could not reach the peer: " + cause);
            }

            @Override
            public void onDisconnected() {
            }
        });
    }

    private static final class HelloStreamHandler implements HttpStreamHandler {
        private final String name;

        HelloStreamHandler(String name) {
            this.name = name;
        }

        @Override
        public HttpRequestHandler openStream(HttpResponse response) {
            return new HelloHandler(response, name);
        }
    }

    private static final class HelloHandler extends DefaultHttpRequestHandler {
        private final HttpResponse response;
        private final String name;

        HelloHandler(HttpResponse response, String name) {
            this.response = response;
            this.name = name;
        }

        @Override
        public void endHeaders() {
            response.status(200);
            response.contentType(new ContentType("text", "plain", null));
            response.bodyContent(ByteBuffer.wrap(
                    ("hello from " + name + "\n").getBytes(StandardCharsets.UTF_8)));
            response.endMessage();
        }
    }

    private Peer() {
    }

}
