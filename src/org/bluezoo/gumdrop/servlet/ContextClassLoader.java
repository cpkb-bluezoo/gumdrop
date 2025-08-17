/*
 * ContextClassLoader.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

import org.bluezoo.gumdrop.util.IteratorEnumeration;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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
import java.util.logging.Level;

/**
 * Class loader that loads classes in a web application.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ContextClassLoader extends ClassLoader {

    final Context context;
    Map<String,URL> resourceCache;
    Map<String,URL> jarResourceCache;
    Map<String,URL> commonResourceCache;
    Map<String,File> jarCache;

    ContextClassLoader(Context context) {
        this.context = context;
        resourceCache = new HashMap<>();
        jarResourceCache = new HashMap<>();
        commonResourceCache = new HashMap<>();
        jarCache = new HashMap<>();
    }

    protected Class findClass(String name) throws ClassNotFoundException {
        byte[] bytes = loadClassData(name);
        return (bytes != null) ? defineClass(name, bytes, 0, bytes.length) : findSystemClass(name);
    }

    protected URL findResource(String name) {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        try {
            // Resource in WEB-INF/classes
            URL url = resourceCache.get(name);
            if (url != null) {
                return url;
            }
            url = context.getResource("/WEB-INF/classes/" + name);
            if (url != null) {
                resourceCache.put(name, url);
                return url;
            }
            // Resource in jar resource in /WEB-INF/lib
            Collection<String> jars = context.getResourcePaths("/WEB-INF/lib");
            if (jars != null) {
                // Sort in alphabetical order
                List<String> sorted = new ArrayList<>(jars);
                Collections.sort(sorted);
                jars = sorted;
                // Check cache
                url = jarResourceCache.get(name);
                if (url != null) {
                    return url;
                }
                for (String path : jars) {
                    File file = jarCache.get(path);
                    if (file == null) {
                        if (context.warFile == null) {
                            file = new File(context.root, path.substring(1));
                            if (file.isFile()) {
                                jarCache.put(path, file);
                            }
                        } else {
                            InputStream in = context.getResourceAsStream(path);
                            if (in != null) {
                                String fileName = path.replace('/', '_');
                                file = File.createTempFile("gumdrop", fileName);
                                copy(in, file);
                                in.close();
                                file.deleteOnExit();
                                jarCache.put(path, file);
                            }
                        }
                    }
                    if (file != null) {
                        JarFile jarFile = new JarFile(file);
                        // Cache each entry in the jar file
                        try {
                            for (Enumeration e = jarFile.entries(); e.hasMoreElements(); ) {
                                JarEntry entry = (JarEntry) e.nextElement();
                                URL entryUrl =
                                        new URL("jar:" + file.toURL().toString() + "!/" + entry);
                                jarResourceCache.put(name, entryUrl);
                                if (entry.getName().equals(name)) url = entryUrl;
                            }
                            if (url != null) return url;
                        } finally {
                            jarFile.close();
                        }
                    }
                }
            }
            // Resource in common lib
            if (context.commonDir != null) {
                // Check cache
                url = commonResourceCache.get(name);
                if (url != null) {
                    return url;
                }
                File commonDir = new File(context.commonDir);
                if (commonDir.exists() && commonDir.isDirectory()) {
                    File[] commonJars = commonDir.listFiles();
                    for (int i = 0; i < commonJars.length; i++) {
                        try {
                            JarFile jarFile = new JarFile(commonJars[i]);
                            String jarFileUrl = commonJars[i].toURL().toString();
                            try {
                                for (Enumeration e = jarFile.entries(); e.hasMoreElements(); ) {
                                    JarEntry entry = (JarEntry) e.nextElement();
                                    URL entryUrl = new URL(String.format("jar:%s!/%s", jarFileUrl, entry));
                                    commonResourceCache.put(name, entryUrl);
                                    if (entry.getName().equals(name)) {
                                        url = entryUrl;
                                    }
                                }
                                if (url != null) {
                                    return url;
                                }
                            } finally {
                                jarFile.close();
                            }
                        } catch (IOException e) {
                            // NOOP
                        }
                    }
                }
            }
            return super.findResource(name);
        } catch (IOException e) {
            Context.LOGGER.log(Level.FINER, e.getMessage(), e);
            return null;
        }
    }

    protected Enumeration findResources(String name) throws IOException {
        if (!name.startsWith("/")) {
            name = "/" + name;
        }
        Set resources = context.getResourcePaths("/WEB-INF/classes" + name);
        List acc;
        if (resources == null) {
            acc = Collections.EMPTY_LIST;
        } else {
            acc = new ArrayList(resources.size());
            for (Iterator i = resources.iterator(); i.hasNext(); ) {
                String resourceName = (String) i.next();
                URL resource = context.getResource(resourceName);
                if (resource == null) {
                    String message = Context.L10N.getString("err.load_resource");
                    message = MessageFormat.format(message, resourceName);
                    throw new IOException(message);
                }
                acc.add(resource);
            }
        }
        return new IteratorEnumeration(acc.iterator());
    }

    byte[] loadClassData(final String name) throws ClassNotFoundException {
        try {
            // Class resource in /WEB-INF/classes
            String path = "/WEB-INF/classes/" + name.replace('.', '/') + ".class";
            InputStream in = context.getResourceAsStream(path);
            if (in != null) {
                return toByteArray(in);
            }
            // Class in jar resource in /WEB-INF/lib
            Set jars = context.getResourcePaths("/WEB-INF/lib");
            if (jars != null && !jars.isEmpty()) {
                String entryName = name.replace('.', '/') + ".class";
                for (Iterator i = jars.iterator(); i.hasNext(); ) {
                    path = (String) i.next();
                    File file = (File) jarCache.get(path);
                    if (file == null) {
                        if (context.warFile == null) {
                            file = new File(context.root, path.substring(1));
                        } else {
                            in = context.getResourceAsStream(path);
                            if (in != null) {
                                String fileName = path.replace('/', '_');
                                file = File.createTempFile("gumdrop", fileName);
                                copy(in, file);
                                in.close();
                                file.deleteOnExit();
                            }
                        }
                    }
                    if (file != null && file.isFile()) {
                        JarFile jarFile = new JarFile(file);
                        try {
                            jarCache.put(path, file);
                            JarEntry entry = jarFile.getJarEntry(entryName);
                            if (entry != null) {
                                in = jarFile.getInputStream(entry);
                                byte[] ret = toByteArray(in);
                                return ret;
                            }
                        } finally {
                            jarFile.close();
                        }
                    }
                }
            }
            // Class in common jar
            if (context.commonDir != null) {
                File commonDir = new File(context.commonDir);
                if (commonDir.exists() && commonDir.isDirectory()) {
                    String entryName = name.replace('.', '/') + ".class";
                    File[] commonJars = commonDir.listFiles();
                    for (int i = 0; i < commonJars.length; i++) {
                        JarFile jarFile = new JarFile(commonJars[i]);
                        JarEntry entry = jarFile.getJarEntry(entryName);
                        if (entry != null) {
                            in = jarFile.getInputStream(entry);
                            return toByteArray(in);
                        }
                    }
                }
            }
        } catch (IOException e) {
            ClassNotFoundException e2 = new ClassNotFoundException(name);
            e2.initCause(e);
            throw e2;
        }
        // Context.LOGGER.log(Level.SEVERE, "Can't load class data: " + name,
        //                   new ClassNotFoundException(name));
        return null;
    }

    byte[] toByteArray(InputStream in) throws IOException {
        in = new BufferedInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int len = in.read(buf); len != -1; len = in.read(buf)) {
            out.write(buf, 0, len);
        }
        in.close();
        return out.toByteArray();
    }

    static void copy(InputStream in, File dst) throws IOException {
        in = new BufferedInputStream(in);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[4096];
        for (int len = in.read(buf); len != -1; len = in.read(buf)) {
            out.write(buf, 0, len);
        }
        out.close();
    }

}
