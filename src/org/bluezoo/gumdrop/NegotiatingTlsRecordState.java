/*
 * NegotiatingTlsRecordState.java
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
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.tls.AlertDescription;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12RecordEngine;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.tls.TlsVersionPick;

/**
 * One TCP TLS connection: pick TLS 1.2 or 1.3 from the first handshake
 * flight (prefer 1.3), then delegate to {@link TlsRecordState} or
 * {@link Tls12RecordState} for the rest of the connection.
 */
final class NegotiatingTlsRecordState {

    private static final int DEFAULT_BUFFER_SIZE = 32768;

    private final HandshakeConfig config13;
    private final Tls12HandshakeConfig config12;
    private final TlsVersion versionPolicy;
    private final TcpEndpoint tcpEndpoint;
    private final TlsRecordState.Callback callback;
    private final boolean clientMode;

    private final ByteArrayOutputStream prefixBuffer = new ByteArrayOutputStream();

    private TlsRecordState active13;
    private Tls12RecordState active12;
    /** Client-only TLS 1.3 probe until ServerHello is seen. */
    private TlsRecordState clientProbe;

    NegotiatingTlsRecordState(HandshakeConfig config13, Tls12HandshakeConfig config12,
            TlsVersion versionPolicy, TcpEndpoint tcpEndpoint, TlsRecordState.Callback callback,
            boolean clientMode) {
        this.config13 = config13;
        this.config12 = config12;
        this.versionPolicy = versionPolicy;
        this.tcpEndpoint = tcpEndpoint;
        this.callback = callback;
        this.clientMode = clientMode;
    }

    int getBufferSize() {
        return DEFAULT_BUFFER_SIZE;
    }

    void startClientHandshake() {
        if (!clientMode || active13 != null || active12 != null) {
            return;
        }
        clientProbe = new TlsRecordState(config13, tcpEndpoint, callback);
        clientProbe.startClientHandshake();
    }

    void unwrap() {
        if (active13 != null) {
            active13.unwrap();
            return;
        }
        if (active12 != null) {
            active12.unwrap();
            return;
        }
        boolean activated = false;
        try {
            synchronized (tcpEndpoint.tlsEngineLock) {
                ByteBuffer in = tcpEndpoint.netIn;
                if (in == null) {
                    return;
                }
                appendFromNetIn(in);
                if (!tryActivate()) {
                    return;
                }
                activated = true;
                byte[] prefix = prefixBuffer.toByteArray();
                prefixBuffer.reset();
                if (active13 != null) {
                    active13.feedCiphertext(prefix);
                } else if (active12 != null) {
                    active12.feedCiphertext(prefix);
                }
            }
        } finally {
            ByteBuffer in = tcpEndpoint.netIn;
            if (in != null) {
                in.compact();
            }
        }
        if (activated) {
            if (active13 != null) {
                active13.unwrap();
            } else if (active12 != null) {
                active12.unwrap();
            }
        }
    }

    void wrap(ByteBuffer data) {
        if (active13 != null) {
            active13.wrap(data);
            return;
        }
        if (active12 != null) {
            active12.wrap(data);
            return;
        }
        // Handshake still in progress; drop app data until version is picked.
    }

    void closeOutbound() {
        if (active13 != null) {
            active13.closeOutbound();
            return;
        }
        if (active12 != null) {
            active12.closeOutbound();
            return;
        }
    }

    TlsRecordEngine getActiveTls13Engine() {
        if (active13 != null) {
            return active13.getEngine();
        }
        if (clientProbe != null) {
            return clientProbe.getEngine();
        }
        return null;
    }

    Tls12RecordEngine getActiveTls12Engine() {
        return active12 != null ? active12.getEngine() : null;
    }

    boolean isTls13Active() {
        return active13 != null;
    }

    private void appendFromNetIn(ByteBuffer in) {
        if (in.hasArray()) {
            int offset = in.arrayOffset() + in.position();
            int length = in.remaining();
            prefixBuffer.write(in.array(), offset, length);
            in.position(in.limit());
        } else {
            byte[] chunk = new byte[in.remaining()];
            in.get(chunk);
            prefixBuffer.write(chunk, 0, chunk.length);
        }
    }

    private boolean tryActivate() {
        if (active13 != null || active12 != null) {
            return true;
        }
        byte[] buf = prefixBuffer.toByteArray();
        try {
            if (clientMode) {
                TlsVersionPick.Picked pick = TlsVersionPick.findServerHelloInRecords(buf);
                if (pick == null) {
                    return false;
                }
                if (!versionPolicy.allowsClientPick(pick)) {
                    protocolError("TLS version not permitted by local policy");
                    return true;
                }
                if (pick == TlsVersionPick.Picked.V13) {
                    active13 = clientProbe;
                    clientProbe = null;
                } else {
                    TlsRecordState probe = clientProbe;
                    clientProbe = null;
                    byte[] ch = probe.getEngine().getClientHelloOutboundWire();
                    active12 = new Tls12RecordState(config12, tcpEndpoint, callback);
                    active12.getEngine().clientNoteClientHelloSent(ch);
                }
                return true;
            }
            TlsVersionPick.Picked pick = TlsVersionPick.findClientHelloInRecords(buf);
            if (pick == null) {
                return false;
            }
            if (!versionPolicy.allowsServerPick(pick)) {
                protocolError("TLS version not permitted by local policy");
                return true;
            }
            if (pick == TlsVersionPick.Picked.V13) {
                active13 = new TlsRecordState(config13, tcpEndpoint, callback);
            } else {
                active12 = new Tls12RecordState(config12, tcpEndpoint, callback);
            }
            return true;
        } catch (Exception e) {
            protocolError("unsupported TLS version in handshake");
            return true;
        }
    }

    private void protocolError(String message) {
        callback.onProtocolError(new TlsProtocolError(AlertDescription.PROTOCOL_VERSION, message));
    }
}
