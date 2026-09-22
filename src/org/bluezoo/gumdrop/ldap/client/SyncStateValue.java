/*
 * SyncStateValue.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.ldap.asn1.Asn1Element;
import org.bluezoo.gumdrop.ldap.asn1.Asn1Exception;
import org.bluezoo.gumdrop.ldap.asn1.BerDecoder;

/**
 * The value of the Sync State Control (RFC 4533 §2.3), attached to a
 * SearchResultEntry during a content synchronization search:
 *
 * <pre>
 * syncStateValue ::= SEQUENCE {
 *     state     ENUMERATED { present (0), add (1), modify (2), delete (3) },
 *     entryUUID syncUUID,
 *     cookie    syncCookie OPTIONAL }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.3">RFC 4533 §2.3</a>
 */
public final class SyncStateValue {

    private final SyncState state;
    private final byte[] entryUUID;
    private final byte[] cookie;

    private SyncStateValue(SyncState state, byte[] entryUUID, byte[] cookie) {
        this.state = state;
        this.entryUUID = entryUUID;
        this.cookie = cookie;
    }

    /**
     * Returns how this entry relates to the client's last known state.
     *
     * @return the sync state
     */
    public SyncState getState() {
        return state;
    }

    /**
     * Returns the entry's UUID (RFC 4533's {@code syncUUID}, normally 16 bytes).
     *
     * @return the entry UUID bytes
     */
    public byte[] getEntryUUID() {
        return Arrays.copyOf(entryUUID, entryUUID.length);
    }

    /**
     * Returns the cookie carried with this entry, if any.
     *
     * <p>Not every entry carries a cookie — RFC 4533 leaves it to server
     * policy how often to include one during a refresh — but a caller
     * that wants to be able to resume mid-refresh after a disconnect
     * should remember the most recent one seen.
     *
     * @return the cookie bytes, or null if this entry didn't carry one
     */
    public byte[] getCookie() {
        return cookie != null ? Arrays.copyOf(cookie, cookie.length) : null;
    }

    /**
     * Returns whether this entry carried a cookie.
     *
     * @return true if a cookie is present
     */
    public boolean hasCookie() {
        return cookie != null;
    }

    /**
     * Parses a syncStateValue from a Sync State Control's raw value.
     *
     * @param value the control value bytes
     * @return the parsed sync state value
     * @throws Asn1Exception if the value is malformed
     */
    public static SyncStateValue parse(byte[] value) throws Asn1Exception {
        if (value == null) {
            throw new Asn1Exception("Empty syncStateValue");
        }
        BerDecoder decoder = new BerDecoder();
        decoder.receive(ByteBuffer.wrap(value));
        Asn1Element seq = decoder.next();
        if (seq == null) {
            throw new Asn1Exception("Empty syncStateValue");
        }
        List<Asn1Element> parts = seq.getChildren();
        if (parts == null || parts.size() < 2) {
            throw new Asn1Exception("Invalid syncStateValue structure");
        }
        SyncState state = SyncState.fromValue(parts.get(0).asInt());
        byte[] entryUUID = parts.get(1).asOctetString();
        byte[] cookie = parts.size() > 2 ? parts.get(2).asOctetString() : null;
        return new SyncStateValue(state, entryUUID, cookie);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SyncStateValue[state=");
        sb.append(state);
        if (cookie != null) {
            sb.append(", cookie=").append(cookie.length).append(" bytes");
        }
        sb.append(']');
        return sb.toString();
    }
}
