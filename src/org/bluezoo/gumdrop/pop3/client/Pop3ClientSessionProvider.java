/*
 * Pop3ClientSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3.client;

import org.bluezoo.gumdrop.ClientSessionProvider;
import org.bluezoo.gumdrop.pop3.client.handler.RemoteGreeting;

/**
 * POP3 client composition SPI — supplies the bootstrap handler for an outbound
 * session.
 */
public interface Pop3ClientSessionProvider
        extends ClientSessionProvider<RemoteGreeting> {
}
