/*
 * RoleBasedFtpSessionProvider.java
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

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.ftp.FtpRoles;
import org.bluezoo.gumdrop.ftp.file.BasicFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleAwareFTPFileSystem;
import org.bluezoo.gumdrop.ftp.file.RoleBasedFTPHandler;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.nio.file.Path;

/**
 * Stock {@link FtpServerSessionProvider} with role-based access control.
 *
 * <p>Authenticates users against a {@link Realm} and authorises operations
 * based on standard FTP roles ({@link FtpRoles}: ftp-admin, ftp-delete,
 * ftp-write, ftp-read).
 *
 * <p>The file system can be provided in two ways: set {@link
 * #rootDirectory(Path)} (and optionally {@link #readOnly(boolean)}) for an
 * automatically created {@link BasicFTPFileSystem}, or set {@link
 * #fileSystem(FtpFileSystem)} directly for a custom implementation.
 *
 * <pre>{@code
 * FtpServer server = FtpServer.compose()
 *         .listener(new FtpListener().port(21).bindWildcard())
 *         .sessionProvider(FtpServerSessionProviders.roleBased()
 *                 .realm(realm)
 *                 .rootDirectory(Path.of("/var/ftp"))
 *                 .welcomeMessage("Welcome to Secure FTP"))
 *         .server();
 * }</pre>
 *
 * <p>When {@link #filesystemEnforcement(boolean)} is enabled, each session's
 * file system is wrapped in a {@link RoleAwareFTPFileSystem} decorator that
 * enforces role checks at the filesystem operation level, in addition to the
 * command-level checks in {@link RoleBasedFTPHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RoleBasedFtpSessionProvider implements FtpServerSessionProvider {

    private FtpFileSystem fileSystem;
    private Path rootDirectory;
    private boolean readOnly;
    private boolean filesystemEnforcement;
    private Realm realm;
    private QuotaManager quotaManager;
    private String welcomeMessage;

    /**
     * Sets a custom file system implementation. When set, this takes
     * precedence over {@link #rootDirectory(Path)}/{@link #readOnly(boolean)}.
     */
    public RoleBasedFtpSessionProvider fileSystem(FtpFileSystem fileSystem) {
        this.fileSystem = fileSystem;
        return this;
    }

    public RoleBasedFtpSessionProvider rootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        return this;
    }

    public RoleBasedFtpSessionProvider readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Enables filesystem-level role enforcement via
     * {@link RoleAwareFTPFileSystem}.
     */
    public RoleBasedFtpSessionProvider filesystemEnforcement(boolean enabled) {
        this.filesystemEnforcement = enabled;
        return this;
    }

    public RoleBasedFtpSessionProvider realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    public RoleBasedFtpSessionProvider quotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
        return this;
    }

    public RoleBasedFtpSessionProvider welcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
        return this;
    }

    @Override
    public void start() {
        if (realm == null) {
            throw new IllegalStateException("realm must be configured");
        }
        if (fileSystem == null) {
            if (rootDirectory == null) {
                throw new IllegalStateException(
                        "Either fileSystem or rootDirectory must be configured");
            }
            fileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
        }
    }

    @Override
    public void stop() {
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        FtpFileSystem fs = fileSystem;
        if (filesystemEnforcement) {
            fs = new RoleAwareFTPFileSystem(fs, realm);
        }
        RoleBasedFTPHandler handler = new RoleBasedFTPHandler(realm, fs);
        if (welcomeMessage != null) {
            handler.setWelcomeMessage(welcomeMessage);
        }
        if (quotaManager != null) {
            handler.setQuotaManager(quotaManager);
        }
        return new LegacyConnectionHandlerAdapter(handler);
    }

}
