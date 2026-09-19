/*
 * H2FlowControlTest.java
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

package org.bluezoo.gumdrop.http.h2;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link H2FlowControl} (RFC 9113 section 6.9 accounting).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2FlowControlTest {

    private H2FlowControl fc;
    private H2FlowControl.DataReceivedResult result;

    @Before
    public void setUp() {
        fc = new H2FlowControl();
        result = new H2FlowControl.DataReceivedResult();
    }

    @Test
    public void defaultsAreRfcInitialWindow() {
        assertEquals(65535, fc.getInitialSendWindowSize());
        assertEquals(65535L, fc.getConnectionSendWindow());
        assertEquals(65535L, fc.getConnectionRecvWindow());
    }

    @Test
    public void unknownStreamHasNoSendWindow() {
        assertEquals(0, fc.availableSendWindow(3));
        assertTrue(fc.isSendBlocked(3));
    }

    @Test
    public void sendWindowIsConsumedAndReplenished() {
        fc.openStream(1);
        assertEquals(65535, fc.availableSendWindow(1));
        fc.consumeSendWindow(1, 65535);
        assertTrue(fc.isSendBlocked(1));
        assertFalse(fc.onWindowUpdate(1, 100));
        assertTrue(fc.isSendBlocked(1));
        assertFalse(fc.onWindowUpdate(0, 100));
        assertEquals(100, fc.availableSendWindow(1));
    }

    @Test
    public void availableWindowIsMinOfConnectionAndStream() {
        fc = new H2FlowControl(1000);
        fc.openStream(1);
        assertEquals(1000, fc.availableSendWindow(1));
        fc.onWindowUpdate(1, 5000);
        assertEquals(6000, fc.availableSendWindow(1));
    }

    @Test
    public void windowUpdateOverflowIsReported() {
        fc.openStream(1);
        assertTrue(fc.onWindowUpdate(0, Integer.MAX_VALUE));
        assertTrue(fc.onWindowUpdate(1, Integer.MAX_VALUE));
    }

    @Test
    public void windowUpdateForUnknownStreamIsIgnored() {
        assertFalse(fc.onWindowUpdate(99, 10));
    }

    @Test
    public void consumeOnUnknownStreamOnlyAffectsConnection() {
        fc.consumeSendWindow(99, 10);
        assertEquals(65525L, fc.getConnectionSendWindow());
    }

    @Test
    public void smallDataDoesNotTriggerWindowUpdate() {
        fc.openStream(1);
        fc.onDataReceived(1, 100, result);
        assertEquals(0, result.connectionIncrement);
        assertEquals(0, result.streamIncrement);
        assertEquals(65535L - 100, fc.getConnectionRecvWindow());
    }

    @Test
    public void zeroLengthDataIsIgnored() {
        fc.openStream(1);
        fc.onDataReceived(1, 0, result);
        assertEquals(0, result.connectionIncrement);
        assertEquals(0, result.streamIncrement);
        assertEquals(65535L, fc.getConnectionRecvWindow());
    }

    @Test
    public void thresholdTriggersConnectionAndStreamUpdates() {
        fc.openStream(1);
        fc.onDataReceived(1, 40000, result);
        assertEquals(40000, result.connectionIncrement);
        assertEquals(40000, result.streamIncrement);
        assertEquals(65535L, fc.getConnectionRecvWindow());
    }

    @Test
    public void dataOnUnknownStreamStillCountsAgainstConnection() {
        fc.onDataReceived(7, 40000, result);
        assertEquals(40000, result.connectionIncrement);
        assertEquals(0, result.streamIncrement);
    }

    @Test
    public void pausedStreamWithholdsIncrementUntilResumed() {
        fc.openStream(1);
        assertFalse(fc.isStreamPaused(1));
        fc.pauseStream(1);
        assertTrue(fc.isStreamPaused(1));
        fc.onDataReceived(1, 40000, result);
        assertEquals(0, result.streamIncrement);
        int resumed = fc.resumeStream(1);
        assertEquals(40000, resumed);
        assertFalse(fc.isStreamPaused(1));
        assertEquals(0, fc.resumeStream(1));
    }

    @Test
    public void resumeFlushesSubThresholdConsumption() {
        fc.openStream(1);
        fc.onDataReceived(1, 100, result);
        fc.pauseStream(1);
        fc.onDataReceived(1, 50, result);
        assertEquals(150, fc.resumeStream(1));
    }

    @Test
    public void pauseAndResumeUnknownStreamAreNoOps() {
        fc.pauseStream(5);
        assertFalse(fc.isStreamPaused(5));
        assertEquals(0, fc.resumeStream(5));
    }

    @Test
    public void unblockedStreamsAreEnumerated() {
        fc.openStream(1);
        fc.openStream(3);
        fc.consumeSendWindow(3, 65535);
        fc.onWindowUpdate(0, 65535);
        final List<Integer> seen = new ArrayList<Integer>();
        fc.forEachUnblockedStream(new H2FlowControl.UnblockedStreamCallback() {
            @Override
            public void onUnblocked(int streamId) {
                seen.add(streamId);
            }
        });
        assertEquals(1, seen.size());
        assertEquals(Integer.valueOf(1), seen.get(0));
    }

    @Test
    public void noUnblockedStreamsWhenConnectionWindowExhausted() {
        fc.openStream(1);
        fc.consumeSendWindow(1, 65535);
        final List<Integer> seen = new ArrayList<Integer>();
        fc.forEachUnblockedStream(new H2FlowControl.UnblockedStreamCallback() {
            @Override
            public void onUnblocked(int streamId) {
                seen.add(streamId);
            }
        });
        assertTrue(seen.isEmpty());
    }

    @Test
    public void settingsInitialWindowAdjustsOpenStreams() {
        fc.openStream(1);
        fc.consumeSendWindow(1, 1000);
        assertFalse(fc.onSettingsInitialWindowSize(65535));
        assertFalse(fc.onSettingsInitialWindowSize(70000));
        assertEquals(70000, fc.getInitialSendWindowSize());
        assertEquals(64535, fc.availableSendWindow(1));
        fc.onWindowUpdate(1, 2000);
        assertTrue(fc.onSettingsInitialWindowSize(Integer.MAX_VALUE));
    }

    @Test
    public void closedStreamStatesAreRecycled() {
        for (int i = 1; i <= 200; i += 2) {
            fc.openStream(i);
        }
        for (int i = 1; i <= 200; i += 2) {
            fc.closeStream(i);
        }
        fc.closeStream(9999);
        fc.openStream(1);
        assertEquals(65535, fc.availableSendWindow(1));
    }
}
