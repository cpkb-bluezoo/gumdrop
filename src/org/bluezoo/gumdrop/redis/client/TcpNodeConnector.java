/*
 * TcpNodeConnector.java
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

package org.bluezoo.gumdrop.redis.client;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * Opens cluster node connections over TCP (or TLS) with a {@link RedisClient},
 * so the usual dialling, DNS and TLS handling apply.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TcpNodeConnector implements NodeConnector {

    private final Gumdrop gumdrop;
    private final SelectorLoop loop;
    private final DnsResolver dnsResolver;
    private final boolean secure;
    private final TlsConfig tls;
    private final String username;
    private final String password;

    TcpNodeConnector(Gumdrop gumdrop, SelectorLoop loop, DnsResolver dnsResolver,
                     boolean secure, TlsConfig tls, String username, String password) {
        this.gumdrop = gumdrop;
        this.loop = loop;
        this.dnsResolver = dnsResolver;
        this.secure = secure;
        this.tls = tls;
        this.username = username;
        this.password = password;
    }

    @Override
    public void connect(ClusterNode node, NodeListener listener) {
        RedisClient client = new RedisClient()
                .host(node.getHost())
                .port(node.getPort())
                .selectorLoop(loop)
                .secure(secure)
                .tls(tls);
        if (dnsResolver != null) {
            client.dnsResolver(dnsResolver);
        }
        client.connect(gumdrop, new NodeAdapter(listener, username, password));
    }
}
