/*
 * TestSciInitializer.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet.sci;

import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

/**
 * Registers {@link TestSciServlet} during SCI startup.
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
