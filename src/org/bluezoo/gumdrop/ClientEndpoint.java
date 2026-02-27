/*
 * ClientEndpoint.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.dns.client.DNSResolver;
import org.bluezoo.gumdrop.dns.client.ResolveCallback;

/**
 * Transport-agnostic convenience class for creating client connections.
 *
 * <p>Wraps a {@link TransportFactory} and provides:
 * <ul>
 * <li>Host and port configuration</li>
 * <li>Standalone mode (auto-obtains a SelectorLoop from Gumdrop)</li>
 * <li>Server-integration mode (uses a caller-supplied SelectorLoop)</li>
 * <li>Transport-agnostic {@code connect()} that works with
 *     {@link TCPTransportFactory} or
 *     {@link org.bluezoo.gumdrop.quic.QuicTransportFactory}</li>
 * </ul>
 *
 * <p>This class does not create protocol-specific subclasses. The protocol
 * handler is an
 * {@link ProtocolHandler} that receives a transport-agnostic
 * {@link Endpoint} in its {@link ProtocolHandler#connected} callback.
 *
 * <h4>Standalone Usage</h4>
 * <pre>{@code
 * TCPTransportFactory factory = new TCPTransportFactory();
 * factory.setSecure(true);
 * factory.start();
 *
 * ClientEndpoint client = new ClientEndpoint(factory, "smtp.example.com", 465);
 * client.connect(new SMTPClientProtocolHandler(callback));
 * }</pre>
 *
 * <h4>Server Integration</h4>
 * <pre>{@code
 * SelectorLoop myLoop = endpoint.getSelectorLoop();
 * ClientEndpoint client = new ClientEndpoint(factory, myLoop, "smtp.example.com", 587);
 * client.connect(new SMTPClientProtocolHandler(callback));
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TransportFactory
 * @see ProtocolHandler
 */
public class ClientEndpoint {

    private static final Logger LOGGER =
            Logger.getLogger(ClientEndpoint.class.getName());

    private final TransportFactory factory;
    private final String hostname;
    private final int port;
    private InetAddress host;
    private SelectorLoop selectorLoop;
    private Gumdrop gumdrop;

    // ── Constructors with explicit SelectorLoop (server integration) ──

    /**
     * Creates a client with a specific SelectorLoop for I/O.
     *
     * <p>Use this constructor for server integration where the client
     * should share a SelectorLoop with existing server connections.
     * DNS resolution is deferred until {@link #connect} is called.
     *
     * @param factory the transport factory (must already be started)
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the target hostname or IP address
     * @param port the target port
     */
    public ClientEndpoint(TransportFactory factory,
                          SelectorLoop selectorLoop,
                          String host, int port) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (selectorLoop == null) {
            throw new NullPointerException("selectorLoop");
        }
        if (host == null) {
            throw new NullPointerException("host");
        }
        this.factory = factory;
        this.selectorLoop = selectorLoop;
        this.hostname = host;
        this.port = port;
    }

    /**
     * Creates a client with a specific SelectorLoop for I/O.
     *
     * @param factory the transport factory (must already be started)
     * @param selectorLoop the SelectorLoop for connection I/O
     * @param host the target host address
     * @param port the target port
     */
    public ClientEndpoint(TransportFactory factory,
                          SelectorLoop selectorLoop,
                          InetAddress host, int port) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (selectorLoop == null) {
            throw new NullPointerException("selectorLoop");
        }
        if (host == null) {
            throw new NullPointerException("host");
        }
        this.factory = factory;
        this.selectorLoop = selectorLoop;
        this.hostname = null;
        this.host = host;
        this.port = port;
    }

    // ── Constructors without SelectorLoop (standalone usage) ──

    /**
     * Creates a client without a SelectorLoop.
     *
     * <p>A SelectorLoop will be obtained automatically from the Gumdrop
     * infrastructure when {@link #connect} is called. DNS resolution is
     * deferred until {@link #connect} is called.
     *
     * @param factory the transport factory (must already be started)
     * @param host the target hostname or IP address
     * @param port the target port
     */
    public ClientEndpoint(TransportFactory factory,
                          String host, int port) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (host == null) {
            throw new NullPointerException("host");
        }
        this.factory = factory;
        this.selectorLoop = null;
        this.hostname = host;
        this.port = port;
    }

    /**
     * Creates a client without a SelectorLoop.
     *
     * @param factory the transport factory (must already be started)
     * @param host the target host address
     * @param port the target port
     */
    public ClientEndpoint(TransportFactory factory,
                          InetAddress host, int port) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (host == null) {
            throw new NullPointerException("host");
        }
        this.factory = factory;
        this.selectorLoop = null;
        this.hostname = null;
        this.host = host;
        this.port = port;
    }

    // ── Properties ──

    /**
     * Returns the transport factory.
     *
     * @return the factory
     */
    public TransportFactory getFactory() {
        return factory;
    }

    /**
     * Returns the target host address.
     *
     * @return the host
     */
    public InetAddress getHost() {
        return host;
    }

    /**
     * Returns the target port.
     *
     * @return the port
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the SelectorLoop used for I/O.
     *
     * <p>May return null before {@link #connect} if the client was
     * created without an explicit SelectorLoop.
     *
     * @return the SelectorLoop, or null
     */
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    // ── Connection ──

    /**
     * Initiates an asynchronous connection to the remote host.
     *
     * <p>The handler's {@link ProtocolHandler#connected(Endpoint)} callback
     * is invoked when the connection is ready for protocol traffic.
     * {@link ProtocolHandler#error(Exception)} is called if the
     * connection fails.
     *
     * <p>If no SelectorLoop was provided at construction time, one is
     * obtained from the Gumdrop infrastructure automatically (starting
     * Gumdrop if needed).
     *
     * <p>This method registers the client with Gumdrop for lifecycle
     * tracking. The client is automatically deregistered when the
     * connection terminates (disconnect or error), or when
     * {@link #close()} is called. When no clients, services, or
     * listeners remain, Gumdrop shuts down automatically.
     *
     * <p>The transport used depends on the factory:
     * <ul>
     * <li>{@link TCPTransportFactory} -- TCP connection (with optional
     *     TLS)</li>
     * <li>{@link org.bluezoo.gumdrop.quic.QuicTransportFactory} --
     *     QUIC connection with auto-opened initial stream</li>
     * </ul>
     *
     * @param handler the protocol handler
     * @throws IOException if the connection cannot be initiated
     */
    public void connect(final ProtocolHandler handler) throws IOException {
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        ensureSelectorLoop();
        gumdrop = Gumdrop.getInstance();
        gumdrop.addClient(this);

        final ProtocolHandler wrapped = wrapHandler(handler);

        if (hostname != null && host == null) {
            DNSResolver resolver = DNSResolver.forLoop(selectorLoop);
            resolver.resolve(hostname, new ResolveCallback() {
                @Override
                public void onResolved(List<InetAddress> addresses) {
                    host = addresses.get(0);
                    try {
                        doConnect(wrapped);
                    } catch (IOException e) {
                        wrapped.error(e);
                    }
                }

                @Override
                public void onError(String error) {
                    wrapped.error(new UnknownHostException(
                            hostname + ": " + error));
                }
            });
        } else {
            try {
                doConnect(wrapped);
            } catch (IOException e) {
                deregister();
                throw e;
            }
        }
    }

    /**
     * Closes this client endpoint, deregistering it from Gumdrop's
     * lifecycle tracking.
     *
     * <p>This method is idempotent. It does not close the underlying
     * transport connection; use the protocol handler or client facade's
     * {@code close()} method for that.
     */
    public void close() {
        deregister();
    }

    private void doConnect(ProtocolHandler handler) throws IOException {
        if (factory instanceof TCPTransportFactory) {
            ((TCPTransportFactory) factory).connect(
                    host, port, handler, selectorLoop);
        } else if (factory instanceof org.bluezoo.gumdrop.quic.QuicTransportFactory) {
            ((org.bluezoo.gumdrop.quic.QuicTransportFactory) factory).connect(
                    host, port, handler, selectorLoop, null);
        } else {
            throw new UnsupportedOperationException(
                    "Transport factory " + factory.getClass().getName()
                    + " does not support client connections");
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Connecting to " + host.getHostAddress()
                    + ":" + port + " via " + factory.getDescription());
        }
    }

    /**
     * Wraps a protocol handler to intercept terminal events
     * ({@code disconnected} and {@code error}) and automatically
     * deregister this client from Gumdrop's lifecycle tracking.
     */
    private ProtocolHandler wrapHandler(final ProtocolHandler handler) {
        return new ProtocolHandler() {
            @Override
            public void receive(ByteBuffer data) {
                handler.receive(data);
            }

            @Override
            public void connected(Endpoint endpoint) {
                handler.connected(endpoint);
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
                handler.securityEstablished(info);
            }

            @Override
            public void disconnected() {
                handler.disconnected();
                deregister();
            }

            @Override
            public void error(Exception cause) {
                handler.error(cause);
                deregister();
            }
        };
    }

    private void deregister() {
        if (gumdrop != null) {
            gumdrop.removeClient(this);
            gumdrop = null;
        }
    }

    /**
     * Ensures a SelectorLoop is available, obtaining one from Gumdrop
     * infrastructure if needed.
     */
    private void ensureSelectorLoop() {
        if (selectorLoop == null) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            gumdrop.start();
            selectorLoop = gumdrop.nextWorkerLoop();
        }
    }
}
