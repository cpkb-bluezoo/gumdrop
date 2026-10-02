/*
 * BootstrapIntegrationTest.java
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the error paths and layout detection in
 * {@link Bootstrap}. These are integration tests rather than unit tests
 * because Bootstrap inspects jar manifests and directory layouts on the real
 * file system and builds URLClassLoaders over them. The success path starts
 * the servlet container and is not exercised here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BootstrapIntegrationTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("bootstrap").toFile();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                files[i].delete();
            }
        }
        dir.delete();
    }

    private static Object call(String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = Bootstrap.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private File jarWithManifest(String name, Manifest manifest) throws Exception {
        File f = new File(dir, name);
        OutputStream out = Files.newOutputStream(f.toPath());
        JarOutputStream jar = (manifest != null) ? new JarOutputStream(out, manifest) : new JarOutputStream(out);
        jar.close();
        return f;
    }

    @Test
    public void libLayoutDetection() throws Exception {
        Class<?>[] types = new Class<?>[] {File.class};
        assertEquals(Boolean.FALSE, call("isLibLayout", types, new Object[] {null}));
        assertEquals(Boolean.FALSE, call("isLibLayout", types, new Object[] {new File(dir, "missing")}));
        assertEquals(Boolean.FALSE, call("isLibLayout", types, new Object[] {dir}));
        assertTrue(new File(dir, "gumdrop-core.jar").createNewFile());
        assertEquals(Boolean.TRUE, call("isLibLayout", types, new Object[] {dir}));
        assertTrue(new File(dir, "gumdrop-core.jar").delete());
        assertTrue(new File(dir, "gumdrop.jar").createNewFile());
        assertEquals(Boolean.TRUE, call("isLibLayout", types, new Object[] {dir}));
    }

    @Test
    public void moduleJarDetection() throws Exception {
        Class<?>[] types = new Class<?>[] {String.class};
        assertEquals(Boolean.TRUE, call("isContainerModuleJar", types, new Object[] {"gumdrop-http.jar"}));
        assertEquals(Boolean.FALSE, call("isContainerModuleJar", types, new Object[] {"other.jar"}));
    }

    @Test
    public void toFileHandlesPlainAndJarUrls() throws Exception {
        File f = new File(dir, "x.jar");
        URL plain = f.toURI().toURL();
        File a = (File) call("toFile", new Class<?>[] {URL.class}, new Object[] {plain});
        assertEquals(f.getCanonicalFile(), a.getCanonicalFile());
        URL inner = new URL("jar:" + plain + "!/lib/inner.jar");
        File b = (File) call("toFile", new Class<?>[] {URL.class}, new Object[] {inner});
        assertEquals(f.getCanonicalFile(), b.getCanonicalFile());
    }

    @Test
    public void libDirWithoutServerJarsIsRejected() throws Exception {
        Class<?>[] types = new Class<?>[] {File.class, ClassLoader.class, String[].class};
        try {
            call("startFromLibDir", types,
                    new Object[] {dir, BootstrapIntegrationTest.class.getClassLoader(), new String[0]});
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No gumdrop server jars"));
        }
    }

    @Test
    public void fatJarWithoutManifestOrContainerEntriesIsRejected() throws Exception {
        Class<?>[] types = new Class<?>[] {URL.class, File.class, ClassLoader.class, String[].class};
        File noManifest = jarWithManifest("none.jar", null);
        try {
            call("startFromFatJar", types, new Object[] {noManifest.toURI().toURL(), noManifest,
                BootstrapIntegrationTest.class.getClassLoader(), new String[0]});
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        File incomplete = jarWithManifest("incomplete.jar", m);
        try {
            call("startFromFatJar", types, new Object[] {incomplete.toURI().toURL(), incomplete,
                BootstrapIntegrationTest.class.getClassLoader(), new String[0]});
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Container-Jar"));
        }
    }

    @Test
    public void resolveLibDirWithoutLayoutReturnsNull() throws Exception {
        File jar = jarWithManifest("plain.jar", null);
        Object r = call("resolveLibDir", new Class<?>[] {File.class}, new Object[] {jar});
        if (System.getenv("GUMDROP_HOME") == null) {
            assertEquals(null, r);
        }
    }

    @Test
    public void resolveLibDirHonoursBootstrapJarName() throws Exception {
        assertTrue(new File(dir, "gumdrop.jar").createNewFile());
        File boot = jarWithManifest("gumdrop-bootstrap.jar", null);
        Object r = call("resolveLibDir", new Class<?>[] {File.class}, new Object[] {boot});
        assertNotNull(r);
        assertEquals(dir.getCanonicalFile(), ((File) r).getCanonicalFile());
    }

    @Test
    public void resolveLibDirHonoursManifestLayoutAttribute() throws Exception {
        assertTrue(new File(dir, "gumdrop.jar").createNewFile());
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Container-Layout", "lib");
        File boot = jarWithManifest("custom.jar", m);
        Object r = call("resolveLibDir", new Class<?>[] {File.class}, new Object[] {boot});
        assertNotNull(r);
    }

    @Test
    public void reconfigureLoggingWithoutConfigFileIsNoop() throws Exception {
        String key = "java.util.logging.config.file";
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            call("reconfigureLogging", new Class<?>[] {ClassLoader.class},
                    new Object[] {BootstrapIntegrationTest.class.getClassLoader()});
            System.setProperty(key, new File(dir, "missing.properties").getPath());
            call("reconfigureLogging", new Class<?>[] {ClassLoader.class},
                    new Object[] {BootstrapIntegrationTest.class.getClassLoader()});
            File props = new File(dir, "logging.properties");
            Files.write(props.toPath(),
                    "java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter\n"
                    .getBytes("UTF-8"));
            System.setProperty(key, props.getPath());
            call("reconfigureLogging", new Class<?>[] {ClassLoader.class},
                    new Object[] {BootstrapIntegrationTest.class.getClassLoader()});
            Handler[] handlers = Logger.getLogger("").getHandlers();
            assertFalse(handlers == null);
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }
}
