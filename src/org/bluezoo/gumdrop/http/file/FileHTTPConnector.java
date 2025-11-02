/*
 * FileHTTPConnector.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.http.file;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPConnector;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * HTTP connector for serving files from a filesystem root.
 * This replaces the older FileBasedHTTPConnector.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FileHTTPConnector extends HTTPConnector {

    private static final Logger LOGGER = Logger.getLogger(FileHTTPConnector.class.getName());

    private Path rootPath = Paths.get(".");
    private boolean allowWrite = false;
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
        return new FileHTTPConnection(channel, engine, secure, rootPath, allowWrite, welcomeFile);
    }

    @Override
    public String getDescription() {
        return String.format("%s file server (root: %s, write: %s, welcome: %s)", 
                            super.getDescription(), rootPath, allowWrite, welcomeFile);
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
