/*
 * AccountState.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations for responding to ACCT (RFC 959 §4.1.1).
 */
public interface AccountState {

    /** Sends {@code 230} — user logged in. */
    void loggedIn(AuthenticatedHandler handler);

    /** Sends {@code 202} — command accepted (no further account needed). */
    void commandOk();

    /** Sends {@code 530} — invalid account. */
    void rejectInvalidAccount();

    /** Sends {@code 530} — invalid password (legacy servers use this). */
    void rejectInvalidPassword();

}
