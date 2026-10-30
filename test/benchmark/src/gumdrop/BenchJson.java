import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.bluezoo.json.JSONDefaultHandler;
import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONParser;
import org.bluezoo.json.JSONWriter;

/**
 * JSON handling shared verbatim (copy-pasted, not linked) between the
 * Gumdrop and Netty benchmark servers, both backed by org.bluezoo.jsonparser
 * so the JSON-mode benchmark measures HTTP-layer overhead using a real,
 * identical JSON parser/writer on both sides rather than a hand-rolled scan.
 *
 * Extractor feeds request-body ByteBuffers into the streaming parser as they
 * arrive, through one fixed buffer that holds only the bytes the parser has
 * not used yet - no whole-body accumulation on either the Gumdrop or Netty
 * side.
 */
public class BenchJson {

    private static final class IdExtractingHandler extends JSONDefaultHandler {
        private String currentKey;
        long id = -1;

        @Override
        public void key(String k) {
            currentKey = k;
        }

        @Override
        public void numberValue(Number n) {
            if ("id".equals(currentKey)) {
                id = n.longValue();
            }
        }
    }

    public static final class Extractor {
        private final JSONParser parser = new JSONParser();
        private final IdExtractingHandler handler = new IdExtractingHandler();
        // Bytes the parser could not use yet, which it wants back at the head
        // of the next buffer; the parser also needs a buffer larger than the
        // longest key it allows (4096 bytes).
        private ByteBuffer pending = ByteBuffer.allocate(8192);

        public Extractor() {
            parser.setContentHandler(handler);
        }

        /** Feeds one chunk of request body to the parser, after any bytes it left unconsumed. */
        public void receive(ByteBuffer data) {
            try {
                if (pending.capacity() < pending.position() + data.remaining()) {
                    ByteBuffer larger = ByteBuffer.allocate(pending.position() + data.remaining());
                    pending.flip();
                    larger.put(pending);
                    pending = larger;
                }
                pending.put(data);
                pending.flip();
                parser.receive(pending);
                pending.compact();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        public long finish() {
            try {
                parser.close();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return handler.id;
        }
    }

    public static byte[] buildResponse(long id) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JSONWriter writer = new JSONWriter(out);
            writer.writeStartObject();
            writer.writeKey("message");
            writer.writeString("Hello, World!");
            writer.writeKey("id");
            writer.writeNumber(id);
            writer.writeEndObject();
            writer.flush();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
