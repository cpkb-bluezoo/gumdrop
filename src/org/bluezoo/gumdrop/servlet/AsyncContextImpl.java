/*
 * AsyncContextImpl.java
 * Copyright (C) 2025 Chris Burdess
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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the Servlet 3.0+ AsyncContext interface.
 * 
 * <p>This allows servlets to perform asynchronous processing by deferring
 * the response completion until a later time (possibly in a different thread).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class AsyncContextImpl implements AsyncContext {

    private static final Logger LOGGER = Logger.getLogger(AsyncContextImpl.class.getName());
    private static final long DEFAULT_TIMEOUT = 30000L; // 30 seconds

    private final ServletHandler handler;
    private final Request request;
    private final Response response;
    private final ServletRequest originalRequest;
    private final ServletResponse originalResponse;
    private final ServletServer server;
    private final List<AsyncListener> listeners = new ArrayList<>();
    
    private long timeout = DEFAULT_TIMEOUT;
    private volatile boolean completed = false;
    private volatile boolean dispatching = false;
    private AsyncTimeoutHandle timeoutHandle;

    AsyncContextImpl(ServletHandler handler, Request request, Response response) {
        this(handler, request, response, request, response);
    }

    AsyncContextImpl(ServletHandler handler, Request request, Response response,
                     ServletRequest originalRequest, ServletResponse originalResponse) {
        this.handler = handler;
        this.request = request;
        this.response = response;
        this.originalRequest = originalRequest;
        this.originalResponse = originalResponse;
        this.server = handler.getServer();
        
        // Schedule initial timeout
        scheduleTimeout();
    }

    @Override
    public ServletRequest getRequest() {
        return originalRequest;
    }

    @Override
    public ServletResponse getResponse() {
        return originalResponse;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return originalRequest == request && originalResponse == response;
    }

    @Override
    public void dispatch() {
        dispatch(request.context, request.getRequestURI());
    }

    @Override
    public void dispatch(String path) {
        dispatch(request.context, path);
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        if (completed) {
            throw new IllegalStateException(ServletServer.L10N.getString("async.already_completed"));
        }
        if (dispatching) {
            throw new IllegalStateException("Already dispatching");
        }
        
        dispatching = true;
        cancelTimeout();
        
        LOGGER.fine(MessageFormat.format(
            ServletServer.L10N.getString("async.dispatching_path"), path));
        
        // Submit dispatch to worker thread pool
        server.getWorkerThreadPool().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    // Notify listeners of start
                    notifyListeners(new AsyncEvent(AsyncContextImpl.this, request, response), true);
                    
                    // Get dispatcher for the path
                    Context ctx = (context instanceof Context) ? (Context) context : request.context;
                    ContextRequestDispatcher dispatcher = 
                        (ContextRequestDispatcher) ctx.getRequestDispatcher(path);
                    
                    if (dispatcher != null) {
                        dispatcher.handleRequest(request, response);
                    }
                    
                    // Complete after dispatch unless async was started again
                    if (!request.isAsyncStarted()) {
                        complete();
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_dispatch"), e);
                    try {
                        notifyError(e);
                    } catch (Exception ex) {
                        LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_listener"), ex);
                    }
                }
            }
        });
    }

    @Override
    public void complete() {
        if (completed) {
            return; // Already completed, ignore
        }
        
        completed = true;
        cancelTimeout();
        
        LOGGER.fine(ServletServer.L10N.getString("async.completing"));
        
        try {
            // Notify listeners
            notifyComplete();
            
            // Flush and close response
            response.flushBuffer();
            response.endResponse();
            
            LOGGER.fine(ServletServer.L10N.getString("async.completed"));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_flush"), e);
        }
    }

    @Override
    public void start(Runnable run) {
        if (completed) {
            throw new IllegalStateException(ServletServer.L10N.getString("async.already_completed"));
        }
        
        // Run the task in the worker thread pool
        server.getWorkerThreadPool().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_task"), e);
                    try {
                        notifyError(e);
                    } catch (Exception ex) {
                        LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_listener"), ex);
                    }
                }
            }
        });
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        // Store the request/response with the listener for event creation
        listeners.add(new AsyncListenerWrapper(listener, request, response));
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            return clazz.newInstance();
        } catch (Exception e) {
            throw new ServletException("Cannot create listener", e);
        }
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
        LOGGER.fine(MessageFormat.format(
            ServletServer.L10N.getString("async.set_timeout"), timeout));
        
        // Reschedule timeout with new value
        cancelTimeout();
        if (timeout > 0 && !completed) {
            scheduleTimeout();
        }
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    boolean isCompleted() {
        return completed;
    }

    private void scheduleTimeout() {
        if (timeout <= 0) {
            return;
        }
        
        AsyncTimeoutScheduler scheduler = server.getAsyncTimeoutScheduler();
        if (scheduler != null) {
            timeoutHandle = scheduler.schedule(timeout, new AsyncTimeoutCallback() {
                @Override
                public void onTimeout() {
                    handleTimeout();
                }
            });
            
            LOGGER.fine(MessageFormat.format(
                ServletServer.L10N.getString("async.scheduled_timeout"), timeout));
        }
    }

    private void cancelTimeout() {
        if (timeoutHandle != null) {
            timeoutHandle.cancel();
            timeoutHandle = null;
            LOGGER.fine(ServletServer.L10N.getString("async.cancelled_timeout"));
        }
    }

    private void handleTimeout() {
        if (completed) {
            return;
        }
        
        LOGGER.fine(MessageFormat.format(
            ServletServer.L10N.getString("async.timeout"), timeout));
        
        try {
            // Notify listeners - they may handle the timeout
            boolean handled = notifyTimeout();
            
            if (!handled) {
                // No listener handled the timeout, complete with error
                LOGGER.warning(ServletServer.L10N.getString("async.timeout_no_handler"));
                if (!response.isCommitted()) {
                    response.sendError(500, ServletServer.L10N.getString("async.timeout_error"));
                }
                complete();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, ServletServer.L10N.getString("async.error_timeout"), e);
            complete();
        }
    }

    private void notifyComplete() {
        AsyncEvent event = new AsyncEvent(this, request, response);
        for (AsyncListener listener : listeners) {
            try {
                if (listener instanceof AsyncListenerWrapper) {
                    AsyncListenerWrapper wrapper = (AsyncListenerWrapper) listener;
                    event = new AsyncEvent(this, wrapper.request, wrapper.response);
                    wrapper.listener.onComplete(event);
                } else {
                    listener.onComplete(event);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, ServletServer.L10N.getString("async.error_listener"), e);
            }
        }
    }

    private boolean notifyTimeout() throws IOException {
        AsyncEvent event = new AsyncEvent(this, request, response);
        boolean handled = false;
        for (AsyncListener listener : listeners) {
            try {
                if (listener instanceof AsyncListenerWrapper) {
                    AsyncListenerWrapper wrapper = (AsyncListenerWrapper) listener;
                    event = new AsyncEvent(this, wrapper.request, wrapper.response);
                    wrapper.listener.onTimeout(event);
                } else {
                    listener.onTimeout(event);
                }
                // If any listener handles the timeout (doesn't throw), consider it handled
                handled = true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, ServletServer.L10N.getString("async.error_listener"), e);
            }
        }
        return handled;
    }

    private void notifyError(Throwable t) throws IOException {
        AsyncEvent event = new AsyncEvent(this, request, response, t);
        for (AsyncListener listener : listeners) {
            try {
                if (listener instanceof AsyncListenerWrapper) {
                    AsyncListenerWrapper wrapper = (AsyncListenerWrapper) listener;
                    event = new AsyncEvent(this, wrapper.request, wrapper.response, t);
                    wrapper.listener.onError(event);
                } else {
                    listener.onError(event);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, ServletServer.L10N.getString("async.error_listener"), e);
            }
        }
    }

    private void notifyListeners(AsyncEvent event, boolean startAsync) throws IOException {
        for (AsyncListener listener : listeners) {
            try {
                if (startAsync) {
                    listener.onStartAsync(event);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, ServletServer.L10N.getString("async.error_listener"), e);
            }
        }
    }

    /**
     * Wrapper to associate a listener with specific request/response.
     */
    private static class AsyncListenerWrapper implements AsyncListener {
        final AsyncListener listener;
        final ServletRequest request;
        final ServletResponse response;

        AsyncListenerWrapper(AsyncListener listener, ServletRequest request, ServletResponse response) {
            this.listener = listener;
            this.request = request;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            listener.onComplete(event);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            listener.onTimeout(event);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            listener.onError(event);
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            listener.onStartAsync(event);
        }
    }

}

