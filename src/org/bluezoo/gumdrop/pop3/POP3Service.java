/*
 * POP3Service.java
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

package org.bluezoo.gumdrop.pop3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.pop3.handler.ClientConnected;

/**
 * Abstract base for POP3 application services.
 *
 * <p>A {@code POP3Service} defines the application logic for handling
 * POP3 connections. It owns authentication, mailbox storage, and
 * protocol configuration, and acts as the handler factory: subclasses
 * override {@link #createHandler(TCPListener)} to return the
 * appropriate {@link ClientConnected} handler for each new connection.
 *
 * <p>Service-level configuration is pushed into each listener during
 * {@link #start()} so that the existing endpoint handler code
 * continues to read configuration from the endpoint.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="com.example.MyPop3Service">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mboxStorage"/>
 *   <listener class="org.bluezoo.gumdrop.pop3.POP3Listener"
 *           port="110"/>
 *   <listener class="org.bluezoo.gumdrop.pop3.POP3Listener"
 *           port="995" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see POP3Listener
 */
public abstract class POP3Service implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(POP3Service.class.getName());

    private final List listeners = new ArrayList();

    // ── Service-level configuration ──

    private Realm realm;
    private MailboxFactory mailboxFactory;
    private long loginDelayMs = 0;
    private long transactionTimeoutMs = 600000;
    private boolean enableAPOP = true;
    private boolean enableUTF8 = true;
    private boolean enablePipelining = false;

    // ── Listener management ──

    /**
     * Adds a POP3 listener to this service.
     *
     * @param endpoint the POP3 endpoint
     */
    public void addListener(POP3Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be a {@link POP3Listener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof POP3Listener) {
                addListener((POP3Listener) item);
            }
        }
    }

    @Override
    public List getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    // ── Configuration accessors ──

    public Realm getRealm() {
        return realm;
    }

    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    public void setMailboxFactory(MailboxFactory factory) {
        this.mailboxFactory = factory;
    }

    public long getLoginDelayMs() {
        return loginDelayMs;
    }

    public void setLoginDelayMs(long loginDelayMs) {
        this.loginDelayMs = loginDelayMs;
    }

    public long getTransactionTimeoutMs() {
        return transactionTimeoutMs;
    }

    public void setTransactionTimeoutMs(long transactionTimeoutMs) {
        this.transactionTimeoutMs = transactionTimeoutMs;
    }

    public boolean isEnableAPOP() {
        return enableAPOP;
    }

    public void setEnableAPOP(boolean enableAPOP) {
        this.enableAPOP = enableAPOP;
    }

    public boolean isEnableUTF8() {
        return enableUTF8;
    }

    public void setEnableUTF8(boolean enableUTF8) {
        this.enableUTF8 = enableUTF8;
    }

    public boolean isEnablePipelining() {
        return enablePipelining;
    }

    public void setEnablePipelining(boolean enablePipelining) {
        this.enablePipelining = enablePipelining;
    }

    // ── Handler creation ──

    /**
     * Creates a new handler for an incoming POP3 connection on the
     * given endpoint.
     *
     * <p>Subclasses must implement this to provide connection-level
     * POP3 behaviour (authentication, mailbox access, etc.).
     * The {@code endpoint} parameter identifies which listener
     * accepted the connection.
     *
     * @param endpoint the endpoint that accepted the connection
     * @return a handler for the new connection, or null for default
     */
    protected abstract ClientConnected createHandler(
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
            if (listener instanceof POP3Listener) {
                POP3Listener ep = (POP3Listener) listener;
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
        destroyService();
    }

    // ── Internal wiring ──

    /**
     * Pushes service-level configuration into a listener.
     */
    private void wireEndpoint(POP3Listener ep) {
        if (realm != null) {
            ep.setRealm(realm);
        }
        if (mailboxFactory != null) {
            ep.setMailboxFactory(mailboxFactory);
        }
        ep.setLoginDelayMs(loginDelayMs);
        ep.setTransactionTimeoutMs(transactionTimeoutMs);
        ep.setEnableAPOP(enableAPOP);
        ep.setEnableUTF8(enableUTF8);
        ep.setEnablePipelining(enablePipelining);
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

}
