/*
 * FTPService.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.Service;

/**
 * Abstract base for FTP application services.
 *
 * <p>An {@code FTPService} defines the application logic for handling
 * FTP connections. It acts as its own handler factory: subclasses
 * override {@link #createHandler(TCPListener)} to return the
 * appropriate {@link FTPConnectionHandler} for each new connection.
 *
 * <p>In addition to static (configured) control listeners, the FTP
 * service manages <em>dynamic</em> data-connection listeners that are
 * created at runtime when a client issues a PASV or EPSV command.
 * The service keeps track of these dynamic listeners so that it can
 * shut them down cleanly and, in future, allow the control connection
 * to abort in-progress transfers.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="com.example.MyFtpService">
 *   <property name="require-tls-for-data">true</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener"
 *           port="21"/>
 *   <listener class="org.bluezoo.gumdrop.ftp.FTPListener"
 *           port="990" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see FTPListener
 * @see FTPConnectionHandler
 */
public abstract class FTPService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(FTPService.class.getName());

    private final List listeners = new ArrayList();
    private final List dynamicListeners = new ArrayList();

    // ── Service-level configuration ──

    private boolean requireTLSForData = false;

    // ── Listener management ──

    /**
     * Adds a static FTP control listener to this service.
     *
     * @param endpoint the FTP control endpoint
     */
    public void addListener(FTPListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link FTPListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof FTPListener) {
                addListener((FTPListener) item);
            }
        }
    }

    @Override
    public List getListeners() {
        List all = new ArrayList(listeners);
        synchronized (dynamicListeners) {
            all.addAll(dynamicListeners);
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Registers a dynamically created data-connection listener.
     *
     * <p>Called by the FTP protocol handler when a client issues a
     * PASV or EPSV command and a new passive-mode listener is created.
     * Tracking dynamic listeners allows the service to shut them down
     * on stop and to support ABOR-style transfer cancellation.
     *
     * @param endpoint the data-connection endpoint
     */
    public void addDynamicListener(Listener endpoint) {
        synchronized (dynamicListeners) {
            dynamicListeners.add(endpoint);
        }
    }

    /**
     * Unregisters a dynamic data-connection listener.
     *
     * <p>Called when a data transfer completes or the data listener
     * is no longer needed.
     *
     * @param endpoint the data-connection endpoint to remove
     */
    public void removeDynamicListener(Listener endpoint) {
        synchronized (dynamicListeners) {
            dynamicListeners.remove(endpoint);
        }
    }

    // ── Configuration accessors ──

    /**
     * Returns whether TLS is required for data connections.
     *
     * @return true if TLS is required for data connections
     */
    public boolean isRequireTLSForData() {
        return requireTLSForData;
    }

    /**
     * Sets whether TLS is required for data connections.
     *
     * @param require true to require TLS for data connections
     */
    public void setRequireTLSForData(boolean require) {
        this.requireTLSForData = require;
    }

    // ── Handler creation ──

    /**
     * Creates a new handler for an incoming FTP control connection on
     * the given endpoint.
     *
     * <p>Subclasses must implement this to provide connection-level
     * FTP behaviour (authentication, file system access, etc.).
     * The {@code endpoint} parameter identifies which control listener
     * accepted the connection.
     *
     * @param endpoint the endpoint that accepted the connection
     * @return a handler for the new connection, or null for default
     */
    protected abstract FTPConnectionHandler createHandler(
            TCPListener endpoint);

    // ── Lifecycle ──

    /**
     * Initialises service resources before listeners are started.
     *
     * <p>The default implementation does nothing.
     */
    protected void initService() {
        // Default: no-op
    }

    /**
     * Tears down service resources after listeners are stopped.
     *
     * <p>The default implementation does nothing.
     */
    protected void destroyService() {
        // Default: no-op
    }

    @Override
    public void start() {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof FTPListener) {
                FTPListener ep = (FTPListener) listener;
                wireEndpoint(ep);
                ep.setService(this);
            }
            startListener(listener);
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < listeners.size(); i++) {
            stopListener(listeners.get(i));
        }
        stopDynamicListeners();
        destroyService();
    }

    // ── Internal wiring ──

    /**
     * Pushes service-level configuration into a control listener.
     */
    private void wireEndpoint(FTPListener ep) {
        ep.setRequireTLSForData(requireTLSForData);
    }

    private void startListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Failed to start listener: " + listener, e);
            }
        }
    }

    private void stopListener(Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error stopping listener: " + listener, e);
            }
        }
    }

    /**
     * Stops and removes all dynamic data-connection listeners.
     */
    private void stopDynamicListeners() {
        List snapshot;
        synchronized (dynamicListeners) {
            snapshot = new ArrayList(dynamicListeners);
            dynamicListeners.clear();
        }
        for (int i = 0; i < snapshot.size(); i++) {
            Object listener = snapshot.get(i);
            stopListener(listener);
        }
    }

}
