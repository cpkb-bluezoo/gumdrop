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
import org.bluezoo.gumdrop.telemetry.EventLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class loader that loads classes inside a web application.
 * The parent of this classloader is a ContainerClassLoader. We can use this
 * to load J2EE dependency jars without the web application classes cloaded
 * by this loader being able to access the container classes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ContextClassLoader extends ClassLoader {

    private EventLogger events() {
        return context.getTelemetryConfig().getLogger(ContextClassLoader.class, Context.L10N);
    }

    static {
        // Required for getClassLoadingLock(name) to return a genuine
        // per-class-name lock; without this it silently returns `this`,
        // which would serialize loading of unrelated classes too.
        ClassLoader.registerAsParallelCapable();
    }

    private final ContainerClassLoader parent;
    private final ClassLoader fallbackParent; // For test environments
    private final Context context;
    private final boolean manager; // if this is the manager webapp

    private Map<String,InputStream> assignments = new HashMap<>();

    ContextClassLoader(ContainerClassLoader parent, Context context, boolean manager) {
        super(parent);
        this.parent = parent;
        this.fallbackParent = null;
        this.context = context;
        this.manager = manager;
    }

    /**
     * Constructor for test environments where ContainerClassLoader is not available.
     */
    ContextClassLoader(ClassLoader fallbackParent, Context context, boolean manager) {
        super(fallbackParent);
        this.parent = null;
        this.fallbackParent = fallbackParent;
        this.context = context;
        this.manager = manager;
    }

    void assign(String className, InputStream in) {
        assignments.put(className, in);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Per-class-name lock (the same one the JDK's own ClassLoader.
        // loadClass uses internally) so two threads racing to load the
        // same not-yet-loaded class serialize on the load-and-define
        // below, instead of both reaching defineClass() and one throwing
        // LinkageError: duplicate class definition. Locking per-name
        // rather than on the whole ContextClassLoader instance means
        // concurrent loads of *different* classes are unaffected.
        synchronized (getClassLoadingLock(name)) {
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
                if (parent == null || !parent.isContainerClass(name)) {
                    return super.loadClass(name, resolve);
                }
                throw new ClassNotFoundException(name);
            }
        }
    }

    void reset() {
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
                if (!jar.toLowerCase().endsWith(".jar")) {
                    // WEB-INF/lib may also hold non-archive files (readme etc.)
                    continue;
                }
                try {
                    Archive jarFile = context.getLibArchive(jar);
                    InputStream in2 = jarFile.stream(entryName);
                    if (in2 != null) {
                        try {
                            byte[] data = loadClassData(in2, name);
                            return defineClass(name, data, 0, data.length);
                            // XXX ProtectionDomain?
                        } finally {
                            in2.close();
                        }
                    }
                } catch (IOException e) {
                    // The (message, cause) constructor: initCause() always
                    // throws IllegalStateException on a ClassNotFoundException
                    // built with the single-argument constructor.
                    throw new ClassNotFoundException(name, e);
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
            throw new ClassNotFoundException(className, e);
        }
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
        if (parent != null) {
            for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
                resourceUrl = parent.findResource(url, name);
                if (resourceUrl != null) {
                    return resourceUrl;
                }
            }
            // Resource is not in dependency jars
            ClassLoader bootstrapClassLoader = parent.getParent();
            return bootstrapClassLoader.getResource(name);
        } else {
            return fallbackParent.getResource(name);
        }
    }

    @Override protected URL findResource(String name) {
        List<URL> found = new ArrayList<>();
        findContextResources(name, found, true);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Finds a resource on the class path of the web application: in
     * WEB-INF/classes, then in each jar in WEB-INF/lib in name order, as
     * classes are found. The documents the application serves (its root,
     * and META-INF/resources of the library jars) are not on the class
     * path: they are reached through the ServletContext.
     *
     * @param name the resource name, without leading '/'
     * @param acc receives the URL of each place the resource is found
     * @param firstOnly whether to stop at the first place
     */
    private void findContextResources(String name, List<URL> acc, boolean firstOnly) {
        try {
            URL url = context.getResource("/WEB-INF/classes/" + name);
            if (url != null) {
                acc.add(url);
                if (firstOnly) {
                    return;
                }
            }
            for (String jar : libJars()) {
                Archive jarFile = context.getLibArchive(jar);
                if (jarFile.isFile(name)) {
                    url = context.libEntryUrl(jar, name);
                    acc.add(url);
                    if (firstOnly) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            events().warn("err.load_resource").attr("resource", name).thrown(e).emit();
        }
    }

    /**
     * Returns the resource paths of the jars in WEB-INF/lib, in the order
     * they are searched.
     */
    private List<String> libJars() {
        List<String> sorted = new ArrayList<>();
        Collection<String> paths = context.getResourcePaths("/WEB-INF/lib", false);
        if (paths != null) {
            for (String path : paths) {
                // WEB-INF/lib may also hold non-archive files (readme etc.)
                if (path.toLowerCase().endsWith(".jar")) {
                    sorted.add(path);
                }
            }
            // Sort in alphabetical order: important!
            Collections.sort(sorted);
        }
        return sorted;
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
        if (parent != null) {
            for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
                in = parent.findResourceAsStream(url, name);
                if (in != null) {
                    return in;
                }
            }
            // Resource is not in dependency jars
            ClassLoader bootstrapClassLoader = parent.getParent();
            return bootstrapClassLoader.getResourceAsStream(name);
        } else {
            return fallbackParent.getResourceAsStream(name);
        }
    }

    private InputStream findResourceAsStream(String name) {
        InputStream in = context.getResourceAsStream("/WEB-INF/classes/" + name);
        if (in != null) {
            return in;
        }
        try {
            for (String jar : libJars()) {
                Archive jarFile = context.getLibArchive(jar);
                if (jarFile.isFile(name)) {
                    // NB we cannot auto-close it, the stream owns its handle
                    return jarFile.streamOwned(name);
                }
            }
        } catch (IOException e) {
            events().warn("err.load_resource").attr("resource", name).thrown(e).emit();
        }
        return null;
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        // In classloader, names should always be absolute
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        findContextResources(name, acc, false);
        if (parent != null) {
            for (URL url : parent.getURLs()) { // This is only the dependency jars, not the container jar
                URL dependencyResource = parent.findResource(url, name);
                if (dependencyResource != null) {
                    acc.add(dependencyResource);
                }
            }
            ClassLoader bootstrapClassLoader = parent.getParent();
            addResources(acc, bootstrapClassLoader.getResources(name));
        } else {
            addResources(acc, fallbackParent.getResources(name));
        }
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    static void addResources(List<URL> acc, Enumeration<URL> e) {
        while (e.hasMoreElements()) {
            acc.add(e.nextElement());
        }
    }

    @Override protected Enumeration<URL> findResources(String name) throws IOException {
        List<URL> acc = new ArrayList<>();
        findContextResources(name, acc, false);
        return new IteratorEnumeration<URL>(acc.iterator());
    }

}
