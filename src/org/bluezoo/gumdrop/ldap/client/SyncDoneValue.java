/*
 * SyncDoneValue.java
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
import org.bluezoo.gumdrop.ldap.asn1.Asn1Type;
import org.bluezoo.gumdrop.ldap.asn1.BerDecoder;

/**
 * The value of the Sync Done Control (RFC 4533 §2.4), attached to
 * SearchResultDone at the end of a refresh:
 *
 * <pre>
 * syncDoneValue ::= SEQUENCE {
 *     cookie         syncCookie OPTIONAL,
 *     refreshDeletes BOOLEAN DEFAULT FALSE }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.4">RFC 4533 §2.4</a>
 */
public final class SyncDoneValue {

    /**
     * The value used when a search completed without a Sync Done
     * Control at all — e.g. a plain (non-sync) search, or a server that
     * doesn't attach one when there is nothing to report.
     */
    public static final SyncDoneValue EMPTY = new SyncDoneValue(null, false);

    private final byte[] cookie;
    private final boolean refreshDeletes;

    private SyncDoneValue(byte[] cookie, boolean refreshDeletes) {
        this.cookie = cookie;
        this.refreshDeletes = refreshDeletes;
    }

    /**
     * Returns the final cookie for this refresh.
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
     * Returns whether entries present before this refresh but not seen
     * during it should be treated as deleted.
     *
     * <p>RFC 4533 §3.4: when true, the client must present/delete-phase
     * every entry it holds (from the prior sync) that the refresh did
     * not confirm as still present via a {@code present} or {@code add}/
     * {@code modify} syncStateValue.
     *
     * @return true if unconfirmed entries should be treated as deleted
     */
    public boolean isRefreshDeletes() {
        return refreshDeletes;
    }

    /**
     * Parses a syncDoneValue from a Sync Done Control's raw value.
     *
     * @param value the control value bytes, or null (an absent OPTIONAL
     *              controlValue, equivalent to an empty syncDoneValue)
     * @return the parsed sync done value
     * @throws Asn1Exception if the value is present but malformed
     */
    public static SyncDoneValue parse(byte[] value) throws Asn1Exception {
        if (value == null) {
            return EMPTY;
        }
        BerDecoder decoder = new BerDecoder();
        decoder.receive(ByteBuffer.wrap(value));
        Asn1Element seq = decoder.next();
        if (seq == null) {
            return EMPTY;
        }
        byte[] cookie = null;
        boolean refreshDeletes = false;
        List<Asn1Element> parts = seq.getChildren();
        if (parts != null) {
            for (Asn1Element part : parts) {
                if (part.getTag() == Asn1Type.OCTET_STRING) {
                    cookie = part.asOctetString();
                } else if (part.getTag() == Asn1Type.BOOLEAN) {
                    refreshDeletes = part.asBoolean();
                }
            }
        }
        return new SyncDoneValue(cookie, refreshDeletes);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SyncDoneValue[");
        if (cookie != null) {
            sb.append("cookie=").append(cookie.length).append(" bytes, ");
        }
        sb.append("refreshDeletes=").append(refreshDeletes).append(']');
        return sb.toString();
    }
}
