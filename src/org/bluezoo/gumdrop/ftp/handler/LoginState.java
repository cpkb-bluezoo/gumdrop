/*
 * LoginState.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.handler;

/**
 * Operations for responding to USER (RFC 959 §4.1.1).
 */
public interface LoginState {

    /** Sends {@code 331} — password required. */
    void needPassword(PasswordHandler handler);

    /** Sends {@code 332} — account information required. */
    void needAccount(AccountHandler handler);

    /** Sends {@code 230} — user logged in. */
    void loggedIn(AuthenticatedHandler handler);

    /** Sends {@code 530} — invalid username. */
    void rejectInvalidUser();

    /** Sends {@code 530} — anonymous access denied. */
    void rejectAnonymousNotAllowed();

    /** Sends {@code 421} — too many login attempts. */
    void rejectTooManyAttempts();

    /** Sends {@code 421} — user limit exceeded. */
    void rejectUserLimitExceeded();

}
