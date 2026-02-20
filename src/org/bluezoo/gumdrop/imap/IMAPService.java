/*
 * IMAPService.java
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

package org.bluezoo.gumdrop.imap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.TCPListener;
import org.bluezoo.gumdrop.Service;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.quota.QuotaManager;

/**
 * Abstract base for IMAP application services.
 *
 * <p>An {@code IMAPService} defines the application logic for handling
 * IMAP connections. It owns authentication, mailbox storage, and quota
 * configuration, and acts as the handler factory: subclasses override
 * {@link #createHandler(TCPListener)} to return the appropriate
 * {@link ClientConnected} handler for each new connection, receiving
 * the originating endpoint so that different policies can be applied
 * per listener.
 *
 * <p>Service-level configuration is pushed into each listener during
 * {@link #start()} so that the existing endpoint handler code
 * continues to read configuration from the endpoint.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * <service class="com.example.MyImapService">
 *   <property name="realm" ref="#myRealm"/>
 *   <property name="mailbox-factory" ref="#mboxStorage"/>
 *   <property name="quota-manager" ref="#quotas"/>
 *   <listener class="org.bluezoo.gumdrop.imap.IMAPListener"
 *           port="143"/>
 *   <listener class="org.bluezoo.gumdrop.imap.IMAPListener"
 *           port="993" secure="true"/>
 * </service>
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Service
 * @see IMAPListener
 */
public abstract class IMAPService implements Service {

    private static final Logger LOGGER =
            Logger.getLogger(IMAPService.class.getName());

    private final List listeners = new ArrayList();

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
    public void addListener(IMAPListener endpoint) {
        listeners.add(endpoint);
    }

    /**
     * Sets the listeners from a configuration list. Each item in the
     * list must be an {@link IMAPListener}.
     *
     * @param list the list of listener endpoints
     */
    public void setListeners(List list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof IMAPListener) {
                addListener((IMAPListener) item);
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
     * Creates a new handler for an incoming IMAP connection on the
     * given endpoint.
     *
     * <p>Subclasses must implement this to provide connection-level
     * IMAP behaviour (authentication, mailbox access, etc.).
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
            if (listener instanceof IMAPListener) {
                IMAPListener ep = (IMAPListener) listener;
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
    private void wireEndpoint(IMAPListener ep) {
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

}
