/*
 * FileHTTPServer.java
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

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPServer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connector for serving files from a filesystem root.
 *
 * <p>When WebDAV is enabled, this server supports RFC 2518 distributed authoring
 * including PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, and UNLOCK methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPServer extends HTTPServer {

    private static final Logger LOGGER = Logger.getLogger(FileHTTPServer.class.getName());

    private Path rootPath = Paths.get(".");
    private boolean allowWrite = false;
    private boolean webdavEnabled = false;
    private String welcomeFile = "index.html"; // Default welcome file list

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
     * <p>When enabled, the server supports PROPFIND, PROPPATCH, MKCOL, COPY, MOVE,
     * LOCK, and UNLOCK methods in addition to the standard HTTP methods.
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
        this.welcomeFile = (welcomeFile != null && !welcomeFile.trim().isEmpty()) 
                          ? welcomeFile.trim() 
                          : "index.html";
    }

    @Override
    public Connection newConnection(SocketChannel channel, SSLEngine engine) {
        return new FileHTTPConnection(channel, engine, secure, rootPath, allowWrite, 
                welcomeFile, webdavEnabled);
    }

    @Override
    public String getDescription() {
        String webdav = webdavEnabled ? ", webdav: enabled" : "";
        return String.format("%s file server (root: %s, write: %s, welcome: %s%s)", 
                            super.getDescription(), rootPath, allowWrite, welcomeFile, webdav);
    }

    /**
     * Validates that the root path is safe for use as a file server root.
     * This prevents configuration errors that could lead to security issues.
     */
    private void validateRootPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Root path cannot be null");
        }

        try {
            // Resolve to canonical path to handle symbolic links
            Path realPath = path.toRealPath();
            
            // Ensure it's a directory
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException("Root path must be a directory: " + realPath);
            }
            
            // Ensure it's readable
            if (!Files.isReadable(realPath)) {
                throw new IllegalArgumentException("Root path must be readable: " + realPath);
            }
            
            // Warn about writable roots if write operations are enabled
            if (allowWrite && !Files.isWritable(realPath)) {
                LOGGER.warning("Root path is not writable but write operations are enabled: " + realPath);
            }
            
            // Security: Log the canonical path being used
            LOGGER.info("File server root validated: " + realPath + 
                       " (write: " + allowWrite + ")");
                       
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot access root path: " + path + " - " + e.getMessage(), e);
        }
    }
}
