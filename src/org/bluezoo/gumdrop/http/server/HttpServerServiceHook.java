/*
 * HttpServerServiceHook.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.http.server;

/**
 * Optional lifecycle hook for {@link HttpStreamHandler} implementations that
 * own resources started by {@link org.bluezoo.gumdrop.http.HttpServer#start()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface HttpServerServiceHook {

    /**
     * Called from {@link org.bluezoo.gumdrop.http.HttpServer#start()} after
     * {@link org.bluezoo.gumdrop.http.HttpServer#initService()} begins.
     */
    void initService();

    /**
     * Called from {@link org.bluezoo.gumdrop.http.HttpServer#stop()} before
     * listeners stop.
     */
    void destroyService();

    /**
     * Returns an authentication provider wired by this router, or {@code null}
     * if none (for example servlet security constraints).
     */
    default HttpAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

}
