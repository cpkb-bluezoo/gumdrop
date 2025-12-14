/*
 * SearchResultHandler.java
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

package org.bluezoo.gumdrop.ldap.client;

/**
 * Handler for search operation results.
 * 
 * <p>Search results are delivered incrementally. This handler receives:
 * <ol>
 * <li>Zero or more {@link #handleEntry} calls for matching entries</li>
 * <li>Zero or more {@link #handleReference} calls for referrals</li>
 * <li>Exactly one {@link #handleDone} call when the search completes</li>
 * </ol>
 * 
 * <p>The order of entries and references depends on the server
 * implementation and may be interleaved.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPSession#search
 */
public interface SearchResultHandler {

    /**
     * Called for each matching entry.
     * 
     * <p>This method may be called zero or more times, once for each
     * entry that matches the search criteria.
     * 
     * @param entry the search result entry
     */
    void handleEntry(SearchResultEntry entry);

    /**
     * Called for each search result reference (referral).
     * 
     * <p>Referrals indicate that part of the search results may be
     * available from other servers. The handler can choose to follow
     * referrals by opening new connections to the referenced servers.
     * 
     * @param referralUrls LDAP URLs pointing to other servers
     */
    void handleReference(String[] referralUrls);

    /**
     * Called when the search operation completes.
     * 
     * <p>This is always called exactly once, after all entries and
     * references have been delivered. The result indicates the overall
     * status of the search.
     * 
     * <p>Common result codes:
     * <ul>
     * <li>{@code SUCCESS} - search completed normally</li>
     * <li>{@code SIZE_LIMIT_EXCEEDED} - more entries exist but limit reached</li>
     * <li>{@code TIME_LIMIT_EXCEEDED} - search took too long</li>
     * <li>{@code NO_SUCH_OBJECT} - base DN does not exist</li>
     * </ul>
     * 
     * @param result the search result status
     * @param session operations for further directory access
     */
    void handleDone(LDAPResult result, LDAPSession session);

}

