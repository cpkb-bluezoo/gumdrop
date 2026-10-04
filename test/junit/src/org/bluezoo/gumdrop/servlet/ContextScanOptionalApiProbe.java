/*
 * ContextScanOptionalApiProbe.java
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

import java.io.InputStream;
import java.util.function.Function;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

/**
 * Scans one of its fixture classes with a new {@link Context} and reports
 * what the scan registered. {@link ContextScanOptionalApiTest} runs it in
 * a class loader that lacks one of the optional APIs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextScanOptionalApiProbe implements Function<String, String> {

    /**
     * Servlet with an annotation of no interest to the scan on the class,
     * on a field and on a method, and a jakarta lifecycle method last.
     */
    @Deprecated
    @WebServlet(name = "optional", urlPatterns = { "/optional" })
    public static class JakartaLifecycle extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Deprecated
        Object field;

        @Deprecated
        public void unrelated() {
        }

        @jakarta.annotation.PostConstruct
        public void start() {
        }
    }

    /** The same, with a javax lifecycle method. */
    @Deprecated
    @WebServlet(name = "optional", urlPatterns = { "/optional" })
    public static class JavaxLifecycle extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Deprecated
        Object field;

        @Deprecated
        public void unrelated() {
        }

        @javax.annotation.PostConstruct
        public void start() {
        }
    }

    /**
     * @param fixture the simple name of the fixture class to scan
     * @return the servlets, scanned classes and lifecycle methods found
     */
    @Override
    public String apply(String fixture) {
        try {
            MemoryFolder tmp = new MemoryFolder();
            Context context = new Context(new Container(), "/scan", tmp.newFolder("scan"));
            WebFragment descriptor = new WebFragment();
            String name = ContextScanOptionalApiProbe.class.getName() + "$" + fixture;
            String resource = name.replace('.', '/') + ".class";
            ClassLoader loader = ContextScanOptionalApiProbe.class.getClassLoader();
            InputStream in = loader.getResourceAsStream(resource);
            try {
                context.scanClass(descriptor, name, in);
            } finally {
                in.close();
            }
            return "servlets=" + descriptor.servletDefs.size()
                    + " scanned=" + context.scannedApplicationClasses.size()
                    + " postConstructs=" + context.postConstructs.size();
        } catch (Exception e) {
            return e.toString();
        }
    }
}
