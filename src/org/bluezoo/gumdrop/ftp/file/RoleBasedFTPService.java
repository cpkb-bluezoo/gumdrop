/*
 * RoleBasedFTPService.java
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

package org.bluezoo.gumdrop.ftp.file;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FTPConnectionHandler;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;
import org.bluezoo.gumdrop.ftp.FTPService;
import org.bluezoo.gumdrop.quota.QuotaManager;

/**
 * FTP service with role-based access control.
 *
 * <p>This service authenticates users against a {@link Realm} and
 * authorises operations based on standard FTP roles (ftp-admin,
 * ftp-delete, ftp-write, ftp-read).
 *
 * <p>The file system can be provided in two ways:
 * <ul>
 *   <li>Set {@code rootDirectory} (and optionally {@code readOnly}) to
 *       have a {@link BasicFTPFileSystem} created automatically.</li>
 *   <li>Set {@code fileSystem} directly for a custom
 *       {@link FTPFileSystem} implementation.</li>
 * </ul>
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.ftp.file.RoleBasedFTPService">
 *   <property name="realm" ref="#ftpRealm"/>
 *   <property name="root-directory">/var/ftp</property>
 *   <property name="welcome-message">Welcome to Secure FTP</property>
 *   <property name="quota-manager" ref="#quotaManager"/>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener" port="21"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPService
 * @see RoleBasedFTPHandler
 * @see org.bluezoo.gumdrop.ftp.FTPRoles
 */
public class RoleBasedFTPService extends FTPService {

    private static final Logger LOGGER =
            Logger.getLogger(RoleBasedFTPService.class.getName());

    private Realm realm;
    private FTPFileSystem fileSystem;
    private Path rootDirectory;
    private boolean readOnly = false;
    private QuotaManager quotaManager;
    private String welcomeMessage;

    // ── Configuration ──

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    public FTPFileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Sets a custom file system implementation. When set, this takes
     * precedence over {@code rootDirectory}/{@code readOnly}.
     *
     * @param fileSystem the file system
     */
    public void setFileSystem(FTPFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = Path.of(rootDirectory);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public QuotaManager getQuotaManager() {
        return quotaManager;
    }

    public void setQuotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    // ── FTPService hooks ──

    @Override
    protected void initService() {
        if (realm == null) {
            throw new IllegalStateException("realm must be configured");
        }
        if (fileSystem == null) {
            if (rootDirectory == null) {
                throw new IllegalStateException(
                        "Either fileSystem or rootDirectory must be "
                        + "configured");
            }
            fileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
        }
        LOGGER.info("RoleBasedFTPService initialised: realm="
                + realm.getClass().getSimpleName()
                + ", quota=" + (quotaManager != null));
    }

    @Override
    protected FTPConnectionHandler createHandler(TCPListener endpoint) {
        RoleBasedFTPHandler handler =
                new RoleBasedFTPHandler(realm, fileSystem);
        if (welcomeMessage != null) {
            handler.setWelcomeMessage(welcomeMessage);
        }
        if (quotaManager != null) {
            handler.setQuotaManager(quotaManager);
        }
        return handler;
    }

}
