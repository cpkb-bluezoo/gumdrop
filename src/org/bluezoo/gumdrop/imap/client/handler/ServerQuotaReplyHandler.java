/*
 * ServerQuotaReplyHandler.java
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

package org.bluezoo.gumdrop.imap.client.handler;

/**
 * Callback interface for QUOTA command responses (RFC 9208).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ServerQuotaReplyHandler {

    /**
     * Called for each QUOTA response line.
     *
     * @param quotaRoot the quota root name
     * @param resourceName the resource name (e.g. "STORAGE", "MESSAGE")
     * @param usage the current usage
     * @param limit the resource limit
     */
    void handleQuota(String quotaRoot, String resourceName,
                     long usage, long limit);

    /**
     * Called for each QUOTAROOT response line.
     *
     * @param mailbox the mailbox name
     * @param quotaRoots the associated quota root names
     */
    void handleQuotaRoot(String mailbox, String[] quotaRoots);

    /**
     * Called when the QUOTA command completes successfully.
     */
    void handleQuotaComplete();

    /**
     * Called when the QUOTA command fails.
     *
     * @param message the error message
     */
    void handleQuotaError(String message);
}
