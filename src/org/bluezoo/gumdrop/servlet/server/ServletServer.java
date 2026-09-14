/*
 * ServletServer.java
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

package org.bluezoo.gumdrop.servlet.server;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.jndi.Resource;

import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Servlet protocol server — Jakarta Servlet container entry point.
 *
 * <p>New applications should prefer {@link org.bluezoo.gumdrop.http.HttpServer#builder()}
 * with {@link ServletRequestHandler} rather than this type. {@code ServletServer}
 * remains for XML configuration and legacy wiring.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see docs/COMPOSITION.md
 * @see HttpServer
 * @see Container
 */
public class ServletServer extends HttpServer {

    public static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");

    private Container container;
    private ServletRequestHandler requestHandler;

    public ServletServer() {
        container = new Container();
        requestHandler = new ServletRequestHandler(container);
    }

    public Container getContainer() {
        return container;
    }

    /**
     * Replaces the internal container with an externally-created one.
     */
    public void setContainer(Container container) {
        if (container == null) {
            throw new NullPointerException("container");
        }
        this.container = container;
        this.requestHandler = new ServletRequestHandler(container);
    }

    public int getBufferSize() {
        return container.getBufferSize();
    }

    public void setBufferSize(int bufferSize) {
        container.setBufferSize(bufferSize);
    }

    public void setHotDeploy(boolean flag) {
        container.setHotDeploy(flag);
    }

    public void setRealms(Map<String, Realm> realms) {
        container.setRealms(realms);
    }

    public void setResources(List<Resource> resources) {
        container.setResources(resources);
    }

    public void addContext(Context context) {
        container.addContext(context);
    }

    public void setContexts(List<Context> contexts) {
        container.setContexts(contexts);
    }

    public void setClusterPort(int port) {
        container.setClusterPort(port);
    }

    public void setClusterGroupAddress(String address) {
        container.setClusterGroupAddress(address);
    }

    public void setClusterKey(String key) {
        container.setClusterKey(key);
    }

    public void setReplicationAllowedClasses(String classNames) {
        container.setReplicationAllowedClasses(classNames);
    }

    public void setAccessLog(String path) {
        container.setAccessLog(path);
    }

    public void setWorkerCorePoolSize(int corePoolSize) {
        container.setWorkerCorePoolSize(corePoolSize);
    }

    public void setWorkerMaximumPoolSize(int maximumPoolSize) {
        container.setWorkerMaximumPoolSize(maximumPoolSize);
    }

    public void setWorkerKeepAlive(String keepAlive) {
        container.setWorkerKeepAlive(keepAlive);
    }

    /**
     * @deprecated use {@link Container#executeWorker(Runnable, Runnable)}
     */
    @Deprecated
    public void executeWorker(Runnable task, Runnable onRejected) {
        container.executeWorker(task, onRejected);
    }

    @Override
    protected HttpStreamHandler getStreamHandler() {
        return requestHandler;
    }

    @Override
    protected HttpAuthenticationProvider getAuthenticationProvider() {
        return container.getAuthenticationProvider();
    }

    @Override
    protected void initService() {
        requestHandler.initService();
    }

    @Override
    protected void destroyService() {
        requestHandler.destroyService();
    }

}
