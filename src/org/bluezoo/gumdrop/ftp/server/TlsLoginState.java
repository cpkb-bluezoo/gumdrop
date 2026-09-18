/*
 * TlsLoginState.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations after AUTH TLS succeeds on the control connection (RFC 4217).
 */
public interface TlsLoginState {

    /**
     * Continues the login phase after TLS; sends {@code 234} if not already sent.
     *
     * @param handler receives USER / PASS commands on the encrypted channel
     */
    void continueLogin(NotAuthenticatedHandler handler);

    /**
     * Sends {@code 530} when TLS client certificate authentication failed.
     */
    void rejectCertificateLogin();

}
