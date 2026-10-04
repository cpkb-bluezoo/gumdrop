/*
 * ContextScanOptionalApiTest.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Function;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The annotation, EJB, persistence and web service APIs are optional at
 * run time. Scanning a web application's classes must work without any
 * one of them: a class cannot carry an annotation whose type is absent,
 * so there is nothing to find, but every other annotation still counts.
 *
 * <p>Each test runs the scan in a class loader that has the test class
 * path except for one of those APIs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextScanOptionalApiTest {

    private static final String EXPECTED = "servlets=1 scanned=1 postConstructs=1";

    /** Loads everything itself, and cannot find one package tree. */
    private static final class HidingClassLoader extends URLClassLoader {

        private final String hiddenPrefix;

        HidingClassLoader(URL[] urls, String hiddenPrefix) {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.hiddenPrefix = hiddenPrefix;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(hiddenPrefix)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    @SuppressWarnings("unchecked") // the probe is a Function<String, String>
    private static String scanWithout(String hiddenPrefix, String fixture) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        String classPath = System.getProperty("java.class.path");
        StringTokenizer st = new StringTokenizer(classPath, File.pathSeparator);
        while (st.hasMoreTokens()) {
            File element = new File(st.nextToken());
            urls.add(element.toURI().toURL());
        }
        URL[] array = urls.toArray(new URL[0]);
        HidingClassLoader loader = new HidingClassLoader(array, hiddenPrefix);
        try {
            Class<?> type = loader.loadClass(ContextScanOptionalApiProbe.class.getName());
            Object probe = type.getDeclaredConstructor().newInstance();
            return ((Function<String, String>) probe).apply(fixture);
        } finally {
            loader.close();
        }
    }

    @Test
    public void testScanWithEverythingPresent() throws Exception {
        assertEquals(EXPECTED, scanWithout("no.such.api.", "JakartaLifecycle"));
        assertEquals(EXPECTED, scanWithout("no.such.api.", "JavaxLifecycle"));
    }

    @Test
    public void testScanWithoutJakartaPersistence() throws Exception {
        assertEquals(EXPECTED, scanWithout("jakarta.persistence.", "JakartaLifecycle"));
    }

    @Test
    public void testScanWithoutJavaxPersistence() throws Exception {
        assertEquals(EXPECTED, scanWithout("javax.persistence.", "JakartaLifecycle"));
    }

    @Test
    public void testScanWithoutEjb() throws Exception {
        assertEquals(EXPECTED, scanWithout("javax.ejb.", "JakartaLifecycle"));
    }

    @Test
    public void testScanWithoutWebServices() throws Exception {
        assertEquals(EXPECTED, scanWithout("javax.xml.ws.", "JakartaLifecycle"));
    }

    @Test
    public void testScanWithoutJavaxAnnotation() throws Exception {
        assertEquals(EXPECTED, scanWithout("javax.annotation.", "JakartaLifecycle"));
    }

    @Test
    public void testScanWithoutJakartaAnnotation() throws Exception {
        assertEquals(EXPECTED, scanWithout("jakarta.annotation.", "JavaxLifecycle"));
    }
}
