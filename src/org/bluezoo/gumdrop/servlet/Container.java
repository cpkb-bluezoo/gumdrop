/*
 * Container.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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

import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.servlet.manager.ContainerService;
import org.bluezoo.gumdrop.servlet.manager.ContextService;

import java.io.IOException;
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
public class Container implements ContainerService {

    final List<Context> contexts = new ArrayList<>();
    final Map<String,Realm> realms = new LinkedHashMap<>();
    final List<Resource> resources = new ArrayList<>();
    boolean started = false;

    boolean hotDeploy = true;
    Thread hotDeploymentThread;

    // Distributed session management
    Cluster cluster;
    int clusterPort = 8080;
    String clusterGroupAddress = "224.0.80.80";

    @Override public Collection<ContextService> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    @Override public ContextService getContext(String contextPath) {
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

    public void addRealm(String name, Realm realm) {
        realms.put(name, realm);
    }

    public void addResource(Resource resource) {
        resources.add(resource);
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

    /**
     * Initialize all contexts
     */
    synchronized void init() {
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
                hotDeploymentThread = new HotDeploymentThread(this);
                hotDeploymentThread.start();
            }
            if (distributable) {
                try {
                    cluster = new Cluster(this);
                    cluster.start();
                } catch (IOException e) {
                    Context.LOGGER.log(Level.SEVERE, e.getMessage(), e);
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
            if (cluster != null) {
                cluster.interrupt();
                cluster = null;
            }
            for (Context context : contexts) {
                context.invalidateSessions(true);
                context.destroy();
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

    Context getContextByDigest(byte[] digest) {
        for (Context context : contexts) {
            if (match(digest, context.digest)) {
                return context;
            }
        }
        return null;
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
