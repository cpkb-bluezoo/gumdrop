/*
 * ServletServer.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.HTTPServer;
import org.bluezoo.gumdrop.util.MessageFormatter;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Iterator;
import java.util.ResourceBundle;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * The servlet connector is responsible for creating new servlet
 * connection handlers to manage incoming TCP connections.
 * It provides a pool of worker threads which pop request/response pairs off
 * the request queue and process them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServletServer extends HTTPServer {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    static final Map<TimeUnit, String> TIME_UNITS = new HashMap<>();
    static {
        TIME_UNITS.put(TimeUnit.NANOSECONDS, "ns");
        TIME_UNITS.put(TimeUnit.MICROSECONDS, "us");
        TIME_UNITS.put(TimeUnit.MILLISECONDS, "ms");
        TIME_UNITS.put(TimeUnit.SECONDS, "s");
        TIME_UNITS.put(TimeUnit.MINUTES, "m");
        TIME_UNITS.put(TimeUnit.HOURS, "h");
        TIME_UNITS.put(TimeUnit.DAYS, "d");
    }

    private Container container;
    private Logger accessLogger;
    private ExecutorService responseSender;
    private ThreadPoolExecutor workerThreadPool;

    public ServletServer() {
        workerThreadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new WorkerThreadFactory());
    }

    public String getDescription() {
        return "servlet";
    }

    public Container getContainer() {
        return container;
    }

    public void setContainer(Container container) {
        this.container = container;
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
        return new StringBuilder().append(t).append(TIME_UNITS.get(timeUnit)).toString();
    }

    public void setWorkerKeepAlive(String keepAlive) {
        String time = keepAlive;
        TimeUnit timeUnit = null;
        for (TimeUnit tu : TimeUnit.values()) {
            String suffix = TIME_UNITS.get(tu);
            if (time.endsWith(suffix)) {
                timeUnit = tu;
                time = time.substring(0, time.length() - suffix.length());
                break;
            }
        }
        if (timeUnit != null) {
            try {
                long keepAliveTime = Long.parseLong(time);
                workerThreadPool.setKeepAliveTime(keepAliveTime, timeUnit);
            } catch (NumberFormatException e) {
                Context.LOGGER.warning("Invalid keep-alive format: " + keepAlive);
            }
        }
    }

    public void start() {
        container.initContexts();
        responseSender = Executors.newSingleThreadExecutor();
        super.start();
    }

    public void stop() {
        workerThreadPool.shutdown();
        responseSender.shutdown();
        container.destroy();
        super.stop();
    }

    public Connection newConnection(SocketChannel sc, SSLEngine engine) {
        return new ServletConnection(sc, engine, secure, container, this);
    }

    public void log(String message) {
        if (accessLogger != null) {
            accessLogger.logrb(Level.FINEST, null, null, (String) null, message, (Throwable) null);
        }
    }

    /**
     * A stream has arrived on a connection.
     */
    void serviceRequest(ServletStream stream) {
        RequestHandler handler = new RequestHandler(stream);
        workerThreadPool.submit(handler);
    }

    /**
     * A response has been flushed for a connection.
     */
    void responseFlushed(ServletConnection connection) {
        responseSender.submit(new ResponseSender(connection));
    }

    /**
     * Process the response queue for a connection.
     * Ensure that pending responses are sent in order.
     */
    static class ResponseSender implements Runnable {

        private ServletConnection connection;

        ResponseSender(ServletConnection connection) {
            this.connection = connection;
        }

        public void run() {
            try {
                while (true) {
                    // Send as many responses as are completed.
                    ServletStream stream = connection.responseQueue.peek();
                    if (stream.isResponseComplete()) {
                        // Actually remove from the queue only when complete
                        connection.responseQueue.take();
                        stream.sendResponse();
                    } else {
                        // Wait for head of queue to become complete
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // terminate
            }
        }

    }

    /**
     * ThreadFactory with worker naming strategy.
     */
    class WorkerThreadFactory implements ThreadFactory {

        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private long threadNum = 0L;

        @Override public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setName("servlet-" + getPort() + "-" + (threadNum++));
            t.setDaemon(true);
            return t;
        }

    }

}
