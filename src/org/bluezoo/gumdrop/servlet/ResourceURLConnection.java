/*
 * ResourceURLConnection.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;

/**
 * URLConnection for a <code>resource:</code> URL identifying a resource in a context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceURLConnection extends URLConnection {

    private final Context context;
    private final String resourcePath;
    private boolean connected = false;
    private File file;
    private JarEntry jarEntry;

    protected ResourceURLConnection(URL url, Context context, String resourcePath) {
        super(url);
        this.context = context;
        this.resourcePath = resourcePath;
    }

    @Override public void connect() throws IOException {
        if (!connected) {
            String path = resourcePath;
            // The context has either a warFile or a root
            if (context.warFile == null) { // file-based
                if (File.separatorChar != '/') {
                    path = path.replace('/', File.separatorChar);
                }
                file = new File(context.root, path);
                if (!file.exists() || !file.isFile()) {
                    throw new FileNotFoundException(url.toString());
                }
            } else { // jar-based
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                jarEntry = context.warFile.getJarEntry(path);
                if (jarEntry == null) {
                    throw new FileNotFoundException(url.toString());
                }
            }
            connected = true;
        }
    }

    @Override public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override public long getContentLengthLong() {
        if (!connected) {
            return -1L;
        }
        if (jarEntry == null) { // file
            return file.length();
        } else { // jarEntry
            return jarEntry.getSize();
        }
    }

    @Override public long getDate() {
        if (!connected) {
            return -1L;
        }
        if (jarEntry == null) { // file
            return file.lastModified();
        } else { // jarEntry
            return jarEntry.getTime();
        }
    }

    @Override public String getContentType() {
        return context.getMimeType(resourcePath);
    }

    @Override public InputStream getInputStream() throws IOException {
        connect();
        if (jarEntry == null) { // file
            return new FileInputStream(file);
        } else { // jarEntry
            return context.warFile.getInputStream(jarEntry);
        }
    }

}
