/*
 * MdnsServer.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.mdns;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.mdns.server.MdnsServer} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.mdns.server.MdnsServer
 * @see docs/NAMING-TAXONOMY.md
 */
public class MdnsServer extends org.bluezoo.gumdrop.mdns.server.MdnsServer {

    public MdnsServer() {
        super();
    }
}
