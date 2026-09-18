/*
 * Pop3Server.java
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

package org.bluezoo.gumdrop.pop3.server;

import org.bluezoo.gumdrop.pop3.Pop3Listener;

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

/**
 * POP3 protocol server — listeners, configuration, and session composition.
 *
 * <p>Do not subclass for application logic. Use {@link #compose()} with a
 * {@link Pop3ServerSessionProvider} (typically
 * {@link MailboxStorePop3SessionProvider}) or configure listeners and properties
 * directly on this type for legacy XML.
 *
 * <p>Service-level configuration is pushed into each listener during
 * {@link #start()} so that the existing endpoint handler code
 * continues to read configuration from the endpoint.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.pop3.server.Pop3Server">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mboxStorage"/>
 *   <listener class="org.bluezoo.gumdrop.pop3.Pop3Listener"
 *           port="110"/>
 *   <listener class="org.bluezoo.gumdrop.pop3.Pop3Listener"
 *           port="995" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Server
 * @see Pop3Listener
 */
public class Pop3Server implements Server, Pop3ServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(Pop3Server.class.getName());

    private final List<Listener> listeners = new ArrayList<Listener>();
    private Pop3ServerSessionProvider sessionProvider;

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
    public void addListener(Pop3Listener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be a {@link Pop3Listener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Pop3Listener) {
                addListener((Pop3Listener) item);
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
        if (factory != null) {
            Pop3ServerSessionProvider provider = sessionProvider;
            if (provider instanceof MailboxStorePop3SessionProvider) {
                ((MailboxStorePop3SessionProvider) provider).mailboxFactory(factory);
            } else if (provider == null) {
                sessionProvider = Pop3ServerSessionProviders.mailbox(factory);
            }
        }
        this.mailboxFactory = factory;
    }

    /**
     * Returns the greeting message sent to clients when using a mailbox store
     * session provider.
     */
    public String getGreeting() {
        Pop3ServerSessionProvider provider = sessionProvider;
        if (provider instanceof MailboxStorePop3SessionProvider) {
            return ((MailboxStorePop3SessionProvider) provider).getGreeting();
        }
        return "POP3 server ready";
    }

    /**
     * Sets the greeting message sent to clients when using a mailbox store
     * session provider.
     */
    public void setGreeting(String greeting) {
        ensureMailboxStoreProvider().greeting(greeting);
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

    @Override
    public ClientConnected openSession(TcpListener listener) {
        Pop3ServerSessionProvider provider = sessionProvider;
        if (provider == null) {
            return null;
        }
        return provider.openSession(listener);
    }

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
     * <p>The default implementation does nothing.
     */
    protected void initService() {
        Pop3ServerSessionProvider provider = getSessionProvider();
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
        Pop3ServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    protected Pop3ServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setComposedSessionProvider(Pop3ServerSessionProvider provider) {
        this.sessionProvider = provider;
    }

    private MailboxStorePop3SessionProvider ensureMailboxStoreProvider() {
        Pop3ServerSessionProvider provider = sessionProvider;
        if (provider instanceof MailboxStorePop3SessionProvider) {
            return (MailboxStorePop3SessionProvider) provider;
        }
        if (provider != null) {
            throw new IllegalStateException(
                    "greeting applies only with MailboxStorePop3SessionProvider");
        }
        MailboxStorePop3SessionProvider mailboxProvider =
                new MailboxStorePop3SessionProvider();
        if (mailboxFactory != null) {
            mailboxProvider.mailboxFactory(mailboxFactory);
        }
        sessionProvider = mailboxProvider;
        return mailboxProvider;
    }

    @Override
    public void start(Gumdrop gumdrop) {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof Pop3Listener) {
                Pop3Listener ep = (Pop3Listener) listener;
                wireEndpoint(ep);
                Pop3ServerSessionProvider provider = getSessionProvider();
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
     * Pushes service-level configuration into a listener.
     */
    private void wireEndpoint(Pop3Listener ep) {
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

    public static Composer compose() {
        return new Composer();
    }

    @Deprecated
    public static Composer builder() {
        return compose();
    }

    public static final class Composer {

        private final List<Pop3Listener> listeners = new ArrayList<Pop3Listener>();
        private Pop3ServerSessionProvider sessionProvider;
        private Realm realm;
        private long loginDelayMs = 0;
        private long transactionTimeoutMs = 600000;
        private boolean enableAPOP = true;
        private boolean enableUTF8 = true;
        private boolean enablePipelining = false;

        private Composer() {
        }

        public Composer listener(Pop3Listener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(Pop3ServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        public Composer sessionPerConnection(
                Supplier<org.bluezoo.gumdrop.pop3.server.ClientConnected> supplier) {
            return sessionProvider(Pop3ServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * @deprecated configure {@link MailboxFactory} on
         *             {@link Pop3ServerSessionProviders#mailbox(MailboxFactory)}
         *             instead.
         */
        @Deprecated
        public Composer mailboxFactory(MailboxFactory mailboxFactory) {
            if (mailboxFactory == null) {
                throw new NullPointerException("mailboxFactory");
            }
            if (sessionProvider == null) {
                sessionProvider = Pop3ServerSessionProviders.mailbox(mailboxFactory);
            } else if (sessionProvider instanceof MailboxStorePop3SessionProvider) {
                ((MailboxStorePop3SessionProvider) sessionProvider)
                        .mailboxFactory(mailboxFactory);
            } else {
                throw new IllegalStateException(
                        "mailboxFactory belongs on"
                                + " MailboxStorePop3SessionProvider, not on the"
                                + " composer when a custom session provider is"
                                + " set");
            }
            return this;
        }

        public Composer loginDelayMs(long loginDelayMs) {
            this.loginDelayMs = loginDelayMs;
            return this;
        }

        public Composer transactionTimeoutMs(long transactionTimeoutMs) {
            this.transactionTimeoutMs = transactionTimeoutMs;
            return this;
        }

        public Composer enableAPOP(boolean enableAPOP) {
            this.enableAPOP = enableAPOP;
            return this;
        }

        public Composer enableUTF8(boolean enableUTF8) {
            this.enableUTF8 = enableUTF8;
            return this;
        }

        public Composer enablePipelining(boolean enablePipelining) {
            this.enablePipelining = enablePipelining;
            return this;
        }

        public Pop3Server server() {
            Pop3ServerSessionProvider provider = sessionProvider;
            if (provider == null && listeners.size() == 1) {
                provider = listeners.get(0).getSessionProvider();
            }
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            Pop3Server server = new Pop3Server();
            server.setComposedSessionProvider(provider);
            if (realm != null) {
                server.setRealm(realm);
            }
            server.setLoginDelayMs(loginDelayMs);
            server.setTransactionTimeoutMs(transactionTimeoutMs);
            server.setEnableAPOP(enableAPOP);
            server.setEnableUTF8(enableUTF8);
            server.setEnablePipelining(enablePipelining);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }

        @Deprecated
        public Pop3Server build() {
            return server();
        }
    }

}
