/*
 * StreamAsyncContext.java
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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;

/**
 * An AsyncContext wrapping a ServletStream.
 * 
 * <p>This implementation provides true async servlet support by:
 * <ul>
 * <li>Running {@link #start(Runnable)} tasks on a container thread pool</li>
 * <li>Executing {@link #dispatch()} operations on a worker thread</li>
 * <li>Properly flushing and completing responses on {@link #complete()}</li>
 * <li>Managing timeouts with proper listener notification</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class StreamAsyncContext implements AsyncContext {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER = Logger.getLogger(StreamAsyncContext.class.getName());
    
    // Shared scheduled executor for timeout management across all async contexts
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = 
        Executors.newScheduledThreadPool(2, new AsyncTimeoutThreadFactory());
    
    // Shared executor for async tasks (start() and dispatch())
    private static final ExecutorService ASYNC_EXECUTOR = 
        Executors.newCachedThreadPool(new AsyncWorkerThreadFactory());

    final ServletStream stream;
    private boolean dispatched;
    private long timeout = 30000L; // Default 30 seconds per spec
    private ScheduledFuture<?> timeoutTask;
    private volatile boolean completed = false;
    private volatile boolean started = false;

    private final Collection<ListenerRegistration> listeners = new ConcurrentLinkedDeque<>();

    StreamAsyncContext(ServletStream stream) {
        this.stream = stream;
    }

    /**
     * Called by ContextRequestDispatcher.doFilter once service has been
     * completed after startAsync was called.
     */
    void asyncStarted() {
        started = true;
        
        // Schedule timeout if configured and not already completed
        scheduleTimeout();
        
        // Notify listeners of async start
        notifyListeners(new ListenerNotifier() {
            public void notify(AsyncListener listener, AsyncEvent event) throws IOException {
                listener.onStartAsync(event);
            }
        });
    }
    
    /**
     * Schedules the timeout task if a timeout is configured.
     */
    private synchronized void scheduleTimeout() {
        if (timeout > 0 && !completed && timeoutTask == null) {
            timeoutTask = TIMEOUT_EXECUTOR.schedule(
                new TimeoutHandler(), timeout, TimeUnit.MILLISECONDS);
            
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                    L10N.getString("async.scheduled_timeout"), timeout));
            }
        }
    }
    
    /**
     * Handles async context timeout by notifying listeners and potentially completing the request.
     */
    private void handleTimeout() {
        synchronized (this) {
            if (completed) {
                return; // Already completed, ignore timeout
            }
        }
        
        LOGGER.info(MessageFormat.format(L10N.getString("async.timeout"), timeout));
        
        // Record timeout in telemetry
        recordTelemetryError(ErrorCategory.TIMEOUT, 
            MessageFormat.format(L10N.getString("async.timeout"), timeout));
        
        // Notify all registered listeners of the timeout
        final boolean[] listenerHandledTimeout = { false };
        notifyListeners(new ListenerNotifier() {
            public void notify(AsyncListener listener, AsyncEvent event) throws IOException {
                listener.onTimeout(event);
                // If listener calls complete() or dispatch(), completed will be set to true
                if (completed) {
                    listenerHandledTimeout[0] = true;
                }
            }
        });
        
        // If no listener handled the timeout, complete the request with error
        synchronized (this) {
            if (!listenerHandledTimeout[0] && !completed) {
                LOGGER.info(L10N.getString("async.timeout_no_handler"));
                try {
                    // Send 500 Internal Server Error for timeout
                    if (!stream.response.committed) {
                        stream.response.sendError(500, L10N.getString("async.timeout_error"));
                    }
                    completeInternal(false); // Don't notify listeners again
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, L10N.getString("async.error_timeout"), e);
                }
            }
        }
    }
    
    /**
     * Cancels any pending timeout task.
     */
    private synchronized void cancelTimeout() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            boolean cancelled = timeoutTask.cancel(false);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(L10N.getString("async.cancelled_timeout") + ": " + cancelled);
            }
            timeoutTask = null;
        }
    }

    /**
     * Notifies listeners of an error.
     * Called when an exception occurs during async processing.
     * 
     * @param throwable the error that occurred
     */
    void error(Throwable throwable) {
        // Record error in telemetry
        recordTelemetryException(throwable);
        
        final Throwable t = throwable;
        notifyListeners(new ListenerNotifier() {
            public void notify(AsyncListener listener, AsyncEvent event) throws IOException {
                listener.onError(new AsyncEvent(
                    StreamAsyncContext.this, 
                    event.getSuppliedRequest(), 
                    event.getSuppliedResponse(), 
                    t));
            }
        });
    }

    @Override 
    public ServletRequest getRequest() {
        return stream.request;
    }

    @Override 
    public ServletResponse getResponse() {
        return stream.response;
    }

    @Override 
    public boolean hasOriginalRequestAndResponse() {
        return (getRequest() instanceof Request) && (getResponse() instanceof Response);
    }

    @Override 
    public void dispatch() {
        dispatchInternal(null, null);
    }

    @Override 
    public void dispatch(String path) {
        dispatch(getRequest().getServletContext(), path);
    }

    @Override 
    public void dispatch(ServletContext context, String path) {
        dispatchInternal(context, path);
    }
    
    /**
     * Internal dispatch implementation that runs on the async executor.
     */
    private void dispatchInternal(final ServletContext targetContext, final String path) {
        synchronized (this) {
            if (completed) {
                throw new IllegalStateException(L10N.getString("async.already_completed"));
            }
            
            completed = true;
            cancelTimeout();
            dispatched = true;
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            if (path != null) {
                LOGGER.fine(MessageFormat.format(
                    L10N.getString("async.dispatching_path"), path));
            } else {
                LOGGER.fine(L10N.getString("async.dispatching"));
            }
        }
        
        // Execute dispatch on container thread pool
        ASYNC_EXECUTOR.execute(new Runnable() {
            public void run() {
                try {
                    if (path != null && targetContext != null) {
                        // Dispatch to specific path
                        RequestDispatcher rd = targetContext.getRequestDispatcher(path);
                        rd.forward(getRequest(), getResponse());
                    } else {
                        // Dispatch to original servlet
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
                        Servlet servlet = context.loadServlet(servletDef);
                        servlet.service(request, response);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, L10N.getString("async.error_dispatch"), e);
                    error(e);
                }
            }
        });
    }

    @Override 
    public void complete() {
        completeInternal(true);
    }
    
    /**
     * Internal completion implementation.
     * 
     * @param notifyListeners whether to notify completion listeners
     */
    private void completeInternal(boolean notifyListeners) {
        synchronized (this) {
            if (completed) {
                return; // Already completed
            }
            
            completed = true;
            cancelTimeout();
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("async.completing"));
        }
        
        // Flush response to ensure all data is written
        try {
            Response response = stream.response;
            if (response != null) {
                if (response.outputStream != null) {
                    response.outputStream.flush();
                }
                if (response.writer != null) {
                    response.writer.flush();
                }
            }
            
            // Signal to stream that response is complete
            stream.endResponse();
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("async.error_flush"), e);
            recordTelemetryException(e);
        }
        
        // Notify completion listeners
        if (notifyListeners) {
            notifyListeners(new ListenerNotifier() {
                public void notify(AsyncListener listener, AsyncEvent event) throws IOException {
                    listener.onComplete(event);
                }
            });
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("async.completed"));
        }
    }

    @Override 
    public void start(Runnable run) {
        if (completed) {
            throw new IllegalStateException(L10N.getString("async.already_completed"));
        }
        
        // Execute on container thread pool
        ASYNC_EXECUTOR.execute(new AsyncTask(run));
    }

    @Override 
    public void addListener(AsyncListener listener) {
        listeners.add(new ListenerRegistration(listener, null, null));
    }

    @Override 
    public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        listeners.add(new ListenerRegistration(listener, request, response));
    }

    @Override 
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            Constructor<T> constructor = clazz.getConstructor();
            return constructor.newInstance();
        } catch (InstantiationException e) {
            throw new ServletException(e);
        } catch (IllegalAccessException e) {
            throw new ServletException(e);
        } catch (InvocationTargetException e) {
            throw new ServletException(e);
        } catch (NoSuchMethodException e) {
            throw new ServletException(e);
        }
    }

    @Override 
    public synchronized void setTimeout(long timeout) {
        this.timeout = timeout;
        
        // If already started and timeout changed, reschedule
        if (started && timeoutTask != null) {
            cancelTimeout();
            scheduleTimeout();
        }
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("async.set_timeout"), timeout));
        }
    }

    @Override 
    public long getTimeout() {
        return timeout;
    }
    
    /**
     * Returns true if this async context has been completed.
     */
    boolean isCompleted() {
        return completed;
    }
    
    /**
     * Records an error in telemetry if enabled.
     */
    private void recordTelemetryError(ErrorCategory category, String message) {
        Span span = stream.getStreamSpan();
        if (span != null) {
            span.recordError(category, message);
        }
    }
    
    /**
     * Records an exception in telemetry if enabled.
     */
    private void recordTelemetryException(Throwable t) {
        Span span = stream.getStreamSpan();
        if (span != null) {
            span.recordException(t);
        }
    }
    
    /**
     * Notifies all registered listeners using the given notifier.
     */
    private void notifyListeners(ListenerNotifier notifier) {
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
                notifier.notify(listener, event);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, L10N.getString("async.error_listener"), e);
            }
        }
    }
    
    /**
     * Functional interface for listener notification.
     */
    private interface ListenerNotifier {
        void notify(AsyncListener listener, AsyncEvent event) throws IOException;
    }

    /**
     * Registration of a listener with optional request/response.
     */
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

    /**
     * Thread factory for async timeout threads.
     */
    private static class AsyncTimeoutThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "async-timeout");
            t.setDaemon(true);
            return t;
        }
    }
    
    /**
     * Thread factory for async worker threads.
     */
    private static class AsyncWorkerThreadFactory implements ThreadFactory {
        private static final AtomicInteger THREAD_COUNT = new AtomicInteger(0);
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "async-worker-" + THREAD_COUNT.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Runnable to handle timeout events.
     */
    private class TimeoutHandler implements Runnable {
        @Override
        public void run() {
            handleTimeout();
        }
    }
    
    /**
     * Wrapper for async tasks that handles exceptions.
     */
    private class AsyncTask implements Runnable {
        private final Runnable delegate;
        
        AsyncTask(Runnable delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void run() {
            try {
                delegate.run();
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, L10N.getString("async.error_task"), t);
                error(t);
            }
        }
    }
}
