/*
 * RedisClient.java
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

import org.bluezoo.gumdrop.tls.KeystoreFormat;
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
 * High-level Redis client facade.
 *
 * <p>This class provides a simple, concrete API for connecting to Redis servers
 * using the RESP (Redis Serialization Protocol) wire format. It internally
 * creates a {@link TcpTransportFactory}, {@link ClientEndpoint}, and
 * {@link RedisClientProtocolHandler}, wiring them together and forwarding
 * lifecycle events to the caller's {@link RedisConnectionReady} handler.
 *
 * <h4>Basic Usage</h4>
 * <pre>{@code
 * RedisClient client = new RedisClient()
 *         .host("localhost")
 *         .port(6379);
 * client.connect(new RedisConnectionReady() {
 *     public void handleReady(RedisSession session) {
 *         session.set("key", "value", handler);
 *     }
 *     public void onConnected(Endpoint endpoint) { }
 *     public void onSecurityEstablished(SecurityInfo info) { }
 *     public void onError(Exception cause) { cause.printStackTrace(); }
 *     public void onDisconnected() { }
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see RedisConnectionReady
 * @see RedisClientProtocolHandler
 */
public class RedisClient {

    private final ClientDial dial = ClientDial.withDefaultPort(6379);
    private final TlsConfig tls = new TlsConfig();
    private boolean secure;

    private TcpTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private RedisClientProtocolHandler endpointHandler;
    private boolean connected;

    /** Creates a client for fluent dial configuration before {@link #connect}. */
    public RedisClient() {
    }

    public RedisClient(String host, int port) {
        this(null, host, port);
    }

    public RedisClient(SelectorLoop selectorLoop, String host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public RedisClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public RedisClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        dial.selectorLoop(selectorLoop).host(host).port(port);
    }

    public RedisClient(String socketPath) {
        this(null, socketPath);
    }

    public RedisClient(SelectorLoop selectorLoop, String socketPath) {
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

    public void setKeystorePass(String password) {
        tls.keystorePass(password);
    }

    public void setKeystoreFormat(KeystoreFormat format) {
        tls.keystoreFormat(format);
    }

    public RedisClient host(String host) {
        dial.host(host);
        return this;
    }

    public RedisClient host(InetAddress hostAddress) {
        dial.host(hostAddress);
        return this;
    }

    public RedisClient port(int port) {
        dial.port(port);
        return this;
    }

    public RedisClient socketPath(String socketPath) {
        dial.socketPath(socketPath);
        return this;
    }

    public RedisClient selectorLoop(SelectorLoop selectorLoop) {
        dial.selectorLoop(selectorLoop);
        return this;
    }

    public RedisClient dnsResolver(DnsResolver dnsResolver) {
        dial.dnsResolver(dnsResolver);
        return this;
    }

    public RedisClient secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public RedisClient clientCredentials(ServerCredentials clientCredentials) {
        tls.serverCredentials(clientCredentials);
        return this;
    }

    public RedisClient trustManager(X509TrustManager trustManager) {
        tls.trustManager(trustManager);
        return this;
    }

    public RedisClient keystoreFile(Path path) {
        tls.keystoreFile(path);
        return this;
    }

    public RedisClient keystorePass(String password) {
        tls.keystorePass(password);
        return this;
    }

    public RedisClient keystoreFormat(KeystoreFormat format) {
        tls.keystoreFormat(format);
        return this;
    }

    public ClientDial getDial() {
        return dial;
    }

    public TlsConfig getTls() {
        return tls;
    }

    /**
     * Connects to the remote Redis server.
     */
    public void connect(Gumdrop gumdrop, RedisConnectionReady handler) {
        transportFactory = new TcpTransportFactory();
        endpointHandler = new RedisClientProtocolHandler(handler);

        ClientConnect.discoverEch(gumdrop, secure, dial, tls, new ClientConnect.EchDiscoveryCallback() {
            @Override
            public void discovered(byte[] echConfigList) {
                try {
                    ClientConnect.prepareTls(secure, tls, transportFactory, echConfigList);
                    clientEndpoint = ClientConnect.openAndConnect(
                            gumdrop, dial, transportFactory, endpointHandler);
                    connected = true;
                } catch (IOException e) {
                    handler.onError(e);
                }
            }
        });
    }

    public boolean isOpen() {
        return connected && endpointHandler != null;
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
