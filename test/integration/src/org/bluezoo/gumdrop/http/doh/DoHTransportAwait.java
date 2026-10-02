/*
 * DoHTransportAwait.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http.doh;

/**
 * Test access to the package-private connection-ready signal of
 * {@link DoHClientTransport}, so integration tests in other packages can
 * synchronise on it instead of polling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DoHTransportAwait {

    private DoHTransportAwait() {
    }

    /**
     * Blocks until the transport's HTTPS connection is up or has failed.
     *
     * @param transport the opened transport
     * @param hangGuardMs hang-guard timeout in milliseconds
     * @return false only if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean awaitConnected(DoHClientTransport transport,
            long hangGuardMs) throws InterruptedException {
        return transport.awaitConnected(hangGuardMs);
    }
}
