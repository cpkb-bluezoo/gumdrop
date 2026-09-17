/*
 * SimpleRelaySessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.smtp.SimpleRelayHandler;

/**
 * Stock {@link SmtpServerSessionProvider} for MX-based open relay.
 *
 * <p>Accepts mail for any domain and forwards via MX lookups using a shared
 * {@link DnsResolver}. Compose explicitly — this is an open relay and must not
 * be the default for an unconfigured {@link SmtpServer}.
 *
 * <pre>{@code
 * SmtpServer server = SmtpServer.compose()
 *         .listener(new SmtpListener().port(25).bindWildcard())
 *         .sessionProvider(new SimpleRelaySessionProvider()
 *                 .hostname("relay.example.com")
 *                 .server(InetAddress.ofLiteral("8.8.8.8")))
 *         .server();
 * }</pre>
 *
 * @see SimpleRelayHandler
 */
public final class SimpleRelaySessionProvider implements SmtpServerSessionProvider {

    private static final Logger LOGGER =
            Logger.getLogger(SimpleRelaySessionProvider.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.smtp.L10N");

    private String hostname;
    private String legacyDnsServer;
    private final List<InetAddress> servers = new ArrayList<InetAddress>();
    private long timeoutMs = 5000;
    private DnsResolver dnsResolver;
    private org.bluezoo.gumdrop.Gumdrop gumdrop;

    /**
     * Sets the local hostname advertised in EHLO.
     *
     * @param hostname the hostname
     * @return this provider
     */
    public SimpleRelaySessionProvider hostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * Adds a DNS server for MX lookups (literal address, no hostname lookup).
     *
     * @param address the resolver address
     * @return this provider
     */
    public SimpleRelaySessionProvider server(InetAddress address) {
        if (address == null) {
            throw new NullPointerException("address");
        }
        servers.add(address);
        return this;
    }

    /**
     * Replaces the DNS server list used for MX lookups.
     *
     * @param addresses the resolver addresses
     * @return this provider
     */
    public SimpleRelaySessionProvider servers(InetAddress... addresses) {
        servers.clear();
        if (addresses != null) {
            for (int i = 0; i < addresses.length; i++) {
                server(addresses[i]);
            }
        }
        return this;
    }

    /**
     * Sets the DNS query timeout in milliseconds.
     *
     * @param timeoutMs the timeout
     * @return this provider
     */
    public SimpleRelaySessionProvider timeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    /**
     * Legacy XML configuration: DNS server by hostname or IP string.
     *
     * @param dnsServer the DNS server address
     * @deprecated use {@link #server(InetAddress)} or {@link #servers(InetAddress...)}
     */
    @Deprecated
    public SimpleRelaySessionProvider dnsServer(String dnsServer) {
        this.legacyDnsServer = dnsServer;
        return this;
    }

    /**
     * Returns the configured EHLO hostname, or {@code null} if unset.
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Returns the legacy DNS server string, or {@code null}.
     */
    public String getDnsServer() {
        return legacyDnsServer;
    }

    /**
     * Returns the configured query timeout in milliseconds.
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Sets the runtime whose worker loop the resolver's UDP transport
     * binds to. Called by {@code SmtpServer.start(Gumdrop)} before {@link
     * #start()} runs (this provider is shared across the listeners a
     * single server configuration serves, so it has no owning connection
     * of its own to read this from).
     *
     * @param gumdrop the owning runtime, or null if none is running
     */
    public void setGumdrop(org.bluezoo.gumdrop.Gumdrop gumdrop) {
        this.gumdrop = gumdrop;
    }

    @Override
    public void start() {
        if (dnsResolver != null) {
            return;
        }
        if (hostname == null) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostname = "localhost";
            }
        }

        dnsResolver = new DnsResolver().timeoutMs(timeoutMs);
        if (!servers.isEmpty()) {
            for (int i = 0; i < servers.size(); i++) {
                dnsResolver.server(servers.get(i));
            }
        } else if (legacyDnsServer != null) {
            try {
                dnsResolver.addServer(legacyDnsServer);
            } catch (UnknownHostException e) {
                LOGGER.log(Level.WARNING, "Invalid DNS server: " + legacyDnsServer, e);
            }
        } else {
            dnsResolver.useSystemResolvers();
        }

        // No live Gumdrop (e.g. a unit test exercising this provider's
        // wiring directly, via start()/openSession() with no server
        // actually running): leave the resolver unopened rather than
        // failing start() outright -- openSession() below still returns a
        // working handler, it just cannot resolve until a real Gumdrop is
        // set. UdpDnsClientTransport.open() requires a SelectorLoop owned
        // by a running Gumdrop, which is exactly what setGumdrop() above
        // supplies in the real composed-server path.
        if (gumdrop != null) {
            try {
                dnsResolver.setSelectorLoop(gumdrop.nextWorkerLoop());
                dnsResolver.open();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        L10N.getString("err.dns_resolver_init_failed"), e);
                throw new RuntimeException(
                        L10N.getString("err.dns_resolver_init_failed"), e);
            }
        }

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.simple_relay_service_initialized"), hostname));
        }
    }

    @Override
    public void stop() {
        if (dnsResolver != null) {
            dnsResolver.close();
            dnsResolver = null;
        }
    }

    @Override
    public ClientConnected openSession(TcpListener listener) {
        if (dnsResolver == null) {
            throw new IllegalStateException(
                    "SimpleRelaySessionProvider not started; add to SmtpServer.compose()"
                            + " or call start()");
        }
        return new SimpleRelayHandler(dnsResolver, hostname);
    }

}
