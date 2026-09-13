/*
 * NotAuthenticatedHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.handler;

import org.bluezoo.gumdrop.SecurityInfo;

/**
 * Handler for the FTP login phase before authentication completes (RFC 959
 * §4.1.1).
 */
public interface NotAuthenticatedHandler {

    /**
     * Called when the client sends USER.
     *
     * @param state operations for responding to USER
     * @param username the username argument
     */
    void user(LoginState state, String username);

    /**
     * Called after a successful AUTH TLS upgrade (RFC 4217).
     *
     * @param state operations for continuing login after TLS
     * @param securityInfo TLS session details
     */
    void tlsEstablished(TlsLoginState state, SecurityInfo securityInfo);

}
