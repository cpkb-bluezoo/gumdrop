/*
 * RoleAwareFTPFileSystem.java
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

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FTPConnectionMetadata;
import org.bluezoo.gumdrop.ftp.FTPFileInfo;
import org.bluezoo.gumdrop.ftp.FTPFileOperationResult;
import org.bluezoo.gumdrop.ftp.FTPFileSystem;

/**
 * Decorator that enforces role-based access control on an
 * {@link FTPFileSystem}.
 *
 * <p>Each file operation checks the authenticated user's roles via the
 * configured {@link Realm}:
 * <ul>
 *   <li><b>Read operations</b> ({@code listDirectory}, {@code openForReading},
 *       {@code getFileInfo}, {@code changeDirectory}): require the read role
 *       (default {@code "ftp-read"}).</li>
 *   <li><b>Write operations</b> ({@code openForWriting},
 *       {@code createDirectory}, {@code rename}, {@code generateUniqueName},
 *       {@code allocateSpace}): require the write role
 *       (default {@code "ftp-write"}).</li>
 *   <li><b>Delete operations</b> ({@code deleteFile},
 *       {@code removeDirectory}): require the delete role
 *       (default {@code "ftp-delete"}).</li>
 * </ul>
 *
 * <p>Optionally, home directory confinement can be enabled via
 * {@link #setHomeDirectoryConfinement(boolean)}. When active, all paths
 * are verified to be under {@code /home/<username>}; requests outside
 * that tree are denied.
 *
 * <p>Role names are configurable via setters so deployments can use
 * their own naming convention.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * FTPFileSystem base = new BasicFTPFileSystem(rootPath);
 * RoleAwareFTPFileSystem secured =
 *         new RoleAwareFTPFileSystem(base, realm);
 * secured.setHomeDirectoryConfinement(true);
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see FTPFileSystem
 * @see Realm
 */
public class RoleAwareFTPFileSystem implements FTPFileSystem {

    private static final Logger LOGGER =
            Logger.getLogger(RoleAwareFTPFileSystem.class.getName());

    private final FTPFileSystem delegate;
    private final Realm realm;

    private String readRole = "ftp-read";
    private String writeRole = "ftp-write";
    private String deleteRole = "ftp-delete";
    private boolean homeDirectoryConfinement;

    /**
     * Creates a role-aware file system wrapping the given delegate.
     *
     * @param delegate the underlying file system
     * @param realm    the realm for role checks
     */
    public RoleAwareFTPFileSystem(FTPFileSystem delegate, Realm realm) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        if (realm == null) {
            throw new NullPointerException("realm");
        }
        this.delegate = delegate;
        this.realm = realm;
    }

    // ── Configuration ──

    /**
     * Sets the role required for read operations.
     *
     * @param role the read role name (default {@code "ftp-read"})
     */
    public void setReadRole(String role) {
        this.readRole = role;
    }

    /**
     * Sets the role required for write operations.
     *
     * @param role the write role name (default {@code "ftp-write"})
     */
    public void setWriteRole(String role) {
        this.writeRole = role;
    }

    /**
     * Sets the role required for delete operations.
     *
     * @param role the delete role name (default {@code "ftp-delete"})
     */
    public void setDeleteRole(String role) {
        this.deleteRole = role;
    }

    /**
     * Enables or disables home directory confinement.
     *
     * <p>When enabled, users are restricted to
     * {@code /home/<username>/} and its descendants.
     *
     * @param enabled true to confine users to their home directory
     */
    public void setHomeDirectoryConfinement(boolean enabled) {
        this.homeDirectoryConfinement = enabled;
    }

    // ── Read operations ──

    @Override
    public List<FTPFileInfo> listDirectory(String path,
                                           FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, readRole) || !checkConfinement(path, metadata)) {
            return null;
        }
        return delegate.listDirectory(path, metadata);
    }

    @Override
    public DirectoryChangeResult changeDirectory(String path,
                                                 String currentDirectory,
                                                 FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, readRole) || !checkConfinement(path, metadata)) {
            return new DirectoryChangeResult(
                    FTPFileOperationResult.ACCESS_DENIED, currentDirectory);
        }
        return delegate.changeDirectory(path, currentDirectory, metadata);
    }

    @Override
    public FTPFileInfo getFileInfo(String path,
                                   FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, readRole) || !checkConfinement(path, metadata)) {
            return null;
        }
        return delegate.getFileInfo(path, metadata);
    }

    @Override
    public ReadableByteChannel openForReading(String path,
                                              long restartOffset,
                                              FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, readRole) || !checkConfinement(path, metadata)) {
            return null;
        }
        return delegate.openForReading(path, restartOffset, metadata);
    }

    // ── Write operations ──

    @Override
    public WritableByteChannel openForWriting(String path,
                                              boolean append,
                                              FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, writeRole) || !checkConfinement(path, metadata)) {
            return null;
        }
        return delegate.openForWriting(path, append, metadata);
    }

    @Override
    public FTPFileOperationResult createDirectory(String path,
                                                  FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, writeRole) || !checkConfinement(path, metadata)) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        return delegate.createDirectory(path, metadata);
    }

    @Override
    public FTPFileOperationResult rename(String fromPath, String toPath,
                                         FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, writeRole)
                || !checkConfinement(fromPath, metadata)
                || !checkConfinement(toPath, metadata)) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        return delegate.rename(fromPath, toPath, metadata);
    }

    @Override
    public UniqueNameResult generateUniqueName(String basePath,
                                               String suggestedName,
                                               FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, writeRole) || !checkConfinement(basePath, metadata)) {
            return new UniqueNameResult(
                    FTPFileOperationResult.ACCESS_DENIED, null);
        }
        return delegate.generateUniqueName(basePath, suggestedName, metadata);
    }

    @Override
    public FTPFileOperationResult allocateSpace(String path, long size,
                                                FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, writeRole) || !checkConfinement(path, metadata)) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        return delegate.allocateSpace(path, size, metadata);
    }

    // ── Delete operations ──

    @Override
    public FTPFileOperationResult deleteFile(String path,
                                             FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, deleteRole) || !checkConfinement(path, metadata)) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        return delegate.deleteFile(path, metadata);
    }

    @Override
    public FTPFileOperationResult removeDirectory(String path,
                                                  FTPConnectionMetadata metadata) {
        if (!checkRole(metadata, deleteRole) || !checkConfinement(path, metadata)) {
            return FTPFileOperationResult.ACCESS_DENIED;
        }
        return delegate.removeDirectory(path, metadata);
    }

    // ── Internal helpers ──

    private boolean checkRole(FTPConnectionMetadata metadata, String role) {
        String user = metadata.getAuthenticatedUser();
        if (user == null) {
            return false;
        }
        if (realm.isUserInRole(user, role)) {
            return true;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Access denied for user " + user
                    + ": missing role " + role);
        }
        return false;
    }

    private boolean checkConfinement(String path,
                                     FTPConnectionMetadata metadata) {
        if (!homeDirectoryConfinement) {
            return true;
        }
        String user = metadata.getAuthenticatedUser();
        if (user == null) {
            return false;
        }
        String homePrefix = "/home/" + user + "/";
        String homePath = "/home/" + user;
        String normalized = normalizePath(path);
        if (normalized.equals(homePath) || normalized.startsWith(homePrefix)) {
            return true;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Home confinement denied for user " + user
                    + ": path " + path + " is outside " + homePath);
        }
        return false;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.endsWith("/") && path.length() > 1) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
