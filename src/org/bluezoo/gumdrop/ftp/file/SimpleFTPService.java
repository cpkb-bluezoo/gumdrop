/*
 * SimpleFTPService.java
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
import org.bluezoo.gumdrop.ftp.FTPService;

/**
 * FTP service for basic file-based access with optional realm
 * authentication.
 *
 * <p>This service provides a simple FTP server that serves files from a
 * configured root directory. When a {@link Realm} is set, users are
 * authenticated against it; otherwise any non-empty password is accepted.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.ftp.file.SimpleFTPService">
 *   <property name="root-directory">/var/ftp</property>
 *   <property name="read-only">false</property>
 *   <property name="realm" ref="#ftpRealm"/>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener" port="21"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPService
 * @see SimpleFTPHandler
 */
public class SimpleFTPService extends FTPService {

    private static final Logger LOGGER =
            Logger.getLogger(SimpleFTPService.class.getName());

    private Path rootDirectory;
    private boolean readOnly = false;
    private Realm realm;

    private BasicFTPFileSystem fileSystem;

    // ── Configuration ──

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

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    // ── FTPService hooks ──

    @Override
    protected void initService() {
        if (rootDirectory == null) {
            throw new IllegalStateException(
                    "rootDirectory must be configured");
        }
        fileSystem = new BasicFTPFileSystem(rootDirectory, readOnly);
        LOGGER.info("SimpleFTPService initialised: root=" + rootDirectory
                + ", readOnly=" + readOnly
                + ", realm=" + (realm != null));
    }

    @Override
    protected FTPConnectionHandler createHandler(TCPListener endpoint) {
        return new SimpleFTPHandler(fileSystem, realm);
    }

}
