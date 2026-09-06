/*
 * DoHTransportFactoryImpl.java
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

package org.bluezoo.gumdrop.http.doh;

import org.bluezoo.gumdrop.dns.client.DNSClientTransport;
import org.bluezoo.gumdrop.dns.client.DoHTransportFactory;

/**
 * {@link java.util.ServiceLoader}-discovered {@link DoHTransportFactory}
 * implementation, letting {@link org.bluezoo.gumdrop.dns.client.DNSResolver}
 * (in core, which cannot depend on the HTTP client stack) create {@link
 * DoHClientTransport} instances when the HTTP module is present.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DoHTransportFactoryImpl implements DoHTransportFactory {

    @Override
    public DNSClientTransport createTransport(String path) {
        DoHClientTransport transport = new DoHClientTransport();
        if (path != null) {
            transport.setPath(path);
        }
        return transport;
    }

}
