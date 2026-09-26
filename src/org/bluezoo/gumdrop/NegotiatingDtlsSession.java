/*
 * NegotiatingDtlsSession.java
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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.DtlsVersionPick;
import org.bluezoo.gumdrop.tls.TlsVersionPick;

/**
 * One UDP peer: pick DTLS 1.2 or 1.3 from the first handshake flight, then
 * run exactly one {@link Dtls12Session} or {@link Dtls13Session}.
 */
final class NegotiatingDtlsSession {

    private final UdpEndpoint endpoint;
    private final InetSocketAddress remoteAddress;
    private final DtlsVersion versionPolicy;
    private final Dtls12HandshakeConfig config12;
    private final Dtls13HandshakeConfig config13;
    private final boolean clientMode;

    private final ByteArrayOutputStream prefixBuffer = new ByteArrayOutputStream();

    private Dtls12Session active12;
    private Dtls13Session active13;
    private Dtls13Session clientProbe;

    NegotiatingDtlsSession(UdpEndpoint endpoint, InetSocketAddress remoteAddress,
            DtlsVersion versionPolicy, Dtls12HandshakeConfig config12, Dtls13HandshakeConfig config13,
            boolean clientMode) {
        this.endpoint = endpoint;
        this.remoteAddress = remoteAddress;
        this.versionPolicy = versionPolicy;
        this.config12 = config12;
        this.config13 = config13;
        this.clientMode = clientMode;
        if (clientMode) {
            clientProbe = new Dtls13Session(config13, endpoint, remoteAddress);
        }
    }

    void beginHandshake() {
        if (clientProbe != null) {
            clientProbe.beginHandshake();
        }
    }

    void receive(byte[] datagram) {
        receive(datagram, 0, datagram.length);
    }

    void receive(byte[] datagram, int offset, int length) {
        if (active13 != null) {
            active13.receive(datagram, offset, length);
            return;
        }
        if (active12 != null) {
            active12.receive(datagram, offset, length);
            return;
        }
        prefixBuffer.write(datagram, offset, length);
        if (!tryActivate()) {
            return;
        }
        byte[] prefix = prefixBuffer.toByteArray();
        prefixBuffer.reset();
        if (active13 != null) {
            active13.receive(prefix);
        } else if (active12 != null) {
            active12.receive(prefix);
        }
    }

    void sendApplicationData(ByteBuffer data) {
        if (active13 != null) {
            if (data.hasArray()) {
                active13.sendApplicationData(data.array(),
                        data.arrayOffset() + data.position(), data.remaining());
            } else {
                byte[] plaintext = new byte[data.remaining()];
                data.get(plaintext);
                active13.sendApplicationData(plaintext);
            }
            return;
        }
        if (active12 != null) {
            active12.send(data);
        }
    }

    boolean isHandshakeComplete() {
        if (active13 != null) {
            return active13.isHandshakeComplete();
        }
        if (active12 != null) {
            return active12.isHandshakeComplete();
        }
        return false;
    }

    SecurityInfo getSecurityInfo() {
        if (active13 != null) {
            return active13.getSecurityInfo();
        }
        if (active12 != null) {
            return active12.getSecurityInfo();
        }
        return null;
    }

    void close() {
        if (active13 != null) {
            active13.close();
        } else if (active12 != null) {
            active12.close();
        } else if (clientProbe != null) {
            clientProbe.close();
        }
        endpoint.removeNegotiatingDtlsSession(remoteAddress);
    }

    private boolean tryActivate() {
        if (active12 != null || active13 != null) {
            return true;
        }
        byte[] buf = prefixBuffer.toByteArray();
        try {
            if (clientMode) {
                TlsVersionPick.Picked pick = DtlsVersionPick.findServerHelloInDtls(buf);
                if (pick == null) {
                    return false;
                }
                if (!versionPolicy.allowsClientPick(pick)) {
                    endpoint.onDtls13SessionFailed(remoteAddress,
                            new IllegalStateException("DTLS version not permitted by local policy"));
                    return true;
                }
                if (pick == TlsVersionPick.Picked.V13) {
                    active13 = clientProbe;
                    clientProbe = null;
                } else {
                    Dtls13Session probe = clientProbe;
                    clientProbe = null;
                    byte[] ch = probe.getRecordEngine().getClientHelloOutboundWire();
                    long seq = probe.getRecordEngine().plaintextWriteSeq();
                    active12 = new Dtls12Session(config12, endpoint, remoteAddress);
                    active12.clientContinueAfterTls13Probe(ch, seq);
                }
                return true;
            }
            TlsVersionPick.Picked pick = DtlsVersionPick.findClientHelloInDtls(buf);
            if (pick == null) {
                return false;
            }
            if (!versionPolicy.allowsServerPick(pick)) {
                endpoint.onDtls13SessionFailed(remoteAddress,
                        new IllegalStateException("DTLS version not permitted by local policy"));
                return true;
            }
            if (pick == TlsVersionPick.Picked.V13) {
                active13 = new Dtls13Session(config13, endpoint, remoteAddress);
            } else {
                active12 = new Dtls12Session(config12, endpoint, remoteAddress);
            }
            return true;
        } catch (Exception e) {
            endpoint.onDtls13SessionFailed(remoteAddress, e);
            return true;
        }
    }
}
