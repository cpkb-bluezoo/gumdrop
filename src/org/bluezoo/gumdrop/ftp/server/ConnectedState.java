/*
 * ConnectedState.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations available immediately after a control connection is established.
 *
 * <p>Accepting sends {@code 220} and enters the login phase. Rejecting sends
 * {@code 421} and closes the connection.
 */
public interface ConnectedState {

    /**
     * Accepts the connection and sends a {@code 220} greeting.
     *
     * @param greeting custom greeting text, or {@code null} for the default
     * @param handler receives USER / AUTH commands
     */
    void acceptConnection(String greeting, NotAuthenticatedHandler handler);

    /**
     * Accepts the connection when the client is already authenticated (e.g.
     * TLS client certificate).
     *
     * @param greeting greeting text for the {@code 230} response
     * @param handler receives post-login commands
     */
    void acceptLoggedIn(String greeting, AuthenticatedHandler handler);

    /**
     * Rejects the connection with {@code 421} using the default message.
     */
    void rejectConnection();

    /**
     * Rejects the connection with {@code 421} and a custom message.
     *
     * @param message the service-unavailable text
     */
    void rejectConnection(String message);

}
