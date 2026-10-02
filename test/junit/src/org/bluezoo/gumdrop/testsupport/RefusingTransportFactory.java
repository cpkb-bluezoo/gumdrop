/*
 * RefusingTransportFactory.java
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

package org.bluezoo.gumdrop.testsupport;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;

/**
 * {@link TcpTransportFactory} whose outbound connects are refused
 * deterministically: the handler's {@code error} callback receives a
 * {@link ConnectException} exactly as a real "connection refused" would
 * deliver it, with no socket (and so no racy ephemeral port) involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RefusingTransportFactory extends TcpTransportFactory {

    private int attempts;

    @Override
    public TcpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port, String tlsServerNameHint,
                               ProtocolHandler handler, SelectorLoop loop) throws IOException {
        synchronized (this) {
            attempts++;
        }
        handler.error(new ConnectException("Connection refused"));
        return null;
    }

    @Override
    public TcpEndpoint connect(Gumdrop gumdrop, String path, ProtocolHandler handler,
                               SelectorLoop loop) throws IOException {
        synchronized (this) {
            attempts++;
        }
        handler.error(new ConnectException("Connection refused"));
        return null;
    }

    /** @return how many connects have been attempted on this factory */
    public synchronized int attempts() {
        return attempts;
    }
}
