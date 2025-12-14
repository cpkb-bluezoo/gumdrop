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

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.bluezoo.gumdrop.servlet.jndi.ServletInitialContext;
import org.bluezoo.gumdrop.servlet.jndi.ServletInitialContextFactory;
import org.bluezoo.gumdrop.servlet.manager.ManagerContainerService;
import org.bluezoo.gumdrop.servlet.manager.ManagerContextService;
import org.bluezoo.gumdrop.servlet.session.Cluster;
import org.bluezoo.gumdrop.servlet.session.ClusterContainer;
import org.bluezoo.gumdrop.servlet.session.SessionContext;
import org.bluezoo.gumdrop.servlet.session.SessionManager;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;

/**
 * Container for a number of web application contexts.
 * The web container represents a namespace in which contexts can be
 * "mounted".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Container implements ManagerContainerService, ClusterContainer {

    final List<Context> contexts = new ArrayList<>();
    final Map<String,Realm> realms = new LinkedHashMap<>();
    final List<Resource> resources = new ArrayList<>();
    boolean started = false;

    boolean hotDeploy = true;
    Thread hotDeploymentThread;

    // Distributed session management
    byte[] clusterKey;
    int clusterPort = 8080;
    String clusterGroupAddress = "224.0.80.80";
    Cluster cluster;

    @Override public Collection<ManagerContextService> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    @Override public ManagerContextService getContext(String contextPath) {
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
    }
    
    public void setContexts(List<Context> contextList) {
        contexts.clear();
        contexts.addAll(contextList);
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

    public void setClusterGroupAddress(String address) {
        clusterGroupAddress = address;
    }

    public void setClusterKey(String key) {
        byte[] bytes = new java.math.BigInteger(key, 16).toByteArray();
        if (bytes.length < 32) {
            byte[] tmp = new byte[32];
            System.arraycopy(bytes, 0, tmp, tmp.length - bytes.length, bytes.length);
            bytes = tmp; 
        }
        clusterKey = bytes;
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
                Context.LOGGER.fine("URL stream handler factory already set");
            }
        }
    }

    /**
     * Initialize all contexts.
     * This is called by ServletServer.start() after the server is configured.
     */
    synchronized void initContexts() {
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
                context.init();
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
                        cluster.open();

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
    synchronized void destroy() {
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

    Context getContextByPath(String path) {
        // locate context with longest matching context path
        int longest = -1;
        Context match = null;
        for (Context context : contexts) {
            if (path.startsWith(context.contextPath)) {
                int len = context.contextPath.length();
                if (len > longest) {
                    longest = len;
                    match = context;
                }
            }
        }
        return match;
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
    public String getClusterGroupAddress() {
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
