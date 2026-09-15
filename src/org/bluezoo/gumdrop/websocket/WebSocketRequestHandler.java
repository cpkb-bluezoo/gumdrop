/*
 * WebSocketRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.websocket;

/**
 * Protocol-root re-export of
 * {@link org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler}
 * (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler
 * @see docs/COMPOSITION.md
 */
public final class WebSocketRequestHandler {

    private WebSocketRequestHandler() {
    }

    /**
     * @see org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler#builder()
     */
    public static org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler.Builder builder() {
        return org.bluezoo.gumdrop.websocket.server.WebSocketRequestHandler.builder();
    }

}
