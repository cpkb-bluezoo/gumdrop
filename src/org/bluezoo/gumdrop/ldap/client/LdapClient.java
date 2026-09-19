/*
 * LdapClient.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level LDAPv3 client facade (RFC 4511).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LdapClient {

    private final ClientDial dial = ClientDial.withDefaultPort(389);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private LdapClientProtocolHandler endpointHandler;

    public LdapClient() {
    }

    public LdapClient(String host, int port) {
        this(null, host, port);
    }

    public LdapClient(SelectorLoop selectorLoop, String host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public LdapClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public LdapClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public LdapClient(String socketPath) {
        this(null, socketPath);
    }

    public LdapClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public void setClientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
    }

    public void setTrustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
    }

    public void setKeystoreFile(Path path) {
        tls.keystoreFile(path);
    }

    public void setKeystoreFile(String path) {
        tls.keystoreFile(Path.of(path));
    }

    public void setKeystorePass(String password) {
        tls.keystorePass(password);
    }

    public void setKeystoreFormat(String format) {
        tls.keystoreFormat(format);
    }

    public LdapClient host(String host) {
        dial.host(host);
        return this;
    }

    public LdapClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    public LdapClient port(int port) {
        dial.port(port);
        return this;
    }

    public LdapClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    public LdapClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    public LdapClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    public LdapClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public LdapClient clientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
        return this;
    }

    public LdapClient trustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
        return this;
    }

    public LdapClient keystoreFile(Path path) {
        tls.keystoreFile(path);
        return this;
    }

    public LdapClient keystorePass(String password) {
        tls.keystorePass(password);
        return this;
    }

    public LdapClient keystoreFormat(String format) {
        tls.keystoreFormat(format);
        return this;
    }

    public void connect(Gumdrop gumdrop, LdapConnectionReady handler) {
        transportFactory = new TcpTransportFactory();
        endpointHandler = new LdapClientProtocolHandler(handler, secure);

        try {
            ClientConnect.prepareTls(secure, tls, transportFactory);
            clientEndpoint = ClientConnect.openAndConnect(
                    gumdrop, dial, transportFactory, endpointHandler);
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    public boolean isOpen() {
        return endpointHandler != null && endpointHandler.isOpen();
    }

    public void close() {
        if (endpointHandler != null) {
            endpointHandler.close();
        }
        if (clientEndpoint != null) {
            clientEndpoint.close();
        }
    }
}
