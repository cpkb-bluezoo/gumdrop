import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * Minimal raw-API (non-servlet) Gumdrop HTTP server used for benchmarking
 * against an equivalent raw Netty server. Port of the August 2026 harness
 * to the Gumdrop 3 message-event API. Modes:
 *   plaintext - GET returns a fixed short text body
 *   json      - POST with a small JSON body, returns a small JSON body
 *   tls       - like plaintext but over HTTPS (h2 or http/1.1 via ALPN)
 *   h3        - like plaintext but over HTTP/3, with a PEM certificate and key
 */
public class GumdropBenchServer {

    static final byte[] HELLO = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    static final ContentType TEXT_PLAIN = new ContentType("text", "plain", null);
    static final ContentType APPLICATION_JSON = new ContentType("application", "json", null);

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        final String mode = opt.getOrDefault("mode", "plaintext");
        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));

        HttpStreamHandler streamHandler = new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse response) {
                if ("json".equals(mode)) {
                    return new JsonHandler(response);
                }
                return new PlaintextHandler(response);
            }
        };

        HttpServer.Composer composer = HttpServer.compose()
                .streamHandler(streamHandler)
                .addSecurityHeaders(false);
        if ("h3".equals(mode)) {
            composer.listener(new Http3Listener().port(port).tls(TlsConfig.pem(
                    Path.of(req(opt, "cert")), Path.of(req(opt, "key")))));
        } else {
            Http2Listener listener = new Http2Listener().port(port);
            if ("tls".equals(mode)) {
                listener.secure(true).tls(TlsConfig.keystore(
                        Path.of(req(opt, "keystore")), req(opt, "keystore-pass")));
            }
            composer.listener(listener);
        }

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(composer.server());

        System.out.println("GumdropBenchServer mode=" + mode + " port=" + port + " ready");
        gumdrop.join();
    }

    static class PlaintextHandler extends DefaultHttpRequestHandler {
        private final HttpResponse response;
        private boolean get;

        PlaintextHandler(HttpResponse response) {
            this.response = response;
        }

        @Override
        public void method(HttpMethod method) {
            get = (method == HttpMethod.GET);
        }

        @Override
        public void endHeaders() {
            if (get) {
                response.status(200);
                response.contentType(TEXT_PLAIN);
                response.bodyContent(ByteBuffer.wrap(HELLO));
                response.endMessage();
            }
        }
    }

    static class JsonHandler extends DefaultHttpRequestHandler {
        private final HttpResponse response;
        private final BenchJson.Extractor extractor = new BenchJson.Extractor();

        JsonHandler(HttpResponse response) {
            this.response = response;
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            extractor.receive(data);
        }

        @Override
        public void endMessage() {
            long id = extractor.finish();
            byte[] out = BenchJson.buildResponse(id);
            response.status(200);
            response.contentType(APPLICATION_JSON);
            response.bodyContent(ByteBuffer.wrap(out));
            response.endMessage();
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<String, String>();
        for (String a : args) {
            if (a.startsWith("--")) {
                String kv = a.substring(2);
                int eq = kv.indexOf('=');
                if (eq >= 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
                else m.put(kv, "true");
            }
        }
        return m;
    }

    static String req(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
