/*
 * ImapClientSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.client;

import org.bluezoo.gumdrop.ClientSessionProvider;
import org.bluezoo.gumdrop.imap.client.handler.RemoteGreeting;

/**
 * IMAP client composition SPI — supplies the bootstrap handler for an outbound
 * session.
 */
public interface ImapClientSessionProvider
        extends ClientSessionProvider<RemoteGreeting> {
}
