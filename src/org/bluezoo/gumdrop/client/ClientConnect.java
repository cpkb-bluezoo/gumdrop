/*
 * ClientConnect.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import java.io.IOException;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.tls.ClientTlsConfig;

/**
 * Shared TCP connect path for {@link ClientDial} + {@link ClientTlsConfig} facades.
 */
public final class ClientConnect {

    private ClientConnect() {
    }

    /**
     * Applies {@link ClientDefaults#effectiveTls}, configures the factory, and
     * starts it. Does not connect.
     */
    public static ClientTlsConfig prepareTls(ClientTlsConfig tls,
                                           TcpTransportFactory factory) {
        ClientTlsConfig effective = ClientDefaults.effectiveTls(tls);
        effective.applyTo(factory);
        factory.start();
        return effective;
    }

    public static ClientEndpoint openAndConnect(ClientDial dial,
                                                TcpTransportFactory factory,
                                                ProtocolHandler handler)
            throws IOException {
        ClientEndpoint endpoint = dial.openEndpoint(factory);
        endpoint.connect(handler);
        return endpoint;
    }

    /**
     * Full TCP dial: effective TLS, endpoint open, connect.
     *
     * @return the effective TLS config
     */
    public static ClientTlsConfig connect(ClientDial dial,
                                          ClientTlsConfig tls,
                                          TcpTransportFactory factory,
                                          ProtocolHandler handler)
            throws IOException {
        ClientTlsConfig effective = prepareTls(tls, factory);
        openAndConnect(dial, factory, handler);
        return effective;
    }

}
