/*
 * TestSciOrderListener.java
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

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;

/**
 * Asserts that {@link TestSciInitializer} has already run.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TestSciOrderListener implements ServletContextListener {

    public static final String ORDER_ATTRIBUTE =
            "org.bluezoo.gumdrop.servlet.sci.listener-order";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (!"done".equals(sce.getServletContext().getAttribute(
                TestSciInitializer.STARTUP_ATTRIBUTE))) {
            throw new RuntimeException(new ServletException(
                    "ServletContainerInitializer must run before "
                            + "ServletContextListener.contextInitialized"));
        }
        sce.getServletContext().setAttribute(ORDER_ATTRIBUTE, "listener-ran");
    }
}
