/*
 * AuthenticatingHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;

/**
 * Optional capability for handlers whose authentication may block (e.g. realm
 * password verification). The protocol handler may evaluate this off the
 * selector loop.
 */
public interface AuthenticatingHandler {

    /**
     * Evaluates USER/PASS/ACCT credentials.
     */
    FtpAuthenticationResult evaluateAuthentication(String username,
            String password, String account);

}
