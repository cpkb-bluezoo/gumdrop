/*
 * DownstreamService.java
 * The service LookupService calls: answers {"query": q} with {"result": ...}.
 */

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONWriter;

public final class DownstreamService {

    private static final class ReverseHandler extends DefaultHttpRequestHandler {
        private final HttpResponse response;
        private final JSONParser parser = new JSONParser();
        private final LookupService.FieldHandler query = new LookupService.FieldHandler("query");

        ReverseHandler(HttpResponse response) {
            this.response = response;
            parser.setContentHandler(query);
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            try {
                parser.receive(data);
            } catch (JSONException e) {
                // reported when the request ends
            }
        }

        @Override
        public void endMessage() {
            String text = "";
            try {
                parser.close();
                if (query.getValue() != null) {
                    text = new StringBuilder(query.getValue()).reverse().toString();
                }
            } catch (JSONException e) {
                response.status(400);
                response.endMessage();
                return;
            }
            response.status(200);
            response.contentType(new ContentType("application", "json", null));
            try {
                JSONWriter json = new JSONWriter(new BodyChannel(response), 1024);
                json.writeStartObject();
                json.writeKey("result");
                json.writeString(text);
                json.writeEndObject();
                json.close();
            } catch (IOException e) {
                response.cancel();
                return;
            }
            response.endMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9091;
        HttpServer server = HttpServer.compose()
                .plaintextListener(port)
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(HttpResponse response) {
                        return new ReverseHandler(response);
                    }
                })
                .server();
        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);
        System.out.println("downstream service on port " + port);
        gumdrop.join();
    }

    private DownstreamService() {
    }

}
