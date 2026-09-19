/*
 * TestSciInitializer.java
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

package org.bluezoo.gumdrop.servlet.sci;

import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

/**
 * Registers {@link TestSciServlet} during SCI startup.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestSciInitializer implements ServletContainerInitializer {

    public static final String STARTUP_ATTRIBUTE = "org.bluezoo.gumdrop.servlet.sci.startup";

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext servletContext)
            throws ServletException {
        servletContext.setAttribute(STARTUP_ATTRIBUTE, "done");
        servletContext.addServlet("sci-test", TestSciServlet.class)
                .addMapping("/sci-test");
    }
}
