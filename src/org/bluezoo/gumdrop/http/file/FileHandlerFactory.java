/*
 * FileHandlerFactory.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.Headers;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating file request handlers.
 *
 * <p>When WebDAV is enabled, the factory provides RFC 2518 distributed authoring
 * support including PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, and UNLOCK methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FileHandlerFactory implements HTTPRequestHandlerFactory {

    private final Path rootPath;
    private final boolean allowWrite;
    private final boolean webdavEnabled;
    private final String allowedOptions;
    private final String[] welcomeFiles;
    private final Map<String, String> contentTypes;
    
    // Shared lock manager for WebDAV (shared across all handlers)
    private final WebDAVLockManager lockManager;

    FileHandlerFactory(Path rootPath, boolean allowWrite, String welcomeFile) {
        this(rootPath, allowWrite, welcomeFile, false);
    }

    FileHandlerFactory(Path rootPath, boolean allowWrite, String welcomeFile, boolean webdavEnabled) {
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.webdavEnabled = webdavEnabled;
        
        // Build allowed options based on capabilities
        if (webdavEnabled && allowWrite) {
            this.allowedOptions = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK";
        } else if (webdavEnabled) {
            this.allowedOptions = "OPTIONS, GET, HEAD, PROPFIND";
        } else if (allowWrite) {
            this.allowedOptions = "OPTIONS, GET, HEAD, PUT, DELETE";
        } else {
            this.allowedOptions = "OPTIONS, GET, HEAD";
        }
        
        // Parse comma-separated welcome file list
        if (welcomeFile != null && !welcomeFile.trim().isEmpty()) {
            int fileCount = 1;
            for (int i = 0; i < welcomeFile.length(); i++) {
                if (welcomeFile.charAt(i) == ',') {
                    fileCount++;
                }
            }
            welcomeFiles = new String[fileCount];
            int fileIndex = 0;
            int start = 0;
            int length = welcomeFile.length();
            while (start <= length && fileIndex < fileCount) {
                int end = welcomeFile.indexOf(',', start);
                if (end < 0) {
                    end = length;
                }
                welcomeFiles[fileIndex++] = welcomeFile.substring(start, end).trim();
                start = end + 1;
            }
        } else {
            welcomeFiles = new String[]{"index.html"};
        }
        
        // Initialize content types for WebDAV
        this.contentTypes = new HashMap<String, String>();
        contentTypes.put("html", "text/html");
        contentTypes.put("htm", "text/html");
        contentTypes.put("txt", "text/plain");
        contentTypes.put("css", "text/css");
        contentTypes.put("js", "application/javascript");
        contentTypes.put("json", "application/json");
        contentTypes.put("xml", "application/xml");
        contentTypes.put("pdf", "application/pdf");
        contentTypes.put("jpg", "image/jpeg");
        contentTypes.put("jpeg", "image/jpeg");
        contentTypes.put("png", "image/png");
        contentTypes.put("gif", "image/gif");
        contentTypes.put("svg", "image/svg+xml");
        contentTypes.put("ico", "image/x-icon");
        contentTypes.put("zip", "application/zip");
        contentTypes.put("jar", "application/java-archive");
        contentTypes.put("mp3", "audio/mpeg");
        contentTypes.put("mp4", "video/mp4");
        contentTypes.put("webm", "video/webm");
        
        // Create lock manager if WebDAV enabled
        this.lockManager = webdavEnabled ? new WebDAVLockManager() : null;
    }

    @Override
    public HTTPRequestHandler createHandler(Headers headers, HTTPResponseState state) {
        return new FileHandler(rootPath, allowWrite, webdavEnabled, allowedOptions, 
                               welcomeFiles, contentTypes, lockManager);
    }

}

