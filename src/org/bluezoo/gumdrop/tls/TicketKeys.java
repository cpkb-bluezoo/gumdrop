/*
 * TicketKeys.java
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

package org.bluezoo.gumdrop.tls;

import java.util.ArrayList;
import java.util.List;

/**
 * A server's session-ticket encryption keyring: the AES-128-GCM key new
 * tickets are sealed under, plus the single most-recently-rotated-away
 * key, so tickets minted just before a rotation don't instantly stop
 * resuming. A sealed ticket identity carries no key ID (RFC 8446 leaves
 * the ticket's opaque bytes entirely up to the issuing server), so there
 * is no way to tell, on receipt, which key a given ticket was sealed
 * under -- {@link #candidateKeys} is the ordered list of every key still
 * worth trying to open one against, current first.
 *
 * <p>Rotation <em>cadence</em> is deliberately not this class's job --
 * deciding when to rotate is caller/deployment policy, not something a
 * synchronous handshake engine should own; {@link #rotate} just performs
 * one rotation when told to.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TicketKeys {

    private byte[] currentKey;
    private byte[] previousKey;

    /**
     * Creates a keyring with a single key -- no rotation history yet.
     *
     * @param key the AES-128-GCM key new tickets are sealed under, 16 bytes
     */
    public TicketKeys(byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("Ticket key must be 16 bytes (AES-128-GCM), was " + key.length);
        }
        this.currentKey = key;
    }

    /**
     * Returns the key new tickets are sealed under.
     *
     * @return the current key
     */
    public byte[] getCurrentKey() {
        return currentKey;
    }

    /**
     * Rotates: {@code newKey} becomes {@link #getCurrentKey}, the old
     * current key becomes the (single) key still accepted for
     * decryption, and whatever was accepted before that is dropped.
     *
     * @param newKey the new current key, 16 bytes
     */
    public void rotate(byte[] newKey) {
        if (newKey.length != 16) {
            throw new IllegalArgumentException("Ticket key must be 16 bytes (AES-128-GCM), was " + newKey.length);
        }
        previousKey = currentKey;
        currentKey = newKey;
    }

    /**
     * Returns every key worth trying to open a ticket against, current
     * first.
     *
     * @return the candidate keys, in the order to try them
     */
    public List<byte[]> candidateKeys() {
        List<byte[]> candidates = new ArrayList<byte[]>(2);
        candidates.add(currentKey);
        if (previousKey != null) {
            candidates.add(previousKey);
        }
        return candidates;
    }

}
