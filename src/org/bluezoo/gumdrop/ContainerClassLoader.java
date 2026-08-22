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

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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
     * URLs of server/module jars (monolithic gumdrop.jar or modular closure).
     */
    private final List<URL> containerUrls;

    /**
     * Constructor for the container classloader (single server jar).
     */
    public ContainerClassLoader(URL containerUrl, List<URL> dependencyUrls, ClassLoader parent) {
        this(Collections.singletonList(containerUrl), dependencyUrls, parent);
    }

    /**
     * Constructor for the container classloader (modular server jars).
     */
    public ContainerClassLoader(List<URL> containerUrls, List<URL> dependencyUrls, ClassLoader parent) {
        super(dependencyUrls, parent);
        this.containerUrls = Collections.unmodifiableList(new ArrayList<>(containerUrls));
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if ("org.bluezoo.gumdrop.ContainerClassLoader".equals(name)) {
            return ContainerClassLoader.class;
        }
        Class<?> t = findLoadedClass(name);
        if (t != null) {
            return t;
        }
        for (URL containerUrl : containerUrls) {
            t = findClass(containerUrl, name);
            if (t != null) {
                if (resolve) {
                    resolveClass(t);
                }
                return t;
            }
        }
        for (URL url : urls) {
            t = findClass(url, name);
            if (t != null) {
                if (resolve) {
                    resolveClass(t);
                }
                return t;
            }
        }
        return super.loadClass(name, resolve);
    }

    /**
     * Indicates whether the specified class is a container class.
     */
    public boolean isContainerClass(String name) {
        for (URL containerUrl : containerUrls) {
            try (InputStream in = findResourceAsStream(containerUrl, name)) {
                if (in != null) {
                    return true;
                }
            } catch (IOException e) {
                // try next jar
            }
        }
        return false;
    }

    @Override public URL getResource(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL containerUrl : containerUrls) {
            URL resourceUrl = findResource(containerUrl, name);
            if (resourceUrl != null) {
                return resourceUrl;
            }
        }
        return super.getResource(name);
    }

    @Override protected URL findResource(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL containerUrl : containerUrls) {
            URL resourceUrl = findResource(containerUrl, name);
            if (resourceUrl != null) {
                return resourceUrl;
            }
        }
        return super.findResource(name);
    }

    @Override public InputStream getResourceAsStream(String name) {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        for (URL containerUrl : containerUrls) {
            InputStream in = findResourceAsStream(containerUrl, name);
            if (in != null) {
                return in;
            }
        }
        return super.getResourceAsStream(name);
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        for (URL containerUrl : containerUrls) {
            URL containerResource = findResource(containerUrl, name);
            if (containerResource != null) {
                acc.add(containerResource);
            }
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
        name = (name.charAt(0) == '/') ? name.substring(1) : name;
        List<URL> acc = new ArrayList<>();
        for (URL containerUrl : containerUrls) {
            URL containerResource = findResource(containerUrl, name);
            if (containerResource != null) {
                acc.add(containerResource);
            }
        }
        for (URL url : urls) {
            URL dependencyResource = findResource(url, name);
            if (dependencyResource != null) {
                acc.add(dependencyResource);
            }
        }
        return new IteratorEnumeration<URL>(acc.iterator());
    }

    @Override public List<File> getClasspathFiles() throws IOException {
        List<File> result = new ArrayList<>();
        for (URL containerUrl : containerUrls) {
            result.add(getFile(containerUrl));
        }
        result.addAll(super.getClasspathFiles());
        return result;
    }

    @Override protected boolean urlValid(URL url) {
        return containerUrls.contains(url) || urls.contains(url);
    }

}
