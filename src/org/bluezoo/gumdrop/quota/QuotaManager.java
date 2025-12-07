/*
 * QuotaManager.java
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

package org.bluezoo.gumdrop.quota;

/**
 * Manages storage quotas for users.
 * 
 * <p>A quota manager provides the interface for checking and updating
 * user quotas. It supports both user-specific quotas and role-based
 * quotas, with user-specific taking precedence.</p>
 * 
 * <h4>Quota Resolution Priority</h4>
 * <ol>
 *   <li>User-specific quota (highest priority)</li>
 *   <li>Role-based quota (if user has roles with quotas)</li>
 *   <li>Default quota (system-wide fallback)</li>
 *   <li>Unlimited (if no quota is configured)</li>
 * </ol>
 * 
 * <h4>Usage Example</h4>
 * <pre>{@code
 * QuotaManager quotaManager = ...;
 * 
 * // Check if user can store a file
 * if (!quotaManager.canStore("user1", fileSize)) {
 *     throw new QuotaExceededException("Storage quota exceeded");
 * }
 * 
 * // After successful store
 * quotaManager.recordBytesAdded("user1", fileSize);
 * 
 * // After delete
 * quotaManager.recordBytesRemoved("user1", fileSize);
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Quota
 * @see QuotaSource
 */
public interface QuotaManager {
    
    /**
     * Gets the effective quota for a user.
     * 
     * <p>This returns the quota that applies to the user based on the
     * priority order: user-specific, role-based, default, or unlimited.</p>
     * 
     * @param username the username
     * @return the user's effective quota (never null)
     */
    Quota getQuota(String username);
    
    /**
     * Recalculates and updates the current usage for a user.
     * 
     * <p>This method forces a full recalculation of the user's storage
     * usage, typically by scanning their mailbox or file system.</p>
     * 
     * @param username the username
     */
    void recalculateUsage(String username);
    
    /**
     * Checks if a user can store additional bytes.
     * 
     * @param username the username
     * @param additionalBytes bytes to be added
     * @return true if the operation is allowed, false if it would exceed quota
     */
    boolean canStore(String username, long additionalBytes);
    
    /**
     * Checks if a user can store an additional message (for mail systems).
     * 
     * @param username the username
     * @return true if the operation is allowed, false if it would exceed quota
     */
    boolean canStoreMessage(String username);
    
    /**
     * Records bytes added after a successful store operation.
     * 
     * @param username the username
     * @param bytesAdded bytes that were added
     */
    void recordBytesAdded(String username, long bytesAdded);
    
    /**
     * Records bytes removed after a successful delete operation.
     * 
     * @param username the username
     * @param bytesRemoved bytes that were removed
     */
    void recordBytesRemoved(String username, long bytesRemoved);
    
    /**
     * Records a message added (for mail systems).
     * 
     * @param username the username
     * @param messageSize size of the message in bytes
     */
    void recordMessageAdded(String username, long messageSize);
    
    /**
     * Records a message removed (for mail systems).
     * 
     * @param username the username
     * @param messageSize size of the message in bytes
     */
    void recordMessageRemoved(String username, long messageSize);
    
    /**
     * Sets a user-specific quota, overriding any role-based quota.
     * 
     * <p>This is typically an administrative operation.</p>
     * 
     * @param username the username
     * @param storageLimit storage limit in bytes, or -1 for unlimited
     * @param messageLimit message count limit, or -1 for unlimited
     */
    void setUserQuota(String username, long storageLimit, long messageLimit);
    
    /**
     * Clears a user-specific quota, reverting to role-based quota.
     * 
     * @param username the username
     */
    void clearUserQuota(String username);
    
    /**
     * Checks if a user has a user-specific quota defined.
     * 
     * @param username the username
     * @return true if a user-specific quota exists
     */
    boolean hasUserQuota(String username);
    
    /**
     * Persists current quota usage data.
     * 
     * <p>Called periodically or on shutdown to ensure usage data is saved.</p>
     */
    void saveUsageData();
    
    /**
     * Loads quota usage data from persistent storage.
     * 
     * <p>Called on startup to restore previous usage state.</p>
     */
    void loadUsageData();
}

