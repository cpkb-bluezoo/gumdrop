/*
 * AuthenticatedHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.handler;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.ftp.FtpOperation;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;

/**
 * Handler for an authenticated FTP session (RFC 959 §4.1.2–§4.1.3).
 *
 * <p>File and directory commands are executed by the protocol handler after
 * consulting {@link #isAuthorized}. {@link #getFileSystem()} supplies the
 * storage view for the logged-in user.
 */
public interface AuthenticatedHandler {

    /**
     * Returns the file system for this session.
     */
    FtpFileSystem getFileSystem();

    /**
     * Checks whether the authenticated user may perform an operation.
     */
    default boolean isAuthorized(FtpOperation operation, String path) {
        return true;
    }

    default void transferStarting(String path, boolean upload, long size) {
    }

    default void transferProgress(String path, boolean upload, ByteBuffer data,
            long totalBytesTransferred) {
    }

    default void transferCompleted(String path, boolean upload,
            long totalBytesTransferred, boolean success) {
    }

    default FtpFileOperationResult handleSiteCommand(String command) {
        return FtpFileOperationResult.NOT_SUPPORTED;
    }

    default QuotaManager getQuotaManager() {
        return null;
    }

    default boolean canStore(String username, long bytesToStore) {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            return true;
        }
        return quotaManager.canStore(username, bytesToStore);
    }

    default Quota getQuota(String username) {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager == null) {
            return null;
        }
        return quotaManager.getQuota(username);
    }

    default void recordBytesAdded(String username, long bytesAdded) {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager != null) {
            quotaManager.recordBytesAdded(username, bytesAdded);
        }
    }

    default void recordBytesRemoved(String username, long bytesRemoved) {
        QuotaManager quotaManager = getQuotaManager();
        if (quotaManager != null) {
            quotaManager.recordBytesRemoved(username, bytesRemoved);
        }
    }

}
