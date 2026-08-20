/*
 * QuicTransportParametersExtension.java
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

package org.bluezoo.gumdrop.quic.tls;

import java.nio.ByteBuffer;
import java.util.List;

import tech.kwik.agent15.extension.Extension;
import tech.kwik.agent15.extension.UnknownExtension;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Wraps {@link TransportParameters} as the TLS {@code quic_transport_parameters}
 * extension (RFC 9001 section 8.2), so Agent15 can carry it in ClientHello
 * (client) or EncryptedExtensions (server) without needing to know
 * anything about QUIC itself.
 *
 * <p>There is deliberately no custom {@code ExtensionParser} registered
 * with Agent15's {@code TlsMessageParser} for the receive direction:
 * Agent15 already surfaces an extension it doesn't recognise as an
 * {@code UnknownExtension} carrying the raw bytes, which is all
 * {@link QuicTlsClientEngine} and {@link QuicTlsServerEngine} need to
 * find and decode this one themselves.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-8.2">RFC 9001 section 8.2</a>
 */
public final class QuicTransportParametersExtension extends Extension {

    /** RFC 9001 section 8.2. */
    public static final int EXTENSION_TYPE = 0x39;

    private final TransportParameters transportParameters;

    public QuicTransportParametersExtension(TransportParameters transportParameters) {
        this.transportParameters = transportParameters;
    }

    public TransportParameters getTransportParameters() {
        return transportParameters;
    }

    @Override
    public int getType() {
        return EXTENSION_TYPE;
    }

    @Override
    public byte[] getBytes() {
        byte[] data = transportParameters.encode();
        ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
        buf.putShort((short) EXTENSION_TYPE);
        buf.putShort((short) data.length);
        buf.put(data);
        return buf.array();
    }

    /**
     * Finds and decodes the {@code quic_transport_parameters} extension
     * in a list of received extensions, if present.
     *
     * <p>No custom {@code ExtensionParser} is registered with Agent15
     * (see the class documentation), so a received
     * {@code quic_transport_parameters} extension always arrives here as
     * an {@link UnknownExtension} carrying the raw
     * type/length/data bytes.
     *
     * @param extensions the received extensions
     * @return the decoded transport parameters, or {@code null} if not present
     */
    public static TransportParameters find(List<Extension> extensions) {
        for (Extension extension : extensions) {
            if (extension.getType() == EXTENSION_TYPE && extension instanceof UnknownExtension) {
                byte[] raw = ((UnknownExtension) extension).getData();
                ByteBuffer buf = ByteBuffer.wrap(raw, 4, raw.length - 4);
                return TransportParameters.decode(buf);
            }
        }
        return null;
    }
}
