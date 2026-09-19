/*
 * FtpServerSessionProviders.java
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
 * Factory methods for {@link FtpServerSessionProvider} implementations.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FtpServerSessionProviders {

    private FtpServerSessionProviders() {
    }

    /**
     * Returns a provider for local filesystem access (Simple FTP semantics).
     */
    public static FileSystemFtpSessionProvider fileSystem() {
        return new FileSystemFtpSessionProvider();
    }

    /**
     * Returns a provider for anonymous, read-only public file distribution.
     */
    public static AnonymousFtpSessionProvider anonymous() {
        return new AnonymousFtpSessionProvider();
    }

    /**
     * Returns a provider with role-based access control.
     */
    public static RoleBasedFtpSessionProvider roleBased() {
        return new RoleBasedFtpSessionProvider();
    }

    /**
     * Wraps a legacy {@link FtpConnectionHandler} supplier.
     */
    public static LegacyConnectionHandlerSessionProvider connectionHandler(
            Supplier<FtpConnectionHandler> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new LegacyConnectionHandlerSessionProvider(supplier);
    }

    public static FtpServerSessionProvider perSession(
            final Supplier<ClientConnected> supplier) {
        if (supplier == null) {
            throw new NullPointerException("supplier");
        }
        return new FtpServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener listener) {
                return supplier.get();
            }
        };
    }

}
