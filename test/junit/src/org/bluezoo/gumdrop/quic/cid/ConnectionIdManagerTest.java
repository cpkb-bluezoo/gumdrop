/*
 * ConnectionIdManagerTest.java
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

package org.bluezoo.gumdrop.quic.cid;

import java.util.List;

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link ConnectionIdManager} (RFC 9000 section 5.1):
 * issuance sequencing, retirement, and {@code active_connection_id_limit}
 * enforcement in both directions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectionIdManagerTest {

    private static final byte[] STATIC_KEY = ByteArrays.toByteArray(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e");
    private static final byte[] OUR_HANDSHAKE_CID = ByteArrays.toByteArray("aaaaaaaaaaaaaaaa");
    private static final byte[] PEER_HANDSHAKE_CID = ByteArrays.toByteArray("bbbbbbbbbbbbbbbb");

    private static ConnectionIdManager newManager() {
        return new ConnectionIdManager(OUR_HANDSHAKE_CID, PEER_HANDSHAKE_CID, STATIC_KEY);
    }

    private static byte[] cid(int fill) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) fill;
        }
        return bytes;
    }

    private static byte[] token(int fill) {
        byte[] bytes = new byte[StatelessResetToken.LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) fill;
        }
        return bytes;
    }

    @Test
    public void testInitialStateHasHandshakeConnectionIds() {
        ConnectionIdManager manager = newManager();
        ConnectionIdEntry active = manager.getActivePeerConnectionId();
        assertNotNull(active);
        assertEquals(0, active.getSequenceNumber());
        assertArrayEquals(PEER_HANDSHAKE_CID, active.getConnectionId());
        assertNull(active.getStatelessResetToken());
    }

    @Test
    public void testIssueNextAssignsSequentialNumbers() {
        ConnectionIdManager manager = newManager();
        manager.setPeerAdvertisedLimit(10);

        ConnectionIdEntry first = manager.issueNext();
        ConnectionIdEntry second = manager.issueNext();

        assertEquals(1, first.getSequenceNumber());
        assertEquals(2, second.getSequenceNumber());
        assertEquals(StatelessResetToken.LENGTH, first.getStatelessResetToken().length);
        assertEquals(ConnectionIdManager.MAX_CONNECTION_ID_LENGTH, first.getConnectionId().length);
    }

    @Test
    public void testIssueNextRespectsPeerAdvertisedLimit() {
        ConnectionIdManager manager = newManager(); // default peerAdvertisedLimit = 2, "ours" already has seq 0
        assertNotNull(manager.issueNext()); // ours.size() 1 -> 2
        assertNull(manager.issueNext()); // ours.size() already at the limit
    }

    @Test
    public void testDrainPendingIssuanceClearsQueue() {
        ConnectionIdManager manager = newManager();
        manager.setPeerAdvertisedLimit(10);
        manager.issueNext();
        manager.issueNext();

        List<ConnectionIdEntry> drained = manager.drainPendingIssuance();
        assertEquals(2, drained.size());
        assertEquals(1, drained.get(0).getSequenceNumber());
        assertEquals(2, drained.get(1).getSequenceNumber());

        assertTrue(manager.drainPendingIssuance().isEmpty());
    }

    @Test
    public void testRetireOursRemovesEntryAndAllowsReissue() {
        ConnectionIdManager manager = newManager(); // default limit 2
        ConnectionIdEntry issued = manager.issueNext();
        assertNull(manager.issueNext()); // now at the limit

        assertTrue(manager.retireOurs(issued.getSequenceNumber()));
        assertNotNull(manager.issueNext()); // room again after retirement
    }

    @Test
    public void testRetireOursRejectsNeverIssuedSequenceNumber() {
        ConnectionIdManager manager = newManager();
        assertFalse(manager.retireOurs(999));
        assertNotNull(manager.takeLastError());
        assertNull(manager.takeLastError()); // cleared after reading
    }

    @Test
    public void testRetireOursDuplicateIsHarmless() {
        ConnectionIdManager manager = newManager();
        ConnectionIdEntry issued = manager.issueNext();
        assertTrue(manager.retireOurs(issued.getSequenceNumber()));
        assertTrue(manager.retireOurs(issued.getSequenceNumber())); // already gone, still not an error
        assertNull(manager.takeLastError());
    }

    @Test
    public void testAddPeerConnectionIdTracksActive() {
        ConnectionIdManager manager = newManager();
        manager.setOurAdvertisedLimit(10);

        assertTrue(manager.addPeerConnectionId(1, 0, cid(1), token(1)));
        ConnectionIdEntry active = manager.getActivePeerConnectionId();
        assertEquals(1, active.getSequenceNumber());
        assertArrayEquals(cid(1), active.getConnectionId());
    }

    @Test
    public void testAddPeerConnectionIdEnforcesOurAdvertisedLimit() {
        ConnectionIdManager manager = newManager();
        manager.setOurAdvertisedLimit(2); // "peers" already has seq 0 from the handshake

        assertTrue(manager.addPeerConnectionId(1, 0, cid(1), token(1)));
        assertFalse(manager.addPeerConnectionId(2, 0, cid(2), token(2)));
        assertNotNull(manager.takeLastError());
    }

    @Test
    public void testAddPeerConnectionIdRetiresPriorEntries() {
        ConnectionIdManager manager = newManager();
        manager.setOurAdvertisedLimit(10);
        manager.addPeerConnectionId(1, 0, cid(1), token(1));
        manager.addPeerConnectionId(2, 0, cid(2), token(2));

        // Retire everything below sequence 2 (handshake seq 0, and seq 1).
        assertTrue(manager.addPeerConnectionId(3, 2, cid(3), token(3)));

        long[] retired = manager.drainPendingRetirement();
        assertEquals(2, retired.length);
        assertEquals(0, retired[0]);
        assertEquals(1, retired[1]);

        ConnectionIdEntry active = manager.getActivePeerConnectionId();
        assertEquals(3, active.getSequenceNumber());
    }

    @Test
    public void testAddPeerConnectionIdDuplicateSequenceIsIdempotent() {
        ConnectionIdManager manager = newManager();
        manager.setOurAdvertisedLimit(2); // handshake seq 0 already stored; only room for one more

        assertTrue(manager.addPeerConnectionId(1, 0, cid(1), token(1)));
        assertTrue(manager.addPeerConnectionId(1, 0, cid(1), token(1))); // duplicate, not counted again
        assertFalse(manager.addPeerConnectionId(2, 0, cid(2), token(2))); // limit still enforced
    }

    @Test
    public void testRetirePeerConnectionId() {
        ConnectionIdManager manager = newManager();
        manager.setOurAdvertisedLimit(10);
        manager.addPeerConnectionId(1, 0, cid(1), token(1));

        manager.retirePeerConnectionId(0); // retire the handshake connection ID
        long[] retired = manager.drainPendingRetirement();
        assertEquals(1, retired.length);
        assertEquals(0, retired[0]);

        ConnectionIdEntry active = manager.getActivePeerConnectionId();
        assertEquals(1, active.getSequenceNumber());
    }
}
