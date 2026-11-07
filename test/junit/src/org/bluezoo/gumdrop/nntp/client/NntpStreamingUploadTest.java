/*
 * NntpStreamingUploadTest.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.mime.UuScanner;
import org.bluezoo.gumdrop.nntp.client.NntpClient.StartTls;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.testsupport.StubTelemetry;
import org.bluezoo.gumdrop.util.DotUnstuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Uploads are streamed: an article is written a chunk at a time, each chunk
 * only once the connection has taken the last, and an attachment is read,
 * encoded and written out in chunks too. These tests send tens of megabytes
 * and measure how much is ever outstanding; they decode what is sent as it
 * is sent, so they themselves hold none of it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class NntpStreamingUploadTest {

    /** The most that one write of the client puts on the wire: a slice, doubled, plus a held CR. */
    private static final int ONE_WRITE = 2 * 8192 + 2;

    private static final String HEADERS = "From: a@example.com\r\nSubject: big\r\nNewsgroups: x.y\r\n\r\n";

    /** Sees the bytes sent after the POST command. */
    private interface Tap {
        void bytes(ByteBuffer data);
    }

    /**
     * A connection that can hold back its write-ready notifications and counts
     * what is sent. Tasks run under one lock, as on a single event loop, so the
     * test can drive it from its own thread while another thread completes reads.
     */
    private static final class Wire implements Endpoint {
        boolean open = true;
        boolean deferReady;
        Runnable pendingReady;
        long sentSinceReady;
        long mostSinceReady;
        long total;
        Tap tap;

        @Override public void send(ByteBuffer data) {
            if (tap == null) {
                data.position(data.limit()); // the commands
                return;
            }
            int n = data.remaining();
            sentSinceReady += n;
            total += n;
            tap.bytes(data);
        }

        @Override public void onWriteReady(Runnable callback) {
            mostSinceReady = Math.max(mostSinceReady, sentSinceReady);
            sentSinceReady = 0;
            if (deferReady) {
                pendingReady = callback;
            } else if (callback != null) {
                callback.run();
            }
        }

        /** Lets the connection take what it has been given, until nothing more waits on it. */
        void drain() {
            while (pendingReady != null) {
                Runnable r = pendingReady;
                pendingReady = null;
                r.run();
            }
        }

        @Override public void execute(Runnable task) {
            synchronized (this) {
                task.run();
            }
        }

        @Override public boolean isOpen() { return open; }
        @Override public boolean isClosing() { return false; }
        @Override public void close() { open = false; }
        @Override public SocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 119); }
        @Override public boolean isSecure() { return false; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public void startTLS() { }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public TimerHandle scheduleTimer(long delayMs, Runnable cb) {
            return new TimerHandle() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return false; }
            };
        }
        @Override public Trace getTrace() { return null; }
        @Override public void setTrace(Trace trace) { }
        @Override public TelemetryConfig getTelemetryConfig() { return StubTelemetry.CONFIG; }
        @Override public void pauseRead() { }
        @Override public void resumeRead() { }
    }

    /**
     * Decodes the article as it is sent, holding none of it: unstuffs it, finds
     * the attachments and checksums them.
     */
    private static final class Decoding implements Tap {
        final CRC32 crc = new CRC32();
        final List<String> files = new ArrayList<String>();
        final CountDownLatch ended = new CountDownLatch(1);
        long bytes;
        boolean terminated;
        boolean allComplete = true;

        private final UuScanner scanner = new UuScanner(new UuScanner.Handler() {
            @Override public void text(ByteBuffer data) {
            }

            @Override public void begin(int mode, String name) {
                files.add(Integer.toOctalString(mode) + " " + name);
            }

            @Override public void content(ByteBuffer data) {
                bytes += data.remaining();
                byte[] b = new byte[data.remaining()];
                data.get(b);
                crc.update(b, 0, b.length);
            }

            @Override public void end(boolean complete) {
                allComplete &= complete;
            }
        });

        private final DotUnstuffer unstuffer = new DotUnstuffer(new DotUnstuffer.Callback() {
            @Override public void content(ByteBuffer chunk) {
                scanner.receive(chunk);
            }

            @Override public void complete() {
                scanner.finish();
            }
        });

        @Override public void bytes(ByteBuffer data) {
            if (!terminated && !unstuffer.process(data)) {
                terminated = true;
                ended.countDown();
            }
        }
    }

    private static final class Outcome implements NntpCallback<Void> {
        volatile boolean done;
        volatile NntpException error;

        @Override public void completed(Void result) {
            done = true;
        }

        @Override public void failed(NntpException e) {
            error = e;
            done = true;
        }
    }

    private static final class Events implements NntpClientHandler {
        NntpSession session;

        @Override public void connected(NntpSession s, NntpGreeting g) {
            session = s;
        }

        @Override public void error(Exception cause) {
        }

        @Override public void disconnected() {
        }
    }

    /** A source of deterministic bytes that is read without being held. */
    private static final class Generated implements NntpAttachment.Source {
        final long length;
        final CRC32 crc = new CRC32();
        long produced;
        long failAfter = -1;
        boolean closed;
        private long state = 0x9E3779B97F4A7C15L;

        Generated(long length) {
            this.length = length;
        }

        @Override public void read(ByteBuffer dst, CompletionHandler<Integer, ByteBuffer> handler) {
            if (failAfter >= 0 && produced >= failAfter) {
                handler.failed(new IOException("disk error"), dst);
                return;
            }
            int n = (int) Math.min(dst.remaining(), length - produced);
            if (n <= 0) {
                handler.completed(Integer.valueOf(-1), dst);
                return;
            }
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) {
                state ^= state << 13;
                state ^= state >>> 7;
                state ^= state << 17;
                b[i] = (byte) state;
            }
            crc.update(b, 0, n);
            dst.put(b);
            produced += n;
            handler.completed(Integer.valueOf(n), dst);
        }

        @Override public void close() { closed = true; }
    }

    private Wire wire;
    private Events events;
    private NntpClientProtocolHandler handler;

    @Before
    public void setUp() {
        wire = new Wire();
        events = new Events();
        handler = new NntpClientProtocolHandler(events, null, null, StartTls.NEVER, 0, null, "news.example.com");
        handler.connected(wire);
        server("200 hi\r\n101 caps\r\n.\r\n");
        assertNotNull(events.session);
    }

    private void server(String text) {
        synchronized (wire) {
            handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
        }
    }

    private static byte[] headers() {
        return HEADERS.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ── attachments, in constant memory ──

    @Test
    public void testLargeAttachmentIsStreamedInConstantMemory() {
        long size = 32L * 1024 * 1024;
        Generated source = new Generated(size);
        Decoding decoded = new Decoding();
        Outcome done = new Outcome();
        wire.deferReady = true;
        events.session.postWithAttachments(headers(),
                Collections.singletonList(new NntpAttachment("big.bin", 0644, source)), done);
        wire.tap = decoded;
        server("340 send it\r\n");
        wire.drain();
        assertTrue("the article was ended", decoded.terminated);
        server("240 received\r\n");
        assertTrue(done.done);
        assertNull(done.error);
        assertEquals(size, source.produced);
        assertEquals(size, decoded.bytes);
        assertEquals(source.crc.getValue(), decoded.crc.getValue());
        assertEquals(java.util.Arrays.asList("644 big.bin"), decoded.files);
        assertTrue(decoded.allComplete);
        assertTrue(source.closed);
        // never more than one write waited on the connection, whatever the size of the file
        assertTrue("most outstanding at once: " + wire.mostSinceReady, wire.mostSinceReady <= ONE_WRITE);
        // the wire carried the file uuencoded: 62 characters per 45 bytes
        assertTrue(wire.total > size * 62 / 45);
    }

    @Test
    public void testSeveralLargeAttachmentsOneAfterAnother() {
        Generated first = new Generated(5L * 1024 * 1024);
        Generated second = new Generated(3L * 1024 * 1024 + 1);
        Decoding decoded = new Decoding();
        Outcome done = new Outcome();
        wire.deferReady = true;
        List<NntpAttachment> list = new ArrayList<NntpAttachment>();
        list.add(new NntpAttachment("one.bin", 0644, first));
        list.add(new NntpAttachment("two.bin", 0755, second));
        events.session.postWithAttachments(headers(), list, done);
        wire.tap = decoded;
        server("340 go\r\n");
        wire.drain();
        server("240 ok\r\n");
        assertNull(done.error);
        assertEquals(java.util.Arrays.asList("644 one.bin", "755 two.bin"), decoded.files);
        assertEquals(first.produced + second.produced, decoded.bytes);
        assertTrue(first.closed && second.closed);
        assertTrue(wire.mostSinceReady <= ONE_WRITE);
    }

    @Test
    public void testPostFromMemoryIsWrittenInSlices() {
        byte[] article = new byte[1024 * 1024];
        Random r = new Random(3);
        for (int i = 0; i < article.length; i++) {
            article[i] = (byte) ('a' + r.nextInt(26));
            if (i % 70 == 69) {
                article[i] = '\n';
            }
        }
        System.arraycopy(headers(), 0, article, 0, HEADERS.length());
        Outcome done = new Outcome();
        wire.deferReady = true;
        events.session.post(article, done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        wire.drain();
        server("240 ok\r\n");
        assertTrue(done.done);
        assertNull(done.error);
        assertTrue("most outstanding at once: " + wire.mostSinceReady, wire.mostSinceReady <= ONE_WRITE);
        assertTrue(wire.total > article.length); // a CRLF for every LF, and the terminator
    }

    @Test
    public void testFileAttachmentIsReadAsynchronously() throws Exception {
        Path file = Files.createTempFile("nntp-upload", ".bin");
        try {
            byte[] content = new byte[3 * 1024 * 1024 + 17];
            new Random(9).nextBytes(content);
            Files.write(file, content);
            CRC32 expected = new CRC32();
            expected.update(content, 0, content.length);

            Decoding decoded = new Decoding();
            Outcome done = new Outcome();
            events.session.postWithAttachments(headers(),
                    Collections.singletonList(new NntpAttachment("photo.jpg", 0600, file)), done);
            wire.tap = decoded;
            server("340 go\r\n"); // the file is read off the event loop; its reads complete on other threads
            assertTrue("the article was ended", decoded.ended.await(30, TimeUnit.SECONDS));
            server("240 ok\r\n");
            assertTrue(done.done);
            assertNull(done.error);
            assertEquals(content.length, decoded.bytes);
            assertEquals(expected.getValue(), decoded.crc.getValue());
            assertEquals(java.util.Arrays.asList("600 photo.jpg"), decoded.files);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void testFileThatDoesNotExistFailsThePost() {
        Outcome done = new Outcome();
        events.session.postWithAttachments(headers(), Collections.singletonList(
                new NntpAttachment("gone.bin", 0644, java.nio.file.Paths.get("/no/such/file"))), done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        assertNotNull(done.error);
        assertTrue(done.error.getMessage(), done.error.getMessage().contains("gone.bin"));
        assertFalse(wire.open);
    }

    @Test
    public void testSourceThatFailsFailsThePostAndIsClosed() {
        Generated source = new Generated(10L * 1024 * 1024);
        source.failAfter = 1024 * 1024;
        Outcome done = new Outcome();
        events.session.postWithAttachments(headers(),
                Collections.singletonList(new NntpAttachment("big.bin", 0644, source)), done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        assertTrue(done.done);
        assertNotNull(done.error);
        assertTrue(done.error.getMessage(), done.error.getMessage().contains("big.bin"));
        assertTrue(source.closed);
        assertFalse(wire.open);
        assertTrue("it stopped reading", source.produced <= 2 * 1024 * 1024);
    }

    @Test
    public void testServerThatAnswersEarlyStopsTheReading() {
        Generated source = new Generated(64L * 1024 * 1024);
        Outcome done = new Outcome();
        wire.deferReady = true;
        events.session.postWithAttachments(headers(),
                Collections.singletonList(new NntpAttachment("big.bin", 0644, source)), done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        // a few chunks go, then the server gives up on us
        for (int i = 0; i < 5; i++) {
            Runnable r = wire.pendingReady;
            wire.pendingReady = null;
            r.run();
        }
        server("400 timeout\r\n");
        wire.drain();
        assertTrue(done.done);
        assertEquals(400, done.error.getCode());
        assertTrue(source.closed);
        assertTrue("read only " + source.produced, source.produced < 1024 * 1024);
    }

    // ── the stream itself ──

    /** Writes the pieces it is given and finishes. */
    private static final class Pieces implements NntpPoster {
        final List<byte[]> pieces;
        int next;

        Pieces(List<byte[]> pieces) {
            this.pieces = pieces;
        }

        @Override public void ready(final NntpPostStream stream) {
            final Runnable[] step = new Runnable[1];
            step[0] = new Runnable() {
                @Override public void run() {
                    if (next == pieces.size()) {
                        stream.finish();
                    } else {
                        stream.write(ByteBuffer.wrap(pieces.get(next++)), step[0]);
                    }
                }
            };
            step[0].run();
        }
    }

    /** The article as it should be on the wire, worked out a line at a time from the whole. */
    private static String reference(String article) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int n = article.length();
        for (int i = 0; i < n; i++) {
            char c = article.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && article.charAt(i + 1) == '\n') {
                    i++;
                }
                out.append(line.length() > 0 && line.charAt(0) == '.' ? "." : "").append(line).append("\r\n");
                line.setLength(0);
            } else {
                line.append(c);
            }
        }
        if (line.length() > 0) {
            out.append(line.charAt(0) == '.' ? "." : "").append(line).append("\r\n");
        }
        return out.append(".\r\n").toString();
    }

    private String sendInPieces(List<byte[]> pieces) {
        setUp();
        final StringBuilder sent = new StringBuilder();
        Outcome done = new Outcome();
        events.session.post(new Pieces(pieces), done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                sent.append(new String(b, StandardCharsets.ISO_8859_1));
            }
        };
        server("340 go\r\n");
        server("240 ok\r\n");
        assertTrue(done.done);
        return sent.toString();
    }

    @Test
    public void testEncodingDoesNotDependOnHowTheArticleIsSplit() {
        Random r = new Random(77);
        char[] alphabet = {'a', 'b', '.', '.', '\r', '\n', '\n', ' '};
        for (int n = 0; n < 1500; n++) {
            StringBuilder article = new StringBuilder();
            int len = r.nextInt(60);
            for (int i = 0; i < len; i++) {
                article.append(alphabet[r.nextInt(alphabet.length)]);
            }
            byte[] bytes = article.toString().getBytes(StandardCharsets.ISO_8859_1);
            List<byte[]> pieces = new ArrayList<byte[]>();
            for (int p = 0; p < bytes.length; ) {
                int k = Math.min(1 + r.nextInt(5), bytes.length - p);
                byte[] piece = new byte[k];
                System.arraycopy(bytes, p, piece, 0, k);
                pieces.add(piece);
                p += k;
            }
            assertEquals("article " + article.toString().replace("\r", "\\r").replace("\n", "\\n"),
                    reference(article.toString()), sendInPieces(pieces));
        }
    }

    @Test
    public void testSecondWriteBeforeTheFirstIsTakenIsRefused() {
        wire.deferReady = true;
        final boolean[] refused = {false};
        events.session.post(new NntpPoster() {
            @Override public void ready(NntpPostStream stream) {
                stream.write(ByteBuffer.wrap(headers()), null);
                try {
                    stream.write(ByteBuffer.wrap(new byte[] {'x'}), null);
                } catch (IllegalStateException expected) {
                    refused[0] = true;
                }
            }
        }, new Outcome());
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        assertTrue(refused[0]);
    }

    @Test
    public void testPosterThatThrowsFailsThePost() {
        Outcome done = new Outcome();
        events.session.post(new NntpPoster() {
            @Override public void ready(NntpPostStream stream) {
                throw new IllegalStateException("application bug");
            }
        }, done);
        server("340 go\r\n");
        assertNotNull(done.error);
        assertFalse(wire.open);
    }

    @Test
    public void testPosterCanAbort() {
        Outcome done = new Outcome();
        events.session.post(new NntpPoster() {
            @Override public void ready(NntpPostStream stream) {
                stream.abort("changed my mind");
                assertFalse(stream.isOpen());
            }
        }, done);
        server("340 go\r\n");
        assertTrue(done.error.getMessage(), done.error.getMessage().contains("changed my mind"));
    }

    @Test
    public void testWritesAfterTheEndAreDropped() {
        final boolean[] called = {false};
        Outcome done = new Outcome();
        events.session.post(new NntpPoster() {
            @Override public void ready(NntpPostStream stream) {
                stream.finish();
                stream.write(ByteBuffer.wrap(new byte[] {'x'}), new Runnable() {
                    @Override public void run() {
                        called[0] = true;
                    }
                });
                stream.finish();
            }
        }, done);
        wire.tap = new Tap() {
            @Override public void bytes(ByteBuffer data) {
            }
        };
        server("340 go\r\n");
        assertTrue(called[0]);
        assertEquals(".\r\n".length(), wire.total);
    }
}
