/*
 * RoleBasedQuotaManager.java
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

import org.bluezoo.gumdrop.auth.Realm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A quota manager that supports both user-specific and role-based quotas.
 * 
 * <p>This implementation uses a {@link Realm} for role membership checks
 * and persists usage data to the filesystem.</p>
 * 
 * <h4>Quota Resolution</h4>
 * <ol>
 *   <li><b>User quota</b> - Specific override for a user (highest priority)</li>
 *   <li><b>Role quota</b> - Based on user's role memberships (most generous wins)</li>
 *   <li><b>Default quota</b> - System-wide fallback</li>
 *   <li><b>Unlimited</b> - If none of the above are defined</li>
 * </ol>
 * 
 * <h4>Configuration Example</h4>
 * <pre>{@code
 * <component id="quotaManager" class="org.bluezoo.gumdrop.quota.RoleBasedQuotaManager">
 *   <property name="realm" ref="#mainRealm"/>
 *   <property name="storage-dir">/var/gumdrop/quota</property>
 *   <property name="default-quota">1GB</property>
 *   
 *   <!-- Role-based quotas -->
 *   <property name="role-quota.admin">unlimited</property>
 *   <property name="role-quota.premium">10GB</property>
 *   <property name="role-quota.standard">1GB</property>
 *   <property name="role-quota.guest">100MB</property>
 * </component>
 * }</pre>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuotaManager
 * @see Quota
 */
public class RoleBasedQuotaManager implements QuotaManager {
    
    private static final Logger LOGGER = Logger.getLogger(RoleBasedQuotaManager.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.quota.L10N");
    
    private Realm realm;
    private File storageDir;
    private QuotaPolicy defaultPolicy;
    
    // Role name -> QuotaPolicy
    private final Map<String, QuotaPolicy> rolePolicies;
    
    // Username -> QuotaPolicy (user-specific overrides)
    private final Map<String, QuotaPolicy> userPolicies;
    
    // Username -> Quota (cached quotas with usage data)
    private final Map<String, Quota> userQuotas;
    
    /**
     * Creates a new role-based quota manager.
     */
    public RoleBasedQuotaManager() {
        this.rolePolicies = new ConcurrentHashMap<String, QuotaPolicy>();
        this.userPolicies = new ConcurrentHashMap<String, QuotaPolicy>();
        this.userQuotas = new ConcurrentHashMap<String, Quota>();
    }
    
    /**
     * Sets the realm for role membership checks.
     * 
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }
    
    /**
     * Sets the directory for storing quota usage data.
     * 
     * @param storageDir the storage directory path
     */
    public void setStorageDir(String storageDir) {
        this.storageDir = new File(storageDir);
        if (!this.storageDir.exists()) {
            this.storageDir.mkdirs();
        }
    }
    
    /**
     * Sets the default quota for users without role-based quotas.
     * 
     * @param quota the default quota (e.g., "1GB", "unlimited")
     */
    public void setDefaultQuota(String quota) {
        long limit = QuotaPolicy.parseSize(quota);
        this.defaultPolicy = new QuotaPolicy("default", limit);
    }
    
    /**
     * Sets the default quota policy.
     * 
     * @param policy the default quota policy
     */
    public void setDefaultPolicy(QuotaPolicy policy) {
        this.defaultPolicy = policy;
    }
    
    /**
     * Adds a role-based quota policy.
     * 
     * @param role the role name
     * @param storageLimit storage limit string (e.g., "10GB")
     */
    public void addRoleQuota(String role, String storageLimit) {
        addRoleQuota(role, storageLimit, "-1");
    }
    
    /**
     * Adds a role-based quota policy with message limit.
     * 
     * @param role the role name
     * @param storageLimit storage limit string
     * @param messageLimit message limit string
     */
    public void addRoleQuota(String role, String storageLimit, String messageLimit) {
        long storage = QuotaPolicy.parseSize(storageLimit);
        long messages = Long.parseLong(messageLimit);
        rolePolicies.put(role, new QuotaPolicy(role, storage, messages));
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.role_policy_added"), 
                role, QuotaPolicy.formatSize(storage)));
        }
    }
    
    /**
     * Sets a role quota (for DI property injection like roleQuota.admin=10GB).
     * 
     * @param role the role name
     * @param quota the quota value
     */
    public void setRoleQuota(String role, String quota) {
        addRoleQuota(role, quota);
    }
    
    @Override
    public Quota getQuota(String username) {
        // Check cache first
        Quota cached = userQuotas.get(username);
        if (cached != null) {
            return cached;
        }
        
        // Resolve quota based on priority
        Quota quota = resolveQuota(username);
        
        // Load any existing usage data
        loadUserUsage(username, quota);
        
        // Cache it
        userQuotas.put(username, quota);
        
        return quota;
    }
    
    /**
     * Resolves the effective quota for a user based on priority.
     */
    private Quota resolveQuota(String username) {
        // 1. Check for user-specific policy
        QuotaPolicy userPolicy = userPolicies.get(username);
        if (userPolicy != null) {
            Quota quota = userPolicy.createQuota(QuotaSource.USER);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.user_quota_applied"),
                    username, QuotaPolicy.formatSize(quota.getStorageLimit())));
            }
            return quota;
        }
        
        // 2. Find best quota from user's roles
        if (realm != null) {
            Quota roleQuota = findBestRoleQuota(username);
            if (roleQuota != null) {
                return roleQuota;
            }
        }
        
        // 3. Use default policy
        if (defaultPolicy != null) {
            Quota quota = defaultPolicy.createQuota(QuotaSource.DEFAULT);
            return quota;
        }
        
        // 4. No quota (unlimited)
        return Quota.unlimited();
    }
    
    /**
     * Finds the most generous quota from all roles the user has.
     */
    private Quota findBestRoleQuota(String username) {
        long maxStorage = 0;
        long maxMessages = 0;
        String bestRole = null;
        boolean foundAny = false;
        
        for (Map.Entry<String, QuotaPolicy> entry : rolePolicies.entrySet()) {
            String role = entry.getKey();
            if (realm.isUserInRole(username, role)) {
                QuotaPolicy policy = entry.getValue();
                foundAny = true;
                
                // -1 means unlimited, which is always "higher"
                if (policy.getStorageLimit() < 0) {
                    maxStorage = Quota.UNLIMITED;
                    bestRole = role;
                } else if (maxStorage >= 0 && policy.getStorageLimit() > maxStorage) {
                    maxStorage = policy.getStorageLimit();
                    bestRole = role;
                }
                
                if (policy.getMessageLimit() < 0) {
                    maxMessages = Quota.UNLIMITED;
                } else if (maxMessages >= 0 && policy.getMessageLimit() > maxMessages) {
                    maxMessages = policy.getMessageLimit();
                }
            }
        }
        
        if (!foundAny) {
            return null;
        }
        
        Quota quota = new Quota(maxStorage, maxMessages);
        quota.setSource(QuotaSource.ROLE, bestRole);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.role_quota_applied"),
                username, bestRole, QuotaPolicy.formatSize(maxStorage)));
        }
        
        return quota;
    }
    
    @Override
    public void recalculateUsage(String username) {
        // Clear cached quota to force reload
        userQuotas.remove(username);
        
        // The next getQuota() call will recalculate
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.usage_recalculated"), username));
        }
    }
    
    @Override
    public boolean canStore(String username, long additionalBytes) {
        Quota quota = getQuota(username);
        return quota.canAddStorage(additionalBytes);
    }
    
    @Override
    public boolean canStoreMessage(String username) {
        Quota quota = getQuota(username);
        return quota.canAddMessage();
    }
    
    @Override
    public void recordBytesAdded(String username, long bytesAdded) {
        Quota quota = getQuota(username);
        quota.addStorageUsed(bytesAdded);
        saveUserUsage(username, quota);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.bytes_added"),
                username, QuotaPolicy.formatSize(bytesAdded), 
                QuotaPolicy.formatSize(quota.getStorageUsed())));
        }
    }
    
    @Override
    public void recordBytesRemoved(String username, long bytesRemoved) {
        Quota quota = getQuota(username);
        quota.subtractStorageUsed(bytesRemoved);
        saveUserUsage(username, quota);
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("quota.log.bytes_removed"),
                username, QuotaPolicy.formatSize(bytesRemoved),
                QuotaPolicy.formatSize(quota.getStorageUsed())));
        }
    }
    
    @Override
    public void recordMessageAdded(String username, long messageSize) {
        Quota quota = getQuota(username);
        quota.addStorageUsed(messageSize);
        quota.incrementMessageCount();
        saveUserUsage(username, quota);
    }
    
    @Override
    public void recordMessageRemoved(String username, long messageSize) {
        Quota quota = getQuota(username);
        quota.subtractStorageUsed(messageSize);
        quota.decrementMessageCount();
        saveUserUsage(username, quota);
    }
    
    @Override
    public void setUserQuota(String username, long storageLimit, long messageLimit) {
        QuotaPolicy policy = new QuotaPolicy(username, storageLimit, messageLimit);
        userPolicies.put(username, policy);
        
        // Clear cached quota to force recalculation
        userQuotas.remove(username);
        
        // Persist the user policy
        saveUserPolicy(username, policy);
        
        LOGGER.info(MessageFormat.format(L10N.getString("quota.log.user_quota_set"),
            username, QuotaPolicy.formatSize(storageLimit)));
    }
    
    @Override
    public void clearUserQuota(String username) {
        userPolicies.remove(username);
        userQuotas.remove(username);
        
        // Remove persisted user policy
        deleteUserPolicy(username);
        
        LOGGER.info(MessageFormat.format(L10N.getString("quota.log.user_quota_cleared"), username));
    }
    
    @Override
    public boolean hasUserQuota(String username) {
        return userPolicies.containsKey(username);
    }
    
    @Override
    public void saveUsageData() {
        for (Map.Entry<String, Quota> entry : userQuotas.entrySet()) {
            saveUserUsage(entry.getKey(), entry.getValue());
        }
        LOGGER.info(MessageFormat.format(L10N.getString("quota.log.usage_saved"), 
            String.valueOf(userQuotas.size())));
    }
    
    @Override
    public void loadUsageData() {
        if (storageDir == null || !storageDir.exists()) {
            return;
        }
        
        File[] usageFiles = storageDir.listFiles();
        if (usageFiles == null) {
            return;
        }
        
        int loaded = 0;
        for (File file : usageFiles) {
            if (file.getName().endsWith(".usage")) {
                String username = file.getName().replace(".usage", "");
                Quota quota = getQuota(username);
                loadUserUsage(username, quota);
                loaded++;
            } else if (file.getName().endsWith(".policy")) {
                String username = file.getName().replace(".policy", "");
                loadUserPolicy(username);
                loaded++;
            }
        }
        
        if (loaded > 0) {
            LOGGER.info(MessageFormat.format(L10N.getString("quota.log.usage_loaded"), 
                String.valueOf(loaded)));
        }
    }
    
    // Persistence methods
    
    private void saveUserUsage(String username, Quota quota) {
        if (storageDir == null) {
            return;
        }
        
        File file = new File(storageDir, username + ".usage");
        Properties props = new Properties();
        props.setProperty("storage.used", String.valueOf(quota.getStorageUsed()));
        props.setProperty("message.count", String.valueOf(quota.getMessageCount()));
        props.setProperty("last.updated", String.valueOf(System.currentTimeMillis()));
        
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file));
            props.store(writer, "Quota usage for " + username);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.save_usage_failed"), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
    
    private void loadUserUsage(String username, Quota quota) {
        if (storageDir == null) {
            return;
        }
        
        File file = new File(storageDir, username + ".usage");
        if (!file.exists()) {
            return;
        }
        
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            Properties props = new Properties();
            props.load(reader);
            
            String storageUsed = props.getProperty("storage.used");
            if (storageUsed != null) {
                quota.setStorageUsed(Long.parseLong(storageUsed));
            }
            
            String messageCount = props.getProperty("message.count");
            if (messageCount != null) {
                quota.setMessageCount(Long.parseLong(messageCount));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.load_usage_failed"), e);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.load_usage_failed"), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
    
    private void saveUserPolicy(String username, QuotaPolicy policy) {
        if (storageDir == null) {
            return;
        }
        
        File file = new File(storageDir, username + ".policy");
        Properties props = new Properties();
        props.setProperty("storage.limit", String.valueOf(policy.getStorageLimit()));
        props.setProperty("message.limit", String.valueOf(policy.getMessageLimit()));
        
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file));
            props.store(writer, "User quota policy for " + username);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.save_policy_failed"), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
    
    private void loadUserPolicy(String username) {
        if (storageDir == null) {
            return;
        }
        
        File file = new File(storageDir, username + ".policy");
        if (!file.exists()) {
            return;
        }
        
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            Properties props = new Properties();
            props.load(reader);
            
            long storageLimit = Long.parseLong(props.getProperty("storage.limit", "-1"));
            long messageLimit = Long.parseLong(props.getProperty("message.limit", "-1"));
            
            userPolicies.put(username, new QuotaPolicy(username, storageLimit, messageLimit));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.load_policy_failed"), e);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, L10N.getString("quota.err.load_policy_failed"), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
    
    private void deleteUserPolicy(String username) {
        if (storageDir == null) {
            return;
        }
        
        File file = new File(storageDir, username + ".policy");
        if (file.exists()) {
            file.delete();
        }
    }
}

