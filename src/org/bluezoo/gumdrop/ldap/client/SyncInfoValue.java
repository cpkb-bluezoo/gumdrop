/*
 * SyncInfoValue.java
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.ldap.asn1.Asn1Element;
import org.bluezoo.gumdrop.ldap.asn1.Asn1Exception;
import org.bluezoo.gumdrop.ldap.asn1.Asn1Type;
import org.bluezoo.gumdrop.ldap.asn1.BerDecoder;

/**
 * The syncInfoValue carried by an IntermediateResponse (RFC 4533 §2.5,
 * OID {@link LdapConstants#OID_SYNC_INFO}) during a refreshAndPersist
 * content synchronization search — a CHOICE of four alternatives:
 *
 * <pre>
 * syncInfoValue ::= CHOICE {
 *     newcookie      [0] syncCookie,
 *     refreshDelete  [1] SEQUENCE {
 *         cookie         syncCookie OPTIONAL,
 *         refreshDone    BOOLEAN DEFAULT TRUE },
 *     refreshPresent [2] SEQUENCE {
 *         cookie         syncCookie OPTIONAL,
 *         refreshDone    BOOLEAN DEFAULT TRUE },
 *     syncIdSet      [3] SEQUENCE {
 *         cookie         syncCookie OPTIONAL,
 *         refreshDeletes BOOLEAN DEFAULT FALSE,
 *         syncUUIDs      SET OF syncUUID } }
 * </pre>
 *
 * <p>{@link #getKind()} says which alternative this instance holds;
 * only the accessors relevant to that alternative are meaningful (see
 * each accessor's own doc).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.5">RFC 4533 §2.5</a>
 */
public final class SyncInfoValue {

    /** Which syncInfoValue CHOICE alternative a {@link SyncInfoValue} holds. */
    public enum Kind {
        /** {@code newcookie [0]} — a cookie update with no other content. */
        NEW_COOKIE,
        /** {@code refreshDelete [1]} — the delete phase of a refresh has completed. */
        REFRESH_DELETE,
        /** {@code refreshPresent [2]} — the present phase of a refresh has completed. */
        REFRESH_PRESENT,
        /** {@code syncIdSet [3]} — a set of entry UUIDs whose sync state the server is reporting in bulk. */
        SYNC_ID_SET
    }

    private final Kind kind;
    private final byte[] cookie;
    private final boolean refreshDone;
    private final boolean refreshDeletes;
    private final List<byte[]> entryUUIDs;

    private SyncInfoValue(Kind kind, byte[] cookie, boolean refreshDone,
            boolean refreshDeletes, List<byte[]> entryUUIDs) {
        this.kind = kind;
        this.cookie = cookie;
        this.refreshDone = refreshDone;
        this.refreshDeletes = refreshDeletes;
        this.entryUUIDs = entryUUIDs;
    }

    /**
     * Returns which CHOICE alternative this value holds.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the cookie carried by this message.
     *
     * <p>Meaningful for every {@link Kind}: it's the whole content of
     * {@link Kind#NEW_COOKIE}, and OPTIONAL on the other three.
     *
     * @return the cookie bytes, or null if none was sent
     */
    public byte[] getCookie() {
        return cookie != null ? Arrays.copyOf(cookie, cookie.length) : null;
    }

    /**
     * Returns whether a cookie is present.
     *
     * @return true if a cookie is present
     */
    public boolean hasCookie() {
        return cookie != null;
    }

    /**
     * Returns whether the phase this message announces has completed.
     *
     * <p>Only meaningful for {@link Kind#REFRESH_DELETE} and
     * {@link Kind#REFRESH_PRESENT}; defaults to true (RFC 4533's
     * {@code refreshDone BOOLEAN DEFAULT TRUE}) when absent from the wire.
     *
     * @return true if the refresh phase is done
     */
    public boolean isRefreshDone() {
        return refreshDone;
    }

    /**
     * Returns whether entries not confirmed present in this
     * {@link Kind#SYNC_ID_SET} batch should be treated as deleted.
     *
     * <p>Only meaningful for {@link Kind#SYNC_ID_SET}; see
     * {@link SyncDoneValue#isRefreshDeletes()} for the same flag's
     * meaning at the end of a whole refresh.
     *
     * @return true if unconfirmed entries should be treated as deleted
     */
    public boolean isRefreshDeletes() {
        return refreshDeletes;
    }

    /**
     * Returns the entry UUIDs this {@link Kind#SYNC_ID_SET} batch covers.
     *
     * @return unmodifiable list of entry UUIDs, empty for every other {@link Kind}
     */
    public List<byte[]> getEntryUUIDs() {
        return entryUUIDs != null ? entryUUIDs : Collections.<byte[]>emptyList();
    }

    /**
     * Parses a syncInfoValue from an IntermediateResponse's raw responseValue.
     *
     * @param responseValue the IntermediateResponse's responseValue bytes
     * @return the parsed sync info value
     * @throws Asn1Exception if the value is malformed or its CHOICE tag is unrecognised
     */
    public static SyncInfoValue parse(byte[] responseValue) throws Asn1Exception {
        if (responseValue == null) {
            throw new Asn1Exception("Empty syncInfoValue");
        }
        BerDecoder decoder = new BerDecoder();
        decoder.receive(ByteBuffer.wrap(responseValue));
        Asn1Element choice = decoder.next();
        if (choice == null) {
            throw new Asn1Exception("Empty syncInfoValue");
        }
        int tagNumber = Asn1Type.getTagNumber(choice.getTag());
        switch (tagNumber) {
            case 0:
                return new SyncInfoValue(Kind.NEW_COOKIE, choice.asOctetString(), true, false, null);
            case 1:
                return parseRefresh(Kind.REFRESH_DELETE, choice);
            case 2:
                return parseRefresh(Kind.REFRESH_PRESENT, choice);
            case 3:
                return parseSyncIdSet(choice);
            default:
                throw new Asn1Exception("Unrecognised syncInfoValue CHOICE tag " + tagNumber);
        }
    }

    private static SyncInfoValue parseRefresh(Kind kind, Asn1Element seq) {
        byte[] cookie = null;
        boolean refreshDone = true; // RFC 4533 §2.5: BOOLEAN DEFAULT TRUE
        List<Asn1Element> parts = seq.getChildren();
        if (parts != null) {
            for (Asn1Element part : parts) {
                if (part.getTag() == Asn1Type.OCTET_STRING) {
                    cookie = part.asOctetString();
                } else if (part.getTag() == Asn1Type.BOOLEAN) {
                    try {
                        refreshDone = part.asBoolean();
                    } catch (Asn1Exception e) {
                        // Malformed BOOLEAN encoding -- keep the DEFAULT
                        // TRUE rather than fail the whole message over
                        // one optional field.
                    }
                }
            }
        }
        return new SyncInfoValue(kind, cookie, refreshDone, false, null);
    }

    private static SyncInfoValue parseSyncIdSet(Asn1Element seq) {
        byte[] cookie = null;
        boolean refreshDeletes = false; // RFC 4533 §2.5: BOOLEAN DEFAULT FALSE
        List<byte[]> entryUUIDs = new ArrayList<byte[]>();
        List<Asn1Element> parts = seq.getChildren();
        if (parts != null) {
            for (Asn1Element part : parts) {
                if (part.getTag() == Asn1Type.OCTET_STRING) {
                    cookie = part.asOctetString();
                } else if (part.getTag() == Asn1Type.BOOLEAN) {
                    try {
                        refreshDeletes = part.asBoolean();
                    } catch (Asn1Exception e) {
                        // Malformed BOOLEAN encoding -- keep the DEFAULT
                        // FALSE rather than fail the whole message over
                        // one optional field.
                    }
                } else if (part.getTag() == Asn1Type.SET) {
                    List<Asn1Element> uuidElements = part.getChildren();
                    if (uuidElements != null) {
                        for (Asn1Element uuidElement : uuidElements) {
                            entryUUIDs.add(uuidElement.asOctetString());
                        }
                    }
                }
            }
        }
        return new SyncInfoValue(Kind.SYNC_ID_SET, cookie, false, refreshDeletes,
                Collections.unmodifiableList(entryUUIDs));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SyncInfoValue[").append(kind);
        if (cookie != null) {
            sb.append(", cookie=").append(cookie.length).append(" bytes");
        }
        if (kind == Kind.REFRESH_DELETE || kind == Kind.REFRESH_PRESENT) {
            sb.append(", refreshDone=").append(refreshDone);
        }
        if (kind == Kind.SYNC_ID_SET) {
            sb.append(", refreshDeletes=").append(refreshDeletes);
            sb.append(", entryUUIDs=").append(getEntryUUIDs().size());
        }
        sb.append(']');
        return sb.toString();
    }
}
