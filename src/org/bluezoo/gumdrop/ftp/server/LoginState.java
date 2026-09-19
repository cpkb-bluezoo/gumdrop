/*
 * LoginState.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.ftp.server;

/**
 * Operations for responding to USER (RFC 959 §4.1.1).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
