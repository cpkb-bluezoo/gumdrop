/*
 * MDNSListener.java
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

package org.bluezoo.gumdrop.mdns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Listener;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.UDPEndpoint;
import org.bluezoo.gumdrop.UDPTransportFactory;

/**
 * UDP multicast transport listener for multicast DNS (RFC 6762).
 *
 * <p>Unlike {@link org.bluezoo.gumdrop.dns.DNSListener}, this endpoint
 * binds a single shared datagram socket on port 5353 and joins the
 * mDNS IPv4 multicast group ({@code 224.0.0.251}) on every active,
 * multicast-capable, non-loopback network interface, rather than a
 * single unicast address. (IPv6 multicast, {@code ff02::fb}, is not
 * yet supported &mdash; see the RFC 6762 phase plan.)
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MDNSService
 */
public class MDNSListener extends Listener {

    private static final Logger LOGGER =
            Logger.getLogger(MDNSListener.class.getName());

    static final int DEFAULT_PORT = 5353;

    /** RFC 6762 section 3: the mDNS IPv4 multicast group address. */
    static final String MDNS_GROUP_ADDRESS = "224.0.0.251";

    /**
     * RFC 6762 section 11: all mDNS packets are sent with IP TTL 255,
     * so that a receiver can tell a packet genuinely originated on the
     * local link (as opposed to being forwarded from elsewhere).
     */
    private static final int MULTICAST_TTL = 255;

    private int port = DEFAULT_PORT;
    private MDNSService service;
    private UDPTransportFactory transportFactory;
    private UDPEndpoint endpoint;
    private InetAddress group;
    private InetSocketAddress groupAddress;

    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port number this endpoint should bind to.
     *
     * @param port the port number (default 5353)
     */
    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String getDescription() {
        return "mdns";
    }

    /**
     * Sets the owning mDNS service. Called by {@link MDNSService}
     * during wiring.
     *
     * @param service the owning service
     */
    void setService(MDNSService service) {
        this.service = service;
    }

    /**
     * Returns the owning service, or null if used standalone.
     *
     * @return the owning service
     */
    public MDNSService getService() {
        return service;
    }

    /**
     * Returns true if this listener successfully bound its datagram
     * endpoint. {@link #start()} logs and swallows bind failures
     * (matching {@link org.bluezoo.gumdrop.dns.DNSListener}'s
     * convention) rather than throwing, so callers that need to know
     * whether starting actually worked &mdash; e.g. {@link MDNSService}
     * deciding whether it's safe to begin probing &mdash; must check
     * this explicitly instead of relying on {@code start()} throwing.
     *
     * @return true if bound
     */
    boolean isBound() {
        return endpoint != null;
    }

    @Override
    public void start() {
        try {
            group = InetAddress.getByName(MDNS_GROUP_ADDRESS);
        } catch (UnknownHostException e) {
            // Not reachable: MDNS_GROUP_ADDRESS is a literal IP.
            throw new AssertionError(e);
        }
        groupAddress = new InetSocketAddress(group, port);

        transportFactory = new UDPTransportFactory();
        transportFactory.start();

        try {
            DatagramChannel channel =
                    DatagramChannel.open(StandardProtocolFamily.INET);
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(port));
            channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL,
                    MULTICAST_TTL);

            int joined = joinAllInterfaces(channel);
            if (joined == 0) {
                LOGGER.warning(MDNSService.L10N.getString(
                        "warn.mdns_no_multicast_interface"));
            }

            endpoint = transportFactory.createServerEndpoint(
                    channel, new MDNSDatagramHandler());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "Failed to bind mDNS datagram endpoint on port " + port,
                    e);
        }
    }

    /**
     * Joins the mDNS multicast group on every up, multicast-capable,
     * non-loopback, non-point-to-point interface that has an IPv4
     * address. RFC 6762 doesn't restrict mDNS to a single interface, so
     * (unlike e.g. {@code Cluster}'s single-interface join, which
     * serves a different purpose) every eligible interface is joined,
     * not just the first. The first interface joined is also set as
     * {@code IP_MULTICAST_IF} &mdash; without it, outgoing sends on a
     * multi-homed host follow the OS's default multicast route, which
     * can land on an interface that was never joined at all (observed
     * in practice with an always-up VPN tunnel adapter, producing
     * "No route to host" on every send); {@link #isEligible} also
     * excludes point-to-point interfaces outright, since a tunnel link
     * is never a genuine LAN broadcast domain mDNS should use.
     *
     * @param channel the bound, not-yet-joined channel
     * @return the number of interfaces successfully joined
     */
    private int joinAllInterfaces(DatagramChannel channel) throws IOException {
        int joined = 0;
        NetworkInterface outgoingInterface = null;
        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!isEligible(ni)) {
                continue;
            }
            try {
                channel.join(group, ni);
                joined++;
                if (outgoingInterface == null) {
                    outgoingInterface = ni;
                }
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    String msg = MessageFormat.format(MDNSService.L10N.getString(
                            "warn.mdns_join_failed"), ni.getName());
                    LOGGER.log(Level.FINE, msg, e);
                }
            }
        }
        if (outgoingInterface != null) {
            channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, outgoingInterface);
        }
        return joined;
    }

    private static boolean isEligible(NetworkInterface ni) throws SocketException {
        if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()
                || !ni.supportsMulticast()) {
            return false;
        }
        Enumeration<InetAddress> addresses = ni.getInetAddresses();
        while (addresses.hasMoreElements()) {
            if (addresses.nextElement() instanceof java.net.Inet4Address) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop() {
        if (service != null) {
            service.sendGoodbye(this);
        }
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
    }

    /**
     * Returns the address/port of the mDNS multicast group.
     *
     * @return the group address
     */
    InetSocketAddress getGroupAddress() {
        return groupAddress;
    }

    /**
     * Sends a message to the mDNS multicast group.
     *
     * @param data the serialised message
     */
    void sendToGroup(ByteBuffer data) {
        endpoint.sendTo(data, groupAddress);
    }

    /**
     * Sends a message to a specific unicast destination (used for QU
     * "unicast response requested" replies).
     *
     * @param data the serialised message
     * @param destination the target address
     */
    void sendTo(ByteBuffer data, InetSocketAddress destination) {
        endpoint.sendTo(data, destination);
    }

    /**
     * Schedules a callback on this listener's transport thread, the
     * same thread all inbound datagrams for this listener are
     * delivered on, so callers never need extra synchronization.
     *
     * @param delayMs delay in milliseconds
     * @param callback the callback to run
     * @return a handle allowing cancellation
     */
    TimerHandleWrapper scheduleTimer(long delayMs, Runnable callback) {
        return new TimerHandleWrapper(endpoint.scheduleTimer(delayMs, callback));
    }

    /**
     * Thin wrapper so callers in this package don't need to import
     * {@code org.bluezoo.gumdrop.TimerHandle} directly.
     */
    static final class TimerHandleWrapper {
        private final org.bluezoo.gumdrop.TimerHandle delegate;

        TimerHandleWrapper(org.bluezoo.gumdrop.TimerHandle delegate) {
            this.delegate = delegate;
        }

        void cancel() {
            delegate.cancel();
        }
    }

    /**
     * Inner handler that dispatches received datagrams to the owning
     * {@link MDNSService}.
     */
    private class MDNSDatagramHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint ep) {
            // endpoint is already bound and joined via MDNSListener.start()
        }

        @Override
        public void receive(ByteBuffer data) {
            if (service == null) {
                LOGGER.warning(MDNSService.L10N.getString("warn.mdns_no_service_set"));
                return;
            }
            InetSocketAddress source =
                    (InetSocketAddress) endpoint.getRemoteAddress();
            service.handleDatagram(MDNSListener.this, data, source);
        }

        @Override
        public void disconnected() {
            // Server endpoint closed
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            // no-op for plain UDP
        }

        @Override
        public void error(Exception cause) {
            LOGGER.log(Level.WARNING, "mDNS endpoint error", cause);
        }
    }

}
