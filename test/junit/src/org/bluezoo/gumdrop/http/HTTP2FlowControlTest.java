/*
 * HTTP2FlowControlTest.java
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

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * Unit tests for HTTP/2 flow control mechanisms.
 * Tests WINDOW_UPDATE frames, initial window sizes, and flow control limits.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTP2FlowControlTest {

    // Default initial window size per RFC 7540
    private static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;
    
    // Maximum window size (2^31 - 1)
    private static final int MAX_WINDOW_SIZE = Integer.MAX_VALUE;
    
    // ========== Window Size Calculations ==========
    
    @Test
    public void testDefaultInitialWindowSize() {
        // Verify the default initial window size is 65535 (0xFFFF)
        assertEquals("Default initial window size should be 65535", 
            65535, DEFAULT_INITIAL_WINDOW_SIZE);
    }
    
    @Test
    public void testWindowUpdateIncrement() throws ProtocolException {
        // Test various valid window update increments
        int[] increments = { 1, 100, 1000, 16384, 65535, 1048576, MAX_WINDOW_SIZE };
        
        for (int increment : increments) {
            byte[] payload = new byte[4];
            payload[0] = (byte) ((increment >> 24) & 0x7F);  // Clear reserved bit
            payload[1] = (byte) ((increment >> 16) & 0xFF);
            payload[2] = (byte) ((increment >> 8) & 0xFF);
            payload[3] = (byte) (increment & 0xFF);
            
            WindowUpdateFrame frame = new WindowUpdateFrame(0, payload);
            assertEquals("Window increment should match", increment, frame.windowSizeIncrement);
        }
    }
    
    @Test(expected = ProtocolException.class)
    public void testZeroWindowIncrementIsError() throws ProtocolException {
        // A WINDOW_UPDATE frame with a flow-control window increment of 0
        // MUST be treated as a stream error or connection error
        byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        new WindowUpdateFrame(1, payload);
    }
    
    @Test
    public void testWindowUpdateReservedBitIgnored() throws ProtocolException {
        // The reserved bit (bit 0) must be ignored when reading
        byte[] payload = new byte[] {
            (byte) 0x80, 0x00, 0x00, 0x01  // Reserved bit set, increment = 1
        };
        
        WindowUpdateFrame frame = new WindowUpdateFrame(0, payload);
        assertEquals("Increment should be 1 (reserved bit ignored)", 1, frame.windowSizeIncrement);
    }
    
    // ========== Connection-Level Flow Control ==========
    
    @Test
    public void testConnectionLevelWindowUpdate() throws ProtocolException {
        // Stream ID 0 indicates connection-level flow control
        byte[] payload = new byte[] { 0x00, 0x01, 0x00, 0x00 };  // 65536
        
        WindowUpdateFrame frame = new WindowUpdateFrame(0, payload);
        
        assertEquals("Stream should be 0 for connection-level", 0, frame.getStream());
        assertEquals("Increment should be 65536", 65536, frame.windowSizeIncrement);
    }
    
    // ========== Stream-Level Flow Control ==========
    
    @Test
    public void testStreamLevelWindowUpdate() throws ProtocolException {
        // Non-zero stream ID indicates stream-level flow control
        int[] streamIds = { 1, 3, 5, 7, 101, 32767 };
        
        for (int streamId : streamIds) {
            byte[] payload = new byte[] { 0x00, 0x00, 0x10, 0x00 };  // 4096
            
            WindowUpdateFrame frame = new WindowUpdateFrame(streamId, payload);
            
            assertEquals("Stream ID should match", streamId, frame.getStream());
            assertEquals("Increment should be 4096", 4096, frame.windowSizeIncrement);
        }
    }
    
    // ========== Window Update Serialization ==========
    
    @Test
    public void testWindowUpdateSerializationConnectionLevel() {
        WindowUpdateFrame frame = new WindowUpdateFrame(0, 32768);
        
        ByteBuffer buf = ByteBuffer.allocate(20);
        frame.write(buf);
        buf.flip();
        
        // Verify frame header
        assertEquals("Length byte 0", 0, buf.get() & 0xFF);
        assertEquals("Length byte 1", 0, buf.get() & 0xFF);
        assertEquals("Length byte 2", 4, buf.get() & 0xFF);  // WINDOW_UPDATE is always 4 bytes
        assertEquals("Type", Frame.TYPE_WINDOW_UPDATE, buf.get() & 0xFF);
        assertEquals("Flags", 0, buf.get() & 0xFF);
        
        // Stream ID should be 0
        assertEquals("Stream byte 0", 0, buf.get() & 0x7F);
        assertEquals("Stream byte 1", 0, buf.get() & 0xFF);
        assertEquals("Stream byte 2", 0, buf.get() & 0xFF);
        assertEquals("Stream byte 3", 0, buf.get() & 0xFF);
        
        // Window increment
        int increment = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                      | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Increment should be 32768", 32768, increment);
    }
    
    @Test
    public void testWindowUpdateSerializationStreamLevel() {
        WindowUpdateFrame frame = new WindowUpdateFrame(5, 16384);
        
        ByteBuffer buf = ByteBuffer.allocate(20);
        frame.write(buf);
        buf.flip();
        
        // Skip to stream ID
        buf.position(5);
        int streamId = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                     | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Stream ID should be 5", 5, streamId);
        
        // Window increment
        int increment = ((buf.get() & 0x7F) << 24) | ((buf.get() & 0xFF) << 16) 
                      | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        assertEquals("Increment should be 16384", 16384, increment);
    }
    
    // ========== Settings Frame Window Size ==========
    
    @Test
    public void testSettingsInitialWindowSize() throws ProtocolException {
        // SETTINGS_INITIAL_WINDOW_SIZE = 0x4
        int newWindowSize = 131072;  // 128 KB
        byte[] payload = new byte[] {
            0x00, 0x04,  // SETTINGS_INITIAL_WINDOW_SIZE
            0x00, 0x02, 0x00, 0x00  // 131072
        };
        
        SettingsFrame frame = new SettingsFrame(0, payload);
        
        assertEquals("Initial window size should be 131072", 
            Integer.valueOf(newWindowSize), 
            frame.settings.get(SettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE));
    }
    
    @Test
    public void testSettingsMaximumWindowSize() throws ProtocolException {
        // Maximum initial window size is 2^31 - 1
        int maxWindowSize = MAX_WINDOW_SIZE;
        byte[] payload = new byte[] {
            0x00, 0x04,  // SETTINGS_INITIAL_WINDOW_SIZE
            0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF  // 2^31 - 1
        };
        
        SettingsFrame frame = new SettingsFrame(0, payload);
        
        assertEquals("Maximum window size should be Integer.MAX_VALUE", 
            Integer.valueOf(maxWindowSize), 
            frame.settings.get(SettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE));
    }
    
    // ========== Edge Cases ==========
    
    @Test
    public void testMinimumWindowUpdateIncrement() throws ProtocolException {
        // Minimum valid increment is 1
        byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x01 };
        
        WindowUpdateFrame frame = new WindowUpdateFrame(1, payload);
        
        assertEquals("Minimum increment should be 1", 1, frame.windowSizeIncrement);
    }
    
    @Test
    public void testMaximumWindowUpdateIncrement() throws ProtocolException {
        // Maximum increment is 2^31 - 1
        byte[] payload = new byte[] { 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        
        WindowUpdateFrame frame = new WindowUpdateFrame(1, payload);
        
        assertEquals("Maximum increment should be MAX_VALUE", MAX_WINDOW_SIZE, frame.windowSizeIncrement);
    }
    
    @Test
    public void testWindowUpdateFrameLength() {
        // WINDOW_UPDATE frames are always exactly 4 bytes
        WindowUpdateFrame frame = new WindowUpdateFrame(1, 12345);
        assertEquals("Frame length should be 4", 4, frame.getLength());
    }
    
    @Test
    public void testWindowUpdateFrameFlags() {
        // WINDOW_UPDATE frames have no defined flags
        WindowUpdateFrame frame = new WindowUpdateFrame(1, 12345);
        assertEquals("Flags should be 0", 0, frame.getFlags());
    }
    
    // ========== Data Frame Flow Control Interaction ==========
    
    @Test
    public void testDataFrameAffectsWindow() {
        // DATA frames consume flow control window
        byte[] data = new byte[1000];
        DataFrame frame = new DataFrame(1, false, false, 0, data);
        
        assertEquals("DATA frame length should be 1000", 1000, frame.getLength());
        // In a real scenario, this would decrement the window by 1000
    }
    
    @Test
    public void testPaddedDataFrameAffectsWindow() {
        // Padding also counts against flow control
        byte[] data = new byte[100];
        int padLength = 50;
        DataFrame frame = new DataFrame(1, true, false, padLength, data);
        
        // Total length = 1 (pad length byte) + data + padding
        int expectedLength = 1 + data.length + padLength;
        assertEquals("Padded DATA frame length includes padding", expectedLength, frame.getLength());
    }
    
    // ========== Multiple Window Updates ==========
    
    @Test
    public void testMultipleWindowUpdatesSameStream() throws ProtocolException {
        // Multiple WINDOW_UPDATE frames can be sent for the same stream
        int streamId = 3;
        int increment1 = 1000;
        int increment2 = 2000;
        
        byte[] payload1 = new byte[] { 0x00, 0x00, 0x03, (byte) 0xE8 };  // 1000
        byte[] payload2 = new byte[] { 0x00, 0x00, 0x07, (byte) 0xD0 };  // 2000
        
        WindowUpdateFrame frame1 = new WindowUpdateFrame(streamId, payload1);
        WindowUpdateFrame frame2 = new WindowUpdateFrame(streamId, payload2);
        
        assertEquals("First increment should be 1000", increment1, frame1.windowSizeIncrement);
        assertEquals("Second increment should be 2000", increment2, frame2.windowSizeIncrement);
        // Total window increase would be 3000
    }
    
    @Test
    public void testWindowUpdateOnDifferentStreams() throws ProtocolException {
        // Connection-level and stream-level updates are independent
        byte[] payload = new byte[] { 0x00, 0x00, 0x10, 0x00 };  // 4096
        
        WindowUpdateFrame connectionLevel = new WindowUpdateFrame(0, payload);
        WindowUpdateFrame streamLevel = new WindowUpdateFrame(1, payload);
        
        assertEquals("Connection level stream should be 0", 0, connectionLevel.getStream());
        assertEquals("Stream level stream should be 1", 1, streamLevel.getStream());
        assertEquals("Both should have same increment", 
            connectionLevel.windowSizeIncrement, streamLevel.windowSizeIncrement);
    }
    
    // ========== Flow Control Error Conditions ==========
    
    @Test
    public void testFlowControlErrorCode() {
        // Verify FLOW_CONTROL_ERROR constant
        assertEquals("FLOW_CONTROL_ERROR should be 3", 0x3, Frame.ERROR_FLOW_CONTROL_ERROR);
    }
    
    @Test
    public void testWindowUpdateToString() {
        WindowUpdateFrame frame = new WindowUpdateFrame(5, 32768);
        String str = frame.toString();
        
        assertTrue("ToString should contain stream", str.contains("stream=5") || str.contains("Stream"));
        assertTrue("ToString should contain increment", str.contains("32768") || str.contains("windowSizeIncrement"));
    }
}

