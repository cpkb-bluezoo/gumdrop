/*
 * ServletRequestHandlerCompositionTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Workstream C.3 — {@link org.bluezoo.gumdrop.servlet.server.ServletRequestHandler}.
 */
public class ServletRequestHandlerCompositionTest {

    @Test
    public void testHandlerUsesConfiguredContainer() {
        Container container = new Container();
        org.bluezoo.gumdrop.servlet.server.ServletRequestHandler handler =
                new org.bluezoo.gumdrop.servlet.server.ServletRequestHandler(container);
        assertSame(container, handler.getContainer());
        assertTrue(handler instanceof HttpServerServiceHook);
    }

}
