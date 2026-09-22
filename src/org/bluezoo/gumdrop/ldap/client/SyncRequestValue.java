/*
 * SyncRequestValue.java
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

import java.util.Arrays;

import org.bluezoo.gumdrop.ldap.asn1.BerEncoder;

/**
 * The value of the Sync Request Control (RFC 4533 §2.2), attached to a
 * SearchRequest to turn it into a content synchronization search:
 *
 * <pre>
 * syncRequestValue ::= SEQUENCE {
 *     mode       ENUMERATED { refreshOnly (1), refreshAndPersist (3) },
 *     cookie     syncCookie OPTIONAL,
 *     reloadHint BOOLEAN DEFAULT FALSE }
 * </pre>
 *
 * <p>Usage: build the value, wrap it in a {@link Control}, attach it via
 * {@link LdapSession#setRequestControls}, then issue the search:
 *
 * <pre>{@code
 * SyncRequestValue syncRequest = new SyncRequestValue(SyncRequestMode.REFRESH_AND_PERSIST, cookie);
 * session.setRequestControls(Collections.singletonList(syncRequest.toControl()));
 * session.search(request, new SyncReplDispatcher(syncHandler));
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.2">RFC 4533 §2.2</a>
 */
public final class SyncRequestValue {

    private final SyncRequestMode mode;
    private final byte[] cookie;
    private final boolean reloadHint;

    /**
     * Creates a sync request with no cookie (an initial sync) and no reload hint.
     *
     * @param mode the sync mode
     */
    public SyncRequestValue(SyncRequestMode mode) {
        this(mode, null, false);
    }

    /**
     * Creates a sync request resuming from a previously received cookie.
     *
     * @param mode the sync mode
     * @param cookie the last cookie received from the server, or null for an initial sync
     */
    public SyncRequestValue(SyncRequestMode mode, byte[] cookie) {
        this(mode, cookie, false);
    }

    /**
     * Creates a sync request.
     *
     * @param mode the sync mode
     * @param cookie the last cookie received from the server, or null for an initial sync
     * @param reloadHint whether the server should reload its whole
     *                   content sync state rather than relying on any
     *                   internal change log it may keep for the given cookie
     */
    public SyncRequestValue(SyncRequestMode mode, byte[] cookie, boolean reloadHint) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        this.cookie = cookie != null ? Arrays.copyOf(cookie, cookie.length) : null;
        this.reloadHint = reloadHint;
    }

    /**
     * Returns the sync mode.
     *
     * @return the sync mode
     */
    public SyncRequestMode getMode() {
        return mode;
    }

    /**
     * Returns the cookie this sync resumes from.
     *
     * @return the cookie bytes, or null for an initial sync
     */
    public byte[] getCookie() {
        return cookie != null ? Arrays.copyOf(cookie, cookie.length) : null;
    }

    /**
     * Returns the reload hint.
     *
     * @return true if the server is asked to reload its whole content sync state
     */
    public boolean isReloadHint() {
        return reloadHint;
    }

    /**
     * Encodes this value as a syncRequestValue SEQUENCE.
     *
     * @return the BER-encoded control value
     */
    public byte[] encode() {
        BerEncoder encoder = new BerEncoder();
        encoder.beginSequence();
        encoder.writeEnumerated(mode.getValue());
        if (cookie != null) {
            encoder.writeOctetString(cookie);
        }
        if (reloadHint) {
            encoder.writeBoolean(true);
        }
        encoder.endSequence();
        return encoder.toByteArray();
    }

    /**
     * Wraps this value in a critical Sync Request Control, ready to
     * attach via {@link LdapSession#setRequestControls}.
     *
     * <p>Critical, since a server that doesn't understand this control
     * would otherwise silently run a plain search instead of a content
     * synchronization one, which a caller expecting sync semantics
     * (a resumable, change-only stream of entries) must not mistake
     * for one.
     *
     * @return the Sync Request Control
     */
    public Control toControl() {
        return new Control(Control.OID_SYNC_REQUEST, true, encode());
    }
}
