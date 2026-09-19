/*
 * LegacyConnectionHandlerSessionProvider.java
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

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.ftp.FtpConnectionHandler;

import java.util.function.Supplier;

/**
 * Session provider that wraps a legacy {@link FtpConnectionHandler} per
 * connection.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LegacyConnectionHandlerSessionProvider
        implements FtpServerSessionProvider {

    private final Supplier<FtpConnectionHandler> supplier;

    LegacyConnectionHandlerSessionProvider(
            Supplier<FtpConnectionHandler> supplier) {
        this.supplier = supplier;
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        return new LegacyConnectionHandlerAdapter(supplier.get());
    }

}
