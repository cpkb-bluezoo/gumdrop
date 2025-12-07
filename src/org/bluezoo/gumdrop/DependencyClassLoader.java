/*
 * DependencyClassLoader.java
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

package org.bluezoo.gumdrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import org.bluezoo.gumdrop.util.JarInputStream;

/**
 * Class loader to load J2EE dependency classes from jars.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DependencyClassLoader extends ClassLoader {

    /**
     * List of URLs of the J2EE dependency jars.
     */
    protected final List<URL> urls;

    /**
     * The jars are all in a jar themselves, and JarFile or jar: URLs
     * don't support nested jars, only files. So we have to extract the
     * jar URL to a file and use that as a JarFile.
     */
    private Map<URL,File> files = new HashMap<>();

    public DependencyClassLoader(List<URL> urls, ClassLoader parent) {
        super(parent);
        this.urls = Collections.unmodifiableList(urls);
    }

    /**
     * Returns the URLs that will be searched by this classloader.
     */
    public List<URL> getURLs() {
        return urls;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Check if class has already been loaded
        Class<?> t = findLoadedClass(name);
        if (t != null) {
            return t;
        }
        // Try to load the class from our own URLs first
        for (URL url : urls) {
            t = findClass(url, name);
            if (t != null) {
                if (resolve) {
                    resolveClass(t);
                }
                return t;
            }
        }
        // Lastly, try to load from the parent (bootstrap)
        return super.loadClass(name, resolve);
    }

    public Class<?> bootstrapLoadClass(String name, boolean resolve) throws ClassNotFoundException {
        return super.loadClass(name, resolve);
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        /*for (URL url : urls) {
            Class<?> t = findClass(url, name);
            if (t != null) {
                return t;
            }
        }*/
        throw new ClassNotFoundException(name);
    }

    /**
     * Searches a URL representing a jar file for the specified class.
     * @param url the URL of the jar file to search
     * @param name the class name
     * @return the found class, or null if not found
     * @throws ClassNotFoundException only if there was an underlying error
     * accessing the temporary file to be searched
     */
    public Class<?> findClass(URL url, String name) throws ClassNotFoundException {
        String entryName = name.replace('.', '/') + ".class";
        try (InputStream in = findResourceAsStream(url, entryName)) {
            if (in != null) {
                // Get the class data
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buf = new byte[Math.max(4096, in.available())];
                for (int len = in.read(buf); len > -1; len = in.read(buf)) {
                    sink.write(buf, 0, len);
                }
                buf = sink.toByteArray();
                // Define the class
                return defineClass(name, buf, 0, buf.length);
                // XXX ProtectionDomain?
            }
            return null; // NB do NOT throw exception
        } catch (IOException e) {
            // Error reading the temporary file
            e.printStackTrace(System.err);
            throw new ClassNotFoundException(name);
        }
    }

    @Override public URL getResource(String name) {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL url : urls) {
            URL resourceUrl = findResource(url, name);
            if (resourceUrl != null) {
                return resourceUrl;
            }
        }
        return getParent().getResource(name);
    }

    @Override protected URL findResource(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL url : urls) {
            URL resourceUrl = findResource(url, name);
            if (resourceUrl != null) {
                return resourceUrl;
            }
        }
        return null; // not found
    }

    /**
     * Returns a URL for the specified resource.
     * @param url the URL of the jar file to search
     * @param name the name of the resource, NOT prefixed by '/'
     */
    public URL findResource(URL url, String name) {
        if (!urlValid(url)) {
            return null;
        }
        try {
            // Find the temporary file corresponding to this URL
            File file = getFile(url);
            try (JarFile jarFile = new JarFile(file)) {
                JarEntry jarEntry = jarFile.getJarEntry(name);
                if (jarEntry != null) {
                    return new URL("jar:" + file.toURI().toURL() + "!/" + name);
                }
            }
            return null; // not found
        } catch (IOException e) {
            // Error creating or reading the temporary file.
            // This generally indicates a serious configuration error
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    @Override public InputStream getResourceAsStream(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL url : urls) {
            InputStream in = findResourceAsStream(url, name);
            if (in != null) {
                return in;
            }
        }
        return null;
    }

    public InputStream findResourceAsStream(String name) {
        // Ensure resource name is not prefixed by '/'
        String entryName = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL url : urls) {
            InputStream in = findResourceAsStream(url, name);
            if (in != null) {
                return in;
            }
        }
        return null; // not found
    }

    /**
     * Returns an InputStream for the specified resource.
     * This avoids the need for URLs and URLStreamHandler.
     * @param url the URL of the jar file to search
     * @param name the name of the resource, NOT prefixed by '/'
     */
    public InputStream findResourceAsStream(URL url, String name) {
        if (!urlValid(url)) {
            return null;
        }
        try {
            // Find the temporary file corresponding to this URL
            File file = getFile(url);
            JarFile jarFile = new JarFile(file); // NB cannot close yet, use JarInputStream
            JarEntry jarEntry = jarFile.getJarEntry(name);
            if (jarEntry != null) {
                return new JarInputStream(jarFile, jarEntry);
            } else {
                jarFile.close();
                return null; // not found
            }
        } catch (IOException e) {
            // error creating or reading the temporary file
            RuntimeException e2 = new RuntimeException();
            e2.initCause(e);
            throw e2;
        }
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        for (URL url : urls) {
            URL resource = findResource(url, name);
            if (resource != null) {
                acc.add(resource);
            }
        }
        ClassLoader bootstrapClassLoader = getParent();
        addResources(acc, bootstrapClassLoader.getResources(name));
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    static void addResources(List<URL> acc, Enumeration<URL> e) {
        while (e.hasMoreElements()) {
            acc.add(e.nextElement());
        }
    }

    @Override protected Enumeration<URL> findResources(String name) throws IOException {
        // Ensure resource name is not prefixed by '/'
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        for (URL url : urls) {
            acc.add(findResource(url, name));
        }
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    /**
     * Returns the temporary file being used to store the jar file at the
     * specified URL.
     */
    protected synchronized File getFile(URL url) throws IOException {
        File file = files.get(url);
        if (file == null) {
            // Get jar file as a stream
            InputStream in = url.openStream();
            // Create temporary file to store it in
            String fileName = url.toString();
            fileName = fileName.substring(fileName.indexOf("!/") + 2);
            file = File.createTempFile("gumdrop", fileName);
            file.deleteOnExit();
            OutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[Math.max(4096, in.available())];
            for (int len = in.read(buf); len > -1; len = in.read(buf)) {
                out.write(buf, 0, len);
            }
            out.close();
            // cache it
            files.put(url, file);
        }
        return file;
    }

    protected boolean urlValid(URL url) {
        return urls.contains(url);
    }

}
