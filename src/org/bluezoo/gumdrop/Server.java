/*
 * Server.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for server connectors that listen on ports.
 * A server in Gumdrop terminology corresponds exactly to a server in 
 * common internet parlance: it is a service running and listening on 
 * a specific port for client connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Server extends Connector {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    // Server-specific fields (moved from Connector)
    private List<ServerSocketChannel> serverChannels = new ArrayList<ServerSocketChannel>();
    private Set<InetAddress> addresses = null;
    protected boolean needClientAuth = false;

    protected Server() {
        super();
    }

    /**
     * Configures the addresses this server should bind to.
     */
    public void setAddresses(String value) {
        if (value == null) {
            addresses = null;
            return;
        }
        addresses = new LinkedHashSet<>();
        StringTokenizer st = new StringTokenizer(value);
        while (st.hasMoreTokens()) {
            String host = st.nextToken();
            try {
                addresses.add(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                String message = SelectorLoop.L10N.getString("err.unknown_host");
                message = MessageFormat.format(message, host);
                LOGGER.log(Level.SEVERE, message, e);
            }
        }
    }

    public List<ServerSocketChannel> getServerChannels() {
        return serverChannels;
    }

    void addServerChannel(ServerSocketChannel channel) {
        serverChannels.add(channel);
    }

    void closeServerChannels() throws IOException {
        for (Iterator<ServerSocketChannel> i = serverChannels.iterator(); i.hasNext(); ) {
            i.next().close();
        }
    }

    public void setNeedClientAuth(boolean flag) {
        needClientAuth = flag;
    }

    /**
     * Determines whether to accept a connection from the specified remote address.
     * This method is called for each incoming connection before resources are allocated.
     * Implementations can use this to implement IP filtering, rate limiting, or other
     * connection policies.
     * 
     * @param remoteAddress the remote socket address attempting to connect
     * @return true to accept the connection, false to reject it
     */
    public boolean acceptConnection(InetSocketAddress remoteAddress) {
        // Default implementation accepts all connections
        // Subclasses can override for specific filtering policies
        return true;
    }

    /**
     * Returns the IP addresses this server should be bound to.
     */
    protected Set<InetAddress> getAddresses() throws IOException {
        if (addresses == null) {
            addresses = new LinkedHashSet<>();
            for (Enumeration<NetworkInterface> e1 = NetworkInterface.getNetworkInterfaces(); e1.hasMoreElements(); ) {
                NetworkInterface ni = e1.nextElement();
                for (Enumeration<InetAddress> e2 = ni.getInetAddresses(); e2.hasMoreElements(); ) {
                    addresses.add(e2.nextElement());
                }
            }
        }
        return addresses;
    }

    /**
     * Returns the port number this server should be bound to.
     */
    protected abstract int getPort();

    /**
     * Override to configure SSL engine for server-side use with client auth settings.
     */
    @Override
    protected void configureSSLEngine(javax.net.ssl.SSLEngine engine) {
        super.configureSSLEngine(engine); // Sets useClientMode(false)
        engine.setUseClientMode(false); // we are a server
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        } else {
            engine.setWantClientAuth(true);
        }
    }
}
