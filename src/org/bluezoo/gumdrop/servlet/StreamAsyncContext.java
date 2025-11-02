/*
 * StreamAsyncContext.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    
    // Shared scheduled executor for timeout management across all async contexts
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = 
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "async-timeout");
            t.setDaemon(true);
            return t;
        });

    ServletStream stream;
    boolean dispatched;
    long timeout = 30000L; // Default 30 seconds
    private ScheduledFuture<?> timeoutTask;
    private boolean completed = false;

    Collection<ListenerRegistration> listeners = new ConcurrentLinkedDeque<>();

    StreamAsyncContext(ServletStream stream) {
        this.stream = stream;
    }

    // Called by ContextRequestDispatcher.doFilter once service has been
    // completed after startAsync was called
    void asyncStarted() {
        // Schedule timeout if configured and not already completed
        scheduleTimeout();
        
        // Notify listeners
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
    
    /**
     * Schedules the timeout task if a timeout is configured.
     */
    private synchronized void scheduleTimeout() {
        if (timeout > 0 && !completed && timeoutTask == null) {
            timeoutTask = TIMEOUT_EXECUTOR.schedule(() -> {
                handleTimeout();
            }, timeout, TimeUnit.MILLISECONDS);
            
            LOGGER.fine("Scheduled async timeout for " + timeout + "ms on stream " + stream);
        }
    }
    
    /**
     * Handles async context timeout by notifying listeners and potentially completing the request.
     */
    private synchronized void handleTimeout() {
        if (completed) {
            return; // Already completed, ignore timeout
        }
        
        LOGGER.info("Async context timeout after " + timeout + "ms on stream " + stream);
        
        boolean listenerHandledTimeout = false;
        
        // Notify all registered listeners of the timeout
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
                listener.onTimeout(event);
                // If listener calls complete() or dispatch(), completed will be set to true
                if (completed) {
                    listenerHandledTimeout = true;
                    break;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error in async timeout listener", e);
            }
        }
        
        // If no listener handled the timeout, complete the request with error
        if (!listenerHandledTimeout && !completed) {
            LOGGER.info("No listener handled timeout, completing request with error");
            try {
                // Send 500 Internal Server Error for timeout
                stream.response.sendError(500, "Async operation timed out");
                complete();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error completing timed-out async request", e);
            }
        }
    }
    
    /**
     * Cancels any pending timeout task.
     */
    private synchronized void cancelTimeout() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            boolean cancelled = timeoutTask.cancel(false);
            LOGGER.fine("Cancelled async timeout task: " + cancelled + " on stream " + stream);
            timeoutTask = null;
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

    @Override public synchronized void dispatch() {
        if (completed) {
            throw new IllegalStateException("AsyncContext already completed");
        }
        
        completed = true;
        cancelTimeout();
        dispatched = true;
        
        LOGGER.fine("Dispatching async context on stream " + stream);
        
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

    @Override public synchronized void dispatch(ServletContext context, String path) {
        if (completed) {
            throw new IllegalStateException("AsyncContext already completed");
        }
        
        completed = true;
        cancelTimeout();
        dispatched = true;
        
        LOGGER.fine("Dispatching async context to path " + path + " on stream " + stream);
        
        RequestDispatcher rd = context.getRequestDispatcher(path);
        try {
            rd.forward(getRequest(), getResponse());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override public synchronized void complete() {
        if (completed) {
            return; // Already completed
        }
        
        completed = true;
        cancelTimeout();
        
        LOGGER.fine("Completing async context on stream " + stream);
        
        // Notify completion listeners
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
        
        // Mark the response as completed - the stream will be closed by the container
        // when the response is fully sent
        LOGGER.fine("Async context completed on stream " + stream);
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

    @Override public synchronized void setTimeout(long timeout) {
        this.timeout = timeout;
        
        // If already started and timeout changed, reschedule
        if (timeoutTask != null) {
            cancelTimeout();
            scheduleTimeout();
        }
        
        LOGGER.fine("Set async timeout to " + timeout + "ms on stream " + stream);
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
