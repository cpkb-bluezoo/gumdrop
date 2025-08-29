/*
 * StreamAsyncContext.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;

/**
 * An AsyncContext wrapping a ServletStream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class StreamAsyncContext implements AsyncContext {

    static final Logger LOGGER = Logger.getLogger(StreamAsyncContext.class.getName());

    ServletStream stream;
    boolean dispatched;
    long timeout = 30000L; // TODO

    Collection<ListenerRegistration> listeners = new ConcurrentLinkedDeque<>();

    StreamAsyncContext(ServletStream stream) {
        this.stream = stream;
    }

    // Called by ContextRequestDispatcher.doFilter once service has been
    // completed after startAsync was called
    void asyncStarted() {
        for (ListenerRegistration item : listeners) {
            AsyncListener listener = item.listener;
            ServletRequest request = item.request;
            ServletResponse response = item.response;
            if (request == null) {
                request = stream.request;
            }
            if (response == null) {
                response = stream.response;
            }
            AsyncEvent event = new AsyncEvent(this, request, response);
            try {
                listener.onStartAsync(event);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    void error(Throwable throwable) {
        for (ListenerRegistration item : listeners) {
            AsyncListener listener = item.listener;
            ServletRequest request = item.request;
            ServletResponse response = item.response;
            if (request == null) {
                request = stream.request;
            }
            if (response == null) {
                response = stream.response;
            }
            AsyncEvent event = new AsyncEvent(this, request, response, throwable);
            try {
                listener.onError(event);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    @Override public ServletRequest getRequest() {
        return stream.request;
    }

    @Override public ServletResponse getResponse() {
        return stream.response;
    }

    @Override public boolean hasOriginalRequestAndResponse() {
        return (getRequest() instanceof Request) && (getResponse() instanceof Response);
    }

    @Override public void dispatch() {
        ServletRequest request = getRequest();
        ServletResponse response = getResponse();
        // Determine servlet used for request
        ServletRequest cur = request;
        while (cur != null && (cur instanceof ServletRequestWrapper)) {
            cur = ((ServletRequestWrapper) cur).getRequest();
        }
        Request r = (Request) cur;
        ServletDef servletDef = r.match.servletDef;
        Context context = r.context;
        // Call service
        try {
            Servlet servlet = context.loadServlet(servletDef);
            servlet.service(request, response);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override public void dispatch(String path) {
        dispatch(getRequest().getServletContext(), path);
    }

    @Override public void dispatch(ServletContext context, String path) {
        RequestDispatcher rd = context.getRequestDispatcher(path);
        try {
            rd.forward(getRequest(), getResponse());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override public void complete() {
        // TODO close response
        for (ListenerRegistration item : listeners) {
            AsyncListener listener = item.listener;
            ServletRequest request = item.request;
            ServletResponse response = item.response;
            if (request == null) {
                request = stream.request;
            }
            if (response == null) {
                response = stream.response;
            }
            AsyncEvent event = new AsyncEvent(this, request, response);
            try {
                listener.onComplete(event);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    @Override public void start(Runnable run) {
        run.run();
    }

    @Override public void addListener(AsyncListener listener) {
        listeners.add(new ListenerRegistration(listener, null, null));
    }

    @Override public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        listeners.add(new ListenerRegistration(listener, request, response));
    }

    @Override public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            Constructor<T> constructor = clazz.getConstructor();
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new ServletException(e);
        }
    }

    @Override public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @Override public long getTimeout() {
        return timeout;
    }

    static class ListenerRegistration {

        final AsyncListener listener;
        final ServletRequest request;
        final ServletResponse response;

        ListenerRegistration(AsyncListener listener, ServletRequest request, ServletResponse response) {
            this.listener = listener;
            this.request = request;
            this.response = response;
        }

    }

}
