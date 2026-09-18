/*
 * FtpClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.ftp.client;

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
 * High-level FTP client facade.
 */
public class FtpClient {

    private final ClientDial dial = ClientDial.withDefaultPort(21);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private FtpClientProtocolHandler endpointHandler;

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

    public FtpClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public FtpClient trustJvm() {
        tls.trustJvm();
        return this;
    }

    public FtpClient clientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
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

    public void connect(Gumdrop gumdrop, RemoteGreeting handler) {
        dial.requireTarget();
        transportFactory = new TcpTransportFactory();
        endpointHandler = new FtpClientProtocolHandler(handler);
        endpointHandler.setGumdrop(gumdrop);
        try {
            TlsConfig effective = ClientConnect.prepareTls(secure, tls, transportFactory);
            endpointHandler.setSecure(secure);
            if (effective.getServerCredentials() != null) {
                endpointHandler.setClientCredentials(effective.getServerCredentials());
            }
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
