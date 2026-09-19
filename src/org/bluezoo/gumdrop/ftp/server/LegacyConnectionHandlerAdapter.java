/*
 * LegacyConnectionHandlerAdapter.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;
import org.bluezoo.gumdrop.ftp.FtpConnectionMetadata;

/**
 * Adapts a legacy {@link FtpConnectionHandler} to {@link ClientConnected}.
 *
 * <p>Used by {@link org.bluezoo.gumdrop.ftp.server.LegacyConnectionHandlerSessionProvider}
 * during migration. The protocol handler unwraps the delegate and continues to
 * use the legacy callback interface until the staged path is fully wired.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LegacyConnectionHandlerAdapter implements ClientConnected {

    private final FtpConnectionHandler delegate;

    public LegacyConnectionHandlerAdapter(FtpConnectionHandler delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * Returns the wrapped legacy handler.
     */
    public FtpConnectionHandler getDelegate() {
        return delegate;
    }

    /**
     * Unwraps a session pipeline entry to a legacy handler when possible.
     */
    public static FtpConnectionHandler unwrap(ClientConnected session) {
        if (session instanceof LegacyConnectionHandlerAdapter) {
            return ((LegacyConnectionHandlerAdapter) session).getDelegate();
        }
        return null;
    }

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        // Legacy handlers are driven by FtpProtocolHandler directly.
    }

    @Override
    public void disconnected() {
        // Legacy handlers receive disconnected(metadata) from the protocol handler.
    }

    /**
     * @deprecated for protocol use only — supplies metadata to legacy callbacks
     */
    @Deprecated
    public FtpConnectionHandler getConnectionHandler() {
        return delegate;
    }

}
