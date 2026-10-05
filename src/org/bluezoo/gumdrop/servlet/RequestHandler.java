/*
 * RequestHandler.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.logging.Level;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The request handler retrieves a stream from the request
 * queue, locates the correct servlet for the request, and services it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RequestHandler implements Runnable {

    final ServletHandler handler;
    final Container container;

    public RequestHandler(ServletHandler handler, Container container) {
        this.handler = handler;
        this.container = container;
    }

    public void run() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        final Request request = handler.getRequest();
        final Response response = handler.getResponse();
        try {
            ContextRequestDispatcher dispatcher = getRequestDispatcher(request, response);
            if (dispatcher == null) {
                if (!response.isCommitted()) {
                    String message = "unable to locate context for " + request.uri;
                    response.sendError(404, message);
                }
            } else {
                notifyRequestInitialized(request);
                dispatcher.handleRequest(request, response);
                notifyRequestDestroyed(request);

                // Replicate session if necessary
                if (request.sessionId != null && dispatcher.context != null) {
                    Context context = dispatcher.context;
                    if (context.distributable) {
                        String id = request.sessionId;
                        HttpSession session = context.getSessionManager().getSession(id);
                        if (session != null) {
                            context.getSessionManager().replicateSession(session);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(loader);

            // Only complete the response if async was NOT started
            // If async was started, the AsyncContext.complete() will handle it
            if (!request.isAsyncStarted() && !request.isUpgraded()) {
                handler.publishApplicationPrincipal(request);
                try {
                    response.flushBuffer();
                    response.endResponse();
                } catch (ClosedChannelException e) {
                    // ignore
                } catch (IOException e) {
                    Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            } else {
                handler.publishApplicationPrincipal(request);
            }
        }
    }

    void notifyRequestInitialized(Request request) {
        try {
            ServletRequestEvent event = new ServletRequestEvent(request.context, request);
            for (ServletRequestListener l : request.context.servletRequestListeners) {
                l.requestInitialized(event);
            }
        } catch (Exception e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    void notifyRequestDestroyed(Request request) {
        try {
            ServletRequestEvent event = new ServletRequestEvent(request.context, request);
            for (ServletRequestListener l : request.context.servletRequestListeners) {
                l.requestDestroyed(event);
            }
        } catch (Exception e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    /**
     * Locate the request dispatcher for the given request.
     * This method also modifies the request to provide the context path and
     * servlet path.
     */
    ContextRequestDispatcher getRequestDispatcher(Request request, Response response) throws IOException {
        URI uri = request.getURI();
        String path = (uri == null) ? "" : uri.getPath();

        // Lookup context
        Context context = container.getContextByPath(path);
        // Lookup request dispatcher
        if (context == null) {
            return null;
        }
        if (context.loadFailed) {
            // The web application could not be loaded. It may be there
            // again once it has been corrected and redeployed.
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return null;
        }
        Thread.currentThread().setContextClassLoader(context.getContextClassLoader());
        request.context = context;
        request.contextPath = context.contextPath;
        response.context = context;
        // strip contextPath from path
        if (!context.contextPath.equals("/") && path.startsWith(context.contextPath)) {
            path = path.substring(context.contextPath.length());
        }
        if ("".equals(path)) {
            path = "/";
        }
        ContextRequestDispatcher crd =
            (ContextRequestDispatcher) context.getRequestDispatcher(path);
        request.match = crd.match;
        // Only update queryString if not already set (e.g., from the original request URI)
        if (request.queryString == null) {
            request.queryString = crd.queryString;
        }
        request.initSession();
        return crd;
    }

}
