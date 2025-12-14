/*
 * HTTP2FrameTest.java
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

/**
 * Unit tests for HTTP/2 frame parsing and serialization.
 * Tests all frame types defined in RFC 7540.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP2FrameTest {

    // ========== DATA Frame Tests ==========
    
    @Test
    public void testDataFrameParsing() {
        // DATA frame without padding: flags=0x00, stream=1, data="Hello"
        byte[] payload = "Hello".getBytes();
        int flags = 0;
        int stream = 1;
        
        DataFrame frame = new DataFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream ID should match", 1, frame.getStream());
        assertEquals("Data should match", "Hello", bufferToString(frame.data));
        assertFalse("Should not be padded", frame.padded);
        assertFalse("Should not be end of stream", frame.endStream);
        assertEquals("Type should be DATA", Frame.TYPE_DATA, frame.getType());
    }
    
    @Test
    public void testDataFrameWithPadding() {
        // DATA frame with padding: flags=0x08 (PADDED), padLength=3, data="Hi", padding=0x00,0x00,0x00
        byte[] payload = new byte[] { 
            0x03,  // pad length
            'H', 'i',  // data
            0x00, 0x00, 0x00  // padding
        };
        int flags = Frame.FLAG_PADDED;
        int stream = 1;
        
        DataFrame frame = new DataFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertTrue("Should be padded", frame.padded);
        assertEquals("Pad length should be 3", 3, frame.padLength);
        assertEquals("Data should be 'Hi'", "Hi", bufferToString(frame.data));
    }
    
    @Test
    public void testDataFrameWithEndStream() {
        byte[] payload = "Final".getBytes();
        int flags = Frame.FLAG_END_STREAM;
        int stream = 1;
        
        DataFrame frame = new DataFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertTrue("Should be end of stream", frame.endStream);
    }
    
    @Test
    public void testDataFrameSerialization() {
        byte[] data = "Test data".getBytes();
        DataFrame frame = new DataFrame(1, false, true, 0, ByteBuffer.wrap(data));
        
        ByteBuffer buf = ByteBuffer.allocate(100);
        frame.write(buf);
        buf.flip();
        
        // Verify header
        assertEquals("Length high byte", 0, buf.get() & 0xFF);
        assertEquals("Length mid byte", 0, buf.get() & 0xFF);
        assertEquals("Length low byte", data.length, buf.get() & 0xFF);
        assertEquals("Type should be DATA", Frame.TYPE_DATA, buf.get() & 0xFF);
        assertEquals("Flags should be END_STREAM", Frame.FLAG_END_STREAM, buf.get() & 0xFF);
        
        // Stream ID (4 bytes, first bit reserved)
        assertEquals("Stream ID byte 1", 0, buf.get() & 0x7F);
        assertEquals("Stream ID byte 2", 0, buf.get() & 0xFF);
        assertEquals("Stream ID byte 3", 0, buf.get() & 0xFF);
        assertEquals("Stream ID byte 4", 1, buf.get() & 0xFF);
        
        // Verify payload
        byte[] readData = new byte[data.length];
        buf.get(readData);
        assertArrayEquals("Data should match", data, readData);
    }
    
    @Test
    public void testDataFrameWithPaddingSerialization() {
        byte[] data = "Padded".getBytes();
        int padLength = 5;
        DataFrame frame = new DataFrame(1, true, false, padLength, ByteBuffer.wrap(data));
        
        ByteBuffer buf = ByteBuffer.allocate(100);
        frame.write(buf);
        buf.flip();
        
        // Verify length includes padding
        int expectedLength = 1 + data.length + padLength; // padLength byte + data + padding
        assertEquals("Length should include padding", expectedLength, 
            ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF));
        
        assertEquals("Type should be DATA", Frame.TYPE_DATA, buf.get() & 0xFF);
        assertTrue("Flags should include PADDED", (buf.get() & Frame.FLAG_PADDED) != 0);
    }
    
    // ========== HEADERS Frame Tests ==========
    
    @Test
    public void testHeadersFrameParsing() throws ProtocolException {
        // Simple HEADERS frame without padding or priority
        byte[] headerBlock = new byte[] { (byte) 0x82 }; // indexed header :method: GET
        int flags = Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        HeadersFrame frame = new HeadersFrame(flags, stream, ByteBuffer.wrap(headerBlock));
        
        assertEquals("Stream ID should match", 1, frame.getStream());
        assertFalse("Should not be padded", frame.padded);
        assertFalse("Should not have priority", frame.priority);
        assertTrue("Should have END_HEADERS", frame.endHeaders);
        assertArrayEquals("Header block should match", headerBlock, bufferToBytes(frame.headerBlockFragment));
    }
    
    @Test
    public void testHeadersFrameWithPriority() throws ProtocolException {
        // HEADERS frame with priority: exclusive=true, dependency=0, weight=16
        byte[] payload = new byte[] {
            (byte) 0x80, 0x00, 0x00, 0x00,  // exclusive + stream dependency (0)
            0x10,  // weight (16)
            (byte) 0x82  // header block fragment
        };
        int flags = Frame.FLAG_PRIORITY | Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        HeadersFrame frame = new HeadersFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertTrue("Should have priority", frame.priority);
        assertTrue("Should be exclusive", frame.streamDependencyExclusive);
        assertEquals("Stream dependency should be 0", 0, frame.streamDependency);
        assertEquals("Weight should be 16", 16, frame.weight);
        assertEquals("Header block should be 1 byte", 1, frame.headerBlockFragment.remaining());
    }
    
    @Test
    public void testHeadersFrameWithPadding() throws ProtocolException {
        byte[] payload = new byte[] {
            0x02,  // pad length
            (byte) 0x82,  // header block
            0x00, 0x00  // padding
        };
        int flags = Frame.FLAG_PADDED | Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        HeadersFrame frame = new HeadersFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertTrue("Should be padded", frame.padded);
        assertEquals("Pad length should be 2", 2, frame.padLength);
        assertEquals("Header block should be 1 byte", 1, frame.headerBlockFragment.remaining());
    }
    
    @Test
    public void testHeadersFrameSerialization() {
        byte[] headerBlock = new byte[] { (byte) 0x82 };
        HeadersFrame frame = new HeadersFrame(1, false, true, true, false, 0, 0, false, 0, headerBlock);
        
        ByteBuffer buf = ByteBuffer.allocate(100);
        frame.write(buf);
        buf.flip();
        
        // Skip to flags
        buf.position(4);
        int flags = buf.get() & 0xFF;
        assertTrue("Should have END_STREAM", (flags & Frame.FLAG_END_STREAM) != 0);
        assertTrue("Should have END_HEADERS", (flags & Frame.FLAG_END_HEADERS) != 0);
        assertFalse("Should not have PADDED", (flags & Frame.FLAG_PADDED) != 0);
        assertFalse("Should not have PRIORITY", (flags & Frame.FLAG_PRIORITY) != 0);
    }
    
    // ========== SETTINGS Frame Tests ==========
    
    @Test
    public void testSettingsFrameParsing() throws ProtocolException {
        // SETTINGS frame with header_table_size=8192 and max_concurrent_streams=100
        byte[] payload = new byte[] {
            0x00, 0x01, 0x00, 0x00, 0x20, 0x00,  // SETTINGS_HEADER_TABLE_SIZE = 8192
            0x00, 0x03, 0x00, 0x00, 0x00, 0x64   // SETTINGS_MAX_CONCURRENT_STREAMS = 100
        };
        int flags = 0;
        
        SettingsFrame frame = new SettingsFrame(flags, ByteBuffer.wrap(payload));
        
        assertFalse("Should not be ACK", frame.ack);
        assertEquals("Should have 2 settings", 2, frame.settings.size());
        assertEquals("Header table size should be 8192", 
            Integer.valueOf(8192), frame.settings.get(SettingsFrame.SETTINGS_HEADER_TABLE_SIZE));
        assertEquals("Max concurrent streams should be 100",
            Integer.valueOf(100), frame.settings.get(SettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS));
    }
    
    @Test
    public void testSettingsFrameAck() throws ProtocolException {
        byte[] payload = new byte[0];
        int flags = Frame.FLAG_ACK;
        
        SettingsFrame frame = new SettingsFrame(flags, ByteBuffer.wrap(payload));
        
        assertTrue("Should be ACK", frame.ack);
        assertTrue("Settings should be empty for ACK", frame.settings.isEmpty());
    }
    
    @Test(expected = ProtocolException.class)
    public void testSettingsFrameInvalidEnablePush() throws ProtocolException {
        // ENABLE_PUSH must be 0 or 1
        byte[] payload = new byte[] {
            0x00, 0x02, 0x00, 0x00, 0x00, 0x02  // SETTINGS_ENABLE_PUSH = 2 (invalid)
        };
        new SettingsFrame(0, ByteBuffer.wrap(payload));
    }
    
    @Test(expected = ProtocolException.class)
    public void testSettingsFrameInvalidMaxFrameSize() throws ProtocolException {
        // MAX_FRAME_SIZE must be >= 16384
        byte[] payload = new byte[] {
            0x00, 0x05, 0x00, 0x00, 0x10, 0x00  // SETTINGS_MAX_FRAME_SIZE = 4096 (too small)
        };
        new SettingsFrame(0, ByteBuffer.wrap(payload));
    }
    
    @Test
    public void testSettingsFrameSerialization() {
        SettingsFrame frame = new SettingsFrame(false);
        frame.set(SettingsFrame.SETTINGS_HEADER_TABLE_SIZE, 4096);
        frame.set(SettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS, 256);
        
        ByteBuffer buf = ByteBuffer.allocate(100);
        frame.write(buf);
        buf.flip();
        
        // Length should be 12 (2 settings * 6 bytes each)
        int length = ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Length should be 12", 12, length);
        assertEquals("Type should be SETTINGS", Frame.TYPE_SETTINGS, buf.get() & 0xFF);
        assertEquals("Flags should be 0", 0, buf.get() & 0xFF);
    }
    
    @Test
    public void testSettingsFrameAckSerialization() {
        SettingsFrame frame = new SettingsFrame(true);
        
        ByteBuffer buf = ByteBuffer.allocate(100);
        frame.write(buf);
        buf.flip();
        
        // ACK frames have empty payload
        int length = ((buf.get() & 0xFF) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("ACK should have length 0", 0, length);
        buf.get(); // type
        assertEquals("Flags should be ACK", Frame.FLAG_ACK, buf.get() & 0xFF);
    }
    
    // ========== WINDOW_UPDATE Frame Tests ==========
    
    @Test
    public void testWindowUpdateFrameParsing() throws ProtocolException {
        // WINDOW_UPDATE with increment of 65535
        byte[] payload = new byte[] {
            0x00, 0x00, (byte) 0xFF, (byte) 0xFF  // increment = 65535
        };
        int stream = 0;  // Connection-level
        
        WindowUpdateFrame frame = new WindowUpdateFrame(stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 0", 0, frame.getStream());
        assertEquals("Window increment should be 65535", 65535, frame.windowSizeIncrement);
    }
    
    @Test
    public void testWindowUpdateFrameStreamLevel() throws ProtocolException {
        byte[] payload = new byte[] {
            0x00, 0x01, 0x00, 0x00  // increment = 65536
        };
        int stream = 5;  // Stream-level
        
        WindowUpdateFrame frame = new WindowUpdateFrame(stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 5", 5, frame.getStream());
        assertEquals("Window increment should be 65536", 65536, frame.windowSizeIncrement);
    }
    
    @Test(expected = ProtocolException.class)
    public void testWindowUpdateFrameZeroIncrement() throws ProtocolException {
        // Zero increment is a protocol error
        byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        new WindowUpdateFrame(0, ByteBuffer.wrap(payload));
    }
    
    @Test
    public void testWindowUpdateFrameSerialization() {
        WindowUpdateFrame frame = new WindowUpdateFrame(1, 32768);
        
        ByteBuffer buf = ByteBuffer.allocate(20);
        frame.write(buf);
        buf.flip();
        
        // Length should be 4
        assertEquals("Length high", 0, buf.get() & 0xFF);
        assertEquals("Length mid", 0, buf.get() & 0xFF);
        assertEquals("Length low", 4, buf.get() & 0xFF);
        assertEquals("Type should be WINDOW_UPDATE", Frame.TYPE_WINDOW_UPDATE, buf.get() & 0xFF);
        assertEquals("Flags should be 0", 0, buf.get() & 0xFF);
        
        // Stream ID
        buf.get(); buf.get(); buf.get();
        assertEquals("Stream should be 1", 1, buf.get() & 0xFF);
        
        // Window increment
        int increment = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                      | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Increment should be 32768", 32768, increment);
    }
    
    // ========== PRIORITY Frame Tests ==========
    
    @Test
    public void testPriorityFrameParsing() throws ProtocolException {
        // PRIORITY frame: exclusive=true, dependency=3, weight=200
        byte[] payload = new byte[] {
            (byte) 0x80, 0x00, 0x00, 0x03,  // exclusive + dependency=3
            (byte) 0xC8  // weight=200
        };
        int stream = 5;
        
        PriorityFrame frame = new PriorityFrame(stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 5", 5, frame.getStream());
        assertTrue("Should be exclusive", frame.streamDependencyExclusive);
        assertEquals("Dependency should be 3", 3, frame.streamDependency);
        assertEquals("Weight should be 200", 200, frame.weight);
    }
    
    @Test
    public void testPriorityFrameNonExclusive() throws ProtocolException {
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x00,  // non-exclusive + dependency=0
            0x10  // weight=16
        };
        int stream = 1;
        
        PriorityFrame frame = new PriorityFrame(stream, ByteBuffer.wrap(payload));
        
        assertFalse("Should not be exclusive", frame.streamDependencyExclusive);
        assertEquals("Dependency should be 0", 0, frame.streamDependency);
        assertEquals("Weight should be 16", 16, frame.weight);
    }
    
    // ========== RST_STREAM Frame Tests ==========
    
    @Test
    public void testRstStreamFrameParsing() throws ProtocolException {
        // RST_STREAM with error code CANCEL
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, (byte) Frame.ERROR_CANCEL
        };
        int stream = 1;
        
        RstStreamFrame frame = new RstStreamFrame(stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 1", 1, frame.getStream());
        assertEquals("Error code should be CANCEL", Frame.ERROR_CANCEL, frame.errorCode);
    }
    
    @Test
    public void testRstStreamFrameAllErrorCodes() throws ProtocolException {
        int[] errorCodes = {
            Frame.ERROR_NO_ERROR,
            Frame.ERROR_PROTOCOL_ERROR,
            Frame.ERROR_INTERNAL_ERROR,
            Frame.ERROR_FLOW_CONTROL_ERROR,
            Frame.ERROR_SETTINGS_TIMEOUT,
            Frame.ERROR_STREAM_CLOSED,
            Frame.ERROR_FRAME_SIZE_ERROR,
            Frame.ERROR_REFUSED_STREAM,
            Frame.ERROR_CANCEL,
            Frame.ERROR_COMPRESSION_ERROR,
            Frame.ERROR_CONNECT_ERROR,
            Frame.ERROR_ENHANCE_YOUR_CALM,
            Frame.ERROR_INADEQUATE_SECURITY,
            Frame.ERROR_HTTP_1_1_REQUIRED
        };
        
        for (int errorCode : errorCodes) {
            byte[] payload = new byte[] {
                (byte) ((errorCode >> 24) & 0xFF),
                (byte) ((errorCode >> 16) & 0xFF),
                (byte) ((errorCode >> 8) & 0xFF),
                (byte) (errorCode & 0xFF)
            };
            
            RstStreamFrame frame = new RstStreamFrame(1, ByteBuffer.wrap(payload));
            assertEquals("Error code should match", errorCode, frame.errorCode);
        }
    }
    
    // ========== PING Frame Tests ==========
    
    @Test
    public void testPingFrameParsing() {
        int flags = 0;
        PingFrame frame = new PingFrame(flags);
        
        assertFalse("Should not be ACK", frame.ack);
        assertEquals("Type should be PING", Frame.TYPE_PING, frame.getType());
        assertEquals("Stream should be 0", 0, frame.getStream());
    }
    
    @Test
    public void testPingFrameAck() {
        int flags = Frame.FLAG_ACK;
        PingFrame frame = new PingFrame(flags);
        
        assertTrue("Should be ACK", frame.ack);
    }
    
    // ========== GOAWAY Frame Tests ==========
    
    @Test
    public void testGoawayFrameParsing() throws ProtocolException {
        // GOAWAY with last_stream_id=7 and error=NO_ERROR
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x07,  // last stream ID = 7
            0x00, 0x00, 0x00, 0x00   // NO_ERROR
        };
        
        GoawayFrame frame = new GoawayFrame(ByteBuffer.wrap(payload));
        
        assertEquals("Last stream ID should be 7", 7, frame.lastStream);
        assertEquals("Error code should be NO_ERROR", Frame.ERROR_NO_ERROR, frame.errorCode);
        assertEquals("Type should be GOAWAY", Frame.TYPE_GOAWAY, frame.getType());
        assertEquals("Stream should be 0", 0, frame.getStream());
    }
    
    @Test
    public void testGoawayFrameWithDebugData() throws ProtocolException {
        // GOAWAY with debug data
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x05,  // last stream ID = 5
            0x00, 0x00, 0x00, 0x02,  // INTERNAL_ERROR
            'T', 'e', 's', 't'       // debug data
        };
        
        GoawayFrame frame = new GoawayFrame(ByteBuffer.wrap(payload));
        
        assertEquals("Last stream ID should be 5", 5, frame.lastStream);
        assertEquals("Error code should be INTERNAL_ERROR", Frame.ERROR_INTERNAL_ERROR, frame.errorCode);
    }
    
    // ========== CONTINUATION Frame Tests ==========
    
    @Test
    public void testContinuationFrameParsing() throws ProtocolException {
        byte[] headerBlock = new byte[] { (byte) 0x82, (byte) 0x84 };
        int flags = Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        ContinuationFrame frame = new ContinuationFrame(flags, stream, ByteBuffer.wrap(headerBlock));
        
        assertEquals("Stream should be 1", 1, frame.getStream());
        assertTrue("Should have END_HEADERS", frame.endHeaders);
        assertArrayEquals("Header block should match", headerBlock, bufferToBytes(frame.headerBlockFragment));
        assertEquals("Type should be CONTINUATION", Frame.TYPE_CONTINUATION, frame.getType());
    }
    
    // ========== PUSH_PROMISE Frame Tests ==========
    
    @Test
    public void testPushPromiseFrameParsing() throws ProtocolException {
        // PUSH_PROMISE with promised stream ID = 2
        byte[] payload = new byte[] {
            0x00, 0x00, 0x00, 0x02,  // promised stream ID = 2
            (byte) 0x82              // header block
        };
        int flags = Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        PushPromiseFrame frame = new PushPromiseFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertEquals("Stream should be 1", 1, frame.getStream());
        assertEquals("Promised stream should be 2", 2, frame.promisedStream);
        assertTrue("Should have END_HEADERS", frame.endHeaders);
    }
    
    @Test
    public void testPushPromiseFrameWithPadding() throws ProtocolException {
        byte[] payload = new byte[] {
            0x02,                    // pad length
            0x00, 0x00, 0x00, 0x04,  // promised stream ID = 4
            (byte) 0x82,             // header block
            0x00, 0x00               // padding
        };
        int flags = Frame.FLAG_PADDED | Frame.FLAG_END_HEADERS;
        int stream = 1;
        
        PushPromiseFrame frame = new PushPromiseFrame(flags, stream, ByteBuffer.wrap(payload));
        
        assertTrue("Should be padded", frame.padded);
        assertEquals("Pad length should be 2", 2, frame.padLength);
        assertEquals("Promised stream should be 4", 4, frame.promisedStream);
    }
    
    // ========== Frame Header Tests ==========
    
    @Test
    public void testFrameHeaderLength() {
        // Test that frame length can be encoded correctly for various sizes
        byte[] smallData = new byte[100];
        DataFrame smallFrame = new DataFrame(1, false, false, 0, ByteBuffer.wrap(smallData));
        assertEquals("Small frame length", 100, smallFrame.getLength());
        
        byte[] mediumData = new byte[16384];  // Max default frame size
        DataFrame mediumFrame = new DataFrame(1, false, false, 0, ByteBuffer.wrap(mediumData));
        assertEquals("Medium frame length", 16384, mediumFrame.getLength());
    }
    
    @Test
    public void testFrameStreamIdEncoding() {
        // Test various stream IDs
        int[] streamIds = { 1, 3, 5, 127, 255, 256, 65535, 16777215 };
        
        for (int streamId : streamIds) {
            byte[] data = "test".getBytes();
            DataFrame frame = new DataFrame(streamId, false, false, 0, ByteBuffer.wrap(data));
            
            ByteBuffer buf = ByteBuffer.allocate(20);
            frame.write(buf);
            buf.flip();
            
            // Skip to stream ID (bytes 5-8)
            buf.position(5);
            int readStreamId = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                             | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
            assertEquals("Stream ID should match", streamId, readStreamId);
        }
    }
    
    // ========== Frame Type String Tests ==========
    
    @Test
    public void testFrameTypeToString() {
        assertEquals("DATA", Frame.typeToString(Frame.TYPE_DATA));
        assertEquals("HEADERS", Frame.typeToString(Frame.TYPE_HEADERS));
        assertEquals("PRIORITY", Frame.typeToString(Frame.TYPE_PRIORITY));
        assertEquals("RST_STREAM", Frame.typeToString(Frame.TYPE_RST_STREAM));
        assertEquals("SETTINGS", Frame.typeToString(Frame.TYPE_SETTINGS));
        assertEquals("PUSH_PROMISE", Frame.typeToString(Frame.TYPE_PUSH_PROMISE));
        assertEquals("PING", Frame.typeToString(Frame.TYPE_PING));
        assertEquals("GOAWAY", Frame.typeToString(Frame.TYPE_GOAWAY));
        assertEquals("WINDOW_UPDATE", Frame.typeToString(Frame.TYPE_WINDOW_UPDATE));
        assertEquals("CONTINUATION", Frame.typeToString(Frame.TYPE_CONTINUATION));
        assertEquals("(unknown type)", Frame.typeToString(99));
    }
    
    @Test
    public void testFlagsToString() {
        // DATA/HEADERS flags
        assertEquals("END_STREAM", Frame.flagsToString(Frame.TYPE_DATA, Frame.FLAG_END_STREAM));
        assertEquals("END_STREAM|END_HEADERS", 
            Frame.flagsToString(Frame.TYPE_HEADERS, Frame.FLAG_END_STREAM | Frame.FLAG_END_HEADERS));
        assertEquals("PADDED", Frame.flagsToString(Frame.TYPE_DATA, Frame.FLAG_PADDED));
        assertEquals("PRIORITY", Frame.flagsToString(Frame.TYPE_HEADERS, Frame.FLAG_PRIORITY));
        
        // SETTINGS/PING ACK flag
        assertEquals("ACK", Frame.flagsToString(Frame.TYPE_SETTINGS, Frame.FLAG_ACK));
        assertEquals("ACK", Frame.flagsToString(Frame.TYPE_PING, Frame.FLAG_ACK));
    }

    // ========== Helper Methods ==========

    private static String bufferToString(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        int pos = buf.position();
        buf.get(bytes);
        buf.position(pos); // restore position
        return new String(bytes);
    }

    private static byte[] bufferToBytes(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        int pos = buf.position();
        buf.get(bytes);
        buf.position(pos); // restore position
        return bytes;
    }
}

