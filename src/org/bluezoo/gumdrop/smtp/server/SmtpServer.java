/*
 * SmtpServer.java
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

package org.bluezoo.gumdrop.smtp.server;

import org.bluezoo.gumdrop.smtp.SmtpListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.smtp.handler.ClientConnected;

/**
 * Abstract base for SMTP protocol servers.
 *
 * <p>An {@code SmtpServer} defines the application logic for handling
 * SMTP connections. It implements {@link SmtpServerSessionProvider}:
 * {@link #openSession(TcpListener)} returns the staged handler pipeline for
 * each new control connection.
 *
 * <p>Stateless protocols (HTTP, DNS) do not use {@link
 * org.bluezoo.gumdrop.ServerSessionProvider}; they compose with request or
 * query handlers instead — see {@code docs/COMPOSITION.md}.
 *
 * <p>New applications should use {@link #compose()} rather than subclassing
 * {@code SmtpServer} or configuring XML services.
 *
 * <h2>Composition Example</h2>
 * <pre>{@code
 * SmtpServer server = SmtpServer.compose()
 *         .listener(new SmtpListener().port(2525).bindWildcard())
 *         .sessionPerConnection(() -> new MyMailHandler())
 *         .server();
 * gumdrop.addServer(server);
 * }</pre>
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
 *   <listener class="org.bluezoo.gumdrop.smtp.SmtpListener"
 *           name="mx" port="25"/>
 *   <listener class="org.bluezoo.gumdrop.smtp.SmtpListener"
 *           name="submission" port="587" auth-required="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321 - SMTP</a>
 * @see Server
 * @see SmtpListener
 */
public abstract class SmtpServer implements Server, SmtpServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(SmtpServer.class.getName());

    private final List<Listener> listeners = new ArrayList<Listener>();

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
    public void addListener(SmtpListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link SmtpListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof SmtpListener) {
                addListener((SmtpListener) item);
            }
        }
    }

    @Override
    public List<Listener> getListeners() {
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
     * Opens the staged handler pipeline for an incoming SMTP connection on
     * the given listener.
     *
     * <p>Subclasses implement this to provide connection-level SMTP behaviour
     * (message acceptance, relay decisions, etc.). The {@code listener}
     * parameter identifies which control listener accepted the connection,
     * allowing different policies per listener (e.g., MX on port 25 vs.
     * submission on port 587).
     *
     * @param listener the listener that accepted the connection
     * @return the session pipeline entry handler, or {@code null} for default
     */
    @Override
    public abstract ClientConnected openSession(TcpListener listener);

    /**
     * @deprecated use {@link #openSession(TcpListener)}.
     */
    @Deprecated
    public final ClientConnected createHandler(TcpListener endpoint) {
        return openSession(endpoint);
    }

    // ── Lifecycle ──

    /**
     * Initialises service resources before listeners are started.
     *
     * <p>The default implementation does nothing. Subclasses may
     * override to initialise application-level resources.
     */
    protected void initService() {
        SmtpServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.start();
        }
    }

    /**
     * Tears down service resources after listeners are stopped.
     *
     * <p>The default implementation does nothing.
     */
    protected void destroyService() {
        SmtpServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    /**
     * Returns the session provider for composed servers, or {@code null}.
     *
     * <p>When non-null, {@link #start()} wires this provider onto each
     * {@link SmtpListener} and calls {@link SmtpServerSessionProvider#start()}.
     */
    protected SmtpServerSessionProvider getSessionProvider() {
        return null;
    }

    @Override
    public void start(Gumdrop gumdrop) {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof SmtpListener) {
                SmtpListener ep = (SmtpListener) listener;
                wireEndpoint(ep);
                SmtpServerSessionProvider provider = getSessionProvider();
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
        destroyService();
    }

    // ── Internal wiring ──

    /**
     * Pushes service-level configuration into a listener so that the
     * existing endpoint handler code can read it from the endpoint.
     */
    private void wireEndpoint(SmtpListener ep) {
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
     * Starts fluent composition of a concrete {@link SmtpServer}.
     *
     * @return a new composer
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
     * Fluent composition of listeners and a {@link SmtpServerSessionProvider}.
     */
    public static final class Composer {

        private final List<SmtpListener> listeners = new ArrayList<SmtpListener>();
        private SmtpServerSessionProvider sessionProvider;
        private Realm realm;
        private MailboxFactory mailboxFactory;
        private long maxMessageSize = 35882577;
        private int maxRecipients = 100;
        private int maxTransactionsPerSession = 0;
        private boolean authRequired = false;

        private Composer() {
        }

        public Composer listener(SmtpListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(SmtpServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        /**
         * Creates a fresh staged handler pipeline for each accepted connection.
         */
        public Composer sessionPerConnection(Supplier<ClientConnected> supplier) {
            return sessionProvider(SmtpServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        public Composer mailboxFactory(MailboxFactory mailboxFactory) {
            this.mailboxFactory = mailboxFactory;
            return this;
        }

        public Composer maxMessageSize(long maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        public Composer maxRecipients(int maxRecipients) {
            this.maxRecipients = maxRecipients;
            return this;
        }

        public Composer maxTransactionsPerSession(int maxTransactionsPerSession) {
            this.maxTransactionsPerSession = maxTransactionsPerSession;
            return this;
        }

        public Composer authRequired(boolean authRequired) {
            this.authRequired = authRequired;
            return this;
        }

        /**
         * Creates the composed server. At least one listener and a session
         * provider are required.
         */
        public SmtpServer server() {
            SmtpServerSessionProvider provider = sessionProvider;
            if (provider == null && listeners.size() == 1) {
                provider = listeners.get(0).getSessionProvider();
            }
            if (provider == null) {
                throw new IllegalStateException(
                        "sessionProvider is required on the composer or on"
                                + " the sole listener");
            }
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            ComposedSmtpServer server = new ComposedSmtpServer(provider);
            if (realm != null) {
                server.setRealm(realm);
            }
            if (mailboxFactory != null) {
                server.setMailboxFactory(mailboxFactory);
            }
            server.setMaxMessageSize(maxMessageSize);
            server.setMaxRecipients(maxRecipients);
            server.setMaxTransactionsPerSession(maxTransactionsPerSession);
            server.setAuthRequired(authRequired);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }

        /**
         * @deprecated use {@link #server()}.
         */
        @Deprecated
        public SmtpServer build() {
            return server();
        }
    }

}
