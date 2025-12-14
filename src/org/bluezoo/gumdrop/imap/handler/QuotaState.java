/*
 * QuotaState.java
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

package org.bluezoo.gumdrop.imap.handler;

import java.util.List;
import java.util.Map;

/**
 * Operations available when responding to quota commands.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see AuthenticatedHandler#getQuota
 * @see AuthenticatedHandler#getQuotaRoot
 * @see AuthenticatedHandler#setQuota
 */
public interface QuotaState {

    /**
     * Proceeds with the quota operation using the QuotaManager.
     * 
     * <p>The server will query or update quota using the configured
     * QuotaManager.
     * 
     * @param handler continues receiving authenticated commands
     */
    void proceed(AuthenticatedHandler handler);

    /**
     * Quota is not supported by this server.
     * 
     * @param handler continues receiving authenticated commands
     */
    void quotaNotSupported(AuthenticatedHandler handler);

    /**
     * Sends quota information.
     * 
     * <p>For GETQUOTA: sends the quota for the specified root.
     * For SETQUOTA: sends the updated quota.
     * 
     * <p>Each resource entry has: [usage, limit].
     * 
     * @param quotaRoot the quota root name
     * @param resources map of resource name to [usage, limit]
     * @param handler continues receiving authenticated commands
     */
    void sendQuota(String quotaRoot, Map<String, long[]> resources, AuthenticatedHandler handler);

    /**
     * Sends quota root information for GETQUOTAROOT.
     * 
     * @param mailboxName the mailbox name
     * @param quotaRoots the quota roots that apply to this mailbox
     * @param quotas map of quota root to its resources (resource to [usage, limit])
     * @param handler continues receiving authenticated commands
     */
    void sendQuotaRoots(String mailboxName, List<String> quotaRoots,
                        Map<String, Map<String, long[]>> quotas, AuthenticatedHandler handler);

    /**
     * Quota root not found or no permission.
     * 
     * @param message the error message
     * @param handler continues receiving authenticated commands
     */
    void quotaFailed(String message, AuthenticatedHandler handler);

    /**
     * Server is shutting down, close gracefully.
     * 
     * <p>Sends an untagged BYE response indicating the server is shutting
     * down and closes the connection.
     */
    void serverShuttingDown();

}
