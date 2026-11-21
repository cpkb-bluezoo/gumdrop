/*
 * StreamingExample.java
 * Example demonstrating streamed values with the Gumdrop Redis client.
 */

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.redis.client.ArgumentSink;
import org.bluezoo.gumdrop.redis.client.ArgumentSource;
import org.bluezoo.gumdrop.redis.client.ArgumentStream;
import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.client.StringResultHandler;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Example of a value larger than memory: a file is written to a Redis key,
 * and the key is read back into another file, neither ever being held.
 *
 * <p>A request argument is an {@link ArgumentSource}: it declares its length
 * (RESP announces the length of each argument before its bytes) and is read a
 * block at a time, only as the connection can take it. A reply payload is
 * delivered to a {@link BulkResultHandler} as chunks, which here are written
 * straight to a file. The memory this costs is set by the block size and the
 * connection's buffers, not by the size of the file.
 *
 * <p>Both ends use plain file channels on the connection's event loop, which
 * suits a local file read in blocks. A slow source (a socket, a remote
 * store) must not block the loop: its stream returns 0 from {@code read} and
 * calls {@code sink.ready()} when it has more, and a slow consumer returns
 * {@link ReplyFlow#PAUSE} from {@code handleContent} and calls
 * {@link ReplyStream#resume()} when it can take more.
 *
 * <p>Usage: {@code StreamingExample <file> [host] [port]}. The value is read
 * back into {@code <file>.copy}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamingExample {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StreamingExample <file> [host] [port]");
            return;
        }
        Path file = Paths.get(args[0]);
        String host = args.length >= 2 ? args[1] : "localhost";
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : 6379;
        Gumdrop gumdrop = Gumdrop.boot();
        new RedisClient(host, port).connect(gumdrop, new Handler(file));
    }

    /** A file as a request argument, opened afresh for each attempt. */
    static final class FileSource implements ArgumentSource {
        private final Path path;
        private final long length;

        FileSource(Path path) throws IOException {
            this.path = path;
            this.length = java.nio.file.Files.size(path);
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public ArgumentStream open(ArgumentSink sink) {
            try {
                final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
                return new ArgumentStream() {
                    @Override
                    public int read(ByteBuffer dst) throws IOException {
                        return channel.read(dst);
                    }

                    @Override
                    public void close() {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            // nothing more to do
                        }
                    }
                };
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** A bulk reply written to a file as it arrives. */
    static final class FileSink implements BulkResultHandler {
        private final Path path;
        private FileChannel out;
        private long written;

        FileSink(Path path) {
            this.path = path;
        }

        @Override
        public void handleNull() {
            System.out.println("[GET] no such key");
        }

        @Override
        public ReplyFlow handleBegin(long length, ReplyStream stream) {
            try {
                out = FileChannel.open(path, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return ReplyFlow.CONTINUE;
            } catch (IOException e) {
                // give up this reply only; the connection is not affected
                return ReplyFlow.SKIP;
            }
        }

        @Override
        public ReplyFlow handleContent(ByteBuffer chunk) {
            try {
                written += chunk.remaining();
                while (chunk.hasRemaining()) {
                    out.write(chunk);
                }
                return ReplyFlow.CONTINUE;
            } catch (IOException e) {
                return ReplyFlow.SKIP;
            }
        }

        @Override
        public void handleEnd() {
            close();
        }

        private void close() {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                // nothing more to do
            }
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            close();
            if (error != null) {
                System.err.println("[GET] " + error);
            } else {
                System.out.println("[GET] " + written + " bytes written to " + path);
            }
            session.del(new org.bluezoo.gumdrop.redis.client.IntegerResultHandler() {
                @Override
                public void handleResult(long value, RedisSession s) {
                    s.quit();
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    s.close();
                }
            }, "streaming-example");
        }
    }

    static final class Handler implements RedisConnectionReady {
        private final Path file;

        Handler(Path file) {
            this.file = file;
        }

        @Override
        public void handleReady(RedisSession session) {
            final Path copy = Paths.get(file + ".copy");
            ArgumentSource source;
            try {
                source = new FileSource(file);
            } catch (IOException e) {
                System.err.println("cannot read " + file + ": " + e.getMessage());
                session.close();
                return;
            }
            System.out.println("[SET] sending " + source.length() + " bytes from " + file);
            session.set("streaming-example", source, new StringResultHandler() {
                @Override
                public void handleResult(String result, RedisSession s) {
                    System.out.println("[SET] " + result);
                    s.get("streaming-example", new FileSink(copy));
                }

                @Override
                public void handleError(String error, RedisSession s) {
                    System.err.println("[SET] " + error);
                    s.close();
                }
            });
        }

        @Override
        public void onConnected(Endpoint endpoint) {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception e) {
            System.err.println("[Error] " + e.getMessage());
        }

        @Override
        public void onDisconnected() {
        }
    }

}
