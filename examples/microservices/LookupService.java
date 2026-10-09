/*
 * LookupService.java
 * A microservice that answers a JSON request by calling another microservice.
 */

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONWriter;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class LookupService {

    /** Picks one string field out of a JSON object as it is parsed. */
    static final class FieldHandler extends JSONDefaultHandler {
        private final String wanted;
        private String currentKey;
        private String value;

        FieldHandler(String wanted) {
            this.wanted = wanted;
        }

        @Override
        public void key(String key) throws JSONException {
            currentKey = key;
        }

        @Override
        public void stringValue(String text) throws JSONException {
            if (wanted.equals(currentKey)) {
                value = text;
            }
        }

        @Override
        public void endObject() throws JSONException {
            currentKey = null;
        }

        String getValue() {
            return value;
        }
    }

    /** Handles POST /lookup by calling the downstream service. */
    static final class LookupHandler extends DefaultHttpRequestHandler {
        private final Gumdrop gumdrop;
        private final String downstreamHost;
        private final int downstreamPort;
        private final HttpResponse response;
        private final JSONParser jsonParser = new JSONParser();
        private final FieldHandler query = new FieldHandler("query");
        private HttpMethod method;
        private String path;
        private boolean accepted;
        private boolean answered;

        LookupHandler(HttpResponse response, Gumdrop gumdrop, String host, int port) {
            this.response = response;
            this.gumdrop = gumdrop;
            this.downstreamHost = host;
            this.downstreamPort = port;
        }

        @Override
        public void method(HttpMethod method) {
            this.method = method;
        }

        @Override
        public void target(ByteBuffer target) {
            path = UTF_8.decode(target).toString();
        }

        @Override
        public void endHeaders() {
            if (!"/lookup".equals(path)) {
                sendError(404, "Not Found");
            } else if (method != HttpMethod.POST) {
                sendError(405, "Method Not Allowed");
            } else {
                jsonParser.setContentHandler(query);
                jsonParser.reset();
                accepted = true;
            }
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (!accepted) {
                return;
            }
            try {
                jsonParser.receive(data);
            } catch (JSONException e) {
                accepted = false;
                sendError(400, "Invalid JSON: " + e.getMessage());
            }
        }

        @Override
        public void endMessage() {
            if (!accepted) {
                return;
            }
            try {
                jsonParser.close();
            } catch (JSONException e) {
                sendError(400, "Invalid JSON: " + e.getMessage());
                return;
            }
            String text = query.getValue();
            if (text == null || text.isEmpty()) {
                sendError(400, "Missing 'query' field");
                return;
            }
            callDownstream(text);
        }

        private void callDownstream(final String text) {
            // Run the outbound connection on the loop that carries this
            // request: no thread hand-offs, and no extra threads
            SelectorLoop loop = response.getSelectorLoop();
            final HttpClient client = (loop != null)
                    ? new HttpClient(loop, downstreamHost, downstreamPort)
                    : new HttpClient(downstreamHost, downstreamPort);
            // Carry the trace on so the two services appear in one trace
            client.trace(response.getTrace());

            client.connect(gumdrop, new HttpClientHandler() {
                @Override
                public void onConnected(Endpoint endpoint) {
                    HttpRequest request = client.post("/lookup",
                            new DownstreamResponse(client));
                    request.header("Content-Type", "application/json");
                    request.header("Accept", "application/json");
                    try {
                        JSONWriter json = new JSONWriter(new BodyChannel(request), 1024);
                        json.writeStartObject();
                        json.writeKey("query");
                        json.writeString(text);
                        json.writeEndObject();
                        json.close();
                    } catch (IOException e) {
                        request.cancel();
                        sendError(502, "Could not call downstream: " + e.getMessage());
                        return;
                    }
                    request.endMessage();
                }

                @Override
                public void onError(Exception cause) {
                    sendError(502, "Connection failed: " + cause.getMessage());
                }

                @Override
                public void onSecurityEstablished(SecurityInfo info) {
                }

                @Override
                public void onDisconnected() {
                }
            });
        }

        /** Reads the downstream JSON response as it arrives. */
        private final class DownstreamResponse extends DefaultHttpResponseHandler {
            private final HttpClient client;
            private final JSONParser parser = new JSONParser();
            private final FieldHandler result = new FieldHandler("result");
            private int status;
            private boolean failed;

            DownstreamResponse(HttpClient client) {
                this.client = client;
                parser.setContentHandler(result);
            }

            @Override
            public void status(int code) {
                status = code;
            }

            @Override
            public void bodyContent(ByteBuffer data) {
                if (failed || status != 200) {
                    return;
                }
                try {
                    parser.receive(data);
                } catch (JSONException e) {
                    failed = true;
                }
            }

            @Override
            public void endMessage() {
                client.close();
                try {
                    if (!failed && status == 200) {
                        parser.close();
                    }
                } catch (JSONException e) {
                    failed = true;
                }
                if (failed || status != 200) {
                    sendError(502, "Bad response from downstream (" + status + ")");
                } else {
                    sendResult(result.getValue());
                }
            }

            @Override
            public void failed(Exception ex) {
                client.close();
                sendError(502, "Downstream error: " + ex.getMessage());
            }
        }

        private void sendResult(String value) {
            if (answered) {
                return;
            }
            answered = true;
            response.status(200);
            response.contentType(new ContentType("application", "json", null));
            try {
                JSONWriter json = new JSONWriter(new BodyChannel(response), 1024);
                json.writeStartObject();
                json.writeKey("result");
                json.writeString(value != null ? value : "");
                json.writeEndObject();
                json.close();
            } catch (IOException e) {
                response.cancel();
                return;
            }
            response.endMessage();
        }

        private void sendError(int code, String message) {
            if (answered) {
                return;
            }
            answered = true;
            response.status(code);
            response.contentType(new ContentType("text", "plain", null));
            response.bodyContent(ByteBuffer.wrap(message.getBytes(UTF_8)));
            response.endMessage();
        }
    }

    /**
     * Usage: LookupService [port [downstream-host downstream-port]]
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9092;
        final String host = args.length > 1 ? args[1] : "localhost";
        final int downstreamPort = args.length > 2 ? Integer.parseInt(args[2]) : 9091;

        final Gumdrop gumdrop = Gumdrop.boot();
        HttpServer server = HttpServer.compose()
                .plaintextListener(port)
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(HttpResponse response) {
                        return new LookupHandler(response, gumdrop, host, downstreamPort);
                    }
                })
                .server();
        gumdrop.addServer(server);
        System.out.println("lookup service on port " + port + ", downstream "
                + host + ":" + downstreamPort);
        gumdrop.join();
    }

    private LookupService() {
    }

}
