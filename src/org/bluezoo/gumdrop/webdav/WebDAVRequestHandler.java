/*
 * WebDAVRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.webdav;

/**
 * Protocol-root re-export of
 * {@link org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler}
 * (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler
 * @see docs/COMPOSITION.md
 */
public final class WebDAVRequestHandler {

    private WebDAVRequestHandler() {
    }

    /**
     * @see org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler#builder()
     */
    public static org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler.Builder builder() {
        return org.bluezoo.gumdrop.webdav.server.WebDAVRequestHandler.builder();
    }

}
