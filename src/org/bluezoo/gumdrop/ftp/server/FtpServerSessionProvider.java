/*
 * FtpServerSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.server;

import org.bluezoo.gumdrop.ServerSessionProvider;

/**
 * FTP server composition SPI — mints a staged handler pipeline per accepted
 * control connection.
 *
 * <p>Stock providers: {@link FileSystemFtpSessionProvider},
 * {@link LegacyConnectionHandlerSessionProvider}; see
 * {@link FtpServerSessionProviders}.
 */
public interface FtpServerSessionProvider
        extends ServerSessionProvider<ClientConnected> {

    default void start() {
    }

    default void stop() {
    }

}
