/*
 * AuthenticatedHandlerConnectionAdapter.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.handler;

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
