/*
 * PasswordHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Handler for the PASS command (RFC 959 §4.1.1).
 */
public interface PasswordHandler {

    /**
     * Called when the client sends PASS.
     *
     * @param state operations for responding to PASS
     * @param password the password argument (may be empty)
     */
    void password(PasswordState state, String password);

}
