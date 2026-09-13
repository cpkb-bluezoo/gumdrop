/*
 * ServletRequestHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.servlet.server;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestRouter;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.ServletHandler;

/**
 * Jakarta Servlet container as an {@link HttpRequestRouter}.
 *
 * <p>Install on {@link org.bluezoo.gumdrop.http.HttpServer} with a
 * pre-configured {@link Container} (contexts, realms, resources). The
 * handler drives container {@link Container#start()} /
 * {@link Container#destroy()} via {@link HttpServerServiceHook}.
 *
 * <pre>{@code
 * Container container = new Container();
 * container.addContext(new Context(container, "/app", appRoot));
 *
 * HttpServer server = HttpServer.builder()
 *         .secureEndpoint(443, HttpTlsConfig.pem("cert.pem", "key.pem"))
 *         .router(new ServletRequestHandler(container))
 *         .build();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServletServer
 * @see docs/COMPOSITION.md
 */
public final class ServletRequestHandler
        implements HttpRequestRouter, HttpServerServiceHook {

    private final Container container;

    public ServletRequestHandler(Container container) {
        if (container == null) {
            throw new NullPointerException("container");
        }
        this.container = container;
    }

    public Container getContainer() {
        return container;
    }

    @Override
    public HttpRequestHandler route(HttpResponseState state, Headers headers) {
        return new ServletHandler(container, container.getBufferSize());
    }

    @Override
    public void initService() {
        container.start();
    }

    @Override
    public void destroyService() {
        container.destroy();
    }

    @Override
    public org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider getAuthenticationProvider() {
        return container.getAuthenticationProvider();
    }

}
