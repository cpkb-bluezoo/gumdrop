/*
 * ImapServer.java
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

package org.bluezoo.gumdrop.imap.server;

import org.bluezoo.gumdrop.imap.ImapListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.quota.QuotaManager;

/**
 * IMAP protocol server — listeners, configuration, and session composition.
 *
 * <p>Do not subclass for application logic. Use {@link #compose()} with an
 * {@link ImapServerSessionProvider} (typically
 * {@link MailboxStoreImapSessionProvider}) or configure listeners and properties
 * directly on this type for legacy XML.
 *
 * <p>Service-level configuration is pushed into each listener during
 * {@link #start()} so that the existing endpoint handler code
 * continues to read configuration from the endpoint.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="org.bluezoo.gumdrop.imap.server.ImapServer">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mboxStorage"/>
 *   <property name="quota-manager" ref="#quotas"/>
 *   <listener class="org.bluezoo.gumdrop.imap.ImapListener"
 *           port="143"/>
 *   <listener class="org.bluezoo.gumdrop.imap.ImapListener"
 *           port="993" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Server
 * @see ImapListener
 */
public class ImapServer implements Server, ImapServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(ImapServer.class.getName());

    private final List<Listener> listeners = new ArrayList<Listener>();
    private ImapServerSessionProvider sessionProvider;

    // ── Service-level configuration ──

    private Realm realm;
    private MailboxFactory mailboxFactory;
    private QuotaManager quotaManager;
    private long loginTimeoutMs = 60000;
    private long commandTimeoutMs = 300000;
    private boolean enableIDLE = true;
    private boolean enableNAMESPACE = true;
    private boolean enableQUOTA = true;
    private boolean enableMOVE = true;
    private int maxLineLength = 8192;
    private int maxLiteralSize = 25 * 1024 * 1024;
    private boolean allowPlaintextLogin = false;

    // ── Listener management ──

    /**
     * Adds an IMAP listener to this service.
     *
     * @param endpoint the IMAP endpoint
     */
    public void addListener(ImapListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link ImapListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof ImapListener) {
                addListener((ImapListener) item);
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
            ImapServerSessionProvider provider = sessionProvider;
            if (provider instanceof MailboxStoreImapSessionProvider) {
                ((MailboxStoreImapSessionProvider) provider).mailboxFactory(factory);
            } else if (provider == null) {
                sessionProvider = ImapServerSessionProviders.mailbox(factory);
            }
        }
        this.mailboxFactory = factory;
    }

    public QuotaManager getQuotaManager() {
        return quotaManager;
    }

    public void setQuotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    public long getLoginTimeoutMs() {
        return loginTimeoutMs;
    }

    public void setLoginTimeoutMs(long loginTimeoutMs) {
        this.loginTimeoutMs = loginTimeoutMs;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public boolean isEnableIDLE() {
        return enableIDLE;
    }

    public void setEnableIDLE(boolean enableIDLE) {
        this.enableIDLE = enableIDLE;
    }

    public boolean isEnableNAMESPACE() {
        return enableNAMESPACE;
    }

    public void setEnableNAMESPACE(boolean enableNAMESPACE) {
        this.enableNAMESPACE = enableNAMESPACE;
    }

    public boolean isEnableQUOTA() {
        return enableQUOTA;
    }

    public void setEnableQUOTA(boolean enableQUOTA) {
        this.enableQUOTA = enableQUOTA;
    }

    public boolean isEnableMOVE() {
        return enableMOVE;
    }

    public void setEnableMOVE(boolean enableMOVE) {
        this.enableMOVE = enableMOVE;
    }

    public int getMaxLineLength() {
        return maxLineLength;
    }

    public void setMaxLineLength(int maxLineLength) {
        this.maxLineLength = maxLineLength;
    }

    public int getMaxLiteralSize() {
        return maxLiteralSize;
    }

    public void setMaxLiteralSize(int maxLiteralSize) {
        this.maxLiteralSize = maxLiteralSize;
    }

    public boolean isAllowPlaintextLogin() {
        return allowPlaintextLogin;
    }

    public void setAllowPlaintextLogin(boolean allow) {
        this.allowPlaintextLogin = allow;
    }

    // ── Handler creation ──

    /**
     * Opens the staged handler pipeline for an incoming IMAP connection on
     * the given listener.
     *
     * @param listener the listener that accepted the connection
     * @return the session pipeline entry handler, or {@code null} for default
     */
    @Override
    public ClientConnected openSession(TcpListener listener) {
        ImapServerSessionProvider provider = sessionProvider;
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
        ImapServerSessionProvider provider = getSessionProvider();
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
        ImapServerSessionProvider provider = getSessionProvider();
        if (provider != null) {
            provider.stop();
        }
    }

    /**
     * Returns the configured session provider, or {@code null} for protocol-only
     * behaviour with no mailbox backing.
     */
    protected ImapServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setComposedSessionProvider(ImapServerSessionProvider provider) {
        this.sessionProvider = provider;
    }

    @Override
    public void start() {
        initService();

        for (int i = 0; i < listeners.size(); i++) {
            Object listener = listeners.get(i);
            if (listener instanceof ImapListener) {
                ImapListener ep = (ImapListener) listener;
                wireEndpoint(ep);
                ImapServerSessionProvider provider = getSessionProvider();
                if (provider != null) {
                    ep.setSessionProvider(provider);
                }
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
    private void wireEndpoint(ImapListener ep) {
        if (realm != null) {
            ep.setRealm(realm);
        }
        if (mailboxFactory != null) {
            ep.setMailboxFactory(mailboxFactory);
        }
        if (quotaManager != null) {
            ep.setQuotaManager(quotaManager);
        }
        ep.setLoginTimeoutMs(loginTimeoutMs);
        ep.setCommandTimeoutMs(commandTimeoutMs);
        ep.setEnableIDLE(enableIDLE);
        ep.setEnableNAMESPACE(enableNAMESPACE);
        ep.setEnableQUOTA(enableQUOTA);
        ep.setEnableMOVE(enableMOVE);
        ep.setMaxLineLength(maxLineLength);
        ep.setMaxLiteralSize(maxLiteralSize);
        ep.setAllowPlaintextLogin(allowPlaintextLogin);
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
     * Starts fluent composition of a concrete {@link ImapServer}.
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
     * Fluent composition of listeners and an {@link ImapServerSessionProvider}.
     */
    public static final class Composer {

        private final List<ImapListener> listeners = new ArrayList<ImapListener>();
        private ImapServerSessionProvider sessionProvider;
        private Realm realm;
        private QuotaManager quotaManager;
        private long loginTimeoutMs = 60000;
        private long commandTimeoutMs = 300000;
        private boolean enableIDLE = true;
        private boolean enableNAMESPACE = true;
        private boolean enableQUOTA = true;
        private boolean enableMOVE = true;
        private int maxLineLength = 8192;
        private int maxLiteralSize = 25 * 1024 * 1024;
        private boolean allowPlaintextLogin = false;

        private Composer() {
        }

        public Composer listener(ImapListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener");
            }
            listeners.add(listener);
            return this;
        }

        public Composer sessionProvider(ImapServerSessionProvider provider) {
            if (provider == null) {
                throw new NullPointerException("provider");
            }
            this.sessionProvider = provider;
            return this;
        }

        public Composer sessionPerConnection(Supplier<ClientConnected> supplier) {
            return sessionProvider(ImapServerSessionProviders.perSession(supplier));
        }

        public Composer realm(Realm realm) {
            this.realm = realm;
            return this;
        }

        /**
         * @deprecated configure {@link MailboxFactory} on
         *             {@link ImapServerSessionProviders#mailbox(MailboxFactory)}
         *             instead.
         */
        @Deprecated
        public Composer mailboxFactory(MailboxFactory mailboxFactory) {
            if (mailboxFactory == null) {
                throw new NullPointerException("mailboxFactory");
            }
            if (sessionProvider == null) {
                sessionProvider = ImapServerSessionProviders.mailbox(mailboxFactory);
            } else if (sessionProvider instanceof MailboxStoreImapSessionProvider) {
                ((MailboxStoreImapSessionProvider) sessionProvider)
                        .mailboxFactory(mailboxFactory);
            } else {
                throw new IllegalStateException(
                        "mailboxFactory belongs on"
                                + " MailboxStoreImapSessionProvider, not on the"
                                + " composer when a custom session provider is"
                                + " set");
            }
            return this;
        }

        public Composer quotaManager(QuotaManager quotaManager) {
            this.quotaManager = quotaManager;
            return this;
        }

        public Composer loginTimeoutMs(long loginTimeoutMs) {
            this.loginTimeoutMs = loginTimeoutMs;
            return this;
        }

        public Composer commandTimeoutMs(long commandTimeoutMs) {
            this.commandTimeoutMs = commandTimeoutMs;
            return this;
        }

        public Composer enableIDLE(boolean enableIDLE) {
            this.enableIDLE = enableIDLE;
            return this;
        }

        public Composer enableNAMESPACE(boolean enableNAMESPACE) {
            this.enableNAMESPACE = enableNAMESPACE;
            return this;
        }

        public Composer enableQUOTA(boolean enableQUOTA) {
            this.enableQUOTA = enableQUOTA;
            return this;
        }

        public Composer enableMOVE(boolean enableMOVE) {
            this.enableMOVE = enableMOVE;
            return this;
        }

        public Composer maxLineLength(int maxLineLength) {
            this.maxLineLength = maxLineLength;
            return this;
        }

        public Composer maxLiteralSize(int maxLiteralSize) {
            this.maxLiteralSize = maxLiteralSize;
            return this;
        }

        public Composer allowPlaintextLogin(boolean allowPlaintextLogin) {
            this.allowPlaintextLogin = allowPlaintextLogin;
            return this;
        }

        public ImapServer server() {
            ImapServerSessionProvider provider = sessionProvider;
            if (provider == null && listeners.size() == 1) {
                provider = listeners.get(0).getSessionProvider();
            }
            if (listeners.isEmpty()) {
                throw new IllegalStateException(
                        "at least one listener is required");
            }
            ImapServer server = new ImapServer();
            server.setComposedSessionProvider(provider);
            if (realm != null) {
                server.setRealm(realm);
            }
            if (quotaManager != null) {
                server.setQuotaManager(quotaManager);
            }
            server.setLoginTimeoutMs(loginTimeoutMs);
            server.setCommandTimeoutMs(commandTimeoutMs);
            server.setEnableIDLE(enableIDLE);
            server.setEnableNAMESPACE(enableNAMESPACE);
            server.setEnableQUOTA(enableQUOTA);
            server.setEnableMOVE(enableMOVE);
            server.setMaxLineLength(maxLineLength);
            server.setMaxLiteralSize(maxLiteralSize);
            server.setAllowPlaintextLogin(allowPlaintextLogin);
            for (int i = 0; i < listeners.size(); i++) {
                server.addListener(listeners.get(i));
            }
            return server;
        }

        /**
         * @deprecated use {@link #server()}.
         */
        @Deprecated
        public ImapServer build() {
            return server();
        }
    }

}
