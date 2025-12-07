/*
 * ContainerClassLoader.java
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bluezoo.gumdrop.util.IteratorEnumeration;

/**
 * Class loader to load the gumdrop server classes and its dependencies.
 * This can be used by the context class loader to gain access to the J2EE
 * classes without being able to load server/container classes or resources.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContainerClassLoader extends DependencyClassLoader {

    /**
     * URL of the server/container class jar.
     * Only this classLoader can access this.
     */
    private final URL containerUrl;

    /**
     * Constructor for the container classloader.
     * @param containerUrl url for the container classes (server.jar)
     * @param dependencyUrls urls for dependency jars (J2EE API jars)
     */
    public ContainerClassLoader(URL containerUrl, List<URL> dependencyUrls, ClassLoader parent) {
        super(dependencyUrls, parent);
        this.containerUrl = containerUrl;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if ("org.bluezoo.gumdrop.ContainerClassLoader".equals(name)) {
            // That's me!
            return ContainerClassLoader.class;
        }
        // Check if class has already been loaded
        Class<?> t = findLoadedClass(name);
        if (t != null) {
            return t;
        }
        // Try to load the class from server.jar
        t = findClass(containerUrl, name);
        if (t != null) {
            if (resolve) {
                resolveClass(t);
            }
            return t;
        }
        // Next try the dependency URLs
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

    /**
     * Indicates whether the specified class is a container class.
     * This will be used by the ContextClassLoader to decide whether to call
     * super.loadClass
     */
    public boolean isContainerClass(String name) {
        String resourceName = name.replace('.', '/') + ".class";
        try (InputStream in = findResourceAsStream(containerUrl, name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public URL getResource(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        URL resourceUrl = findResource(containerUrl, name);
        if (resourceUrl != null) {
            return resourceUrl;
        }
        return super.getResource(name);
    }

    @Override protected URL findResource(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        URL resourceUrl = findResource(containerUrl, name);
        if (resourceUrl != null) {
            return resourceUrl;
        }
        return super.findResource(name);
    }

    @Override public InputStream getResourceAsStream(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        InputStream in = findResourceAsStream(containerUrl, name);
        if (in != null) {
            return in;
        }
        return super.getResourceAsStream(name);
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        URL containerResource = findResource(containerUrl, name);
        if (containerResource != null) {
            acc.add(containerResource);
        }
        for (URL url : urls) {
            URL dependencyResource = findResource(url, name);
            if (dependencyResource != null) {
                acc.add(dependencyResource);
            }
        }
        ClassLoader bootstrapClassLoader = getParent();
        addResources(acc, bootstrapClassLoader.getResources(name));
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    @Override protected Enumeration<URL> findResources(String name) throws IOException {
        // Ensure resource name is not prefixed by '/'
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        URL containerResource = findResource(containerUrl, name);
        if (containerResource != null) {
            acc.add(containerResource);
        }
        for (URL url : urls) {
            URL dependencyResource = findResource(url, name);
            if (dependencyResource != null) {
                acc.add(dependencyResource);
            }
        }
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    @Override protected boolean urlValid(URL url) {
        return containerUrl.equals(url) || urls.contains(url);
    }

}
