/*
 * FtpServer.java
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

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.auth.Realm;

/**
 * FTP protocol server — listeners, configuration, and session composition.
 *
 * <p>Do not subclass for application logic. Use {@link #compose()} with an
 * {@link FtpServerSessionProvider} (typically {@link FileSystemFtpSessionProvider})
 * or configure a legacy {@link org.bluezoo.gumdrop.ftp.FtpConnectionHandler}
 * via {@link FtpServerSessionProviders#connectionHandler(java.util.function.Supplier)}.
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
 * <service class="org.bluezoo.gumdrop.ftp.server.FtpServer">
 *   <property name="require-tls-for-data">true</property>
 *   <listener class="org.bluezoo.gumdrop.ftp.FtpListener"
 *           port="21"/>
 *   <listener class="org.bluezoo.gumdrop.ftp.FtpListener"
 *           port="990" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see FtpListener
 * @see FtpConnectionHandler
 */
public class FtpServer implements Server, FtpServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(FtpServer.class.getName());

    private final List<FtpListener> listeners = new ArrayList<FtpListener>();
    private final List<Listener> dynamicListeners = new ArrayList<Listener>();
    private FtpServerSessionProvider sessionProvider;

    // ── Service-level configuration ──

    private Realm realm;
    private boolean requireTLSForData = false;

    // ── Listener management ──

    /**
     * Adds a static FTP control listener to this service.
     *
     * @param endpoint the FTP control endpoint
     */
    public void addListener(FtpListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link FtpListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof FtpListener) {
                addListener((FtpListener) item);
            }
        }
    }

    @Override
    public List<Listener> getListeners() {
        List<Listener> all = new ArrayList<Listener>(listeners);
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

    /**
     * Returns the authentication realm for this service, or null
     * if authentication is handled by the connection handler directly
     * (or not required, as in anonymous FTP).
     *
     * @return the realm
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for this service.
     *
     * @param realm the realm
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    // ── Session pipeline ──

    /**
     * Opens the staged handler pipeline for an incoming control connection.
     */
    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (sessionProvider != null) {
            return sessionProvider.openSession(listener);
        }
        return null;
    }

    protected FtpServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setComposedSessionProvider(FtpServerSessionProvider provider) {
        this.sessionProvider = provider;
    }

    /**
     * Creates a new handler for an incoming FTP control connection.
     *
     * @deprecated use {@link #openSession(TcpListener)} and
     *             {@link FtpServerSessionProvider} instead.
     */
    @Deprecated
    public FtpConnectionHandler createHandler(TcpListener endpoint) {
        return LegacyConnectionHandlerAdapter.unwrap(openSession(endpoint));
    }

    // ── Lifecycle ──

    /**
     * Initialises service resources before listeners are started.
     *
     * <p>The default implementation does nothing.
     */
    protected void initService() {
        FtpServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.start();
        }
    }

    /**
     * Tears down service resources after listeners are stopped.
     */
    protected void destroyService() {
        FtpServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    @Override
    public void start(Gumdrop gumdrop) {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof FtpListener) {
                FtpListener ep = (FtpListener) listener;
                wireEndpoint(ep);
                FtpServerSessionProvider provider = getSessionProvider();
                if (provider != null) {
                    ep.setSessionProvider(provider);
                }
                ep.setServer(this);
            }
            startListener(gumdrop, listener);
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
    private void wireEndpoint(FtpListener ep) {
        ep.setRequireTLSForData(requireTLSForData);
        if (realm != null) {
            ep.setRealm(realm);
        }
    }

    private void startListener(Gumdrop gumdrop, Object listener) {
        if (listener instanceof Listener) {
            try {
                ((Listener) listener).start(gumdrop);
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
        List<Listener> snapshot;
        synchronized (dynamicListeners) {
            snapshot = new ArrayList<Listener>(dynamicListeners);
            dynamicListeners.clear();
        }
        for (int i = 0; i < snapshot.size(); i++) {
            Object listener = snapshot.get(i);
            stopListener(listener);
        }
    }

    /**
     * Starts fluent composition of a concrete {@link FtpServer}.
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * @deprecated use {@link #compose()}.
     */
    @Deprecated
    public static Composer builder() {
        return compose();
    }

    /**
     * Fluent composition of listeners and an {@link FtpServerSessionProvider}.
     */
    public static final class Composer {

        private final List<FtpListener> listeners = new ArrayList<FtpListener>();
        private FtpServerSessionProvider sessionProvider;
        private Realm realm;
        private boolean requireTLSForData;

        private Composer() {
        }

        public Composer listener(FtpListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(FtpServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        public Composer sessionPerConnection(Supplier<ClientConnected> supplier) {
            return sessionProvider(FtpServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Composer requireTLSForData(boolean requireTLSForData) {
            this.requireTLSForData = requireTLSForData;
            return this;
        }

        public FtpServer server() {
            FtpServerSessionProvider provider = sessionProvider;
            if (provider == null && listeners.size() == 1) {
                provider = listeners.get(0).getSessionProvider();
            }
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            FtpServer server = new FtpServer();
            server.setComposedSessionProvider(provider);
            if (realm != null) {
                server.setRealm(realm);
            }
            server.setRequireTLSForData(requireTLSForData);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }

        @Deprecated
        public FtpServer build() {
            return server();
        }
    }

}
