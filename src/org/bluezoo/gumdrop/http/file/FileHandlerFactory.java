/*
 * FileHandlerFactory.java
 * Copyright (C) 2025 Chris Burdess
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
import java.util.Set;

/**
 * Factory for creating file request handlers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class FileHandlerFactory implements HTTPRequestHandlerFactory {

    private final Path rootPath;
    private final boolean allowWrite;
    private final String allowedOptions;
    private final String[] welcomeFiles;

    FileHandlerFactory(Path rootPath, boolean allowWrite, String welcomeFile) {
        this.rootPath = rootPath;
        this.allowWrite = allowWrite;
        this.allowedOptions = allowWrite ? "OPTIONS, GET, HEAD, PUT, DELETE" : "OPTIONS, GET, HEAD";
        
        // Parse comma-separated welcome file list
        if (welcomeFile != null && !welcomeFile.trim().isEmpty()) {
            String[] files = welcomeFile.split(",");
            welcomeFiles = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                welcomeFiles[i] = files[i].trim();
            }
        } else {
            welcomeFiles = new String[]{"index.html"};
        }
    }

    @Override
    public HTTPRequestHandler createHandler(Headers headers, HTTPResponseState state) {
        return new FileHandler(rootPath, allowWrite, allowedOptions, welcomeFiles);
    }

}

