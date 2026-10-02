/*
 * ClassLoadersIntegrationTest.java
 * Copyright (C) 2026 Chris Burdess
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link DependencyClassLoader} and
 * {@link ContainerClassLoader} using small jars built in a real temporary
 * directory. These are integration tests rather than unit tests because the
 * class loaders are URLClassLoaders over jar files on the real file system,
 * which cannot be reached through an in-memory file system.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClassLoadersIntegrationTest {

    private static final String CLASS_NAME = "org.bluezoo.util.ByteArrays";
    private static final String CLASS_ENTRY = "org/bluezoo/util/ByteArrays.class";

    private File dir;
    private File depJar;
    private File containerJar;
    private final List<DependencyClassLoader> loaders = new ArrayList<DependencyClassLoader>();

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        while (n >= 0) {
            out.write(buf, 0, n);
            n = in.read(buf);
        }
        in.close();
        return out.toByteArray();
    }

    private static void writeJar(File file, String[] names, byte[][] contents) throws IOException {
        OutputStream fos = Files.newOutputStream(file.toPath());
        JarOutputStream jar = new JarOutputStream(fos);
        try {
            for (int i = 0; i < names.length; i++) {
                jar.putNextEntry(new JarEntry(names[i]));
                jar.write(contents[i]);
                jar.closeEntry();
            }
        } finally {
            jar.close();
        }
    }

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("classloaders").toFile();
        InputStream cls = ClassLoadersIntegrationTest.class.getClassLoader().getResourceAsStream(CLASS_ENTRY);
        byte[] bytes = readAll(cls);
        depJar = new File(dir, "dep.jar");
        writeJar(depJar,
                new String[] {CLASS_ENTRY, "data/dep.txt", "shared.txt"},
                new byte[][] {bytes, "dep".getBytes(StandardCharsets.UTF_8),
                    "dep-shared".getBytes(StandardCharsets.UTF_8)});
        containerJar = new File(dir, "container.jar");
        writeJar(containerJar,
                new String[] {"data/container.txt", "shared.txt"},
                new byte[][] {"container".getBytes(StandardCharsets.UTF_8),
                    "container-shared".getBytes(StandardCharsets.UTF_8)});
    }

    @After
    public void tearDown() throws Exception {
        for (int i = 0; i < loaders.size(); i++) {
            loaders.get(i).close();
        }
        File[] files = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            files[i].delete();
        }
        dir.delete();
    }

    private DependencyClassLoader dependency() throws Exception {
        List<URL> urls = new ArrayList<URL>();
        urls.add(depJar.toURI().toURL());
        DependencyClassLoader l = new DependencyClassLoader(urls, ClassLoadersIntegrationTest.class.getClassLoader());
        loaders.add(l);
        return l;
    }

    private ContainerClassLoader container() throws Exception {
        List<URL> deps = new ArrayList<URL>();
        deps.add(depJar.toURI().toURL());
        ContainerClassLoader l = new ContainerClassLoader(containerJar.toURI().toURL(), deps,
                ClassLoadersIntegrationTest.class.getClassLoader());
        loaders.add(l);
        return l;
    }

    private static String text(InputStream in) throws IOException {
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    @Test
    public void dependencyLoaderDefinesClassFromJar() throws Exception {
        DependencyClassLoader l = dependency();
        Class<?> c = l.loadClass(CLASS_NAME);
        assertEquals(CLASS_NAME, c.getName());
        assertSame(l, c.getClassLoader());
        assertSame(c, l.loadClass(CLASS_NAME, true));
        Class<?> viaParent = l.bootstrapLoadClass(CLASS_NAME, false);
        assertNotNull(viaParent);
        Class<?> str = l.loadClass("java.lang.String", true);
        assertSame(String.class, str);
        assertEquals(1, l.getURLs().size());
    }

    @Test
    public void dependencyLoaderFindClassVariants() throws Exception {
        DependencyClassLoader l = dependency();
        URL url = depJar.toURI().toURL();
        assertNull(l.findClass(url, "no.such.Thing"));
        try {
            l.findClass("no.such.Thing");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            // expected
        }
        try {
            l.loadClass("no.such.Thing");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void dependencyLoaderResources() throws Exception {
        DependencyClassLoader l = dependency();
        URL r = l.getResource("/data/dep.txt");
        assertNotNull(r);
        assertTrue(r.toString().startsWith("jar:"));
        assertEquals("dep", text(r.openStream()));
        assertNotNull(l.findResource("data/dep.txt"));
        assertNull(l.findResource("missing.txt"));
        assertNotNull(l.getResource("java/lang/String.class"));
        assertEquals("dep", text(l.getResourceAsStream("/data/dep.txt")));
        assertNull(l.getResourceAsStream("missing.txt"));
        assertEquals("dep", text(l.findResourceAsStream("data/dep.txt")));
        assertNull(l.findResourceAsStream("missing.txt"));
        Enumeration<URL> all = l.getResources("shared.txt");
        assertTrue(all.hasMoreElements());
        Enumeration<URL> found = l.findResources("shared.txt");
        assertTrue(found.hasMoreElements());
        List<File> files = l.getClasspathFiles();
        assertEquals(1, files.size());
        assertEquals(depJar.getCanonicalFile(), files.get(0).getCanonicalFile());
    }

    @Test
    public void dependencyLoaderRejectsForeignUrls() throws Exception {
        DependencyClassLoader l = dependency();
        URL other = containerJar.toURI().toURL();
        assertNull(l.findResource(other, "data/container.txt"));
        assertNull(l.findResourceAsStream(other, "data/container.txt"));
    }

    @Test
    public void closeIsIdempotent() throws Exception {
        DependencyClassLoader l = dependency();
        assertNotNull(l.getResourceAsStream("data/dep.txt"));
        l.close();
        l.close();
    }

    @Test
    public void containerLoaderPrefersContainerResources() throws Exception {
        ContainerClassLoader l = container();
        assertEquals("container-shared", text(l.getResourceAsStream("/shared.txt")));
        assertEquals("dep", text(l.getResourceAsStream("data/dep.txt")));
        assertEquals("container", text(l.getResourceAsStream("data/container.txt")));
        assertNull(l.getResourceAsStream("missing.txt"));
        URL shared = l.getResource("shared.txt");
        assertEquals("container-shared", text(shared.openStream()));
        URL depOnly = l.getResource("data/dep.txt");
        assertNotNull(depOnly);
        assertNotNull(l.findResource("/data/container.txt"));
        assertNotNull(l.findResource("data/dep.txt"));
        assertNull(l.findResource("missing.txt"));
    }

    @Test
    public void containerLoaderEnumeratesAllResources() throws Exception {
        ContainerClassLoader l = container();
        Enumeration<URL> e = l.getResources("shared.txt");
        int count = 0;
        while (e.hasMoreElements()) {
            e.nextElement();
            count++;
        }
        assertEquals(2, count);
        Enumeration<URL> f = l.findResources("/shared.txt");
        count = 0;
        while (f.hasMoreElements()) {
            f.nextElement();
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    public void containerLoaderClassLoading() throws Exception {
        ContainerClassLoader l = container();
        assertSame(ContainerClassLoader.class,
                l.loadClass("org.bluezoo.gumdrop.ContainerClassLoader"));
        Class<?> c = l.loadClass(CLASS_NAME);
        assertSame(l, c.getClassLoader());
        assertSame(c, l.loadClass(CLASS_NAME, true));
        assertSame(String.class, l.loadClass("java.lang.String"));
    }

    @Test
    public void containerLoaderClassPathAndMembership() throws Exception {
        ContainerClassLoader l = container();
        List<File> files = l.getClasspathFiles();
        assertEquals(2, files.size());
        assertEquals(containerJar.getCanonicalFile(), files.get(0).getCanonicalFile());
        assertTrue(l.isContainerClass("data/container.txt"));
        assertFalse(l.isContainerClass("data/dep.txt"));
    }

    @Test
    public void containerLoaderWithSeveralContainerJars() throws Exception {
        File second = new File(dir, "second.jar");
        writeJar(second, new String[] {"second.txt"},
                new byte[][] {"second".getBytes(StandardCharsets.UTF_8)});
        List<URL> containers = new ArrayList<URL>();
        containers.add(containerJar.toURI().toURL());
        containers.add(second.toURI().toURL());
        ContainerClassLoader l = new ContainerClassLoader(containers, new ArrayList<URL>(),
                ClassLoadersIntegrationTest.class.getClassLoader());
        loaders.add(l);
        assertEquals("second", text(l.getResourceAsStream("second.txt")));
        assertEquals(2, l.getClasspathFiles().size());
        assertTrue(l.getURLs().isEmpty());
    }
}
