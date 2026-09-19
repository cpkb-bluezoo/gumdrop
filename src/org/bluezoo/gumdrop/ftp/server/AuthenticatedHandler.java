/*
 * AuthenticatedHandler.java
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

package org.bluezoo.gumdrop.ftp.server;

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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
