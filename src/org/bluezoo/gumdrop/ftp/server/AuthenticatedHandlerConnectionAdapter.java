/*
 * AuthenticatedHandlerConnectionAdapter.java
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

import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.ftp.FtpOperation;

import java.nio.ByteBuffer;

/**
 * Adapts an {@link AuthenticatedHandler} to legacy {@link FtpConnectionHandler}
 * callbacks used by the data-transfer coordinator until it is restaged.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class AuthenticatedHandlerConnectionAdapter
        implements FtpConnectionHandler {

    private final AuthenticatedHandler handler;
    private final FtpConnectionMetadata metadata;

    public AuthenticatedHandlerConnectionAdapter(AuthenticatedHandler handler,
            FtpConnectionMetadata metadata) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.metadata = metadata;
    }

    @Override
    public String connected(FtpConnectionMetadata metadata) {
        return null;
    }

    @Override
    public FtpAuthenticationResult authenticate(String username, String password,
            String account, FtpConnectionMetadata metadata) {
        return FtpAuthenticationResult.SUCCESS;
    }

    @Override
    public FtpFileSystem getFileSystem(FtpConnectionMetadata metadata) {
        return handler.getFileSystem();
    }

    @Override
    public void transferStarting(String path, boolean upload, long size,
            FtpConnectionMetadata metadata) {
        handler.transferStarting(path, upload, size);
    }

    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data,
            long totalBytesTransferred, FtpConnectionMetadata metadata) {
        handler.transferProgress(path, upload, data, totalBytesTransferred);
    }

    @Override
    public void transferCompleted(String path, boolean upload,
            long totalBytesTransferred, boolean success,
            FtpConnectionMetadata metadata) {
        handler.transferCompleted(path, upload, totalBytesTransferred, success);
    }

    @Override
    public FtpFileOperationResult handleSiteCommand(String command,
            FtpConnectionMetadata metadata) {
        return handler.handleSiteCommand(command);
    }

    @Override
    public void disconnected(FtpConnectionMetadata metadata) {
    }

    @Override
    public boolean isAuthorized(FtpOperation operation, String path,
            FtpConnectionMetadata metadata) {
        return handler.isAuthorized(operation, path);
    }

    @Override
    public org.bluezoo.gumdrop.quota.QuotaManager getQuotaManager() {
        return handler.getQuotaManager();
    }

    @Override
    public boolean canStore(String username, long bytesToStore,
            FtpConnectionMetadata metadata) {
        return handler.canStore(username, bytesToStore);
    }

    @Override
    public org.bluezoo.gumdrop.quota.Quota getQuota(String username,
            FtpConnectionMetadata metadata) {
        return handler.getQuota(username);
    }

    @Override
    public void recordBytesAdded(String username, long bytesAdded,
            FtpConnectionMetadata metadata) {
        handler.recordBytesAdded(username, bytesAdded);
    }

    @Override
    public void recordBytesRemoved(String username, long bytesRemoved,
            FtpConnectionMetadata metadata) {
        handler.recordBytesRemoved(username, bytesRemoved);
    }

}
