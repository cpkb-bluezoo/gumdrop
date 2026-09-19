/*
 * ServletRequestHandler.java
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

package org.bluezoo.gumdrop.servlet.server;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.ServletHandler;

/**
 * Jakarta Servlet container as an {@link HttpStreamHandler}.
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
 * HttpServer server = HttpServer.compose()
 *         .secureEndpoint(443, TlsConfig.pem("cert.pem", "key.pem"))
 *         .streamHandler(new ServletRequestHandler(container))
 *         .server();
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see web/configuration.html
 */
public final class ServletRequestHandler
        implements HttpStreamHandler, HttpServerServiceHook {

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
    public HttpRequestHandler openStream(HttpResponseState stream) {
        return new ServletHandler(container, container.getBufferSize());
    }

    @Override
    public void initService(Gumdrop gumdrop) {
        container.start(gumdrop);
    }

    @Override
    public void destroyService() {
        container.destroy();
    }

    // Deliberately null, not container.getAuthenticationProvider() (there is
    // no such thing any more): a provider returned here is attached once,
    // for the listener's whole lifetime, to every request regardless of
    // which Context it lands in -- there is no single auth method/realm
    // that covers every context in a Container. Per-request, per-context,
    // per-security-constraint (url-pattern-scoped) authentication is
    // already handled correctly further down the pipeline, in
    // ContextRequestDispatcher#authorize via Request#authenticate.
    @Override
    public org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider getAuthenticationProvider() {
        return null;
    }

}
