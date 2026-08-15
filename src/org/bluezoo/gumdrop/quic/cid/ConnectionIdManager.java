/*
 * ConnectionIdManager.java
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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Connection ID issuance and retirement for one QUIC connection (RFC 9000
 * section 5.1), transport/frame-agnostic: it tracks state and queues
 * pending output, but never touches the wire itself. The owning
 * connection (a later stage of the QUIC transport rewire) drains
 * {@link #drainPendingIssuance}/{@link #drainPendingRetirement} and
 * writes the corresponding {@code NEW_CONNECTION_ID}/
 * {@code RETIRE_CONNECTION_ID} frames via
 * {@code org.bluezoo.gumdrop.quic.frame.QuicFrameWriter}, and calls
 * {@link #addPeerConnectionId}/{@link #retireOurs} when the matching
 * frames arrive from the peer.
 *
 * <p>Tracks two independent, symmetric pools:
 * <ul>
 * <li><b>Ours</b> -- connection IDs this endpoint has issued, so the
 * peer can address packets to us. Sequence number 0 is the connection ID
 * established during the handshake (RFC 9000 section 5.1.1); further
 * ones are minted by {@link #issueNext}.
 * <li><b>Peer's</b> -- connection IDs the peer has issued, usable as the
 * Destination Connection ID on packets this endpoint sends. Sequence
 * number 0 is likewise the peer's handshake connection ID; further ones
 * arrive via {@link #addPeerConnectionId}.
 * </ul>
 *
 * <p>No path validation logic (PATH_CHALLENGE/PATH_RESPONSE) lives here --
 * that drives this class from the outside, in {@code QuicConnection},
 * rather than the other way around. {@link #getActivePeerConnectionId}
 * always returns the most recently added, unretired peer connection ID;
 * on a validated migration, {@code QuicConnection} calls it to pick a
 * fresh one and {@link #retirePeerConnectionId} to retire whichever one
 * was in use on the old path.
 *
 * <p>Not thread-safe: one instance per connection, used only on that
 * connection's owning thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectionIdEntry
 * @see StatelessResetToken
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.1">RFC 9000 section 5.1</a>
 */
public final class ConnectionIdManager {

    /** RFC 9000 section 17.2: a QUIC v1 connection ID must not exceed this length. */
    public static final int MAX_CONNECTION_ID_LENGTH = 20;

    /** RFC 9000 section 18.2: the default if the peer's transport parameters omit it. */
    public static final int DEFAULT_ACTIVE_CONNECTION_ID_LIMIT = 2;

    private final List<ConnectionIdEntry> ours = new ArrayList<ConnectionIdEntry>();
    private final List<ConnectionIdEntry> peers = new ArrayList<ConnectionIdEntry>();
    private final List<ConnectionIdEntry> pendingIssuance = new ArrayList<ConnectionIdEntry>();
    private final List<Long> pendingRetirement = new ArrayList<Long>();
    private final byte[] staticKey;
    private final SecureRandom random = new SecureRandom();

    private long nextSequenceNumber = 1;
    private long highestIssuedSequenceNumber;

    /**
     * The limit this endpoint has advertised to the peer, bounding how
     * many of the peer's connection IDs (in {@link #peers}) this
     * endpoint will store.
     */
    private int ourAdvertisedLimit = DEFAULT_ACTIVE_CONNECTION_ID_LIMIT;

    /**
     * The limit the peer has advertised to this endpoint, bounding how
     * many connection IDs this endpoint may have simultaneously active
     * (in {@link #ours}) for the peer to store.
     */
    private int peerAdvertisedLimit = DEFAULT_ACTIVE_CONNECTION_ID_LIMIT;

    private String lastError;

    /**
     * Creates a connection ID manager seeded with the two handshake
     * connection IDs (sequence number 0 in each pool, per RFC 9000
     * section 5.1.1).
     *
     * @param ourHandshakeConnectionId the connection ID this endpoint
     *                                 used during the handshake
     * @param peerHandshakeConnectionId the connection ID the peer used
     *                                  during the handshake
     * @param staticKey this endpoint's static key for
     *                  {@link StatelessResetToken} derivation
     */
    public ConnectionIdManager(byte[] ourHandshakeConnectionId, byte[] peerHandshakeConnectionId, byte[] staticKey) {
        ours.add(new ConnectionIdEntry(0, ourHandshakeConnectionId, null));
        peers.add(new ConnectionIdEntry(0, peerHandshakeConnectionId, null));
        this.staticKey = staticKey;
    }

    /**
     * Sets the limit this endpoint advertises to the peer (this
     * endpoint's own {@code active_connection_id_limit} transport
     * parameter), bounding how many of the peer's connection IDs
     * {@link #addPeerConnectionId} will accept.
     *
     * @param limit the limit
     */
    public void setOurAdvertisedLimit(int limit) {
        this.ourAdvertisedLimit = limit;
    }

    /**
     * Records the limit the peer has advertised (the peer's
     * {@code active_connection_id_limit} transport parameter), bounding
     * how many connection IDs {@link #issueNext} will mint at once.
     *
     * @param limit the limit
     */
    public void setPeerAdvertisedLimit(int limit) {
        this.peerAdvertisedLimit = limit;
    }

    /**
     * Returns, and clears, the most recent error from processing a peer
     * connection ID event. The caller should close the connection with
     * {@code PROTOCOL_VIOLATION} if this returns non-null.
     *
     * @return the error message, or null if there was none
     */
    public String takeLastError() {
        String error = lastError;
        lastError = null;
        return error;
    }

    /**
     * Mints a new connection ID for the peer to use as a Destination
     * Connection ID, queuing it for {@link #drainPendingIssuance}.
     *
     * @return the newly issued entry, or {@code null} if issuing one
     *         more would exceed the peer's advertised
     *         {@code active_connection_id_limit}
     */
    public ConnectionIdEntry issueNext() {
        if (ours.size() >= peerAdvertisedLimit) {
            return null;
        }
        byte[] connectionId = new byte[MAX_CONNECTION_ID_LENGTH];
        random.nextBytes(connectionId);
        long sequenceNumber = nextSequenceNumber++;
        byte[] token = StatelessResetToken.generate(staticKey, connectionId);
        ConnectionIdEntry entry = new ConnectionIdEntry(sequenceNumber, connectionId, token);
        ours.add(entry);
        highestIssuedSequenceNumber = sequenceNumber;
        pendingIssuance.add(entry);
        return entry;
    }

    /**
     * Called when a RETIRE_CONNECTION_ID frame arrives from the peer:
     * this endpoint's connection ID with this sequence number will no
     * longer be used by the peer as a Destination Connection ID.
     *
     * @param sequenceNumber the sequence number being retired
     * @return true if accepted (including a harmless duplicate of an
     *         already-retired sequence number); false if
     *         {@code sequenceNumber} was never issued by
     *         {@link #issueNext} -- a protocol violation (RFC 9000
     *         section 19.16), left for the caller to act on via
     *         {@link #takeLastError}
     */
    public boolean retireOurs(long sequenceNumber) {
        if (sequenceNumber > highestIssuedSequenceNumber) {
            lastError = "RETIRE_CONNECTION_ID for sequence " + sequenceNumber
                    + " was never issued (highest issued: " + highestIssuedSequenceNumber + ")";
            return false;
        }
        Iterator<ConnectionIdEntry> it = ours.iterator();
        while (it.hasNext()) {
            if (it.next().getSequenceNumber() == sequenceNumber) {
                it.remove();
                break;
            }
        }
        return true;
    }

    /**
     * Called when a NEW_CONNECTION_ID frame arrives from the peer,
     * offering a connection ID this endpoint may use as a Destination
     * Connection ID. Also retires any of the peer's earlier-issued
     * connection IDs below {@code retirePriorTo} (RFC 9000 section
     * 19.15), queuing the resulting sequence numbers for
     * {@link #drainPendingRetirement}.
     *
     * @param sequenceNumber the new connection ID's sequence number
     * @param retirePriorTo connection IDs below this sequence number
     *                      must be retired
     * @param connectionId the new connection ID bytes
     * @param statelessResetToken the associated stateless reset token
     * @return true if accepted (including a harmless duplicate of an
     *         already-known sequence number); false if accepting it
     *         would exceed this endpoint's advertised
     *         {@code active_connection_id_limit} -- a connection error
     *         (RFC 9000 section 19.15), left for the caller to act on
     *         via {@link #takeLastError}
     */
    public boolean addPeerConnectionId(long sequenceNumber, long retirePriorTo,
            byte[] connectionId, byte[] statelessResetToken) {
        for (ConnectionIdEntry entry : peers) {
            if (entry.getSequenceNumber() == sequenceNumber) {
                return true; // already known: a harmless retransmission
            }
        }

        Iterator<ConnectionIdEntry> it = peers.iterator();
        while (it.hasNext()) {
            ConnectionIdEntry entry = it.next();
            if (entry.getSequenceNumber() < retirePriorTo) {
                it.remove();
                pendingRetirement.add(entry.getSequenceNumber());
            }
        }

        if (peers.size() >= ourAdvertisedLimit) {
            lastError = "peer connection ID count would exceed the advertised active_connection_id_limit of "
                    + ourAdvertisedLimit;
            return false;
        }
        peers.add(new ConnectionIdEntry(sequenceNumber, connectionId, statelessResetToken));
        return true;
    }

    /**
     * Called to retire one of the peer's connection IDs on this
     * endpoint's own initiative (e.g. after a validated connection
     * migration switches to a different one), queuing it for
     * {@link #drainPendingRetirement}.
     *
     * @param sequenceNumber the sequence number to retire
     */
    public void retirePeerConnectionId(long sequenceNumber) {
        Iterator<ConnectionIdEntry> it = peers.iterator();
        while (it.hasNext()) {
            if (it.next().getSequenceNumber() == sequenceNumber) {
                it.remove();
                pendingRetirement.add(sequenceNumber);
                break;
            }
        }
    }

    /**
     * Returns the connection ID to use as the Destination Connection ID
     * on the next packet sent: the most recently added, unretired
     * connection ID the peer has issued.
     *
     * @return the active peer connection ID, or {@code null} if none remain
     */
    public ConnectionIdEntry getActivePeerConnectionId() {
        return peers.isEmpty() ? null : peers.get(peers.size() - 1);
    }

    /**
     * Returns, and clears, the connection IDs newly issued since the
     * last call, needing a NEW_CONNECTION_ID frame sent to the peer.
     *
     * @return the newly issued entries, possibly empty
     */
    public List<ConnectionIdEntry> drainPendingIssuance() {
        List<ConnectionIdEntry> result = new ArrayList<ConnectionIdEntry>(pendingIssuance);
        pendingIssuance.clear();
        return result;
    }

    /**
     * Returns, and clears, the sequence numbers of this endpoint's own
     * connection IDs retired since the last call, needing a
     * RETIRE_CONNECTION_ID frame sent to the peer.
     *
     * @return the retired sequence numbers, possibly empty
     */
    public long[] drainPendingRetirement() {
        long[] result = new long[pendingRetirement.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = pendingRetirement.get(i);
        }
        pendingRetirement.clear();
        return result;
    }
}
