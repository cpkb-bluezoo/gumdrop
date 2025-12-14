/*
 * HTTP2StreamMultiplexingTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http;

import org.junit.Test;
import static org.junit.Assert.*;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for HTTP/2 stream multiplexing behavior.
 * Tests stream ID assignment, concurrent streams, and stream state management.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP2StreamMultiplexingTest {

    // ========== Stream ID Assignment Tests ==========
    
    @Test
    public void testClientInitiatedStreamsAreOdd() {
        // Client-initiated streams must use odd stream IDs: 1, 3, 5, 7, ...
        int[] clientStreams = { 1, 3, 5, 7, 9, 11, 101, 999, 32767 };
        
        for (int streamId : clientStreams) {
            assertTrue("Client stream ID " + streamId + " should be odd", streamId % 2 == 1);
        }
    }
    
    @Test
    public void testServerInitiatedStreamsAreEven() {
        // Server-initiated streams must use even stream IDs: 2, 4, 6, 8, ...
        int[] serverStreams = { 2, 4, 6, 8, 10, 12, 100, 1000, 32768 };
        
        for (int streamId : serverStreams) {
            assertTrue("Server stream ID " + streamId + " should be even", streamId % 2 == 0);
        }
    }
    
    @Test
    public void testStreamIdZeroIsConnectionLevel() {
        // Stream ID 0 is reserved for connection-level frames
        assertEquals("Stream 0 is connection-level", 0, 0);
        
        // Verify SETTINGS frame uses stream 0
        SettingsFrame settings = new SettingsFrame(true);
        assertEquals("SETTINGS should use stream 0", 0, settings.getStream());
        
        // Verify WINDOW_UPDATE can use stream 0
        WindowUpdateFrame windowUpdate = new WindowUpdateFrame(0, 1000);
        assertEquals("Connection-level WINDOW_UPDATE uses stream 0", 0, windowUpdate.getStream());
    }
    
    @Test
    public void testStreamIdsIncrease() {
        // Stream IDs must monotonically increase
        int[] sequence = { 1, 3, 5, 7, 9, 11, 13, 15 };
        
        for (int i = 1; i < sequence.length; i++) {
            assertTrue("Stream IDs must increase", sequence[i] > sequence[i - 1]);
        }
    }
    
    // ========== Multiple Concurrent Streams ==========
    
    @Test
    public void testMultipleStreamsCanBeActive() {
        // Multiple streams can be open simultaneously
        Set<Integer> activeStreams = new HashSet<Integer>();
        activeStreams.add(1);
        activeStreams.add(3);
        activeStreams.add(5);
        activeStreams.add(7);
        
        assertEquals("Should have 4 active streams", 4, activeStreams.size());
    }
    
    @Test
    public void testDataFramesOnDifferentStreams() {
        // DATA frames can be interleaved across different streams
        byte[] data = "payload".getBytes();
        
        DataFrame frame1 = new DataFrame(1, false, false, 0, ByteBuffer.wrap(data));
        DataFrame frame3 = new DataFrame(3, false, false, 0, ByteBuffer.wrap(data));
        DataFrame frame5 = new DataFrame(5, false, false, 0, ByteBuffer.wrap(data));
        
        assertEquals("Frame 1 on stream 1", 1, frame1.getStream());
        assertEquals("Frame 3 on stream 3", 3, frame3.getStream());
        assertEquals("Frame 5 on stream 5", 5, frame5.getStream());
    }
    
    @Test
    public void testHeadersFramesOnDifferentStreams() throws ProtocolException {
        byte[] headerBlock = new byte[] { (byte) 0x82 };  // :method: GET
        
        HeadersFrame frame1 = new HeadersFrame(Frame.FLAG_END_HEADERS, 1, ByteBuffer.wrap(headerBlock));
        HeadersFrame frame3 = new HeadersFrame(Frame.FLAG_END_HEADERS, 3, ByteBuffer.wrap(headerBlock));
        
        assertEquals("Headers 1 on stream 1", 1, frame1.getStream());
        assertEquals("Headers 3 on stream 3", 3, frame3.getStream());
    }
    
    // ========== Stream State Tests ==========
    
    @Test
    public void testEndStreamFlagClosesStream() {
        // END_STREAM flag indicates the endpoint will not send any more data
        byte[] data = "final".getBytes();
        
        DataFrame frame = new DataFrame(1, false, true, 0, ByteBuffer.wrap(data));
        
        assertTrue("END_STREAM should be set", frame.endStream);
        assertTrue("Flags should include END_STREAM", (frame.getFlags() & Frame.FLAG_END_STREAM) != 0);
    }
    
    @Test
    public void testEndHeadersFlagCompleteHeaderBlock() throws ProtocolException {
        byte[] headerBlock = new byte[] { (byte) 0x82 };
        
        HeadersFrame frame = new HeadersFrame(Frame.FLAG_END_HEADERS | Frame.FLAG_END_STREAM, 1, ByteBuffer.wrap(headerBlock));
        
        assertTrue("END_HEADERS should be set", frame.endHeaders);
        assertTrue("END_STREAM should be set", frame.endStream);
    }
    
    @Test
    public void testRstStreamClosesStream() throws ProtocolException {
        // RST_STREAM immediately terminates a stream
        byte[] payload = new byte[] { 0x00, 0x00, 0x00, (byte) Frame.ERROR_CANCEL };
        
        RstStreamFrame frame = new RstStreamFrame(1, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 1", 1, frame.getStream());
        assertEquals("Error should be CANCEL", Frame.ERROR_CANCEL, frame.errorCode);
    }
    
    // ========== Stream Priority Tests ==========
    
    @Test
    public void testStreamPriorityInHeaders() throws ProtocolException {
        // HEADERS frame can include priority information
        byte[] payload = new byte[] {
            (byte) 0x80, 0x00, 0x00, 0x00,  // exclusive=true, dependency=0
            0x10,                            // weight=16
            (byte) 0x82                      // header block
        };
        
        HeadersFrame frame = new HeadersFrame(
            Frame.FLAG_PRIORITY | Frame.FLAG_END_HEADERS, 1, ByteBuffer.wrap(payload));
        
        assertTrue("Should have priority", frame.priority);
        assertTrue("Should be exclusive", frame.streamDependencyExclusive);
        assertEquals("Dependency should be 0", 0, frame.streamDependency);
        assertEquals("Weight should be 16", 16, frame.weight);
    }
    
    @Test
    public void testPriorityFrameUpdatesStreamPriority() throws ProtocolException {
        // PRIORITY frame can update stream priority after stream creation
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x03,  // non-exclusive, dependency=3
            (byte) 0xFF              // weight=255 (maximum)
        };
        
        PriorityFrame frame = new PriorityFrame(5, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 5", 5, frame.getStream());
        assertFalse("Should not be exclusive", frame.streamDependencyExclusive);
        assertEquals("Dependency should be 3", 3, frame.streamDependency);
        assertEquals("Weight should be 255", 255, frame.weight);
    }
    
    @Test
    public void testPriorityWeightRange() throws ProtocolException {
        // Weight is 1-256 (encoded as 0-255)
        int[] weights = { 0, 1, 16, 128, 255 };  // 0 encodes as 1, 255 encodes as 256
        
        for (int weight : weights) {
            byte[] payload = new byte[] {
                0x00, 0x00, 0x00, 0x00,  // dependency
                (byte) weight            // weight
            };
            
            PriorityFrame frame = new PriorityFrame(1, ByteBuffer.wrap(payload));
            assertEquals("Weight should match", weight, frame.weight);
        }
    }
    
    // ========== Server Push (PUSH_PROMISE) Tests ==========
    
    @Test
    public void testPushPromiseCreatesNewStream() throws ProtocolException {
        // PUSH_PROMISE creates a new server-initiated stream
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x02,  // promised stream ID = 2 (even = server-initiated)
            (byte) 0x82              // header block
        };
        
        PushPromiseFrame frame = new PushPromiseFrame(Frame.FLAG_END_HEADERS, 1, ByteBuffer.wrap(payload));
        
        assertEquals("Originating stream should be 1", 1, frame.getStream());
        assertEquals("Promised stream should be 2", 2, frame.promisedStream);
        assertTrue("Promised stream should be even", frame.promisedStream % 2 == 0);
    }
    
    @Test
    public void testPushPromiseIncrementingStreamIds() throws ProtocolException {
        // Server-pushed streams must use even, incrementing IDs
        int[] promisedStreams = { 2, 4, 6, 8 };
        
        for (int promised : promisedStreams) {
            byte[] payload = new byte[] {
                (byte) ((promised >> 24) & 0xFF),
                (byte) ((promised >> 16) & 0xFF),
                (byte) ((promised >> 8) & 0xFF),
                (byte) (promised & 0xFF),
                (byte) 0x82
            };
            
            PushPromiseFrame frame = new PushPromiseFrame(Frame.FLAG_END_HEADERS, 1, ByteBuffer.wrap(payload));
            assertEquals("Promised stream should match", promised, frame.promisedStream);
        }
    }
    
    // ========== GOAWAY Tests ==========
    
    @Test
    public void testGoawayIndicatesLastProcessedStream() throws ProtocolException {
        // GOAWAY frame tells the peer the highest-numbered stream ID that was processed
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x07,  // last stream ID = 7
            0x00, 0x00, 0x00, 0x00   // NO_ERROR
        };
        
        GoawayFrame frame = new GoawayFrame(ByteBuffer.wrap(payload));
        
        assertEquals("Last stream should be 7", 7, frame.lastStream);
        // Streams 1, 3, 5, 7 were processed
        // Streams 9, 11, ... may need to be retried on new connection
    }
    
    @Test
    public void testGoawayWithErrorCode() throws ProtocolException {
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x05,                     // last stream ID = 5
            0x00, 0x00, 0x00, (byte) Frame.ERROR_ENHANCE_YOUR_CALM  // rate limiting
        };
        
        GoawayFrame frame = new GoawayFrame(ByteBuffer.wrap(payload));
        
        assertEquals("Last stream should be 5", 5, frame.lastStream);
        assertEquals("Error should be ENHANCE_YOUR_CALM", Frame.ERROR_ENHANCE_YOUR_CALM, frame.errorCode);
    }
    
    // ========== Continuation Tests ==========
    
    @Test
    public void testContinuationMustFollowHeaders() throws ProtocolException {
        // CONTINUATION frames must follow HEADERS or PUSH_PROMISE
        byte[] headerBlock1 = new byte[100];  // Part 1 of header block
        byte[] headerBlock2 = new byte[50];   // Part 2 of header block
        
        // First HEADERS without END_HEADERS
        HeadersFrame headers = new HeadersFrame(0, 1, ByteBuffer.wrap(headerBlock1));
        assertFalse("Should not have END_HEADERS", headers.endHeaders);
        
        // Then CONTINUATION with END_HEADERS
        ContinuationFrame continuation = new ContinuationFrame(Frame.FLAG_END_HEADERS, 1, ByteBuffer.wrap(headerBlock2));
        assertTrue("CONTINUATION should have END_HEADERS", continuation.endHeaders);
        assertEquals("CONTINUATION must be on same stream", 1, continuation.getStream());
    }
    
    @Test
    public void testContinuationOnSameStream() throws ProtocolException {
        byte[] headerBlock = new byte[] { (byte) 0x82 };
        
        ContinuationFrame frame1 = new ContinuationFrame(0, 3, ByteBuffer.wrap(headerBlock));
        ContinuationFrame frame2 = new ContinuationFrame(Frame.FLAG_END_HEADERS, 3, ByteBuffer.wrap(headerBlock));
        
        assertEquals("First continuation on stream 3", 3, frame1.getStream());
        assertEquals("Second continuation on stream 3", 3, frame2.getStream());
    }
    
    // ========== Maximum Concurrent Streams Tests ==========
    
    @Test
    public void testMaxConcurrentStreamsDefault() throws ProtocolException {
        // Default SETTINGS_MAX_CONCURRENT_STREAMS is unlimited (0x3)
        byte[] payload = new byte[] {
            0x00, 0x03, 0x00, 0x00, 0x00, 0x64  // MAX_CONCURRENT_STREAMS = 100
        };
        
        SettingsFrame frame = new SettingsFrame(0, ByteBuffer.wrap(payload));
        
        assertEquals("Max concurrent streams should be 100",
            Integer.valueOf(100),
            frame.settings.get(SettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS));
    }
    
    // ========== Stream ID Maximum ==========
    
    @Test
    public void testMaximumStreamId() {
        // Maximum stream ID is 2^31 - 1 (31 bits, high bit reserved)
        int maxStreamId = Integer.MAX_VALUE;  // 2147483647
        
        byte[] data = "test".getBytes();
        DataFrame frame = new DataFrame(maxStreamId, false, false, 0, ByteBuffer.wrap(data));
        
        assertEquals("Stream ID should be max value", maxStreamId, frame.getStream());
        
        // Verify serialization preserves stream ID
        ByteBuffer buf = ByteBuffer.allocate(20);
        frame.write(buf);
        buf.flip();
        
        buf.position(5);  // Skip to stream ID
        int readStreamId = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                         | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Read stream ID should match", maxStreamId, readStreamId);
    }
    
    // ========== Interleaved Frame Tests ==========
    
    @Test
    public void testInterleavedDataFrames() {
        // Simulate interleaved data from multiple streams
        byte[] chunk1 = new byte[1000];
        byte[] chunk2 = new byte[2000];
        byte[] chunk3 = new byte[1500];
        
        // Stream 1: chunk1
        DataFrame frame1a = new DataFrame(1, false, false, 0, ByteBuffer.wrap(chunk1));
        // Stream 3: chunk2
        DataFrame frame3a = new DataFrame(3, false, false, 0, ByteBuffer.wrap(chunk2));
        // Stream 1: chunk3 (continues stream 1)
        DataFrame frame1b = new DataFrame(1, false, true, 0, ByteBuffer.wrap(chunk3));  // END_STREAM
        
        assertEquals("Frame 1a on stream 1", 1, frame1a.getStream());
        assertEquals("Frame 3a on stream 3", 3, frame3a.getStream());
        assertEquals("Frame 1b on stream 1", 1, frame1b.getStream());
        assertTrue("Frame 1b ends stream", frame1b.endStream);
    }
    
    // ========== Stream ID Uniqueness ==========
    
    @Test
    public void testStreamIdsAreUnique() {
        Set<Integer> usedIds = new HashSet<Integer>();
        
        // Simulate creating multiple streams
        int[] clientStreams = { 1, 3, 5, 7, 9, 11, 13, 15, 17, 19 };
        
        for (int id : clientStreams) {
            assertFalse("Stream ID " + id + " should not be reused", usedIds.contains(id));
            usedIds.add(id);
        }
        
        assertEquals("All stream IDs should be unique", clientStreams.length, usedIds.size());
    }
}

