/*
 * MqttServer.java
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

package org.bluezoo.gumdrop.mqtt.server;

import org.bluezoo.gumdrop.mqtt.MqttListener;
import org.bluezoo.gumdrop.mqtt.MqttProtocolHandler;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.broker.WillManager;
import org.bluezoo.gumdrop.mqtt.handler.ConnectHandler;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MqttMessageStore;

/**
 * MQTT protocol server — listeners, configuration, and session composition.
 *
 * <p>Owns the broker components (subscription manager, will manager) shared
 * across all listeners and one or more {@link MqttListener} instances. Do
 * not subclass for application logic — use {@link #compose()} with an
 * {@link MqttServerSessionProvider}, or a bare {@code compose()} with no
 * session provider for an open broker (see security warning below).
 *
 * <h2>Composition Example</h2>
 * <pre>{@code
 * MqttServer server = MqttServer.compose()
 *         .listener(new MqttListener().port(1883).bindWildcard())
 *         .sessionPerConnection(() -> new MyConnectHandler())
 *         .server();
 * gumdrop.addServer(server);
 * }</pre>
 *
 * <p><strong>Security warning:</strong> {@code MqttServer.compose()} with no
 * session provider is an <em>open broker</em> — any client that can reach
 * the listener port may connect, publish, and subscribe without
 * authentication (a {@link Realm} still gates username/password if
 * configured). Supply an {@link MqttServerSessionProvider} before exposing
 * a listener to untrusted networks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MqttListener
 * @see MqttServerSessionProvider
 */
public class MqttServer implements Server, MqttServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(MqttServer.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    private final List<MqttListener> listeners = new ArrayList<>();
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();
    private final WillManager willManager = new WillManager();

    private MqttServerSessionProvider sessionProvider;
    private MqttMessageStore messageStore;
    private Realm realm;
    private int maxPacketSize = 1_048_576;

    // ── Listener management ──

    public void addListener(MqttListener listener) {
        listeners.add(listener);
    }

    // Rawtypes: the configuration parser reflectively invokes this bean
    // setter with a raw List of parsed config objects, filtered below by
    // instanceof - the parameter can't be generically typed since the
    // parser has no compile-time knowledge of the target element type.
    @SuppressWarnings("rawtypes")
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof MqttListener) {
                addListener((MqttListener) item);
            }
        }
    }

    @Override
    public List<MqttListener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Configuration ──

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    // ── Broker components ──

    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }

    public WillManager getWillManager() {
        return willManager;
    }

    public MqttMessageStore getMessageStore() {
        return messageStore;
    }

    // ── Message store ──

    /**
     * Creates the message store used for PUBLISH payload storage.
     *
     * <p>The default implementation returns an {@link InMemoryMessageStore}
     * which buffers payloads in memory.
     *
     * @return the message store
     */
    protected MqttMessageStore createMessageStore() {
        return new InMemoryMessageStore();
    }

    // ── Session pipeline ──

    /**
     * Opens the {@link ConnectHandler} for an incoming MQTT connection.
     *
     * <p>Delegates to the composed {@link MqttServerSessionProvider}.
     * Returns {@code null} when no provider is configured, which accepts
     * every connection with no publish/subscribe restrictions.
     */
    @Override
    public ConnectHandler openSession(TcpListener listener) {
        if (sessionProvider != null) {
            return sessionProvider.openSession(listener);
        }
        return null;
    }

    protected MqttServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setComposedSessionProvider(MqttServerSessionProvider provider) {
        this.sessionProvider = provider;
    }

    /**
     * Creates the full protocol handler for a new MQTT connection.
     */
    public MqttProtocolHandler createProtocolHandler(MqttListener listener) {
        MqttProtocolHandler handler = new MqttProtocolHandler(
                listener, subscriptionManager, willManager, messageStore);
        ConnectHandler ch = openSession(listener);
        if (ch != null) {
            handler.setConnectHandler(ch);
        }
        return handler;
    }

    // ── Lifecycle ──

    protected void initService() {
        MqttServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.start();
        }
    }

    protected void destroyService() {
        MqttServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    @Override
    public void start(Gumdrop gumdrop) {
        messageStore = createMessageStore();
        initService();

        for (MqttListener ep : listeners) {
            wireListener(ep);
            ep.setServer(this);
            try {
                ep.start(gumdrop);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, MessageFormat.format(
                        L10N.getString("log.listener_start_failed"), ep), e);
            }
        }
    }

    @Override
    public void stop() {
        for (MqttListener ep : listeners) {
            try {
                ep.stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("log.listener_stop_error"), ep), e);
            }
        }
        destroyService();
    }

    private void wireListener(MqttListener ep) {
        if (realm != null && ep.getRealm() == null) {
            ep.setRealm(realm);
        }
        if (maxPacketSize > 0) {
            ep.setMaxPacketSize(maxPacketSize);
        }
    }

    /**
     * Starts fluent composition of a concrete {@link MqttServer}.
     *
     * @return a new composer
     */
    public static Composer compose() {
        return new Composer();
    }

    /**
     * Fluent composition of listeners and an {@link MqttServerSessionProvider}.
     */
    public static final class Composer {

        private final List<MqttListener> listeners = new ArrayList<MqttListener>();
        private MqttServerSessionProvider sessionProvider;
        private Realm realm;
        private int maxPacketSize = 1_048_576;

        private Composer() {
        }

        public Composer listener(MqttListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(MqttServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        /**
         * Creates a fresh {@link ConnectHandler} for each accepted connection.
         */
        public Composer sessionPerConnection(Supplier<ConnectHandler> supplier) {
            return sessionProvider(MqttServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Composer maxPacketSize(int maxPacketSize) {
            this.maxPacketSize = maxPacketSize;
            return this;
        }

        /**
         * Creates the composed server. At least one listener is required;
         * a session provider is optional (its absence yields an open
         * broker — see the security warning on {@link MqttServer#compose()}).
         */
        public MqttServer server() {
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            MqttServer server = new MqttServer();
            server.setComposedSessionProvider(sessionProvider);
            if (realm != null) {
                server.setRealm(realm);
            }
            server.setMaxPacketSize(maxPacketSize);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }
    }

}
