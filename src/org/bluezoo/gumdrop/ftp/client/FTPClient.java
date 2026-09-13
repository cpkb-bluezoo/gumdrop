/*
 * FtpClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.function.Supplier;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.client.ClientConnect;
import org.bluezoo.gumdrop.client.ClientDial;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.ftp.client.handler.RemoteGreeting;
import org.bluezoo.gumdrop.tls.ClientTlsConfig;
import org.bluezoo.gumdrop.tls.ServerCredentials;

/**
 * High-level FTP client facade.
 */
public class FtpClient {

    private final ClientDial dial = ClientDial.withDefaultPort(21);
    private final ClientTlsConfig tls = new ClientTlsConfig();

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private FtpClientProtocolHandler endpointHandler;
    private FtpClientSessionProvider sessionProvider;

    public FtpClient() {
    }

    public FtpClient(String host, int port) {
        this(null, host, port);
    }

    public FtpClient(SelectorLoop selectorLoop, String host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public FtpClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public FtpClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public FtpClient(String socketPath) {
        this(null, socketPath);
    }

    public FtpClient(SelectorLoop selectorLoop, String socketPath) {
        dial.selectorLoop(selectorLoop).socketPath(socketPath);
    }

    public void setSecure(boolean secure) {
        tls.secure(secure);
    }

    public void setClientCredentials(ServerCredentials clientCredentials) {
        tls.clientCredentials(clientCredentials);
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

    public FtpClient secure(boolean secure) {
        tls.secure(secure);
        return this;
    }

    public FtpClient trustJvm() {
        tls.trustJvm();
        return this;
    }

    public FtpClient clientCredentials(ServerCredentials clientCredentials) {
        tls.clientCredentials(clientCredentials);
        return this;
    }

    public FtpClient trustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
        return this;
    }

    public FtpClient keystoreFile(Path path) {
        tls.keystoreFile(path);
        return this;
    }

    public FtpClient keystorePass(String password) {
        tls.keystorePass(password);
        return this;
    }

    public FtpClient keystoreFormat(String format) {
        tls.keystoreFormat(format);
        return this;
    }

    public FtpClient host(String host) {
        dial.host(host);
        return this;
    }

    public FtpClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    public FtpClient port(int port) {
        dial.port(port);
        return this;
    }

    public FtpClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    public FtpClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    public FtpClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    public FtpClient sessionProvider(FtpClientSessionProvider provider) {
        setSessionProvider(provider);
        return this;
    }

    public FtpClient sessionPerConnection(Supplier<RemoteGreeting> supplier) {
        return sessionProvider(FtpClientSessionProviders.perSession(supplier));
    }

    public void connect(RemoteGreeting handler) {
        dial.requireTarget();
        transportFactory = new TcpTransportFactory();
        endpointHandler = new FtpClientProtocolHandler(handler);
        try {
            ClientTlsConfig effective = ClientConnect.prepareTls(tls, transportFactory);
            endpointHandler.setSecure(effective.useImplicitTls());
            if (effective.getClientCredentials() != null) {
                endpointHandler.setClientCredentials(effective.getClientCredentials());
            }
            clientEndpoint = ClientConnect.openAndConnect(
                    dial, transportFactory, endpointHandler);
        } catch (IOException e) {
            handler.onError(e);
        }
    }

    public void connect(FtpClientSessionProvider provider) {
        connect(provider.openSession());
    }

    public void connect() {
        if (sessionProvider == null) {
            throw new IllegalStateException(
                    "sessionProvider is required; use .sessionProvider(...)"
                            + " or connect(RemoteGreeting)");
        }
        connect(sessionProvider);
    }

    public FtpClientSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    public void setSessionProvider(FtpClientSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
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
