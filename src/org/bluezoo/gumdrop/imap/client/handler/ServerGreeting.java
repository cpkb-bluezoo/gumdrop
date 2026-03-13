/*
 * ServerGreeting.java
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

package org.bluezoo.gumdrop.imap.client.handler;

import org.bluezoo.gumdrop.ClientHandler;

import java.util.List;

/**
 * Entry point handler for IMAP server greeting (RFC 9051 section 7.1).
 *
 * <p>An IMAP server sends one of three greetings:
 * <ul>
 *   <li>{@code * OK} — standard greeting, client in NOT_AUTHENTICATED state</li>
 *   <li>{@code * PREAUTH} — pre-authenticated (e.g. via TLS client cert)</li>
 *   <li>{@code * BYE} — service unavailable, connection will close</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ServerGreeting extends ClientHandler {

    /** RFC 9051 section 7.1 — OK greeting (NOT_AUTHENTICATED state). */
    void handleGreeting(ClientNotAuthenticatedState auth, String greeting, List<String> preAuthCapabilities);

    /** RFC 9051 section 7.1 — PREAUTH greeting (AUTHENTICATED state). */
    void handlePreAuthenticated(ClientAuthenticatedState auth, String greeting);

    /** RFC 9051 section 7.1 — BYE greeting (service unavailable). */
    void handleServiceUnavailable(String message);
}
