/*
 * MQTTService.java
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

package org.bluezoo.gumdrop.mqtt;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mqtt.broker.SubscriptionManager;
import org.bluezoo.gumdrop.mqtt.broker.WillManager;
import org.bluezoo.gumdrop.mqtt.handler.ConnectHandler;
import org.bluezoo.gumdrop.mqtt.handler.PublishHandler;
import org.bluezoo.gumdrop.mqtt.handler.SubscribeHandler;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore;

/**
 * Abstract base for MQTT application services.
 *
 * <p>An {@code MQTTService} owns the broker components (subscription
 * manager, will manager) and one or more {@link MQTTListener} instances.
 * Subclasses may override the handler creation methods to provide
 * custom connection, publish, and subscribe handling.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service id="mqtt" class="org.bluezoo.gumdrop.mqtt.DefaultMQTTService">
 *   <property name="realm" ref="#mqttRealm"/>
 *   <listener class="org.bluezoo.gumdrop.mqtt.MQTTListener"
 *           name="mqtt" port="1883"/>
 *   <listener class="org.bluezoo.gumdrop.mqtt.MQTTListener"
 *           name="mqtts" port="8883" secure="true">
 *     <property name="keystore-file" path="server.p12"/>
 *     <property name="keystore-pass" value="changeit"/>
 *   </listener>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class MQTTService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(MQTTService.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mqtt.L10N");

    private final List<MQTTListener> listeners = new ArrayList<>();
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();
    private final WillManager willManager = new WillManager();

    private MQTTMessageStore messageStore;
    private Realm realm;
    private int maxPacketSize = 268_435_455;

    // ── Listener management ──

    public void addListener(MQTTListener listener) {
        listeners.add(listener);
    }

    @SuppressWarnings("rawtypes")
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof MQTTListener) {
                addListener((MQTTListener) item);
            }
        }
    }

    @Override
    public List<MQTTListener> getListeners() {
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

    public MQTTMessageStore getMessageStore() {
        return messageStore;
    }

    // ── Message store ──

    /**
     * Creates the message store used for PUBLISH payload storage.
     *
     * <p>The default implementation returns an {@link InMemoryMessageStore}
     * which buffers payloads in memory. Subclasses may override to provide
     * file-backed or database-backed storage for large messages.
     *
     * @return the message store
     */
    protected MQTTMessageStore createMessageStore() {
        return new InMemoryMessageStore();
    }

    // ── Handler creation ──

    /**
     * Creates a connect handler for an incoming MQTT connection.
     *
     * <p>Subclasses may override to provide custom connection-level
     * behaviour (authorization, client ID policies, etc.).
     *
     * @param listener the listener that accepted the connection
     * @return a connect handler, or null for default behaviour
     */
    protected ConnectHandler createConnectHandler(TCPListener listener) {
        return null;
    }

    /**
     * Creates a publish handler for authorizing incoming messages.
     *
     * <p>Subclasses may override to provide topic-level access
     * control, message filtering, etc.
     *
     * @param listener the listener that accepted the connection
     * @return a publish handler, or null to allow all publishes
     */
    protected PublishHandler createPublishHandler(TCPListener listener) {
        return null;
    }

    /**
     * Creates a subscribe handler for authorizing subscriptions.
     *
     * <p>Subclasses may override to provide topic-level access
     * control, QoS downgrading, etc.
     *
     * @param listener the listener that accepted the connection
     * @return a subscribe handler, or null to allow all subscriptions
     */
    protected SubscribeHandler createSubscribeHandler(TCPListener listener) {
        return null;
    }

    /**
     * Creates the full protocol handler for a new MQTT connection.
     */
    MQTTProtocolHandler createProtocolHandler(MQTTListener listener) {
        MQTTProtocolHandler handler = new MQTTProtocolHandler(
                listener, subscriptionManager, willManager, messageStore);
        ConnectHandler ch = createConnectHandler(listener);
        if (ch != null) {
            handler.setConnectHandler(ch);
        }
        PublishHandler ph = createPublishHandler(listener);
        if (ph != null) {
            handler.setPublishHandler(ph);
        }
        SubscribeHandler sh = createSubscribeHandler(listener);
        if (sh != null) {
            handler.setSubscribeHandler(sh);
        }
        return handler;
    }

    // ── Lifecycle ──

    protected void initService() {
    }

    protected void destroyService() {
    }

    @Override
    public void start() {
        messageStore = createMessageStore();
        initService();

        for (MQTTListener ep : listeners) {
            wireListener(ep);
            ep.setService(this);
            try {
                ep.start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, MessageFormat.format(
                        L10N.getString("log.listener_start_failed"), ep), e);
            }
        }
    }

    @Override
    public void stop() {
        for (MQTTListener ep : listeners) {
            try {
                ep.stop();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("log.listener_stop_error"), ep), e);
            }
        }
        destroyService();
    }

    private void wireListener(MQTTListener ep) {
        if (realm != null && ep.getRealm() == null) {
            ep.setRealm(realm);
        }
        if (maxPacketSize > 0) {
            ep.setMaxPacketSize(maxPacketSize);
        }
    }
}
