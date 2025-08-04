/*
 * ServletConnector.java
 * Copyright (C) 2005, 2013 Chris Burdess
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
    private int numThreads = -1;
    private ExecutorService responseSender;
    private ExecutorService workerThreadPool;

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

    public int getNumThreads() {
        return numThreads;
    }

    public void setNumThreads(int numThreads) {
        if (numThreads < 1) {
            throw new IllegalArgumentException();
        }
        this.numThreads = numThreads;
        if (workerThreadPool != null) {
            workerThreadPool.shutdown();
            workerThreadPool = Executors.newFixedThreadPool(numThreads, new WorkerThreadFactory());
        }
    }

    public void start() {
        container.init();
        responseSender = Executors.newSingleThreadExecutor();
        if (numThreads > 0) {
            workerThreadPool = Executors.newFixedThreadPool(numThreads, new WorkerThreadFactory());
        } else {
            workerThreadPool = Executors.newCachedThreadPool(new WorkerThreadFactory());
        }
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
