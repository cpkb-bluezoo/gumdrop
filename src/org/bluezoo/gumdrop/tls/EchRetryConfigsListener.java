/*
 * EchRetryConfigsListener.java
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


package org.bluezoo.gumdrop.tls;

/**
 * Told which {@code retry_configs} a server sent when it rejected Encrypted
 * Client Hello (RFC 9849 section 6.1.6), so a later connection can use them.
 * Only called once the server has authenticated for the offered config's
 * {@code public_name}, because unauthenticated configs must not be trusted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface EchRetryConfigsListener {

    /**
     * Reports the authenticated {@code retry_configs}.
     *
     * @param authenticatedConfigs the configs, in the server's order; never empty
     */
    void retryConfigsReceived(EchConfig[] authenticatedConfigs);
}
