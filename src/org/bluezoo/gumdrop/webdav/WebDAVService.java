/*
 * WebDAVService.java
 * Copyright (C) 2005, 2013, 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.webdav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPService;

/**
 * HTTP service for serving files from a filesystem root with optional
 * WebDAV (RFC 2518) distributed authoring support.
 *
 * <p>When WebDAV is enabled, this service supports PROPFIND, PROPPATCH,
 * MKCOL, COPY, MOVE, LOCK, and UNLOCK methods in addition to the
 * standard HTTP methods.
 *
 * <p>Transport endpoints (ports, TLS configuration) are defined by
 * adding listeners via {@link #addListener}. The service creates a
 * {@link FileHandlerFactory} during {@link #initService()} and wires
 * it into each listener.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPService
 */
public class WebDAVService extends HTTPService {

    private static final Logger LOGGER =
            Logger.getLogger(WebDAVService.class.getName());

    private Path rootPath = Paths.get(".");
    private boolean allowWrite = false;
    private boolean webdavEnabled = false;
    private String welcomeFile = "index.html";

    private FileHandlerFactory handlerFactory;

    // ── Configuration ──

    public Path getRootPath() {
        return rootPath;
    }

    public void setRootPath(Path rootPath) {
        validateRootPath(rootPath);
        this.rootPath = rootPath;
    }

    public void setRootPath(String rootPath) {
        Path path = Paths.get(rootPath);
        validateRootPath(path);
        this.rootPath = path;
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    /**
     * Returns whether WebDAV (RFC 2518) support is enabled.
     *
     * @return true if WebDAV is enabled
     */
    public boolean isWebdavEnabled() {
        return webdavEnabled;
    }

    /**
     * Enables or disables WebDAV (RFC 2518) support.
     *
     * <p>When enabled, the service supports PROPFIND, PROPPATCH, MKCOL,
     * COPY, MOVE, LOCK, and UNLOCK methods in addition to the standard
     * HTTP methods.
     *
     * @param webdavEnabled true to enable WebDAV
     */
    public void setWebdavEnabled(boolean webdavEnabled) {
        this.webdavEnabled = webdavEnabled;
    }

    public String getWelcomeFile() {
        return welcomeFile;
    }

    public void setWelcomeFile(String welcomeFile) {
        this.welcomeFile = (welcomeFile != null
                && !welcomeFile.trim().isEmpty())
                ? welcomeFile.trim()
                : "index.html";
    }

    // ── HTTPService hooks ──

    /**
     * Builds the handler factory on startup.
     */
    @Override
    protected void initService() {
        handlerFactory = new FileHandlerFactory(
                rootPath, allowWrite, welcomeFile, webdavEnabled);
    }

    @Override
    protected HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    // ── Validation ──

    /**
     * Validates that the root path is safe for use as a file server root.
     */
    private void validateRootPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException(
                    "Root path cannot be null");
        }

        try {
            Path realPath = path.toRealPath();

            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException(
                        "Root path must be a directory: " + realPath);
            }

            if (!Files.isReadable(realPath)) {
                throw new IllegalArgumentException(
                        "Root path must be readable: " + realPath);
            }

            if (allowWrite && !Files.isWritable(realPath)) {
                LOGGER.warning("Root path is not writable but "
                        + "write operations are enabled: " + realPath);
            }

            LOGGER.info("File server root validated: " + realPath
                    + " (write: " + allowWrite + ")");

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Cannot access root path: " + path
                            + " - " + e.getMessage(), e);
        }
    }

}
