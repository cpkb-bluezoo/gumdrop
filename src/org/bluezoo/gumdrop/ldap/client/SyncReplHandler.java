/*
 * SyncReplHandler.java
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

import java.util.List;

/**
 * Handler for a content synchronization search (RFC 4533).
 *
 * <p>Implement this and wrap it in a {@link SyncReplDispatcher} (which
 * does the syncStateValue/syncDoneValue/syncInfoValue control parsing)
 * to pass as the {@link SearchResultHandler} for a search that carries
 * a {@link SyncRequestValue#toControl() Sync Request Control}.
 *
 * <p>For {@link SyncRequestMode#REFRESH_ONLY}, expect: zero or more
 * {@link #syncEntry} calls, then exactly one {@link #syncDone} call —
 * the search then ends, the same as an ordinary search.
 *
 * <p>For {@link SyncRequestMode#REFRESH_AND_PERSIST}, expect: zero or
 * more {@link #syncEntry} calls (the refresh phase), then a
 * {@link #syncRefreshDelete}/{@link #syncRefreshPresent} announcing the
 * refresh phase is over, after which the search stays open indefinitely
 * — further {@link #syncEntry}/{@link #syncNewCookie}/{@link #syncIdSet}
 * calls arrive as the directory changes, with no {@link #syncDone} call
 * unless the server ends the search (e.g. an error, or the client
 * abandons it).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533">RFC 4533 — LDAP Content Synchronization Operation</a>
 */
public interface SyncReplHandler {

    /**
     * Called for each entry the search returns.
     *
     * @param entry the entry
     * @param syncState how this entry relates to the client's last known
     *                  state, or null if the entry didn't carry a Sync
     *                  State control at all (a server not actually
     *                  supporting content sync despite accepting the
     *                  critical Sync Request Control would be a protocol
     *                  violation, but callers should still handle this
     *                  defensively rather than assume it can't happen)
     */
    void syncEntry(SearchResultEntry entry, SyncStateValue syncState);

    /**
     * Called for each search result reference (referral), same as
     * {@link SearchResultHandler#handleReference}.
     *
     * @param referralUrls LDAP URLs pointing to other servers
     */
    void syncReference(String[] referralUrls);

    /**
     * Called when the server sends a cookie update with no other content
     * ({@code syncInfoValue}'s {@code newcookie} choice, refreshAndPersist only).
     *
     * @param cookie the new cookie
     */
    void syncNewCookie(byte[] cookie);

    /**
     * Called when the delete phase of a refresh completes
     * ({@code syncInfoValue}'s {@code refreshDelete} choice, refreshAndPersist only).
     *
     * @param cookie the cookie at this point, or null if none was sent
     * @param refreshDone true if the whole refresh (both delete and
     *                    present phases) is now complete; false if the
     *                    present phase is still to come
     */
    void syncRefreshDelete(byte[] cookie, boolean refreshDone);

    /**
     * Called when the present phase of a refresh completes
     * ({@code syncInfoValue}'s {@code refreshPresent} choice, refreshAndPersist only).
     *
     * @param cookie the cookie at this point, or null if none was sent
     * @param refreshDone true if the whole refresh is now complete
     */
    void syncRefreshPresent(byte[] cookie, boolean refreshDone);

    /**
     * Called when the server reports a batch of entry UUIDs' sync state
     * in bulk, rather than as individual entries
     * ({@code syncInfoValue}'s {@code syncIdSet} choice, refreshAndPersist only).
     *
     * @param cookie the cookie at this point, or null if none was sent
     * @param refreshDeletes true if entries not in {@code entryUUIDs}
     *                       (and not otherwise confirmed present) should
     *                       be treated as deleted
     * @param entryUUIDs the entry UUIDs this batch covers
     */
    void syncIdSet(byte[] cookie, boolean refreshDeletes, List<byte[]> entryUUIDs);

    /**
     * Called when the search completes.
     *
     * <p>For {@link SyncRequestMode#REFRESH_ONLY} this always fires,
     * ending the sync. For {@link SyncRequestMode#REFRESH_AND_PERSIST}
     * it fires only if the server itself ends the search (e.g. an
     * error) rather than staying open in the persist phase.
     *
     * @param result the search result status
     * @param syncDone the Sync Done Control's value, or
     *                 {@link SyncDoneValue#EMPTY} if the response didn't
     *                 carry one
     */
    void syncDone(LdapResult result, SyncDoneValue syncDone);
}
