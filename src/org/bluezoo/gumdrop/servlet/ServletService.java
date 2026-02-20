/*
 * ServletService.java
 * Copyright (C) 2005, 2013, 2026 Chris Burdess
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
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.HTTPAuthenticationProvider;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPService;
import org.bluezoo.gumdrop.util.MessageFormatter;

/**
 * Servlet application service.
 *
 * <p>This service manages a servlet {@link Container} and a pool of
 * worker threads. Incoming HTTP requests are dispatched through a
 * {@link ServletHandlerFactory} which bridges the gumdrop HTTP handler
 * API to the Servlet API.
 *
 * <p>Transport endpoints (ports, TLS configuration) are defined by
 * adding listeners via {@link #addListener}. The service wires the
 * handler factory and authentication provider into each listener during
 * {@link #start()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPService
 * @see Container
 */
public class ServletService extends HTTPService {

    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    static final Map TIME_UNITS = new HashMap();
    static {
        TIME_UNITS.put(TimeUnit.NANOSECONDS, "ns");
        TIME_UNITS.put(TimeUnit.MICROSECONDS, "us");
        TIME_UNITS.put(TimeUnit.MILLISECONDS, "ms");
        TIME_UNITS.put(TimeUnit.SECONDS, "s");
        TIME_UNITS.put(TimeUnit.MINUTES, "m");
        TIME_UNITS.put(TimeUnit.HOURS, "h");
        TIME_UNITS.put(TimeUnit.DAYS, "d");
    }

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private Container container;
    private ServletHandlerFactory handlerFactory;
    private HTTPAuthenticationProvider authenticationProvider;
    private Logger accessLogger;
    private ThreadPoolExecutor workerThreadPool;
    private AsyncTimeoutScheduler asyncTimeoutScheduler;
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    public ServletService() {
        workerThreadPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue(),
                new WorkerThreadFactory());
        asyncTimeoutScheduler = new AsyncTimeoutScheduler();
    }

    // ── Configuration ──

    /**
     * Returns the buffer size for request/response I/O.
     *
     * @return the buffer size in bytes
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Sets the buffer size for request/response I/O.
     * Must be called before the service is started.
     *
     * @param bufferSize the buffer size in bytes (minimum 1024)
     */
    public void setBufferSize(int bufferSize) {
        this.bufferSize = Math.max(bufferSize, 1024);
    }

    /**
     * Returns the async timeout scheduler.
     */
    AsyncTimeoutScheduler getAsyncTimeoutScheduler() {
        return asyncTimeoutScheduler;
    }

    public Container getContainer() {
        return container;
    }

    public void setContainer(Container container) {
        this.container = container;
        this.handlerFactory = new ServletHandlerFactory(this, container);
    }

    /**
     * Returns the servlet handler factory.
     * This factory is used by all connections to create request handlers.
     */
    ServletHandlerFactory getServletHandlerFactory() {
        return handlerFactory;
    }

    public void setAccessLog(String path) {
        try {
            FileHandler handler = new FileHandler(path, true);
            handler.setFormatter(new MessageFormatter());
            handler.setLevel(Level.FINEST);
            accessLogger = Logger.getAnonymousLogger();
            accessLogger.setLevel(Level.FINEST);
            accessLogger.setUseParentHandlers(false);
            Handler[] oldHandlers = accessLogger.getHandlers();
            for (int i = 0; i < oldHandlers.length; i++) {
                oldHandlers[i].setLevel(Level.SEVERE);
            }
            accessLogger.addHandler(handler);
        } catch (IOException e) {
            Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    public void setWorkerCorePoolSize(int corePoolSize) {
        workerThreadPool.setCorePoolSize(corePoolSize);
    }

    public void setWorkerMaximumPoolSize(int maximumPoolSize) {
        workerThreadPool.setMaximumPoolSize(maximumPoolSize);
    }

    /**
     * Returns the worker thread pool for servlet request processing.
     */
    public ThreadPoolExecutor getWorkerThreadPool() {
        return workerThreadPool;
    }

    public String getWorkerKeepAlive() {
        TimeUnit timeUnit = TimeUnit.NANOSECONDS;
        long t = workerThreadPool.getKeepAliveTime(timeUnit);
        if (t == 0L) {
            timeUnit = TimeUnit.MILLISECONDS;
        } else {
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.MICROSECONDS;
                t = t / 1000L;
            }
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.MILLISECONDS;
                t = t / 1000L;
            }
            if (t % 1000L == 0L) {
                timeUnit = TimeUnit.SECONDS;
                t = t / 1000L;
            }
            if (t % 60L == 0L) {
                timeUnit = TimeUnit.MINUTES;
                t = t / 60L;
            }
            if (t % 60L == 0L) {
                timeUnit = TimeUnit.HOURS;
                t = t / 60L;
            }
            if (t % 24L == 0L) {
                timeUnit = TimeUnit.DAYS;
                t = t / 24L;
            }
        }
        return new StringBuilder()
                .append(t)
                .append(TIME_UNITS.get(timeUnit))
                .toString();
    }

    public void setWorkerKeepAlive(String keepAlive) {
        String time = keepAlive;
        TimeUnit timeUnit = null;
        for (int i = 0; i < TimeUnit.values().length; i++) {
            TimeUnit tu = TimeUnit.values()[i];
            String suffix = (String) TIME_UNITS.get(tu);
            if (time.endsWith(suffix)) {
                timeUnit = tu;
                time = time.substring(0,
                        time.length() - suffix.length());
                break;
            }
        }
        if (timeUnit != null) {
            try {
                long keepAliveTime = Long.parseLong(time);
                workerThreadPool.setKeepAliveTime(
                        keepAliveTime, timeUnit);
            } catch (NumberFormatException e) {
                Context.LOGGER.warning(
                        "Invalid keep-alive format: " + keepAlive);
            }
        }
    }

    // ── HTTPService hooks ──

    /**
     * Returns the servlet handler factory for wiring into listeners.
     */
    @Override
    protected HTTPRequestHandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    /**
     * Returns the authentication provider set by the servlet container's
     * security configuration, or null if no authentication is configured.
     */
    @Override
    protected HTTPAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    /**
     * Sets the authentication provider. Called by {@link Context} during
     * initialisation when a security constraint with an auth-method is
     * configured in web.xml.
     *
     * @param provider the authentication provider
     */
    void setAuthenticationProvider(HTTPAuthenticationProvider provider) {
        this.authenticationProvider = provider;
    }

    /**
     * Initialises the servlet container and starts the async timeout
     * scheduler.
     */
    @Override
    protected void initService() {
        container.initContexts();
        asyncTimeoutScheduler.start();
    }

    /**
     * Shuts down the worker thread pool, async timeout scheduler, and
     * servlet container.
     */
    @Override
    protected void destroyService() {
        workerThreadPool.shutdown();
        asyncTimeoutScheduler.shutdown();
        container.destroy();
    }

    // ── Request processing ──

    public void log(String message) {
        if (accessLogger != null) {
            accessLogger.logrb(Level.FINEST, null, null,
                    (String) null, message, (Throwable) null);
        }
    }

    /**
     * A handler is ready to service a request.
     */
    void serviceRequest(ServletHandler servletHandler) {
        RequestHandler handler =
                new RequestHandler(servletHandler, this);
        workerThreadPool.submit(handler);
    }

    /**
     * ThreadFactory with worker naming strategy.
     */
    class WorkerThreadFactory implements ThreadFactory {

        private final ThreadFactory defaultFactory =
                Executors.defaultThreadFactory();
        private long threadNum = 0L;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setName("servlet-worker-" + (threadNum++));
            t.setDaemon(true);
            return t;
        }

    }

}
