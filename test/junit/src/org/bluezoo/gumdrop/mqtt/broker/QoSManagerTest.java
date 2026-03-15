/*
 * QoSManagerTest.java
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

package org.bluezoo.gumdrop.mqtt.broker;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;

public class QoSManagerTest {

    private QoSManager mgr;

    @Before
    public void setUp() {
        mgr = new QoSManager();
    }

    @Test
    public void testPacketIdWraps() {
        for (int i = 0; i < 65536; i++) {
            int id = mgr.nextPacketId();
            assertTrue(id >= 1 && id <= 65535);
        }
    }

    @Test
    public void testQoS1OutboundFlow() {
        int packetId = mgr.nextPacketId();
        QoSManager.InFlightMessage msg = new QoSManager.InFlightMessage(
                packetId, "test/topic",
                new InMemoryMessageStore.InMemoryContent(new byte[]{1, 2, 3}),
                QoS.AT_LEAST_ONCE);
        mgr.trackOutbound(msg);
        assertEquals(1, mgr.outboundCount());

        QoSManager.InFlightMessage completed = mgr.completeQoS1Outbound(packetId);
        assertNotNull(completed);
        assertEquals(packetId, completed.getPacketId());
        assertEquals(0, mgr.outboundCount());
    }

    @Test
    public void testQoS2OutboundFlow() {
        int packetId = mgr.nextPacketId();
        QoSManager.InFlightMessage msg = new QoSManager.InFlightMessage(
                packetId, "test/topic",
                new InMemoryMessageStore.InMemoryContent(new byte[]{1}),
                QoS.EXACTLY_ONCE);
        mgr.trackOutbound(msg);
        assertEquals(QoSManager.QoS2State.WAIT_PUBREC, msg.getQoS2State());

        QoSManager.InFlightMessage afterPubRec = mgr.receivedPubRec(packetId);
        assertNotNull(afterPubRec);
        assertEquals(QoSManager.QoS2State.WAIT_PUBCOMP, afterPubRec.getQoS2State());

        QoSManager.InFlightMessage completed = mgr.completePubComp(packetId);
        assertNotNull(completed);
        assertEquals(0, mgr.outboundCount());
    }

    @Test
    public void testQoS2InboundFlow() {
        int packetId = 42;
        QoSManager.InFlightMessage msg = new QoSManager.InFlightMessage(
                packetId, "in/topic",
                new InMemoryMessageStore.InMemoryContent(new byte[]{9}),
                QoS.EXACTLY_ONCE);
        mgr.trackInboundQoS2(msg);
        assertTrue(mgr.isInboundQoS2Tracked(packetId));
        assertEquals(1, mgr.inboundCount());

        QoSManager.InFlightMessage delivered = mgr.receivedPubRel(packetId);
        assertNotNull(delivered);
        assertEquals(0, mgr.inboundCount());
        assertFalse(mgr.isInboundQoS2Tracked(packetId));
    }

    @Test
    public void testClear() {
        mgr.trackOutbound(new QoSManager.InFlightMessage(
                1, "t",
                new InMemoryMessageStore.InMemoryContent(new byte[0]),
                QoS.AT_LEAST_ONCE));
        mgr.trackInboundQoS2(new QoSManager.InFlightMessage(
                2, "t",
                new InMemoryMessageStore.InMemoryContent(new byte[0]),
                QoS.EXACTLY_ONCE));
        mgr.clear();
        assertEquals(0, mgr.outboundCount());
        assertEquals(0, mgr.inboundCount());
    }

    @Test
    public void testUnknownPacketIdReturnsNull() {
        assertNull(mgr.completeQoS1Outbound(999));
        assertNull(mgr.receivedPubRec(999));
        assertNull(mgr.completePubComp(999));
        assertNull(mgr.receivedPubRel(999));
    }
}
