/*
 * AccountHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.handler;

/**
 * Handler for the ACCT command (RFC 959 §4.1.1).
 */
public interface AccountHandler {

    /**
     * Called when the client sends ACCT.
     *
     * @param state operations for responding to ACCT
     * @param account the account argument
     */
    void account(AccountState state, String account);

}
