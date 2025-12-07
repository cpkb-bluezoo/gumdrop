/*
 * ContextClassLoader.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.ContainerClassLoader;
import org.bluezoo.gumdrop.util.IteratorEnumeration;
import org.bluezoo.gumdrop.util.JarInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Class loader that loads classes inside a web application.
 * The parent of this classloader is a ContainerClassLoader. We can use this
 * to load J2EE dependency jars without the web application classes cloaded
 * by this loader being able to access the container classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ContextClassLoader extends ClassLoader {

    private final ContainerClassLoader parent;
    private final Context context;
    private final boolean manager; // if this is the manager webapp

    private Map<String,File> files = new ConcurrentHashMap<>();
    private Map<String,InputStream> assignments = new HashMap<>();

    ContextClassLoader(ContainerClassLoader parent, Context context, boolean manager) {
        super(parent);
        this.parent = parent;
        this.context = context;
        this.manager = manager;
    }

    void assign(String className, InputStream in) {
        assignments.put(className, in);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Check if class has already been loaded
        Class<?> t = findLoadedClass(name);
        if (t != null) {
            return t;
        }
        if (manager) {
            // Use container classloader
            return super.loadClass(name, resolve);
        } else {
            // DefaultServlet is loaded by container classloader
            if (DefaultServlet.class.getName().equals(name)) {
                return DefaultServlet.class;
            }
            // Check if this is an assignment loaded during introspection
            // scanning. These are classes found in the context
            InputStream in = assignments.remove(name);
            if (in != null) {
                byte[] data = loadClassData(in, name);
                return defineClass(name, data, 0, data.length);
                // XXX ProtectionDomain?
            }
            // Check if class is located in the context
            t = findContextClass(name);
            if (t != null) {
                return t;
            }
            // Dependency or JRE bootstrap class
            if (!parent.isContainerClass(name)) {
                return super.loadClass(name, resolve);
            }
            throw new ClassNotFoundException(name);
        }
    }

    void reset() {
        files.clear();
    }

    private Class<?> findContextClass(String name) throws ClassNotFoundException {
        // Try to load the class from the context
        String entryName = name.replace('.', '/') + ".class";
        // First try /WEB-INF/classes
        InputStream in = context.getResourceAsStream("/WEB-INF/classes/" + entryName);
        if (in != null) {
            byte[] data = loadClassData(in, name);
            return defineClass(name, data, 0, data.length);
            // XXX ProtectionDomain?
        }
        // Class inside jar resource in /WEB-INF/lib
        Collection<String> jars = context.getResourcePaths("/WEB-INF/lib", false);
        if (jars != null) {
            // Sort in alphabetical order: important!
            List<String> sorted = new ArrayList<>(jars);
            Collections.sort(sorted);
            jars = sorted;
            for (String jar : jars) {
                try {
                    File file = getFile(jar);
                    JarFile jarFile = new JarFile(file); // NB cannot close yet, use JarInputStream
                    JarEntry jarEntry = jarFile.getJarEntry(entryName);
                    if (jarEntry != null) {
                        try (InputStream in2 = new JarInputStream(jarFile, jarEntry)) {
                            byte[] data = loadClassData(in2, name);
                            return defineClass(name, data, 0, data.length);
                            // XXX ProtectionDomain?
                        }
                    } else {
                        jarFile.close();
                    }
                } catch (IOException e) {
                    ClassNotFoundException e2 = new ClassNotFoundException(name);
                    e2.initCause(e);
                    throw e2;
                }
            }
        }
        return null;
    }

    private static byte[] loadClassData(InputStream in, String className) throws ClassNotFoundException {
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buf = new byte[Math.max(4096, in.available())];
            for (int len = in.read(buf); len > -1; len = in.read(buf)) {
                sink.write(buf, 0, len);
            }
            return sink.toByteArray();
        } catch (IOException e) {
            ClassNotFoundException e2 = new ClassNotFoundException(className);
            e2.initCause(e);
            throw e2;
        }
    }

    /**
     * Return a File object that can be used to access the contents of the
     * jar file denoted by the specified path.
     * If the context is based in the filesystem, this can return a File
     * directly. If the context is in a war file, we will extract the
     * content of the jar to a temporary file and return that.
     * @param path the resource path, prefixed with '/'
     */
    synchronized File getFile(String path) {
        File file = files.get(path);
        if (file == null) {
            if (context.root.isDirectory()) {
                file = new File(context.root, path.substring(1));
                if (file.isFile()) {
                    files.put(path, file);
                }
            } else { // war file
                try (InputStream in = context.getResourceAsStream(path)) {
                    if (in != null) {
                        String fileName = (context.getContextPath() + path).replace('/', '_');
                        file = File.createTempFile("gumdrop", fileName);
                        file.deleteOnExit();
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            byte[] buf = new byte[Math.max(4096, in.available())];
                            for (int len = in.read(buf); len != -1; len = in.read(buf)) {
                                out.write(buf, 0, len);
                            }
                            files.put(path, file);
                        }
                    }
                } catch (IOException e) {
                    // Error writing or temporary file or closing resource.
                    RuntimeException e2 = new RuntimeException();
                    e2.initCause(e);
                    throw e2;
                }
            }
        }
        return file;
    }

    @Override public URL getResource(String name) {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        // Look in context
        URL resourceUrl = findResource(name);
        if (resourceUrl != null) {
            return resourceUrl;
        }
        // Resource is not in context. Delegate to parent
        for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
            resourceUrl = parent.findResource(url, name);
            if (resourceUrl != null) {
                return resourceUrl;
            }
        }
        // Resource is not in dependency jars
        ClassLoader bootstrapClassLoader = parent.getParent();
        return bootstrapClassLoader.getResource(name);
    }

    @Override protected URL findResource(String name) {
        try {
            return context.getResource("/WEB-INF/classes/" + name);
            // TODO WEB-INF/resources inside WEB_INF/lib
        } catch (MalformedURLException e) {
            String message = Context.L10N.getString("err.load_resource");
            message = MessageFormat.format(message, name);
            Context.LOGGER.warning(message);
            return null;
        }
    }

    @Override public InputStream getResourceAsStream(String name) {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        // Look in context
        InputStream in = findResourceAsStream(name);
        if (in != null) {
            return in;
        }
        // Resource is not in context. Delegate to parent
        for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
            in = parent.findResourceAsStream(url, name);
            if (in != null) {
                return in;
            }
        }
        // Resource is not in dependency jars
        ClassLoader bootstrapClassLoader = parent.getParent();
        return bootstrapClassLoader.getResourceAsStream(name);
    }

    private InputStream findResourceAsStream(String name) {
        return context.getResourceAsStream("/" + name);
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        URL contextResource = findResource(name);
        if (contextResource != null) {
            acc.add(contextResource);
        }
        for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
            acc.add(parent.findResource(url, name));
        }
        ClassLoader bootstrapClassLoader = parent.getParent();
        addResources(acc, bootstrapClassLoader.getResources(name));
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    static void addResources(List<URL> acc, Enumeration<URL> e) {
        while (e.hasMoreElements()) {
            acc.add(e.nextElement());
        }
    }

    @Override protected Enumeration<URL> findResources(String name) throws IOException {
        String targetName = "/" + name;
        List<URL> acc = new ArrayList<>();
        // Note that this is only an exact name match
        // ServletContext may be referring to multiple resources under the
        // hood with this, but doesn't provide different URLs for them.
        URL resourceUrl = context.getResource(targetName);
        if (resourceUrl != null) {
            acc.add(resourceUrl);
        }
        return new IteratorEnumeration<URL>(acc.iterator());
    }

}
