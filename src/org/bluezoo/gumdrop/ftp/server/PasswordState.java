/*
 * PasswordState.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations for responding to PASS (RFC 959 §4.1.1).
 */
public interface PasswordState {

    /** Sends {@code 230} — user logged in. */
    void loggedIn(AuthenticatedHandler handler);

    /** Sends {@code 332} — account information required. */
    void needAccount(AccountHandler handler);

    /** Sends {@code 530} — invalid password. */
    void rejectInvalidPassword();

    /** Sends {@code 530} — account disabled. */
    void rejectAccountDisabled();

    /** Sends {@code 421} — too many login attempts. */
    void rejectTooManyAttempts();

}
