/*
 * BodyChannel.java
 * Writes to the body of an HTTP response or request as it is produced.
 */

import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.server.HttpResponse;

/**
 * A {@link WritableByteChannel} whose writes are the body chunks of an HTTP
 * response or request, so that a streaming writer such as Gonzalez's
 * {@code XMLWriter} or jsonparser's {@code JSONWriter} sends its output as it
 * writes, not after building a whole document in memory.
 */
final class BodyChannel implements WritableByteChannel {

    private final HttpResponse response;
    private final HttpRequest request;
    private boolean open = true;

    /** A channel to the body of a response. */
    BodyChannel(HttpResponse response) {
        this.response = response;
        this.request = null;
    }

    /** A channel to the body of a request. */
    BodyChannel(HttpRequest request) {
        this.response = null;
        this.request = request;
    }

    @Override
    public int write(ByteBuffer src) {
        int n = src.remaining();
        if (n == 0) {
            return 0;
        }
        byte[] copy = new byte[n];
        src.get(copy);
        ByteBuffer chunk = ByteBuffer.wrap(copy);
        if (response != null) {
            response.bodyContent(chunk);
        } else {
            request.bodyContent(chunk);
        }
        return n;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

}
