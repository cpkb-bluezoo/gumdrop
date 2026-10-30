/*
 * JsonFeed.java
 * Feeds HTTP body chunks to a streaming JSON parser.
 */

import java.nio.ByteBuffer;

import org.bluezoo.json.JSONException;
import org.bluezoo.json.JSONParser;

/**
 * Passes body chunks to a {@link JSONParser}. The parser leaves a token that
 * straddles the end of a chunk (a key, a literal, half of a multi-byte
 * character) unconsumed and expects it back at the head of the next buffer,
 * so the chunk is appended to those bytes here. The buffer is kept larger
 * than the longest key the parser allows, or the parser would refuse a
 * small chunk that holds an unfinished token.
 */
final class JsonFeed {

    private static final int BUFFER_SIZE = 8192;

    private final JSONParser parser;
    private ByteBuffer pending = ByteBuffer.allocate(BUFFER_SIZE);

    JsonFeed(JSONParser parser) {
        this.parser = parser;
    }

    void receive(ByteBuffer data) throws JSONException {
        int needed = pending.position() + data.remaining();
        if (pending.capacity() < needed) {
            ByteBuffer larger = ByteBuffer.allocate(needed);
            pending.flip();
            larger.put(pending);
            pending = larger;
        }
        pending.put(data);
        pending.flip();
        parser.receive(pending);
        pending.compact();
    }
}
