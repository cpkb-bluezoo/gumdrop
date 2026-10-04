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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;

import org.bluezoo.gumdrop.http.ContentTypes;

/**
 * URLConnection for a <code>resource:</code> URL identifying a resource in a context.
 * Supports resources in the context root, WAR file, or META-INF/resources inside
 * JARs in WEB-INF/lib (Servlet 3.0 spec section 4.6), and any entry of such a
 * JAR named as {@code /WEB-INF/lib/name.jar!/entry}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceURLConnection extends URLConnection {

    private final Context context;
    private final String resourcePath;
    private boolean connected = false;
    
    // Resource location - only one of these will be set
    private Path file;                  // Direct file in exploded context
    private String warEntryName;        // Entry in WAR file
    private String libJarFile;          // resource path of the JAR file in WEB-INF/lib
    private String libJarEntryName;     // Entry path within the lib JAR

    protected ResourceURLConnection(URL url, Context context, String resourcePath) {
        super(url);
        this.context = context;
        this.resourcePath = resourcePath;
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        
        String path = resourcePath;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        
        int separator = path.indexOf(Context.LIB_ENTRY_SEPARATOR);
        if (separator != -1 && path.startsWith("WEB-INF/lib/")) {
            // Entry of a library jar, as the context's class loader
            // names the resources on its class path
            String jarPath = "/" + path.substring(0, separator);
            String entryName = path.substring(separator + Context.LIB_ENTRY_SEPARATOR.length());
            Collection<String> jars = context.getResourcePaths("/WEB-INF/lib", false);
            if (jars != null && jars.contains(jarPath)) {
                Archive jar = context.getLibArchive(jarPath);
                if (jar.isFile(entryName)) {
                    libJarFile = jarPath;
                    libJarEntryName = entryName;
                    connected = true;
                    return;
                }
            }
            throw new FileNotFoundException(url.toString());
        }

        if (Files.isDirectory(context.root)) {
            // Exploded context - check direct file first
            Path directFile = context.root.resolve(path);
            if (Files.isRegularFile(directFile)) {
                file = directFile;
                connected = true;
                return;
            }
            
            // Check JARs in WEB-INF/lib for META-INF/resources
            if (searchLibJars(path)) {
                connected = true;
                return;
            }
        } else {
            // WAR file - check entry in WAR first, via the shared kept-open
            // handle rather than reopening the WAR for every connect().
            Archive warFile = context.getWarArchive();
            if (warFile.contains(path)) {
                warEntryName = path;
                connected = true;
                return;
            }

            // Check JARs in WEB-INF/lib for META-INF/resources
            if (searchLibJars(path)) {
                connected = true;
                return;
            }
        }
        
        throw new FileNotFoundException(url.toString());
    }
    
    /**
     * Search for the resource in META-INF/resources inside JARs in WEB-INF/lib.
     * @return true if found
     */
    private boolean searchLibJars(String path) throws IOException {
        Collection<String> jars = context.getResourcePaths("/WEB-INF/lib", false);
        if (jars == null) {
            return false;
        }
        
        String jarResourcePath = "META-INF/resources/" + path;
        for (String jarPath : jars) {
            if (!jarPath.toLowerCase().endsWith(".jar")) {
                continue;
            }
            Archive jar = context.getLibArchive(jarPath);
            if (jar.isFile(jarResourcePath)) {
                libJarFile = jarPath;
                libJarEntryName = jarResourcePath;
                return true;
            }
        }
        return false;
    }

    @Override
    public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override
    public long getContentLengthLong() {
        if (!connected) {
            return -1L;
        }
        if (file != null) {
            try {
                return Files.size(file);
            } catch (IOException e) {
                return 0L; // as File.length() for an unreadable file
            }
        } else if (warEntryName != null) {
            try {
                return context.getWarArchive().size(warEntryName);
            } catch (IOException e) {
                return -1L;
            }
        } else if (libJarFile != null) {
            try {
                return context.getLibArchive(libJarFile).size(libJarEntryName);
            } catch (IOException e) {
                return -1L;
            }
        }
        return -1L;
    }

    @Override
    public long getDate() {
        if (!connected) {
            return -1L;
        }
        if (file != null) {
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                return modified.toMillis();
            } catch (IOException e) {
                return 0L; // as File.lastModified() for an unreadable file
            }
        } else if (warEntryName != null) {
            try {
                return context.getWarArchive().time(warEntryName);
            } catch (IOException e) {
                return -1L;
            }
        } else if (libJarFile != null) {
            try {
                return context.getLibArchive(libJarFile).time(libJarEntryName);
            } catch (IOException e) {
                return -1L;
            }
        }
        return -1L;
    }

    @Override
    public String getContentType() {
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

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        if (libJarFile != null) {
            Archive jar = context.getLibArchive(libJarFile);
            // NB we cannot auto-close it, the stream owns its handle
            return jar.streamOwned(libJarEntryName);
        }
        return context.getResourceAsStream(resourcePath);
    }

}
