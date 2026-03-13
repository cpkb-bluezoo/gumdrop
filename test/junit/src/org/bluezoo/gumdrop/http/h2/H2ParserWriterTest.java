package org.bluezoo.gumdrop.http.h2;

import org.junit.Test;
import org.junit.Before;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Round-trip tests for {@link H2Writer} and {@link H2Parser}.
 * Writes frames with H2Writer, then parses them with H2Parser
 * using a recording handler to verify correctness.
 */
public class H2ParserWriterTest {

    private ByteArrayOutputStream output;
    private H2Writer writer;
    private RecordingHandler handler;
    private H2Parser parser;

    @Before
    public void setUp() {
        output = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(output);
        writer = new H2Writer(channel);
        handler = new RecordingHandler();
        parser = new H2Parser(handler);
    }

    private void flushAndParse() throws IOException {
        writer.flush();
        ByteBuffer buf = ByteBuffer.wrap(output.toByteArray());
        parser.receive(buf);
    }

    // ========== PING round-trip ==========

    @Test
    public void testPingRoundTrip() throws IOException {
        writer.writePing(0x123456789ABCDEF0L, false);
        flushAndParse();

        assertEquals(1, handler.pings.size());
        RecordingHandler.PingRecord ping = handler.pings.get(0);
        assertFalse(ping.ack);
        assertEquals(0x123456789ABCDEF0L, ping.opaqueData);
    }

    @Test
    public void testPingAckRoundTrip() throws IOException {
        writer.writePing(42L, true);
        flushAndParse();

        assertEquals(1, handler.pings.size());
        assertTrue(handler.pings.get(0).ack);
        assertEquals(42L, handler.pings.get(0).opaqueData);
    }

    // ========== SETTINGS round-trip ==========

    @Test
    public void testSettingsRoundTrip() throws IOException {
        Map<Integer, Integer> settings = new LinkedHashMap<>();
        settings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 100);
        settings.put(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE, 65535);
        settings.put(H2FrameHandler.SETTINGS_MAX_FRAME_SIZE, 16384);

        writer.writeSettings(settings);
        flushAndParse();

        assertEquals(1, handler.settings.size());
        RecordingHandler.SettingsRecord rec = handler.settings.get(0);
        assertFalse(rec.ack);
        assertEquals(Integer.valueOf(100), rec.settings.get(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS));
        assertEquals(Integer.valueOf(65535), rec.settings.get(H2FrameHandler.SETTINGS_INITIAL_WINDOW_SIZE));
        assertEquals(Integer.valueOf(16384), rec.settings.get(H2FrameHandler.SETTINGS_MAX_FRAME_SIZE));
    }

    @Test
    public void testSettingsAckRoundTrip() throws IOException {
        writer.writeSettingsAck();
        flushAndParse();

        assertEquals(1, handler.settings.size());
        assertTrue(handler.settings.get(0).ack);
        assertTrue(handler.settings.get(0).settings.isEmpty());
    }

    // ========== DATA round-trip ==========

    @Test
    public void testDataRoundTrip() throws IOException {
        byte[] payload = "Hello, HTTP/2!".getBytes();
        writer.writeData(1, ByteBuffer.wrap(payload), true);
        flushAndParse();

        assertEquals(1, handler.dataFrames.size());
        RecordingHandler.DataFrame df = handler.dataFrames.get(0);
        assertEquals(1, df.streamId);
        assertTrue(df.endStream);
        byte[] actual = new byte[df.data.remaining()];
        df.data.get(actual);
        assertArrayEquals(payload, actual);
    }

    @Test
    public void testDataNotEndStream() throws IOException {
        writer.writeData(3, ByteBuffer.wrap(new byte[]{1, 2, 3}), false);
        flushAndParse();

        assertEquals(1, handler.dataFrames.size());
        assertFalse(handler.dataFrames.get(0).endStream);
        assertEquals(3, handler.dataFrames.get(0).streamId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDataStreamIdZeroThrows() throws IOException {
        writer.writeData(0, ByteBuffer.wrap(new byte[0]), false);
    }

    // ========== HEADERS round-trip ==========

    @Test
    public void testHeadersRoundTrip() throws IOException {
        byte[] headerBlock = new byte[]{0x01, 0x02, 0x03, 0x04};
        writer.writeHeaders(1, ByteBuffer.wrap(headerBlock), true, true);
        flushAndParse();

        assertEquals(1, handler.headersFrames.size());
        RecordingHandler.HeadersFrame hf = handler.headersFrames.get(0);
        assertEquals(1, hf.streamId);
        assertTrue(hf.endStream);
        assertTrue(hf.endHeaders);
        byte[] actual = new byte[hf.headerBlockFragment.remaining()];
        hf.headerBlockFragment.get(actual);
        assertArrayEquals(headerBlock, actual);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHeadersStreamIdZeroThrows() throws IOException {
        writer.writeHeaders(0, ByteBuffer.wrap(new byte[0]), false, true);
    }

    // ========== RST_STREAM round-trip ==========

    @Test
    public void testRstStreamRoundTrip() throws IOException {
        writer.writeRstStream(5, H2FrameHandler.ERROR_CANCEL);
        flushAndParse();

        assertEquals(1, handler.rstStreams.size());
        assertEquals(5, handler.rstStreams.get(0).streamId);
        assertEquals(H2FrameHandler.ERROR_CANCEL, handler.rstStreams.get(0).errorCode);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRstStreamStreamIdZeroThrows() throws IOException {
        writer.writeRstStream(0, H2FrameHandler.ERROR_NO_ERROR);
    }

    // ========== GOAWAY round-trip ==========

    @Test
    public void testGoawayRoundTrip() throws IOException {
        writer.writeGoaway(7, H2FrameHandler.ERROR_NO_ERROR);
        flushAndParse();

        assertEquals(1, handler.goaways.size());
        assertEquals(7, handler.goaways.get(0).lastStreamId);
        assertEquals(H2FrameHandler.ERROR_NO_ERROR, handler.goaways.get(0).errorCode);
    }

    @Test
    public void testGoawayWithDebugData() throws IOException {
        byte[] debug = "test debug info".getBytes();
        writer.writeGoaway(3, H2FrameHandler.ERROR_INTERNAL_ERROR, ByteBuffer.wrap(debug));
        flushAndParse();

        assertEquals(1, handler.goaways.size());
        RecordingHandler.GoawayRecord g = handler.goaways.get(0);
        assertEquals(3, g.lastStreamId);
        assertEquals(H2FrameHandler.ERROR_INTERNAL_ERROR, g.errorCode);
        byte[] actual = new byte[g.debugData.remaining()];
        g.debugData.get(actual);
        assertArrayEquals(debug, actual);
    }

    // ========== WINDOW_UPDATE round-trip ==========

    @Test
    public void testWindowUpdateRoundTrip() throws IOException {
        writer.writeWindowUpdate(1, 65535);
        flushAndParse();

        assertEquals(1, handler.windowUpdates.size());
        assertEquals(1, handler.windowUpdates.get(0).streamId);
        assertEquals(65535, handler.windowUpdates.get(0).increment);
    }

    @Test
    public void testWindowUpdateConnectionLevel() throws IOException {
        writer.writeWindowUpdate(0, 1000);
        flushAndParse();

        assertEquals(1, handler.windowUpdates.size());
        assertEquals(0, handler.windowUpdates.get(0).streamId);
        assertEquals(1000, handler.windowUpdates.get(0).increment);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWindowUpdateZeroIncrementThrows() throws IOException {
        writer.writeWindowUpdate(1, 0);
    }

    // ========== PRIORITY round-trip ==========

    @Test
    public void testPriorityRoundTrip() throws IOException {
        writer.writePriority(3, 1, 128, false);
        flushAndParse();

        assertEquals(1, handler.priorities.size());
        RecordingHandler.PriorityRecord p = handler.priorities.get(0);
        assertEquals(3, p.streamId);
        assertEquals(1, p.streamDependency);
        assertEquals(128, p.weight);
        assertFalse(p.exclusive);
    }

    @Test
    public void testPriorityExclusive() throws IOException {
        writer.writePriority(5, 1, 256, true);
        flushAndParse();

        RecordingHandler.PriorityRecord p = handler.priorities.get(0);
        assertTrue(p.exclusive);
        assertEquals(256, p.weight);
    }

    // ========== PUSH_PROMISE round-trip ==========

    @Test
    public void testPushPromiseRoundTrip() throws IOException {
        byte[] headerBlock = new byte[]{0x0A, 0x0B, 0x0C};
        writer.writePushPromise(1, 2, ByteBuffer.wrap(headerBlock), true);
        flushAndParse();

        assertEquals(1, handler.pushPromises.size());
        RecordingHandler.PushPromiseRecord pp = handler.pushPromises.get(0);
        assertEquals(1, pp.streamId);
        assertEquals(2, pp.promisedStreamId);
        assertTrue(pp.endHeaders);
    }

    // ========== Multiple frames ==========

    @Test
    public void testMultipleFrames() throws IOException {
        Map<Integer, Integer> settings = new LinkedHashMap<>();
        settings.put(H2FrameHandler.SETTINGS_MAX_CONCURRENT_STREAMS, 50);
        writer.writeSettings(settings);
        writer.writePing(1L, false);
        writer.writeData(1, ByteBuffer.wrap("test".getBytes()), false);
        writer.writeData(1, ByteBuffer.wrap(new byte[0]), true);
        flushAndParse();

        assertEquals(1, handler.settings.size());
        assertEquals(1, handler.pings.size());
        assertEquals(2, handler.dataFrames.size());
        assertTrue(handler.dataFrames.get(1).endStream);
    }

    // ========== Parser validation ==========

    @Test
    public void testParserMaxFrameSize() {
        assertEquals(H2Parser.DEFAULT_MAX_FRAME_SIZE, parser.getMaxFrameSize());
        parser.setMaxFrameSize(32768);
        assertEquals(32768, parser.getMaxFrameSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParserMaxFrameSizeTooSmall() {
        parser.setMaxFrameSize(1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParserMaxFrameSizeTooLarge() {
        parser.setMaxFrameSize(H2Parser.MAX_MAX_FRAME_SIZE + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParserNullHandler() {
        new H2Parser(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriterNullChannel() {
        new H2Writer(null);
    }

    @Test
    public void testParserConstants() {
        assertEquals(9, H2Parser.FRAME_HEADER_LENGTH);
        assertEquals(16384, H2Parser.DEFAULT_MAX_FRAME_SIZE);
        assertEquals(16384, H2Parser.MIN_MAX_FRAME_SIZE);
        assertEquals(16777215, H2Parser.MAX_MAX_FRAME_SIZE);
    }

    @Test
    public void testParserPartialFrame() {
        ByteBuffer partial = ByteBuffer.allocate(5);
        partial.put(new byte[]{0, 0, 0, 0, 0});
        partial.flip();

        parser.receive(partial);
        assertEquals(0, handler.totalFrames());
    }

    // ========== Recording handler ==========

    static class RecordingHandler implements H2FrameHandler {
        List<DataFrame> dataFrames = new ArrayList<>();
        List<HeadersFrame> headersFrames = new ArrayList<>();
        List<PriorityRecord> priorities = new ArrayList<>();
        List<RstStreamRecord> rstStreams = new ArrayList<>();
        List<SettingsRecord> settings = new ArrayList<>();
        List<PushPromiseRecord> pushPromises = new ArrayList<>();
        List<PingRecord> pings = new ArrayList<>();
        List<GoawayRecord> goaways = new ArrayList<>();
        List<WindowUpdateRecord> windowUpdates = new ArrayList<>();
        List<ContinuationRecord> continuations = new ArrayList<>();
        List<ErrorRecord> errors = new ArrayList<>();

        int totalFrames() {
            return dataFrames.size() + headersFrames.size() + priorities.size() +
                   rstStreams.size() + settings.size() + pushPromises.size() +
                   pings.size() + goaways.size() + windowUpdates.size() +
                   continuations.size();
        }

        static class DataFrame { int streamId; boolean endStream; ByteBuffer data; }
        static class HeadersFrame { int streamId; boolean endStream; boolean endHeaders; int streamDependency; boolean exclusive; int weight; ByteBuffer headerBlockFragment; }
        static class PriorityRecord { int streamId; int streamDependency; boolean exclusive; int weight; }
        static class RstStreamRecord { int streamId; int errorCode; }
        static class SettingsRecord { boolean ack; Map<Integer, Integer> settings; }
        static class PushPromiseRecord { int streamId; int promisedStreamId; boolean endHeaders; ByteBuffer headerBlockFragment; }
        static class PingRecord { boolean ack; long opaqueData; }
        static class GoawayRecord { int lastStreamId; int errorCode; ByteBuffer debugData; }
        static class WindowUpdateRecord { int streamId; int increment; }
        static class ContinuationRecord { int streamId; boolean endHeaders; ByteBuffer headerBlockFragment; }
        static class ErrorRecord { int errorCode; int streamId; String message; }

        private ByteBuffer copy(ByteBuffer src) {
            ByteBuffer copy = ByteBuffer.allocate(src.remaining());
            copy.put(src);
            copy.flip();
            return copy;
        }

        public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) {
            DataFrame f = new DataFrame(); f.streamId = streamId; f.endStream = endStream; f.data = copy(data); dataFrames.add(f);
        }
        public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders, int streamDependency, boolean exclusive, int weight, ByteBuffer headerBlockFragment) {
            HeadersFrame f = new HeadersFrame(); f.streamId = streamId; f.endStream = endStream; f.endHeaders = endHeaders; f.streamDependency = streamDependency; f.exclusive = exclusive; f.weight = weight; f.headerBlockFragment = copy(headerBlockFragment); headersFrames.add(f);
        }
        public void priorityFrameReceived(int streamId, int streamDependency, boolean exclusive, int weight) {
            PriorityRecord r = new PriorityRecord(); r.streamId = streamId; r.streamDependency = streamDependency; r.exclusive = exclusive; r.weight = weight; priorities.add(r);
        }
        public void rstStreamFrameReceived(int streamId, int errorCode) {
            RstStreamRecord r = new RstStreamRecord(); r.streamId = streamId; r.errorCode = errorCode; rstStreams.add(r);
        }
        public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) {
            SettingsRecord r = new SettingsRecord(); r.ack = ack; r.settings = new LinkedHashMap<>(settings); this.settings.add(r);
        }
        public void pushPromiseFrameReceived(int streamId, int promisedStreamId, boolean endHeaders, ByteBuffer headerBlockFragment) {
            PushPromiseRecord r = new PushPromiseRecord(); r.streamId = streamId; r.promisedStreamId = promisedStreamId; r.endHeaders = endHeaders; r.headerBlockFragment = copy(headerBlockFragment); pushPromises.add(r);
        }
        public void pingFrameReceived(boolean ack, long opaqueData) {
            PingRecord r = new PingRecord(); r.ack = ack; r.opaqueData = opaqueData; pings.add(r);
        }
        public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) {
            GoawayRecord r = new GoawayRecord(); r.lastStreamId = lastStreamId; r.errorCode = errorCode; r.debugData = copy(debugData); goaways.add(r);
        }
        public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) {
            WindowUpdateRecord r = new WindowUpdateRecord(); r.streamId = streamId; r.increment = windowSizeIncrement; windowUpdates.add(r);
        }
        public void continuationFrameReceived(int streamId, boolean endHeaders, ByteBuffer headerBlockFragment) {
            ContinuationRecord r = new ContinuationRecord(); r.streamId = streamId; r.endHeaders = endHeaders; r.headerBlockFragment = copy(headerBlockFragment); continuations.add(r);
        }
        public void frameError(int errorCode, int streamId, String message) {
            ErrorRecord r = new ErrorRecord(); r.errorCode = errorCode; r.streamId = streamId; r.message = message; errors.add(r);
        }
    }
}
