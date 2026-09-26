/*
 * Container.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.bluezoo.gumdrop.servlet.jndi.ServletInitialContext;
import org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory;
import org.bluezoo.gumdrop.servlet.manager.ManagerContainerServer;
import org.bluezoo.gumdrop.servlet.manager.ManagerContextServer;
import org.bluezoo.gumdrop.servlet.session.Cluster;
import org.bluezoo.gumdrop.servlet.session.ClusterContainer;
import org.bluezoo.gumdrop.servlet.session.SessionContext;
import org.bluezoo.gumdrop.servlet.session.SessionManager;

import java.io.IOException;
import java.text.MessageFormat;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import jakarta.servlet.ServletException;

import org.bluezoo.gumdrop.util.MessageFormatter;

/**
 * Container for a number of web application contexts.
 * The web container represents a namespace in which contexts can be
 * "mounted".
 *
 * <p>Owns servlet runtime resources (worker pool, async timeout scheduler,
 * access logging, authentication provider wiring) and coordinates
 * {@link #start()} / {@link #destroy()} lifecycle for composed
 * {@link org.bluezoo.gumdrop.servlet.server.ServletRequestHandler} use.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Container implements ManagerContainerServer, ClusterContainer {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private static final int DEFAULT_WORKER_CORE_POOL_SIZE =
            Math.max(10, Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_WORKER_MAXIMUM_POOL_SIZE =
            Math.max(200, DEFAULT_WORKER_CORE_POOL_SIZE * 4);
    private static final int DEFAULT_WORKER_QUEUE_CAPACITY = 1000;
    private static final long DEFAULT_WORKER_KEEP_ALIVE_SECONDS = 60L;

    private static final AtomicLong WORKER_THREAD_NUM = new AtomicLong();

    private static final ThreadFactory WORKER_THREAD_FACTORY = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = Thread.ofVirtual()
                    .name("servlet-worker-", WORKER_THREAD_NUM.incrementAndGet())
                    .unstarted(r);
            t.setDaemon(true);
            return t;
        }
    };

    private final ThreadPoolExecutor workerThreadPool;
    private final AsyncTimeoutScheduler asyncTimeoutScheduler;
    private Logger accessLogger;
    private int bufferSize = DEFAULT_BUFFER_SIZE;

    public Container() {
        workerThreadPool = new ThreadPoolExecutor(
                DEFAULT_WORKER_CORE_POOL_SIZE,
                DEFAULT_WORKER_MAXIMUM_POOL_SIZE,
                DEFAULT_WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(DEFAULT_WORKER_QUEUE_CAPACITY),
                WORKER_THREAD_FACTORY);
        asyncTimeoutScheduler = new AsyncTimeoutScheduler();
    }
    final List<Context> contexts = new ArrayList<>();

    /**
     * Index of {@link #contexts} by context path, for {@link
     * #getContextByPath} (issue #194). Kept in sync with {@code
     * contexts} at the same two mutation points ({@link #addContext},
     * {@link #setContexts}) rather than rebuilt per lookup: contexts are
     * only ever added at deploy time, while {@code getContextByPath} is
     * called on every request, so paying an O(log n) insert at deploy
     * time to get an O(log n)-typical lookup on the hot path (instead of
     * the O(n) linear scan it replaces) is the right trade. A {@link
     * ConcurrentSkipListMap} rather than a plain {@link
     * java.util.TreeMap} since reads happen concurrently from request
     * threads while this could in principle be mutated by a hot-deploy.
     */
    private final NavigableMap<String, Context> contextsByPath = new ConcurrentSkipListMap<>();

    final Map<String,Realm> realms = new LinkedHashMap<>();
    final List<Resource> resources = new ArrayList<>();
    boolean started = false;

    // Hot deploy is off by default: it uses a WatchService (inotify) that is
    // pointless and sometimes unsupported on immutable/overlay container
    // filesystems. It can be turned on explicitly in configuration
    // (hot-deploy="true") or, when not set in config, via the
    // GUMDROP_HOT_DEPLOY environment variable.
    boolean hotDeploy = defaultHotDeploy();
    Thread hotDeploymentThread;

    private static boolean defaultHotDeploy() {
        String env = System.getenv("GUMDROP_HOT_DEPLOY");
        return env != null && Boolean.parseBoolean(env.trim());
    }

    // Distributed session management
    byte[] clusterKey;
    int clusterPort = 8080;
    InetAddress clusterGroupAddress;
    Set<String> replicationAllowedClasses;
    Cluster cluster;

    @Override public Collection<ManagerContextServer> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    @Override public ManagerContextServer getContext(String contextPath) {
        // Don't need lookup as this is a rarely used admin function
        for (Context context : contexts) {
            if (contextPath.equals(context.contextPath)) {
                return context;
            }
        }
        return null;
    }

    public void addContext(Context context) {
        contexts.add(context);
        contextsByPath.put(context.contextPath, context);
    }

    public void setContexts(List<Context> contextList) {
        contexts.clear();
        contexts.addAll(contextList);
        contextsByPath.clear();
        for (Context context : contextList) {
            contextsByPath.put(context.contextPath, context);
        }
    }

    public void addRealm(String name, Realm realm) {
        realms.put(name, realm);
    }
    
    public void setRealms(Map<String, Realm> realmMap) {
        realms.clear();
        realms.putAll(realmMap);
    }

    public void addResource(Resource resource) {
        resources.add(resource);
    }
    
    public void setResources(List<Resource> resourceList) {
        resources.clear();
        resources.addAll(resourceList);
    }

    public void setHotDeploy(boolean flag) {
        hotDeploy = flag;
    }

    public void setClusterPort(int value) {
        clusterPort = value;
    }

    /**
     * Sets the multicast group the cluster uses, in place of the defaults.
     *
     * <p>When this is not called the cluster joins
     * {@link Cluster#DEFAULT_GROUP_IPV4 224.0.80.80} and, where an interface
     * has an IPv6 address, {@link Cluster#DEFAULT_GROUP_IPV6 ff12::8080},
     * sending every message to each group it joined. An explicit group
     * replaces both and is the only one used.
     *
     * @param address a multicast address, or {@code null} for the defaults
     * @throws IllegalArgumentException if the address is not a multicast address
     */
    public void setClusterGroupAddress(InetAddress address) {
        if (address != null && !address.isMulticastAddress()) {
            throw new IllegalArgumentException("cluster group address must be a multicast address: " + address);
        }
        clusterGroupAddress = address;
    }

    /**
     * Sets the AES-256 session replication key.
     *
     * @param key the 32 raw key bytes; copied
     * @throws IllegalArgumentException if it is not exactly 32 bytes
     */
    public void setClusterKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("cluster key must be exactly 32 bytes");
        }
        clusterKey = key.clone();
    }

    /**
     * Sets fully qualified class names permitted in Java-serialized replicated
     * session attributes, in addition to the built-in JDK allowlist.
     *
     * @param classNames fully qualified class names
     */
    public void setReplicationAllowedClasses(Set<String> classNames) {
        this.replicationAllowedClasses = classNames == null
                ? null : new HashSet<String>(classNames);
    }

    // ── Servlet runtime (worker pool, auth, access log) ──

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = Math.max(bufferSize, 1024);
    }

    public AsyncTimeoutScheduler getAsyncTimeoutScheduler() {
        return asyncTimeoutScheduler;
    }

    public ThreadPoolExecutor getWorkerThreadPool() {
        return workerThreadPool;
    }

    public void setAccessLog(Path path) {
        try {
            FileHandler handler = new FileHandler(path.toString(), true);
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

    public Duration getWorkerKeepAlive() {
        return Duration.ofNanos(workerThreadPool.getKeepAliveTime(TimeUnit.NANOSECONDS));
    }

    public void setWorkerKeepAlive(Duration keepAlive) {
        workerThreadPool.setKeepAliveTime(keepAlive.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Starts the container: JNDI/resource bootstrap, context load, cluster,
     * and the async timeout scheduler.
     *
     * @param gumdrop the runtime this container is starting under
     */
    public synchronized void start(Gumdrop gumdrop) {
        init();
        initContexts(gumdrop);
        asyncTimeoutScheduler.start();
    }

    @SuppressWarnings("deprecation")
    public void log(String message) {
        if (accessLogger != null) {
            accessLogger.logrb(Level.FINEST, null, null,
                    (String) null, message, (Throwable) null);
        }
    }

    /**
     * Dispatches servlet processing to the worker pool.
     */
    public void serviceRequest(ServletHandler servletHandler) {
        RequestHandler handler =
                new RequestHandler(servletHandler, this);
        executeWorker(handler, new Runnable() {
            @Override
            public void run() {
                servletHandler.serviceUnavailable();
            }
        });
    }

    /**
     * Dispatches a task to the servlet worker pool.
     */
    public void executeWorker(Runnable task, Runnable onRejected) {
        try {
            workerThreadPool.execute(task);
        } catch (RejectedExecutionException e) {
            if (Context.LOGGER.isLoggable(Level.WARNING)) {
                Context.LOGGER.warning(MessageFormat.format(
                        Context.L10N.getString("warn.worker_pool_saturated"),
                        workerThreadPool.getActiveCount(),
                        workerThreadPool.getQueue().size()));
            }
            if (onRejected != null) {
                onRejected.run();
            }
        }
    }

    /**
     * Called by DI framework after properties are set but before contexts are initialized.
     * This registers the custom URL protocol handler for resource: URLs.
     */
    public void init() {
        // NB this can only be done once per JVM, so only one container can exist
        try {
            java.net.URL.setURLStreamHandlerFactory(new ResourceHandlerFactory(this));
        } catch (Error e) {
            // Already set - this is okay if reloading
            if (Context.LOGGER.isLoggable(Level.FINE)) {
                Context.LOGGER.fine(Context.L10N.getString("debug.url_stream_handler_factory_set"));
            }
        }
        // Unit tests call init() without initContexts(); Context.init() still
        // binds java:comp/env resources and needs the Gumdrop JNDI factory.
        System.getProperties().putIfAbsent("java.naming.factory.initial",
                ServletInitialContextFactory.class.getName());
    }

    /**
     * Initialize all contexts.
     * This is called by {@link #start(Gumdrop)} after the container is
     * configured.
     *
     * @param gumdrop the runtime this container is starting under
     */
    public synchronized void initContexts(Gumdrop gumdrop) {
        if (!started) {
            // Bootstrap JNDI
            String className = ServletInitialContextFactory.class.getName();
            System.getProperties().put("java.naming.factory.initial", className);
            boolean distributable = false;
            // Init resources
            try {
                ServletInitialContext ctx = (ServletInitialContext) new InitialContext().lookup("");
                for (Resource resource : resources) {
                    try {
                        resource.init();
                        String name = resource.getName();
                        String interfaceName = resource.getInterfaceName();
                        Object instance = resource.newInstance();
                        if (instance != null) {
                            ctx.bind("java:comp/env/" + name, interfaceName, instance);
                        }
                    } catch (ServletException e) {
                        String message = Context.L10N.getString("err.init_resource");
                        Context.LOGGER.log(Level.SEVERE, message, e);
                    }
                }
            } catch (NamingException e) {
                String message = Context.L10N.getString("err.init_resource");
                Context.LOGGER.log(Level.SEVERE, message, e);
            }
            for (Context context : contexts) {
                context.setContainer(this);
                try {
                    context.load();
                } catch (Exception e) {
                    String message = MessageFormat.format(
                            Context.L10N.getString("err.load_context"),
                            context.contextPath);
                    Context.LOGGER.log(Level.SEVERE, message, e);
                }
                context.init(gumdrop);
                distributable = distributable || context.distributable;
            }
            if (hotDeploy) {
                try {
                    hotDeploymentThread = new HotDeploymentThread(this);
                    hotDeploymentThread.start();
                } catch (IOException e) {
                    String message = Context.L10N.getString("err.hot_deploy");
                    Context.LOGGER.log(Level.SEVERE, message, e);
                }
            }
            if (distributable) {
                if (clusterKey == null) {
                    String message = Context.L10N.getString("err.no_cluster_key");
                    Context.LOGGER.severe(message);
                } else {
                    // Create single cluster instance for all contexts
                    try {
                        cluster = new Cluster(this);
                        if (replicationAllowedClasses != null) {
                            cluster.setReplicationAllowedClasses(
                                    replicationAllowedClasses);
                        }
                        cluster.open(gumdrop);

                        // Register each distributable context with the cluster
                        for (Context context : contexts) {
                            if (context.distributable) {
                                SessionManager manager = context.getSessionManager();
                                manager.setCluster(cluster);
                                cluster.registerContext(manager.getContextUuid(), manager);
                            }
                        }
                    } catch (IOException e) {
                        Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            }
            started = true;
        }
    }

    /**
     * Destroy all contexts
     */
    public synchronized void destroy() {
        workerThreadPool.shutdown();
        asyncTimeoutScheduler.shutdown();
        if (started) {
            if (hotDeploymentThread != null) {
                hotDeploymentThread.interrupt();
                hotDeploymentThread = null;
            }
            // Unregister contexts from cluster before destroying them
            for (Context context : contexts) {
                if (cluster != null && context.distributable) {
                    SessionManager manager = context.getSessionManager();
                    cluster.unregisterContext(manager.getContextUuid());
                    manager.setCluster(null);
                }
                context.invalidateSessions(true);
                context.destroy();
            }
            // Close cluster after all contexts are destroyed
            if (cluster != null) {
                cluster.close();
                cluster = null;
            }
            for (Resource resource : resources) {
                resource.close();
            }
            started = false;
        }
    }

    /**
     * Locates the context with the longest matching context path
     * (issue #194).
     *
     * <p>Among all context paths in {@link #contextsByPath} that are a
     * prefix of {@code path}, the longest one is also the
     * lexicographically greatest -- one prefix string is always less
     * than any longer string it is a prefix of. So starting from {@code
     * floorEntry(path)} (the greatest key {@code <= path}) and walking
     * to strictly smaller keys until one actually is a prefix finds the
     * longest match first, without scanning every deployed context.
     */
    Context getContextByPath(String path) {
        Map.Entry<String, Context> entry = contextsByPath.floorEntry(path);
        while (entry != null) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
            entry = contextsByPath.lowerEntry(entry.getKey());
        }
        return null;
    }

    /**
     * Unregisters a context from the cluster.
     * Called before a context is destroyed or reloaded.
     *
     * @param context the context to unregister
     */
    void unregisterContextFromCluster(Context context) {
        if (cluster != null && context.distributable) {
            SessionManager manager = context.getSessionManager();
            cluster.unregisterContext(manager.getContextUuid());
        }
    }

    /**
     * Registers a context with the cluster.
     * Called when a context is initialized or after a reload.
     * If the context was previously registered, it will be re-registered
     * with a new context UUID to trigger session repopulation.
     *
     * @param context the context to register
     */
    void registerContextWithCluster(Context context) {
        if (cluster != null && context.distributable) {
            SessionManager manager = context.getSessionManager();
            manager.setCluster(cluster);
            cluster.registerContext(manager.getContextUuid(), manager);
        }
    }

    // -- ClusterContainer interface implementation --

    @Override
    public int getClusterPort() {
        return clusterPort;
    }

    @Override
    public InetAddress getClusterGroupAddress() {
        return clusterGroupAddress;
    }

    @Override
    public byte[] getClusterKey() {
        return clusterKey;
    }

    @Override
    public SessionContext getContextByDigest(byte[] digest) {
        for (Context context : contexts) {
            if (match(digest, context.digest)) {
                return context;
            }
        }
        return null;
    }

    @Override
    public Iterable<SessionContext> getDistributableContexts() {
        List<SessionContext> distributable = new ArrayList<>();
        for (Context context : contexts) {
            if (context.distributable) {
                distributable.add(context);
            }
        }
        return distributable;
    }

    static boolean match(byte[] b1, byte[] b2) {
        if (b1 == null || b2 == null) {
            return false;
        }
        int l1 = b1.length, l2 = b2.length;
        if (l1 != l2) {
            return false;
        }
        for (int i = 0; i < l1; i++) {
            if (b1[i] != b2[i]) {
                return false;
            }
        }
        return true;
    }

}
