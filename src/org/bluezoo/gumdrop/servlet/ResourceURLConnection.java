/*
 * ResourceURLConnection.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.bluezoo.gumdrop.http.ContentTypes;

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
    private String jarEntryName;

    protected ResourceURLConnection(URL url, Context context, String resourcePath) {
        super(url);
        this.context = context;
        this.resourcePath = resourcePath;
    }

    // TODO it's more complex than this because we can have resources inside
    // the WEB-INF/resources directory of a jar in the WEB-INF/lib
    // directory. Deal with this

    @Override public void connect() throws IOException {
        if (!connected) {
            String path = resourcePath;
            if (context.root.isDirectory()) { // file-based
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
                try (JarFile warFile = new JarFile(context.root)) {
                    JarEntry jarEntry = warFile.getJarEntry(path);
                    if (jarEntry != null) {
                        jarEntryName = path;
                    } else {
                        throw new FileNotFoundException(url.toString());
                    }
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
        if (file != null) { // file
            return file.length();
        } else { // jar
            try (JarFile warFile = new JarFile(context.root)) {
                JarEntry jarEntry = warFile.getJarEntry(jarEntryName);
                return (jarEntry != null) ? jarEntry.getSize() : -1L;
            } catch (IOException e) {
                // log
                return -1L;
            }
        }
    }

    @Override public long getDate() {
        if (!connected) {
            return -1L;
        }
        if (file != null) { // file
            return file.lastModified();
        } else { // jar
            try (JarFile warFile = new JarFile(context.root)) {
                JarEntry jarEntry = warFile.getJarEntry(jarEntryName);
                return (jarEntry != null) ? jarEntry.getTime() : -1L;
            } catch (IOException e) {
                // log
                return -1L;
            }
        }
    }

    @Override public String getContentType() {
        String contentType = context.getMimeType(resourcePath);
        if (contentType == null) {
            int di = resourcePath.lastIndexOf('.');
            if (di != -1) {
                String extension = resourcePath.substring(di + 1);
                contentType = ContentTypes.getContentType(extension);
            }
        }
        return contentType;
    }

    @Override public InputStream getInputStream() throws IOException {
        connect();
        return context.getResourceAsStream(resourcePath);
    }

}
