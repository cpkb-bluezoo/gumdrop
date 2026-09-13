/*
 * TestSciOrderListener.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet.sci;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;

/**
 * Asserts that {@link TestSciInitializer} has already run.
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
