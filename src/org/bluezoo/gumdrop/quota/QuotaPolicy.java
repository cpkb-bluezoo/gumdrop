/*
 * QuotaPolicy.java
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
 * Defines quota limits for a role or as a default policy.
 * 
 * <p>A quota policy specifies the storage and message limits that apply
 * to users who are members of a particular role, or as the system default.</p>
 * 
 * <h4>Size Parsing</h4>
 * <p>The {@link #parseSize(String)} method accepts human-readable sizes:</p>
 * <ul>
 *   <li>{@code "100MB"} - 100 megabytes</li>
 *   <li>{@code "10GB"} - 10 gigabytes</li>
 *   <li>{@code "1TB"} - 1 terabyte</li>
 *   <li>{@code "unlimited"} or {@code "-1"} - no limit</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuotaManager
 */
public class QuotaPolicy {
    
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.quota.L10N");
    
    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * 1024L * 1024L;
    private static final long TB = 1024L * 1024L * 1024L * 1024L;
    
    private final String name;
    private final long storageLimit;
    private final long messageLimit;
    
    /**
     * Creates a quota policy with the specified limits.
     * 
     * @param name the policy name (typically a role name)
     * @param storageLimit storage limit in bytes, or -1 for unlimited
     * @param messageLimit message count limit, or -1 for unlimited
     */
    public QuotaPolicy(String name, long storageLimit, long messageLimit) {
        this.name = name;
        this.storageLimit = storageLimit;
        this.messageLimit = messageLimit;
    }
    
    /**
     * Creates a quota policy with storage limit only.
     * 
     * @param name the policy name
     * @param storageLimit storage limit in bytes, or -1 for unlimited
     */
    public QuotaPolicy(String name, long storageLimit) {
        this(name, storageLimit, Quota.UNLIMITED);
    }
    
    /**
     * Gets the policy name.
     * 
     * @return the policy name (typically a role name)
     */
    public String getName() {
        return name;
    }
    
    /**
     * Gets the storage limit in bytes.
     * 
     * @return storage limit, or -1 for unlimited
     */
    public long getStorageLimit() {
        return storageLimit;
    }
    
    /**
     * Gets the message count limit.
     * 
     * @return message limit, or -1 for unlimited
     */
    public long getMessageLimit() {
        return messageLimit;
    }
    
    /**
     * Checks if this policy has unlimited storage.
     * 
     * @return true if storage is unlimited
     */
    public boolean isStorageUnlimited() {
        return storageLimit < 0;
    }
    
    /**
     * Checks if this policy has unlimited messages.
     * 
     * @return true if messages are unlimited
     */
    public boolean isMessageUnlimited() {
        return messageLimit < 0;
    }
    
    /**
     * Creates a Quota from this policy with zero usage.
     * 
     * @param source the quota source
     * @return a new Quota with this policy's limits
     */
    public Quota createQuota(QuotaSource source) {
        Quota quota = new Quota(storageLimit, messageLimit);
        quota.setSource(source, name);
        return quota;
    }
    
    /**
     * Parses a human-readable size string into bytes.
     * 
     * <p>Supported formats:</p>
     * <ul>
     *   <li>Plain number: "1234567890" (bytes)</li>
     *   <li>Kilobytes: "100KB" or "100K"</li>
     *   <li>Megabytes: "100MB" or "100M"</li>
     *   <li>Gigabytes: "10GB" or "10G"</li>
     *   <li>Terabytes: "1TB" or "1T"</li>
     *   <li>Unlimited: "unlimited" or "-1"</li>
     * </ul>
     * 
     * @param sizeStr the size string to parse
     * @return size in bytes, or -1 for unlimited
     * @throws IllegalArgumentException if the format is invalid
     */
    public static long parseSize(String sizeStr) {
        if (sizeStr == null) {
            throw new IllegalArgumentException(L10N.getString("quota.exception.size_null"));
        }
        
        sizeStr = sizeStr.trim().toUpperCase();
        
        if (sizeStr.equals("UNLIMITED") || sizeStr.equals("-1")) {
            return Quota.UNLIMITED;
        }
        
        long multiplier = 1;
        String numberPart = sizeStr;
        
        if (sizeStr.endsWith("TB")) {
            multiplier = TB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("T")) {
            multiplier = TB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 1);
        } else if (sizeStr.endsWith("GB")) {
            multiplier = GB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("G")) {
            multiplier = GB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 1);
        } else if (sizeStr.endsWith("MB")) {
            multiplier = MB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("M")) {
            multiplier = MB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 1);
        } else if (sizeStr.endsWith("KB")) {
            multiplier = KB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("K")) {
            multiplier = KB;
            numberPart = sizeStr.substring(0, sizeStr.length() - 1);
        }
        
        try {
            long value = Long.parseLong(numberPart.trim());
            return value * multiplier;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                MessageFormat.format(L10N.getString("quota.exception.size_invalid"), sizeStr));
        }
    }
    
    /**
     * Formats a byte size as a human-readable string.
     * 
     * @param bytes the size in bytes
     * @return formatted string (e.g., "1.5 GB")
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return L10N.getString("quota.status.unlimited");
        }
        if (bytes >= TB) {
            return MessageFormat.format(L10N.getString("quota.size.tb"), 
                String.format("%.1f", (double) bytes / TB));
        }
        if (bytes >= GB) {
            return MessageFormat.format(L10N.getString("quota.size.gb"), 
                String.format("%.1f", (double) bytes / GB));
        }
        if (bytes >= MB) {
            return MessageFormat.format(L10N.getString("quota.size.mb"), 
                String.format("%.1f", (double) bytes / MB));
        }
        if (bytes >= KB) {
            return MessageFormat.format(L10N.getString("quota.size.kb"), 
                String.format("%.1f", (double) bytes / KB));
        }
        return MessageFormat.format(L10N.getString("quota.size.bytes"), String.valueOf(bytes));
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("QuotaPolicy{");
        sb.append("name='").append(name).append("'");
        sb.append(", storage=").append(formatSize(storageLimit));
        if (messageLimit >= 0) {
            sb.append(", messages=").append(messageLimit);
        }
        sb.append("}");
        return sb.toString();
    }
}

