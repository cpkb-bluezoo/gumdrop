/*
 * SMTPConnector.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.smtp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Connector;
import org.bluezoo.gumdrop.Realm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

/**
 * Connection factory for SMTP connections on a given port.
 * This connector supports both standard SMTP (port 25) and submission 
 * service (port 587), with transparent SSL/TLS support for SMTPS.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc5321 (SMTP)
 * @see https://www.rfc-editor.org/rfc/rfc6409 (Message Submission)
 */
public class SMTPConnector extends Connector {

    /**
     * The default SMTP port (standard mail transfer).
     */
    protected static final int SMTP_DEFAULT_PORT = 25;

    /**
     * The default SMTP submission port (authenticated mail submission).
     */
    protected static final int SMTP_SUBMISSION_PORT = 587;

    /**
     * The default SMTPS port (SMTP over SSL/TLS).
     */
    protected static final int SMTPS_DEFAULT_PORT = 465;

    protected int port = -1;
    protected long maxMessageSize = 35882577; // ~35MB default, configurable
    protected Realm realm; // Authentication realm for SMTP AUTH

    /**
     * Returns a short description of this connector.
     */
    @Override
    public String getDescription() {
        return secure ? "smtps" : "smtp";
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
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Returns the maximum message size in bytes.
     * @return the maximum message size
     */
    public long getMaxMessageSize() {
        return maxMessageSize;
    }

    /**
     * Sets the maximum message size in bytes.
     * @param maxMessageSize the maximum message size
     */
    public void setMaxMessageSize(long maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * Returns the authentication realm.
     * @return the realm for SMTP authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for SMTP AUTH.
     * @param realm the realm to use for authentication
     */
    public void setRealm(Realm realm) {
        this.realm = realm;
    }

    /**
     * Starts this connector, setting default port if not specified.
     */
    @Override
    protected void start() {
        super.start();
        if (port <= 0) {
            // Use standard SMTP port (25) for non-secure, SMTPS port (465) for secure
            // Note: Port 587 (submission) is typically used for authenticated clients
            // and may or may not use TLS (STARTTLS), so we default to 25/465
            port = secure ? SMTPS_DEFAULT_PORT : SMTP_DEFAULT_PORT;
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
     * Creates a new SMTP connection for the given socket channel.
     * For STARTTLS support: if secure=false but SSL context is available,
     * the connection starts as plaintext and can be upgraded with STARTTLS.
     * @param channel the socket channel for the client connection
     * @param engine the SSL engine if this is a secure connection, null otherwise
     * @return a new SMTPConnection instance
     */
    @Override
    protected Connection newConnection(SocketChannel channel, SSLEngine engine) {
        // For SMTP: if secure=false, always start as plaintext (even if SSL context exists)
        // This allows STARTTLS to upgrade the connection later
        SSLEngine actualEngine = isSecure() ? engine : null;
        return new SMTPConnection(this, channel, actualEngine, isSecure());
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return context != null;
    }

    /**
     * Creates an SSL engine for STARTTLS upgrade.
     * This is called by SMTPConnection when STARTTLS is requested.
     * @param channel the socket channel to create the engine for
     * @return a configured SSLEngine, or null if SSL context is not available
     */
    protected SSLEngine createSSLEngine(SocketChannel channel) throws IOException {
        if (context == null) {
            return null;
        }
        
        InetSocketAddress peerAddress = (InetSocketAddress) channel.getRemoteAddress();
        String peerHost = peerAddress.getHostName();
        int peerPort = peerAddress.getPort();
        SSLEngine engine = context.createSSLEngine(peerHost, peerPort);
        engine.setUseClientMode(false); // we are a server
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        } else {
            engine.setWantClientAuth(true);
        }
        return engine;
    }

}
