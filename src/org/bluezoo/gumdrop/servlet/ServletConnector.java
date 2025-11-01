/*
 * ServletConnector.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.http.AbstractHTTPConnector;
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
public class ServletConnector extends AbstractHTTPConnector {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    private Container container;
    private Logger accessLogger;
    private ExecutorService responseSender;
    private ThreadPoolExecutor workerThreadPool;

    public ServletConnector() {
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
                // fall through
            }
        }
        setKeepAlive(keepAlive); // This will throw an exception
    }

    public void start() {
        container.init();
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
