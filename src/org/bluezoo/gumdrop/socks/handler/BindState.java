/*
 * BindState.java
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

package org.bluezoo.gumdrop.socks.handler;

/**
 * Callback interface for asynchronous SOCKS BIND authorization
 * decisions.
 *
 * <p>When a {@link BindHandler} receives a bind request, it calls
 * one of the methods on this interface to communicate the decision
 * back to the SOCKS protocol handler. The decision may be made
 * asynchronously (e.g. after an LDAP or database lookup).
 *
 * <p>Exactly one method must be called, and it must be called on the
 * connection's SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface BindState {

    /**
     * Allows the BIND request. The SOCKS server will proceed to
     * bind a listening port and wait for an incoming connection.
     */
    void allow();

    /**
     * Denies the BIND request with a SOCKS reply code.
     *
     * <p>The reply code is from RFC 1928 §6 (for SOCKS5) or mapped
     * to SOCKS4 protocol Reply CD values. For SOCKS5 clients, the
     * reply code is sent directly. For SOCKS4 clients, it is mapped
     * to the appropriate SOCKS4 reply (0x5b rejected).
     *
     * @param replyCode the SOCKS5 reply code (e.g.
     *        {@link org.bluezoo.gumdrop.socks.SOCKSConstants#SOCKS5_REPLY_NOT_ALLOWED})
     */
    void deny(int replyCode);

}
