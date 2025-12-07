/*
 * QuotaExceededException.java
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

import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * Exception thrown when an operation would exceed a user's quota.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuotaExceededException extends Exception {
    
    private static final long serialVersionUID = 1L;
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.quota.L10N");
    
    private final String username;
    private final Quota quota;
    private final long requestedBytes;
    
    /**
     * Creates a new quota exceeded exception.
     * 
     * @param message the error message
     */
    public QuotaExceededException(String message) {
        super(message);
        this.username = null;
        this.quota = null;
        this.requestedBytes = 0;
    }
    
    /**
     * Creates a new quota exceeded exception with details.
     * 
     * @param username the user who exceeded quota
     * @param quota the user's quota
     * @param requestedBytes bytes that were requested
     */
    public QuotaExceededException(String username, Quota quota, long requestedBytes) {
        super(buildMessage(username, quota, requestedBytes));
        this.username = username;
        this.quota = quota;
        this.requestedBytes = requestedBytes;
    }
    
    private static String buildMessage(String username, Quota quota, long requestedBytes) {
        return MessageFormat.format(
            L10N.getString("quota.exception.exceeded"),
            username,
            QuotaPolicy.formatSize(requestedBytes),
            QuotaPolicy.formatSize(quota.getStorageRemaining()),
            QuotaPolicy.formatSize(quota.getStorageLimit())
        );
    }
    
    /**
     * Gets the username associated with this exception.
     * 
     * @return the username, or null if not specified
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * Gets the quota that was exceeded.
     * 
     * @return the quota, or null if not specified
     */
    public Quota getQuota() {
        return quota;
    }
    
    /**
     * Gets the number of bytes that were requested.
     * 
     * @return the requested bytes
     */
    public long getRequestedBytes() {
        return requestedBytes;
    }
}

