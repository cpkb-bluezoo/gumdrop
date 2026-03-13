/*
 * TCPDNSClientTransport.java
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

package org.bluezoo.gumdrop.dns.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPEndpoint;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * TCP transport for DNS client queries using standard DNS-over-TCP framing.
 * RFC 1035 section 4.2.2: each DNS message is prefixed with a 2-byte
 * big-endian length field.
 * RFC 7858 section 3.1: when secure (DoT), connects to port 853 over TLS.
 * RFC 7858 section 3.3: all DoT messages use the same 2-octet length framing.
 *
 * <p>By default this is a plain TCP transport on port 53. When configured
 * with {@link #setSecure(boolean) setSecure(true)}, it becomes a
 * DNS-over-TLS (DoT) transport on port 853.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DNSClientTransport
 */
public class TCPDNSClientTransport implements DNSClientTransport {

    // RFC 1035 section 4.2.2: DNS-over-TCP on port 53
    private static final int DEFAULT_TCP_PORT = 53;
    // RFC 7858 section 3.1: DNS-over-TLS on port 853
    private static final int DEFAULT_DOT_PORT = 853;
    // RFC 1035 section 4.2.2: 2-byte length prefix
    private static final int LENGTH_PREFIX_SIZE = 2;
    // RFC 1035 section 4.2.2: max message size 65535 octets
    private static final int MAX_DNS_MESSAGE_SIZE = 65535;

    private boolean secure;
    private int defaultPort = DEFAULT_TCP_PORT;
    private TCPEndpoint endpoint;

    /**
     * RFC 7858 section 4.2: SPKI fingerprints for the Strict usage profile.
     * When set, the server certificate's SubjectPublicKeyInfo hash is
     * verified against these pins after TLS handshake.
     */
    private java.util.Set<String> spkiFingerprints;

    /**
     * Returns a transport configured for DNS-over-TLS (port 853, TLS enabled).
     */
    public static TCPDNSClientTransport createDoT() {
        TCPDNSClientTransport transport = new TCPDNSClientTransport();
        transport.setSecure(true);
        return transport;
    }

    /**
     * Sets whether this transport uses TLS.
     * When true, the default port changes to 853 (DoT).
     *
     * @param secure true for TLS
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
        this.defaultPort = secure ? DEFAULT_DOT_PORT : DEFAULT_TCP_PORT;
    }

    /**
     * RFC 7858 section 4.2: sets the SPKI fingerprints for the Strict
     * usage profile. When set, after the TLS handshake the server's
     * leaf certificate SubjectPublicKeyInfo is hashed with SHA-256 and
     * compared against these pins.
     *
     * @param fingerprints the SPKI SHA-256 fingerprints
     *                     (colon-separated lowercase hex)
     */
    public void setPinnedSPKIFingerprints(java.util.Set<String> fingerprints) {
        this.spkiFingerprints = fingerprints;
    }

    /**
     * Overrides the default port.
     *
     * @param port the default port to use when the caller passes port &lt;= 0
     */
    public void setDefaultPort(int port) {
        this.defaultPort = port;
    }

    @Override
    public void open(InetAddress server, int port, SelectorLoop loop,
                     DNSClientTransportHandler handler) throws IOException {
        TCPTransportFactory factory = new TCPTransportFactory();
        if (secure) {
            factory.setSecure(true);
            // RFC 7858 section 3.4, RFC 7413: use TCP Fast Open
            // when re-establishing DoT connections.
            factory.setTcpFastOpen(true);
        }
        // RFC 7858 section 4.2: SPKI fingerprint verification
        if (spkiFingerprints != null && !spkiFingerprints.isEmpty()) {
            factory.setTrustManager(
                    new org.bluezoo.gumdrop.util
                            .SPKIPinnedCertTrustManager(
                            spkiFingerprints.toArray(
                                    new String[0])));
        }
        factory.start();
        if (port <= 0) {
            port = defaultPort;
        }
        this.endpoint = factory.connect(server, port,
                new TCPProtocolHandler(handler), loop);
    }

    // RFC 1035 section 4.2.2: prefix each message with 2-byte big-endian length
    @Override
    public void send(ByteBuffer data) {
        int length = data.remaining();
        ByteBuffer frame = ByteBufferPool.acquire(LENGTH_PREFIX_SIZE + length);
        try {
            frame.put((byte) ((length >> 8) & 0xFF));
            frame.put((byte) (length & 0xFF));
            frame.put(data);
            frame.flip();
            endpoint.send(frame);
        } finally {
            ByteBufferPool.release(frame);
        }
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return endpoint.scheduleTimer(delayMs, callback);
    }

    @Override
    public void close() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    /**
     * Handles TCP framing and delegates reassembled DNS messages to
     * the transport handler.
     */
    private static class TCPProtocolHandler implements ProtocolHandler {

        private final DNSClientTransportHandler handler;
        private ByteBuffer accumulator;

        TCPProtocolHandler(DNSClientTransportHandler handler) {
            this.handler = handler;
        }

        @Override
        public void connected(Endpoint ep) {
        }

        @Override
        public void receive(ByteBuffer data) {
            appendToAccumulator(data);

            while (accumulator.remaining() >= LENGTH_PREFIX_SIZE) {
                accumulator.mark();
                int messageLength =
                        ((accumulator.get() & 0xFF) << 8)
                        | (accumulator.get() & 0xFF);
                if (messageLength <= 0
                        || messageLength > MAX_DNS_MESSAGE_SIZE) {
                    handler.onError(new IOException(
                            "Invalid DNS message length: " + messageLength));
                    return;
                }
                if (accumulator.remaining() < messageLength) {
                    accumulator.reset();
                    break;
                }
                ByteBuffer messageBuf = accumulator.slice();
                messageBuf.limit(messageLength);
                accumulator.position(
                        accumulator.position() + messageLength);
                handler.onReceive(messageBuf);
            }
            compact();
        }

        @Override
        public void disconnected() {
            if (accumulator != null) {
                ByteBufferPool.release(accumulator);
                accumulator = null;
            }
            handler.onError(new IOException("Connection closed by server"));
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void error(Exception cause) {
            if (accumulator != null) {
                ByteBufferPool.release(accumulator);
                accumulator = null;
            }
            handler.onError(cause);
        }

        private void appendToAccumulator(ByteBuffer data) {
            int dataRemaining = data.remaining();
            if (accumulator == null) {
                accumulator = ByteBufferPool.acquire(dataRemaining);
                accumulator.put(data);
                accumulator.flip();
            } else {
                int currentRemaining = accumulator.remaining();
                int needed = currentRemaining + dataRemaining;
                int newCapacity = Math.max(needed, (currentRemaining << 1) + dataRemaining);
                ByteBuffer newBuf = ByteBufferPool.acquire(newCapacity);
                newBuf.put(accumulator);
                newBuf.put(data);
                newBuf.flip();
                ByteBufferPool.release(accumulator);
                accumulator = newBuf;
            }
        }

        private void compact() {
            if (accumulator != null && !accumulator.hasRemaining()) {
                ByteBufferPool.release(accumulator);
                accumulator = null;
            } else if (accumulator != null && accumulator.position() > 0) {
                accumulator.compact();
                accumulator.flip();
            }
        }
    }

}
