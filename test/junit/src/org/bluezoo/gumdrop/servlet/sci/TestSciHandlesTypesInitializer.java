/*
 * TestSciHandlesTypesInitializer.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet.sci;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;

/**
 * Records the {@code @HandlesTypes} class set passed by the container.
 */
@HandlesTypes(SciMarker.class)
public class TestSciHandlesTypesInitializer implements ServletContainerInitializer {

    public static final String HANDLES_TYPES_COUNT =
            "org.bluezoo.gumdrop.servlet.sci.handles-types-count";

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext servletContext)
            throws ServletException {
        servletContext.setAttribute(HANDLES_TYPES_COUNT, Integer.valueOf(classes.size()));
    }
}
