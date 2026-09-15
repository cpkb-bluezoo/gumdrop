/*
 * LdapClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ldap.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level LDAPv3 client facade (RFC 4511).
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

    public void connect(LdapConnectionReady handler) {
        transportFactory = new TcpTransportFactory();
        endpointHandler = new LdapClientProtocolHandler(handler, secure);

        try {
            ClientConnect.prepareTls(secure, tls, transportFactory);
            clientEndpoint = ClientConnect.openAndConnect(
                    dial, transportFactory, endpointHandler);
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
