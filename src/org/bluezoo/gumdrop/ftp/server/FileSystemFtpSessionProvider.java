/*
 * FileSystemFtpSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.nio.file.Path;

/**
 * Stock {@link FtpServerSessionProvider} for local filesystem FTP access.
 *
 * <pre>{@code
 * FtpServer server = FtpServer.compose()
 *         .listener(new FtpListener().port(21).bindWildcard())
 *         .realm(realm)
 *         .sessionProvider(FtpServerSessionProviders.fileSystem()
 *                 .rootDirectory(Path.of("/var/ftp")))
 *         .server();
 * }</pre>
 */
public final class FileSystemFtpSessionProvider implements FtpServerSessionProvider {

    private Path rootDirectory;
    private boolean readOnly;
    private Realm realm;
    private QuotaManager quotaManager;
    private String welcomeMessage;

    private BasicFTPFileSystem sharedFileSystem;

    public FileSystemFtpSessionProvider rootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    public FileSystemFtpSessionProvider readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public FileSystemFtpSessionProvider realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    public FileSystemFtpSessionProvider quotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
        return this;
    }

    public FileSystemFtpSessionProvider welcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
        return this;
    }

    @Override
    public void start() {
        if (rootDirectory == null) {
            return;
        }
        sharedFileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
    }

    @Override
    public void stop() {
        sharedFileSystem = null;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (sharedFileSystem == null && rootDirectory != null) {
            sharedFileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
        }
        return new DefaultFtpHandler(sharedFileSystem, realm, quotaManager,
                welcomeMessage);
    }

}
