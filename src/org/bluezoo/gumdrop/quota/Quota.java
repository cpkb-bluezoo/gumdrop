/*
 * Quota.java
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
 * Represents a user's quota limits and current usage.
 * 
 * <p>A quota defines limits on storage (in bytes) and optionally message
 * count (for mail systems). A limit of -1 indicates unlimited.</p>
 * 
 * <h4>Usage Example</h4>
 * <pre>{@code
 * // Create a 1GB storage quota
 * Quota quota = new Quota(1073741824L, -1);
 * 
 * // Update usage
 * quota.setStorageUsed(524288000L); // 500MB used
 * 
 * // Check status
 * if (quota.isStorageExceeded()) {
 *     // Deny further uploads
 * }
 * 
 * long remaining = quota.getStorageRemaining(); // ~500MB
 * int percent = quota.getStoragePercentUsed();  // 50
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuotaManager
 */
public class Quota {
    
    /** Constant representing unlimited quota. */
    public static final long UNLIMITED = -1;
    
    private final long storageLimit;
    private final long messageLimit;
    private long storageUsed;
    private long messageCount;
    private QuotaSource source;
    private String sourceDetail;
    
    /**
     * Creates a quota with the specified limits.
     * 
     * @param storageLimit the storage limit in bytes, or -1 for unlimited
     * @param messageLimit the message count limit, or -1 for unlimited
     */
    public Quota(long storageLimit, long messageLimit) {
        this.storageLimit = storageLimit;
        this.messageLimit = messageLimit;
        this.storageUsed = 0;
        this.messageCount = 0;
        this.source = QuotaSource.NONE;
    }
    
    /**
     * Creates a copy of another quota with the same limits but zero usage.
     * 
     * @param other the quota to copy limits from
     */
    public Quota(Quota other) {
        this.storageLimit = other.storageLimit;
        this.messageLimit = other.messageLimit;
        this.storageUsed = 0;
        this.messageCount = 0;
        this.source = other.source;
        this.sourceDetail = other.sourceDetail;
    }
    
    /**
     * Creates an unlimited quota.
     * 
     * @return a quota with no limits
     */
    public static Quota unlimited() {
        Quota quota = new Quota(UNLIMITED, UNLIMITED);
        quota.setSource(QuotaSource.NONE, null);
        return quota;
    }
    
    // Getters
    
    /**
     * Gets the storage limit in bytes.
     * 
     * @return the storage limit, or -1 if unlimited
     */
    public long getStorageLimit() {
        return storageLimit;
    }
    
    /**
     * Gets the message count limit.
     * 
     * @return the message limit, or -1 if unlimited
     */
    public long getMessageLimit() {
        return messageLimit;
    }
    
    /**
     * Gets the current storage used in bytes.
     * 
     * @return bytes currently used
     */
    public long getStorageUsed() {
        return storageUsed;
    }
    
    /**
     * Gets the current message count.
     * 
     * @return number of messages
     */
    public long getMessageCount() {
        return messageCount;
    }
    
    /**
     * Gets the storage limit in kilobytes (for IMAP QUOTA response).
     * 
     * @return the storage limit in KB, or -1 if unlimited
     */
    public long getStorageLimitKB() {
        return storageLimit < 0 ? UNLIMITED : storageLimit / 1024;
    }
    
    /**
     * Gets the storage used in kilobytes (for IMAP QUOTA response).
     * 
     * @return bytes used in KB
     */
    public long getStorageUsedKB() {
        return storageUsed / 1024;
    }
    
    /**
     * Gets the source of this quota.
     * 
     * @return the quota source
     */
    public QuotaSource getSource() {
        return source;
    }
    
    /**
     * Gets additional detail about the quota source.
     * 
     * @return source detail (e.g., role name), or null
     */
    public String getSourceDetail() {
        return sourceDetail;
    }
    
    // Setters
    
    /**
     * Sets the current storage used.
     * 
     * @param storageUsed bytes currently used
     */
    public void setStorageUsed(long storageUsed) {
        this.storageUsed = storageUsed;
    }
    
    /**
     * Sets the current message count.
     * 
     * @param messageCount number of messages
     */
    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }
    
    /**
     * Sets the source information for this quota.
     * 
     * @param source the quota source
     * @param detail additional detail (e.g., role name)
     */
    public void setSource(QuotaSource source, String detail) {
        this.source = source;
        this.sourceDetail = detail;
    }
    
    // Calculations
    
    /**
     * Checks if the storage limit has been exceeded.
     * 
     * @return true if storage used equals or exceeds the limit
     */
    public boolean isStorageExceeded() {
        return storageLimit >= 0 && storageUsed >= storageLimit;
    }
    
    /**
     * Checks if the message limit has been exceeded.
     * 
     * @return true if message count equals or exceeds the limit
     */
    public boolean isMessageLimitExceeded() {
        return messageLimit >= 0 && messageCount >= messageLimit;
    }
    
    /**
     * Checks if the storage limit is unlimited.
     * 
     * @return true if no storage limit is set
     */
    public boolean isStorageUnlimited() {
        return storageLimit < 0;
    }
    
    /**
     * Checks if the message limit is unlimited.
     * 
     * @return true if no message limit is set
     */
    public boolean isMessageUnlimited() {
        return messageLimit < 0;
    }
    
    /**
     * Gets the remaining storage in bytes.
     * 
     * @return bytes remaining, or Long.MAX_VALUE if unlimited
     */
    public long getStorageRemaining() {
        if (storageLimit < 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, storageLimit - storageUsed);
    }
    
    /**
     * Gets the remaining message count.
     * 
     * @return messages remaining, or Long.MAX_VALUE if unlimited
     */
    public long getMessagesRemaining() {
        if (messageLimit < 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, messageLimit - messageCount);
    }
    
    /**
     * Gets the percentage of storage used.
     * 
     * @return percentage (0-100), or 0 if unlimited
     */
    public int getStoragePercentUsed() {
        if (storageLimit <= 0) {
            return 0;
        }
        return (int) Math.min(100, (storageUsed * 100) / storageLimit);
    }
    
    /**
     * Gets the percentage of message quota used.
     * 
     * @return percentage (0-100), or 0 if unlimited
     */
    public int getMessagePercentUsed() {
        if (messageLimit <= 0) {
            return 0;
        }
        return (int) Math.min(100, (messageCount * 100) / messageLimit);
    }
    
    /**
     * Checks if additional storage can be added.
     * 
     * @param additionalBytes bytes to be added
     * @return true if adding the bytes would not exceed the limit
     */
    public boolean canAddStorage(long additionalBytes) {
        if (storageLimit < 0) {
            return true;
        }
        return (storageUsed + additionalBytes) <= storageLimit;
    }
    
    /**
     * Checks if an additional message can be added.
     * 
     * @return true if adding a message would not exceed the limit
     */
    public boolean canAddMessage() {
        if (messageLimit < 0) {
            return true;
        }
        return (messageCount + 1) <= messageLimit;
    }
    
    /**
     * Adds to the storage used.
     * 
     * @param bytes bytes to add
     */
    public void addStorageUsed(long bytes) {
        this.storageUsed += bytes;
    }
    
    /**
     * Subtracts from the storage used.
     * 
     * @param bytes bytes to subtract
     */
    public void subtractStorageUsed(long bytes) {
        this.storageUsed = Math.max(0, this.storageUsed - bytes);
    }
    
    /**
     * Increments the message count.
     */
    public void incrementMessageCount() {
        this.messageCount++;
    }
    
    /**
     * Decrements the message count.
     */
    public void decrementMessageCount() {
        this.messageCount = Math.max(0, this.messageCount - 1);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Quota{");
        sb.append("storage=");
        if (storageLimit < 0) {
            sb.append("unlimited");
        } else {
            sb.append(storageUsed).append("/").append(storageLimit);
        }
        sb.append(", messages=");
        if (messageLimit < 0) {
            sb.append("unlimited");
        } else {
            sb.append(messageCount).append("/").append(messageLimit);
        }
        if (source != null && source != QuotaSource.NONE) {
            sb.append(", source=").append(source);
            if (sourceDetail != null) {
                sb.append("(").append(sourceDetail).append(")");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}

