/*
 * IMAPServer.java
 * Copyright (C) 2025 Chris Burdess
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

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for IMAP connections on a given port.
 * This connector supports both standard IMAP (port 143) and IMAPS (port 993),
 * with transparent SSL/TLS support and STARTTLS capability.
 * 
 * <p>This implementation supports IMAP4rev2 as defined in RFC 9051, with
 * the following extensions:
 * <ul>
 *   <li>RFC 2177 - IDLE (push notifications)</li>
 *   <li>RFC 2342 - NAMESPACE</li>
 *   <li>RFC 6851 - MOVE</li>
 *   <li>RFC 9208 - QUOTA</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2177">RFC 2177 - IDLE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2342">RFC 2342 - NAMESPACE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6851">RFC 6851 - MOVE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9208">RFC 9208 - QUOTA</a>
 */
public class IMAPServer extends Server {

    private static final Logger LOGGER = Logger.getLogger(IMAPServer.class.getName());

    /**
     * The default IMAP port (cleartext or with STARTTLS).
     */
    public static final int IMAP_DEFAULT_PORT = 143;

    /**
     * The default IMAPS port (implicit TLS).
     */
    public static final int IMAPS_DEFAULT_PORT = 993;

    // Configuration
    protected int port = -1;
    protected Realm realm;
    protected MailboxFactory mailboxFactory;
    protected QuotaManager quotaManager;
    
    // Timeouts (in milliseconds)
    protected long loginTimeout = 60000;        // 1 minute for login
    protected long idleTimeout = 1800000;       // 30 minutes idle (RFC 9051 recommends >= 30 min)
    protected long commandTimeout = 300000;     // 5 minutes per command
    
    // Extension support
    protected boolean enableIDLE = true;
    protected boolean enableNAMESPACE = true;
    protected boolean enableQUOTA = true;
    protected boolean enableMOVE = true;
    
    // Limits
    protected int maxLineLength = 8192;         // Max command line length
    protected int maxLiteralSize = 25 * 1024 * 1024; // 25MB max literal
    
    // Security options
    protected boolean allowPlaintextLogin = false; // Allow LOGIN over non-TLS (testing only!)

    /**
     * Returns a short description of this connector.
     */
    @Override
    public String getDescription() {
        return secure ? "imaps" : "imap";
    }

    /**
     * Returns the port number this connector is bound to.
     */
    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port number this connector should bind to.
     * 
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the authentication realm.
     * 
     * @return the realm for IMAP authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for IMAP authentication.
     * 
     * @param realm the realm to use for authentication
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Returns the mailbox factory for this server.
     * 
     * @return the mailbox factory, or null if not configured
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the mailbox factory for creating mailbox store instances.
     * Each IMAP connection will use this factory to obtain a mail store
     * for the authenticated user.
     * 
     * @param mailboxFactory the factory to create mailbox instances
     */
    public void setMailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
    }

    /**
     * Returns the quota manager for this server.
     * 
     * @return the quota manager, or null if quotas are not enabled
     */
    public QuotaManager getQuotaManager() {
        return quotaManager;
    }

    /**
     * Sets the quota manager for enforcing storage quotas.
     * When configured, the IMAP server will:
     * <ul>
     *   <li>Check quotas before APPEND commands</li>
     *   <li>Respond to GETQUOTA and GETQUOTAROOT commands</li>
     *   <li>Allow administrators to use SETQUOTA</li>
     *   <li>Track storage usage after message operations</li>
     * </ul>
     * 
     * @param quotaManager the quota manager
     */
    public void setQuotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    /**
     * Returns the login timeout in milliseconds.
     * 
     * @return the login timeout
     */
    public long getLoginTimeout() {
        return loginTimeout;
    }

    /**
     * Sets the login timeout in milliseconds.
     * This is the maximum time allowed for authentication.
     * 
     * @param loginTimeout the timeout in milliseconds
     */
    public void setLoginTimeout(long loginTimeout) {
        this.loginTimeout = loginTimeout;
    }

    /**
     * Returns the idle timeout in milliseconds.
     * RFC 9051 recommends at least 30 minutes.
     * 
     * @return the idle timeout
     */
    public long getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * Sets the idle timeout in milliseconds.
     * 
     * @param idleTimeout the timeout in milliseconds
     */
    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * Returns the command timeout in milliseconds.
     * 
     * @return the command timeout
     */
    public long getCommandTimeout() {
        return commandTimeout;
    }

    /**
     * Sets the command timeout in milliseconds.
     * This is the maximum time for a single command to complete.
     * 
     * @param commandTimeout the timeout in milliseconds
     */
    public void setCommandTimeout(long commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    /**
     * Returns whether the IDLE extension is enabled.
     * 
     * @return true if IDLE is enabled
     */
    public boolean isEnableIDLE() {
        return enableIDLE;
    }

    /**
     * Sets whether the IDLE extension is enabled.
     * IDLE provides push notification for mailbox changes.
     * 
     * @param enableIDLE true to enable IDLE
     */
    public void setEnableIDLE(boolean enableIDLE) {
        this.enableIDLE = enableIDLE;
    }

    /**
     * Returns whether the NAMESPACE extension is enabled.
     * 
     * @return true if NAMESPACE is enabled
     */
    public boolean isEnableNAMESPACE() {
        return enableNAMESPACE;
    }

    /**
     * Sets whether the NAMESPACE extension is enabled.
     * 
     * @param enableNAMESPACE true to enable NAMESPACE
     */
    public void setEnableNAMESPACE(boolean enableNAMESPACE) {
        this.enableNAMESPACE = enableNAMESPACE;
    }

    /**
     * Returns whether the QUOTA extension is enabled.
     * 
     * @return true if QUOTA is enabled
     */
    public boolean isEnableQUOTA() {
        return enableQUOTA;
    }

    /**
     * Sets whether the QUOTA extension is enabled.
     * 
     * @param enableQUOTA true to enable QUOTA
     */
    public void setEnableQUOTA(boolean enableQUOTA) {
        this.enableQUOTA = enableQUOTA;
    }

    /**
     * Returns whether the MOVE extension is enabled.
     * 
     * @return true if MOVE is enabled
     */
    public boolean isEnableMOVE() {
        return enableMOVE;
    }

    /**
     * Sets whether the MOVE extension is enabled.
     * 
     * @param enableMOVE true to enable MOVE
     */
    public void setEnableMOVE(boolean enableMOVE) {
        this.enableMOVE = enableMOVE;
    }

    /**
     * Returns whether plaintext LOGIN is allowed over non-TLS connections.
     * 
     * @return true if plaintext login is allowed
     */
    public boolean isAllowPlaintextLogin() {
        return allowPlaintextLogin;
    }

    /**
     * Sets whether plaintext LOGIN is allowed over non-TLS connections.
     * <p><strong>WARNING:</strong> This should only be enabled for testing.
     * Enabling this in production exposes passwords to network eavesdropping.
     * 
     * @param allowPlaintextLogin true to allow plaintext login
     */
    public void setAllowPlaintextLogin(boolean allowPlaintextLogin) {
        this.allowPlaintextLogin = allowPlaintextLogin;
    }

    /**
     * Returns the maximum command line length.
     * 
     * @return the max line length in bytes
     */
    public int getMaxLineLength() {
        return maxLineLength;
    }

    /**
     * Sets the maximum command line length.
     * 
     * @param maxLineLength the max line length in bytes
     */
    public void setMaxLineLength(int maxLineLength) {
        this.maxLineLength = maxLineLength;
    }

    /**
     * Returns the maximum literal size.
     * 
     * @return the max literal size in bytes
     */
    public int getMaxLiteralSize() {
        return maxLiteralSize;
    }

    /**
     * Sets the maximum literal size.
     * 
     * @param maxLiteralSize the max literal size in bytes
     */
    public void setMaxLiteralSize(int maxLiteralSize) {
        this.maxLiteralSize = maxLiteralSize;
    }

    /**
     * Starts this connector, setting default port if not specified.
     */
    @Override
    protected void start() {
        super.start();
        if (port <= 0) {
            port = secure ? IMAPS_DEFAULT_PORT : IMAP_DEFAULT_PORT;
        }
        
        if (mailboxFactory == null) {
            LOGGER.warning("No mailbox factory configured - IMAP server will not be functional");
        }
        
        if (realm == null) {
            LOGGER.warning("No realm configured - IMAP authentication will not work");
        }
    }

    /**
     * Stops this connector.
     */
    @Override
    protected void stop() {
        super.stop();
    }

    /**
     * Creates a new IMAP connection for the given socket channel.
     * For STARTTLS support: if secure=false but engine!=null, the connection
     * starts as plaintext but can be upgraded.
     * 
     * @param channel the socket channel for the client connection
     * @param engine the SSL engine (null if no SSL context, non-null if STARTTLS-capable)
     * @return a new IMAPConnection instance
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine) {
        return new IMAPConnection(this, engine, isSecure());
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     * 
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return context != null;
    }

    /**
     * Returns the list of capabilities to advertise.
     * This is used by IMAPConnection to build CAPABILITY responses.
     * 
     * @param authenticated true if the user is authenticated
     * @param secure true if the connection is using TLS
     * @return space-separated capability string
     */
    protected String getCapabilities(boolean authenticated, boolean secure) {
        StringBuilder caps = new StringBuilder();
        caps.append("IMAP4REV2");
        
        // Pre-authentication capabilities
        if (!authenticated && !secure && isSTARTTLSAvailable()) {
            caps.append(" STARTTLS");
        }
        
        if (!authenticated) {
            caps.append(" AUTH=PLAIN AUTH=LOGIN");
            // Additional auth mechanisms based on realm
            if (realm != null) {
                caps.append(" AUTH=CRAM-MD5 AUTH=DIGEST-MD5 AUTH=SCRAM-SHA-256");
            }
            if (!secure && !allowPlaintextLogin) {
                caps.append(" LOGINDISABLED"); // Disable LOGIN over plaintext
            }
        }
        
        // Post-authentication capabilities
        if (authenticated) {
            if (enableIDLE) {
                caps.append(" IDLE");
            }
            if (enableNAMESPACE) {
                caps.append(" NAMESPACE");
            }
            if (enableQUOTA) {
                caps.append(" QUOTA");
            }
            if (enableMOVE) {
                caps.append(" MOVE");
            }
        }
        
        // Always available capabilities
        caps.append(" UNSELECT");
        caps.append(" UIDPLUS");
        caps.append(" CHILDREN");
        caps.append(" LIST-EXTENDED");
        caps.append(" LIST-STATUS");
        
        // TODO: LITERAL+ LITERAL- CONDSTORE QRESYNC
        
        return caps.toString();
    }

}

