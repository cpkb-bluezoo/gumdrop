/*
 * ServletRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.servlet;

/**
 * Protocol-root re-export of
 * {@link org.bluezoo.gumdrop.servlet.server.ServletRequestHandler}
 * (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.servlet.server.ServletRequestHandler
 * @see docs/COMPOSITION.md
 */
public final class ServletRequestHandler {

    private ServletRequestHandler() {
    }

    /**
     * @see org.bluezoo.gumdrop.servlet.server.ServletRequestHandler#ServletRequestHandler(Container)
     */
    public static org.bluezoo.gumdrop.servlet.server.ServletRequestHandler of(
            Container container) {
        return new org.bluezoo.gumdrop.servlet.server.ServletRequestHandler(container);
    }

}
