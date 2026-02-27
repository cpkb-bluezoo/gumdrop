/*
 * UDPTransportFactory.java
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

package org.bluezoo.gumdrop;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * UDP transport factory.
 *
 * <p>Creates {@link UDPEndpoint} instances for both server-side
 * (bound) and client-side (connected) datagram channels.
 *
 * <p>For DTLS, this factory creates a JSSE SSLContext configured for
 * DTLSv1.2.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see UDPEndpoint
 * @see TransportFactory
 */
public class UDPTransportFactory extends TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(UDPTransportFactory.class.getName());

    protected SSLContext dtlsContext;

    public UDPTransportFactory() {
    }

    @Override
    public void start() {
        super.start();

        if (secure && (keystoreFile == null || keystorePass == null)) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new RuntimeException(
                    "Secure UDP factory requires keystore: " + message);
        }

        if (keystoreFile != null && keystorePass != null) {
            try {
                KeyStore ks = KeyStore.getInstance(keystoreFormat);
                InputStream in = new FileInputStream(keystoreFile.toFile());
                char[] pass = keystorePass.toCharArray();
                ks.load(in, pass);
                in.close();
                KeyManagerFactory f = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, pass);
                dtlsContext = SSLContext.getInstance("DTLSv1.2");
                KeyManager[] km = f.getKeyManagers();
                TrustManager[] tm = loadTrustManagers();
                SecureRandom random = new SecureRandom();
                dtlsContext.init(km, tm, random);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException(
                        "Failed to initialise DTLS context");
                e2.initCause(e);
                throw e2;
            }
        }
    }

    /**
     * Loads TrustManagers from the configured truststore.
     * If no truststore is configured, returns null (JVM default truststore).
     */
    private TrustManager[] loadTrustManagers() throws Exception {
        if (truststoreFile != null && truststorePass != null) {
            KeyStore ts = KeyStore.getInstance(truststoreFormat);
            try (InputStream in = new FileInputStream(
                    truststoreFile.toFile())) {
                ts.load(in, truststorePass.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            return tmf.getTrustManagers();
        }
        return null;
    }

    /**
     * Creates a server-side UDPEndpoint bound to a local port.
     *
     * @param bindAddress the local address to bind to, or null for wildcard
     * @param port the local port
     * @param handler the protocol handler
     * @return the new endpoint
     * @throws IOException if the channel cannot be opened or bound
     */
    public UDPEndpoint createServerEndpoint(InetAddress bindAddress,
                                                  int port,
                                                  ProtocolHandler handler)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(false);

        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        InetSocketAddress socketAddress =
                new InetSocketAddress(bindAddress, port);
        channel.bind(socketAddress);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        handler.connected(endpoint);

        return endpoint;
    }

    /**
     * Creates a server-side UDPEndpoint from a pre-configured channel.
     *
     * <p>Use this when the channel requires custom configuration before
     * registration (e.g., multicast group membership). The channel must
     * already be bound and set to non-blocking mode.
     *
     * @param channel the pre-configured datagram channel
     * @param handler the protocol handler
     * @return the new endpoint
     * @throws IOException if registration fails
     */
    public UDPEndpoint createServerEndpoint(DatagramChannel channel,
                                                  ProtocolHandler handler)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(false);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        handler.connected(endpoint);

        return endpoint;
    }

    /**
     * Creates a client-side UDPEndpoint connected to a remote address.
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the protocol handler
     * @return the new endpoint
     * @throws IOException if the channel cannot be opened
     */
    public UDPEndpoint connect(InetAddress host, int port,
                                    ProtocolHandler handler)
            throws IOException {
        return connect(host, port, handler, null);
    }

    /**
     * Creates a client-side UDPEndpoint connected to a remote address,
     * registered with the given SelectorLoop.
     *
     * <p>When {@code loop} is non-null, the endpoint's I/O runs on that
     * SelectorLoop (e.g. for affinity with an existing service). When null,
     * a worker loop is obtained from Gumdrop.
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the protocol handler
     * @param loop the SelectorLoop to register with, or null
     * @return the new endpoint
     * @throws IOException if the channel cannot be opened
     */
    public UDPEndpoint connect(InetAddress host, int port,
                                    ProtocolHandler handler,
                                    SelectorLoop loop)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(true);

        InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
        endpoint.setRemoteAddress(remoteAddress);

        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(remoteAddress);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop = (loop != null) ? loop : gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        handler.connected(endpoint);

        return endpoint;
    }

    @Override
    protected String getDescription() {
        return "UDP";
    }
}
