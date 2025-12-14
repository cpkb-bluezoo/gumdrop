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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.bluezoo.gumdrop.http.ContentTypes;

/**
 * URLConnection for a <code>resource:</code> URL identifying a resource in a context.
 * Supports resources in the context root, WAR file, or META-INF/resources inside
 * JARs in WEB-INF/lib (Servlet 3.0 spec section 4.6).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ResourceURLConnection extends URLConnection {

    private final Context context;
    private final String resourcePath;
    private boolean connected = false;
    
    // Resource location - only one of these will be set
    private File file;                  // Direct file in exploded context
    private String warEntryName;        // Entry in WAR file
    private File libJarFile;            // JAR file in WEB-INF/lib
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
        
        if (context.root.isDirectory()) {
            // Exploded context - check direct file first
            String filePath = path;
            if (File.separatorChar != '/') {
                filePath = filePath.replace('/', File.separatorChar);
            }
            File directFile = new File(context.root, filePath);
            if (directFile.exists() && directFile.isFile()) {
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
            // WAR file - check entry in WAR first
            try (JarFile warFile = new JarFile(context.root)) {
                JarEntry jarEntry = warFile.getJarEntry(path);
                if (jarEntry != null) {
                    warEntryName = path;
                    connected = true;
                    return;
                }
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
            File jarFile = context.getLibFile(jarPath);
            if (jarFile == null) {
                continue;
            }
            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry entry = jar.getJarEntry(jarResourcePath);
                if (entry != null && !entry.isDirectory()) {
                    libJarFile = jarFile;
                    libJarEntryName = jarResourcePath;
                    return true;
                }
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
            return file.length();
        } else if (warEntryName != null) {
            try (JarFile warFile = new JarFile(context.root)) {
                JarEntry jarEntry = warFile.getJarEntry(warEntryName);
                return (jarEntry != null) ? jarEntry.getSize() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        } else if (libJarFile != null) {
            try (JarFile jar = new JarFile(libJarFile)) {
                JarEntry entry = jar.getJarEntry(libJarEntryName);
                return (entry != null) ? entry.getSize() : -1L;
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
            return file.lastModified();
        } else if (warEntryName != null) {
            try (JarFile warFile = new JarFile(context.root)) {
                JarEntry jarEntry = warFile.getJarEntry(warEntryName);
                return (jarEntry != null) ? jarEntry.getTime() : -1L;
            } catch (IOException e) {
                return -1L;
            }
        } else if (libJarFile != null) {
            try (JarFile jar = new JarFile(libJarFile)) {
                JarEntry entry = jar.getJarEntry(libJarEntryName);
                return (entry != null) ? entry.getTime() : -1L;
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
        return context.getResourceAsStream(resourcePath);
    }

}
