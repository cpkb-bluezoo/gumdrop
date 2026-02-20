/*
 * SMTPService.java
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

package org.bluezoo.gumdrop.smtp;

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
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * Abstract base for SMTP application services.
 *
 * <p>An {@code SMTPService} defines the application logic for handling
 * SMTP connections. Subclasses override
 * {@link #createHandler(TCPListener)} to return the appropriate
 * {@link ClientConnected} handler for each new connection, receiving
 * the originating listener so that different policies can be applied
 * per listener (e.g., MX on port 25 vs. submission on port 587).
 *
 * <p>Service-level configuration (realm, mailbox factory, message
 * limits, authentication requirements) is pushed into each listener
 * during {@link #start()} so that the existing endpoint handlers
 * continue to read configuration from the endpoint.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="com.example.MySmtpService">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mboxStorage"/>
 *   <property name="max-message-size">35882577</property>
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener"
 *           name="mx" port="25"/>
 *   <listener class="org.bluezoo.gumdrop.smtp.SMTPListener"
 *           name="submission" port="587" auth-required="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see SMTPListener
 */
public abstract class SMTPService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(SMTPService.class.getName());

    private final List listeners = new ArrayList();

    // ── Service-level configuration ──

    private Realm realm;
    private MailboxFactory mailboxFactory;
    private long maxMessageSize = 35882577;
    private int maxRecipients = 100;
    private int maxTransactionsPerSession = 0;
    private boolean authRequired = false;

    // ── Listener management ──

    /**
     * Adds an SMTP listener to this service.
     *
     * @param endpoint the SMTP endpoint
     */
    public void addListener(SMTPListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link SMTPListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof SMTPListener) {
                addListener((SMTPListener) item);
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

    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public int getMaxRecipients() {
        return maxRecipients;
    }

    public void setMaxRecipients(int maxRecipients) {
        this.maxRecipients = maxRecipients;
    }

    public int getMaxTransactionsPerSession() {
        return maxTransactionsPerSession;
    }

    public void setMaxTransactionsPerSession(int max) {
        this.maxTransactionsPerSession = max;
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }

    // ── Handler creation ──

    /**
     * Creates a new handler for an incoming SMTP connection on the
     * given endpoint.
     *
     * <p>Subclasses must implement this to provide connection-level
     * SMTP behaviour (message acceptance, relay decisions, etc.).
     * The {@code endpoint} parameter identifies which listener
     * accepted the connection, allowing the service to apply
     * different policies per listener.
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
     * <p>The default implementation does nothing. Subclasses may
     * override to initialise application-level resources.
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
            if (listener instanceof SMTPListener) {
                SMTPListener ep = (SMTPListener) listener;
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
     * Pushes service-level configuration into a listener so that the
     * existing endpoint handler code can read it from the endpoint.
     */
    private void wireEndpoint(SMTPListener ep) {
        if (realm != null) {
            ep.setRealm(realm);
        }
        if (mailboxFactory != null) {
            ep.setMailboxFactory(mailboxFactory);
        }
        ep.setMaxMessageSize(maxMessageSize);
        ep.setMaxRecipients(maxRecipients);
        ep.setMaxTransactionsPerSession(maxTransactionsPerSession);
        ep.setAuthRequired(authRequired);
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
