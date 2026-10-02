/*
 * ContextArchiveTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HandlesTypes;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises {@link Context} resource lookup, annotation and fragment
 * scanning, {@link ResourceURLConnection}, {@link ResourceStreamHandler}
 * and {@link ContextClassLoader} against both a WAR-packaged and an
 * exploded web application that carries a library jar.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextArchiveTest {

    /** Servlet found by scanning WEB-INF/classes. */
    @WebServlet(name = "warServlet", urlPatterns = { "/war" })
    public static class WarServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Servlet found by scanning the library jar. */
    @WebServlet(name = "libServlet", urlPatterns = { "/lib" })
    public static class LibServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
    }

    /** Class that exists only inside the library jar. */
    public static class LibMarker implements Runnable {
        public void run() {
        }
    }

    /** Initializer registered through META-INF/services in the library jar. */
    @HandlesTypes(HttpServlet.class)
    public static class LibInitializer implements ServletContainerInitializer {
        public void onStartup(Set<Class<?>> types, ServletContext ctx) throws ServletException {
            ctx.setAttribute("r3.sci", "ran");
            ctx.setAttribute("r3.types", Integer.valueOf(types.size()));
        }
    }

    private static final String FRAGMENT = "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
            + "<name>frag</name></web-fragment>";

    private static final String WEB_XML = "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
            + "<display-name>archive</display-name>"
            + "<mime-mapping><extension>.hi</extension><mime-type>text/x-hi</mime-type></mime-mapping>"
            + "</web-app>";

    private static final String PKG = "org/bluezoo/gumdrop/servlet/";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedFactory;
    private Container container;
    private byte[] libJar;

    @Before
    public void setUp() throws Exception {
        savedFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial",
                "org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory");
        container = SharedContainer.get();
        libJar = buildLibJar();
    }

    @After
    public void tearDown() {
        if (savedFactory == null) {
            System.clearProperty("java.naming.factory.initial");
        } else {
            System.setProperty("java.naming.factory.initial", savedFactory);
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        for (int n = in.read(buf); n != -1; n = in.read(buf)) {
            bout.write(buf, 0, n);
        }
        return bout.toByteArray();
    }

    private static void entry(JarOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildLibJar() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        JarOutputStream out = new JarOutputStream(bout);
        entry(out, "META-INF/web-fragment.xml", text(FRAGMENT));
        entry(out, "META-INF/resources/res.txt", text("from-jar"));
        entry(out, "META-INF/resources/dir/inner.txt", text("inner"));
        entry(out, "META-INF/resources/../evil.txt", text("evil"));
        entry(out, "META-INF/services/jakarta.servlet.ServletContainerInitializer",
                text("# comment\n" + LibInitializer.class.getName() + " # trailing\n\n"));
        entry(out, PKG + "ContextArchiveTest$LibServlet.class", classBytes(LibServlet.class));
        entry(out, PKG + "ContextArchiveTest$LibMarker.class", classBytes(LibMarker.class));
        entry(out, PKG + "ContextArchiveTest$LibInitializer.class", classBytes(LibInitializer.class));
        out.close();
        return bout.toByteArray();
    }

    private File buildWar() throws IOException {
        File war = new File(tmp.getRoot(), "app.war");
        JarOutputStream out = new JarOutputStream(new FileOutputStream(war));
        entry(out, "WEB-INF/web.xml", text(WEB_XML));
        entry(out, "index.html", text("<html>war</html>"));
        entry(out, "page.hi", text("hi"));
        entry(out, "sub/page.txt", text("page"));
        entry(out, "../outside.txt", text("bad"));
        entry(out, "WEB-INF/classes/" + PKG + "ContextArchiveTest$WarServlet.class",
                classBytes(WarServlet.class));
        entry(out, "WEB-INF/lib/frag.jar", libJar);
        entry(out, "WEB-INF/lib/readme.txt", text("not a jar"));
        out.close();
        return war;
    }

    private File buildExploded() throws IOException {
        File dir = tmp.newFolder("exploded");
        write(new File(dir, "WEB-INF/web.xml"), text(WEB_XML));
        write(new File(dir, "index.html"), text("<html>dir</html>"));
        write(new File(dir, "page.hi"), text("hi"));
        write(new File(dir, "WEB-INF/classes/" + PKG + "ContextArchiveTest$WarServlet.class"),
                classBytes(WarServlet.class));
        write(new File(dir, "WEB-INF/lib/frag.jar"), libJar);
        write(new File(dir, "WEB-INF/lib/readme.txt"), text("not a jar"));
        return dir;
    }

    private static void write(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private Context load(String path, File root) throws Exception {
        Context c = new Context(container, path, root);
        container.addContext(c);
        c.load();
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ===== WAR packaged =====

    @Test
    public void testWarLoadScansClassesFragmentsAndInitializers() throws Exception {
        Context c = load("/r3war", buildWar());
        assertTrue(c.servletDefs.containsKey("warServlet"));
        assertTrue(c.servletDefs.containsKey("libServlet"));
        assertEquals("ran", c.getAttribute("r3.sci"));
        Object types = c.getAttribute("r3.types");
        assertNotNull(types);
        assertTrue(((Integer) types).intValue() >= 1);
        c.destroy();
    }

    @Test
    public void testWarResources() throws Exception {
        Context c = load("/r3war", buildWar());
        assertNotNull(c.getResource("/index.html"));
        assertNotNull(c.getResource("/res.txt"));
        assertNotNull(c.getResource("res.txt"));
        assertNull(c.getResource("/missing.txt"));
        assertNull(c.getResource("/sub/"));
        assertNull(c.getResource("/res.txt/"));
        assertEquals("<html>war</html>", read(c.getResourceAsStream("/index.html")));
        assertEquals("from-jar", read(c.getResourceAsStream("/res.txt")));
        assertNull(c.getResourceAsStream("/missing.txt"));
        assertNull(c.getResourceAsStream("/sub/"));
        c.destroy();
    }

    @Test
    public void testWarResourcePaths() throws Exception {
        Context c = load("/r3war", buildWar());
        Set<String> root = c.getResourcePaths("/");
        assertNotNull(root);
        assertTrue(root.contains("/index.html"));
        assertTrue(root.contains("/WEB-INF/"));
        assertTrue(root.contains("/sub/"));
        assertTrue(root.contains("/dir/"));
        assertFalse(root.contains("/outside.txt"));
        assertFalse(root.contains("/../"));
        Set<String> dir = c.getResourcePaths("/dir/");
        assertNotNull(dir);
        assertTrue(dir.contains("/dir/inner.txt"));
        Set<String> noJars = c.getResourcePaths("/", false);
        assertFalse(noJars.contains("/dir/"));
        Set<String> lib = c.getResourcePaths("/WEB-INF/lib/", false);
        assertTrue(lib.contains("/WEB-INF/lib/frag.jar"));
        assertNull(c.getResourcePaths("/nonexistent/"));
        c.destroy();
    }

    @Test
    public void testWarUrlConnections() throws Exception {
        Context c = load("/r3war", buildWar());
        URL url = c.getResource("/index.html");
        URLConnection conn = url.openConnection();
        assertEquals(-1L, conn.getContentLengthLong());
        assertEquals(-1L, conn.getDate());
        conn.connect();
        conn.connect();
        assertEquals(16L, conn.getContentLengthLong());
        assertEquals(16, conn.getContentLength());
        assertTrue(conn.getDate() != -1L);
        assertEquals("text/html", conn.getContentType());
        assertEquals("<html>war</html>", read(conn.getInputStream()));

        URL hi = c.getResource("/page.hi");
        URLConnection hiConn = hi.openConnection();
        assertEquals("text/x-hi", hiConn.getContentType());

        URL jarUrl = c.getResource("/res.txt");
        URLConnection jarConn = jarUrl.openConnection();
        jarConn.connect();
        assertEquals(8L, jarConn.getContentLengthLong());
        assertTrue(jarConn.getDate() != -1L);
        assertEquals("from-jar", read(jarConn.getInputStream()));
        c.destroy();
    }

    @Test
    public void testUrlConnectionErrors() throws Exception {
        Context c = load("/r3war", buildWar());
        URL missing = new URL("resource", "r3war", "/nope.txt");
        URLConnection conn = missing.openConnection();
        try {
            conn.connect();
            fail("expected FileNotFoundException");
        } catch (FileNotFoundException expected) {
            assertNotNull(expected.getMessage());
        }
        URL noExtension = new URL("resource", "r3war", "nofile");
        URLConnection plain = noExtension.openConnection();
        assertNull(plain.getContentType());
        URL noContext = new URL("resource", "r3nosuch", "/x.txt");
        try {
            noContext.openConnection();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().indexOf("No context") >= 0);
        }
        c.destroy();
    }

    @Test
    public void testWarClassLoader() throws Exception {
        Context c = load("/r3war", buildWar());
        ClassLoader loader = c.getContextClassLoader();
        Class<?> marker = loader.loadClass(LibMarker.class.getName());
        assertSame(loader, marker.getClassLoader());
        assertNotSame(LibMarker.class, marker);
        Class<?> again = loader.loadClass(LibMarker.class.getName());
        assertSame(marker, again);
        Class<?> warServlet = loader.loadClass(WarServlet.class.getName());
        assertSame(loader, warServlet.getClassLoader());
        assertSame(DefaultServlet.class, loader.loadClass(DefaultServlet.class.getName()));
        assertSame(String.class, loader.loadClass("java.lang.String"));
        try {
            loader.loadClass("no.such.Clazz");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            assertEquals("no.such.Clazz", expected.getMessage());
        }
        URL res = loader.getResource("/res.txt");
        assertNotNull(res);
        assertNotNull(loader.getResource("index.html"));
        assertNotNull(loader.getResource("java/lang/String.class"));
        assertEquals("from-jar", read(loader.getResourceAsStream("res.txt")));
        assertNotNull(loader.getResourceAsStream("/index.html"));
        Enumeration<URL> all = loader.getResources("res.txt");
        assertTrue(all.hasMoreElements());
        Enumeration<URL> none = loader.getResources("nothing-here.txt");
        assertFalse(none.hasMoreElements());
        c.destroy();
    }

    // ===== exploded =====

    @Test
    public void testExplodedLoadAndResources() throws Exception {
        Context c = load("/r3dir", buildExploded());
        assertTrue(c.servletDefs.containsKey("warServlet"));
        assertTrue(c.servletDefs.containsKey("libServlet"));
        assertEquals("ran", c.getAttribute("r3.sci"));
        assertNotNull(c.getResource("/res.txt"));
        assertEquals("from-jar", read(c.getResourceAsStream("/res.txt")));
        Set<String> root = c.getResourcePaths("/");
        assertTrue(root.contains("/dir/"));
        assertTrue(root.contains("/index.html"));
        assertNull(c.getResource("/missing.txt"));
        assertNull(c.getResourceAsStream("/missing.txt"));
        c.destroy();
    }

    @Test
    public void testExplodedUrlConnections() throws Exception {
        Context c = load("/r3dir", buildExploded());
        URL url = c.getResource("/index.html");
        URLConnection conn = url.openConnection();
        conn.connect();
        assertEquals(16L, conn.getContentLengthLong());
        assertTrue(conn.getDate() != -1L);
        assertEquals("<html>dir</html>", read(conn.getInputStream()));
        URL jarUrl = c.getResource("/res.txt");
        URLConnection jarConn = jarUrl.openConnection();
        jarConn.connect();
        assertEquals(8L, jarConn.getContentLengthLong());
        assertTrue(jarConn.getDate() != -1L);
        URL missing = new URL("resource", "r3dir", "/nope.txt");
        try {
            missing.openConnection().connect();
            fail("expected FileNotFoundException");
        } catch (FileNotFoundException expected) {
            assertNotNull(expected.getMessage());
        }
        c.destroy();
    }

    @Test
    public void testManagerAndAssignedClassLoading() throws Exception {
        Context c = load("/r3dir", buildExploded());
        ClassLoader parent = ContextArchiveTest.class.getClassLoader();
        ContextClassLoader manager = new ContextClassLoader(parent, c, true);
        assertSame(String.class, manager.loadClass("java.lang.String"));
        ContextClassLoader plain = new ContextClassLoader(parent, c, false);
        String name = LibMarker.class.getName();
        plain.assign(name, new java.io.ByteArrayInputStream(classBytes(LibMarker.class)));
        Class<?> assigned = plain.loadClass(name);
        assertSame(plain, assigned.getClassLoader());
        plain.reset();
        File jar = plain.getFile("WEB-INF/lib/frag.jar");
        assertNotNull(jar);
        assertTrue(jar.isFile());
        File missing = plain.getFile("/WEB-INF/lib/none.jar");
        assertFalse(missing.isFile());
        c.destroy();
    }

    @Test
    public void testNonJarFileInLibDirectoryIsIgnoredByClassLoader() throws Exception {
        File dir = tmp.newFolder("nonjar");
        write(new File(dir, "WEB-INF/lib/readme.txt"), text("not a jar"));
        Context c = new Context(container, "/r3nonjar", dir);
        ClassLoader loader = c.getContextClassLoader();
        try {
            loader.loadClass("no.such.Clazz");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            assertEquals("no.such.Clazz", expected.getMessage());
            assertNull(expected.getCause());
        }
    }

    @Test
    public void testCorruptJarReportsCauseOnClassNotFound() throws Exception {
        File dir = tmp.newFolder("corrupt");
        write(new File(dir, "WEB-INF/lib/bad.jar"), text("not a zip file"));
        Context c = new Context(container, "/r3corrupt", dir);
        ClassLoader loader = c.getContextClassLoader();
        try {
            loader.loadClass("no.such.Clazz");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            assertEquals("no.such.Clazz", expected.getMessage());
            assertTrue(expected.getCause() instanceof IOException);
        }
    }
}
