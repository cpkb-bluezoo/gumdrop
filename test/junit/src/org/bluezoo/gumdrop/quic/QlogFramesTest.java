/*
 * QlogFramesTest.java
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

package org.bluezoo.gumdrop.quic;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QlogFrames}: the exact JSON each frame writes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QlogFramesTest {

    @Test
    public void emptyArray() {
        QlogFrames frames = new QlogFrames();
        assertEquals(0, frames.size());
        assertEquals("[]", frames.array());
    }

    @Test
    public void framesAreCommaSeparatedAndCounted() {
        QlogFrames frames = new QlogFrames().ping().handshakeDone();
        assertEquals(2, frames.size());
        assertEquals("[{\"frame_type\":\"ping\"},{\"frame_type\":\"handshake_done\"}]", frames.array());
    }

    @Test
    public void resetStream() {
        assertEquals("[{\"frame_type\":\"reset_stream\",\"stream_id\":4,\"error_code\":9,\"final_size\":100}]",
                new QlogFrames().resetStream(4, 9, 100).array());
    }

    @Test
    public void stopSending() {
        assertEquals("[{\"frame_type\":\"stop_sending\",\"stream_id\":8,\"error_code\":3}]",
                new QlogFrames().stopSending(8, 3).array());
    }

    @Test
    public void newToken() {
        assertEquals("[{\"frame_type\":\"new_token\",\"token\":{\"length\":16}}]",
                new QlogFrames().newToken(16).array());
    }

    @Test
    public void flowControlLimits() {
        assertEquals("[{\"frame_type\":\"max_data\",\"maximum\":1000}]",
                new QlogFrames().maxData(1000).array());
        assertEquals("[{\"frame_type\":\"max_stream_data\",\"stream_id\":4,\"maximum\":2000}]",
                new QlogFrames().maxStreamData(4, 2000).array());
        assertEquals("[{\"frame_type\":\"max_streams\",\"stream_type\":\"bidirectional\",\"maximum\":10}]",
                new QlogFrames().maxStreams(true, 10).array());
        assertEquals("[{\"frame_type\":\"max_streams\",\"stream_type\":\"unidirectional\",\"maximum\":3}]",
                new QlogFrames().maxStreams(false, 3).array());
    }

    @Test
    public void blockedFrames() {
        assertEquals("[{\"frame_type\":\"data_blocked\",\"limit\":500}]",
                new QlogFrames().dataBlocked(500).array());
        assertEquals("[{\"frame_type\":\"stream_data_blocked\",\"stream_id\":12,\"limit\":64}]",
                new QlogFrames().streamDataBlocked(12, 64).array());
        assertEquals("[{\"frame_type\":\"streams_blocked\",\"stream_type\":\"bidirectional\",\"limit\":7}]",
                new QlogFrames().streamsBlocked(true, 7).array());
        assertEquals("[{\"frame_type\":\"streams_blocked\",\"stream_type\":\"unidirectional\",\"limit\":1}]",
                new QlogFrames().streamsBlocked(false, 1).array());
    }

    @Test
    public void newConnectionIdWithAndWithoutResetToken() {
        byte[] cid = new byte[] {1, 2, (byte) 0xab};
        String without = new QlogFrames().newConnectionId(2, 1, cid, null).array();
        assertEquals("[{\"frame_type\":\"new_connection_id\",\"sequence_number\":2,\"retire_prior_to\":1,"
                + "\"connection_id_length\":3,\"connection_id\":\"0102ab\"}]", without);
        String with = new QlogFrames().newConnectionId(2, 1, cid, new byte[] {(byte) 0xff, 0}).array();
        assertTrue(with.contains("\"stateless_reset_token\":\"ff00\""));
    }

    @Test
    public void connectionCloseReasonIsOptional() {
        assertEquals("[{\"frame_type\":\"connection_close\",\"error_space\":\"transport\",\"error_code\":10}]",
                new QlogFrames().connectionClose(false, 10, null).array());
        assertEquals("[{\"frame_type\":\"connection_close\",\"error_space\":\"application\",\"error_code\":256}]",
                new QlogFrames().connectionClose(true, 256, "").array());
        assertEquals("[{\"frame_type\":\"connection_close\",\"error_space\":\"application\","
                + "\"error_code\":1,\"reason\":\"bye \\\"now\\\"\"}]",
                new QlogFrames().connectionClose(true, 1, "bye \"now\"").array());
    }

    @Test
    public void ackWithRangesAndDelay() {
        String json = new QlogFrames().ack(new long[][] {{0, 3}, {5, 9}}, 1234).array();
        assertEquals("[{\"frame_type\":\"ack\",\"ack_delay\":1.234,\"acked_ranges\":[[0,3],[5,9]]}]", json);
    }
}
