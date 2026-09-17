/*
 * package-info.java
 * Copyright (C) 2026 Chris Burdess
 */

/**
 * SOCKS server: listeners, composition, and handler/state interfaces for
 * the proxy's policy decisions.
 *
 * <p>{@link org.bluezoo.gumdrop.socks.server.SocksServer} is the canonical
 * server type — configure it with {@link
 * org.bluezoo.gumdrop.socks.server.SocksServer#compose()}; do not subclass
 * it for application logic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.socks.SocksProtocolHandler
 */
package org.bluezoo.gumdrop.socks.server;
