/*
 * ResourceURLConnection.java
 * Copyright (C) 2025 Chris Burdess
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
                JarEntry jarEntry = warFile.getJarEntry(resourcePath);
                return jarEntry.getSize();
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
                JarEntry jarEntry = warFile.getJarEntry(resourcePath);
                return jarEntry.getTime();
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
