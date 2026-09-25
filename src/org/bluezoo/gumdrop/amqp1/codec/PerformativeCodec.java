/*
 * PerformativeCodec.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Decodes a frame body into the {@link Performative} it carries.
 *
 * <p>Handles the connection and session performatives ({@link Open},
 * {@link Begin}, {@link End}, {@link Close}), the link performatives
 * ({@link Attach}, {@link Flow}, {@link Transfer}, {@link Disposition},
 * {@link Detach}) and the SASL frame bodies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class PerformativeCodec {

    private PerformativeCodec() {
    }

    /**
     * Decodes one performative from the start of a frame body.
     *
     * <p>On return the buffer is positioned immediately after the
     * performative. For most performatives that is the end of the frame;
     * a {@code transfer} is followed by the message payload, which the
     * caller reads directly.
     *
     * @param body the frame body, as delivered to
     *      {@link Amqp1FrameHandler#frame}
     * @return the decoded performative
     * @throws Amqp1ProtocolException if the body is malformed, or its
     *      descriptor is not a supported performative
     */
    public static Performative decode(ByteBuffer body) throws Amqp1ProtocolException {
        Object value = Amqp1Decoder.read(body);
        if (!(value instanceof Amqp1Described)) {
            throw new Amqp1ProtocolException("Frame body is not a described performative");
        }
        Amqp1Described described = (Amqp1Described) value;
        if (!(described.getDescriptor() instanceof Long)) {
            throw new Amqp1ProtocolException("Unsupported performative descriptor "
                    + described.getDescriptor());
        }
        if (!(described.getValue() instanceof List)) {
            throw new Amqp1ProtocolException("Performative value is not a list");
        }
        long descriptor = ((Long) described.getDescriptor()).longValue();
        @SuppressWarnings("unchecked")
        List<Object> fields = (List<Object>) described.getValue();
        if (descriptor < 0 || descriptor > 0xFF) {
            throw new Amqp1ProtocolException("Unsupported performative descriptor 0x"
                    + Long.toHexString(descriptor));
        }
        switch ((int) descriptor) {
            case (int) Performative.DESCRIPTOR_OPEN:
                return Open.fromFields(fields);
            case (int) Performative.DESCRIPTOR_BEGIN:
                return Begin.fromFields(fields);
            case (int) Performative.DESCRIPTOR_ATTACH:
                return Attach.fromFields(fields);
            case (int) Performative.DESCRIPTOR_FLOW:
                return Flow.fromFields(fields);
            case (int) Performative.DESCRIPTOR_TRANSFER:
                return Transfer.fromFields(fields);
            case (int) Performative.DESCRIPTOR_DISPOSITION:
                return Disposition.fromFields(fields);
            case (int) Performative.DESCRIPTOR_DETACH:
                return Detach.fromFields(fields);
            case (int) Performative.DESCRIPTOR_END:
                return End.fromFields(fields);
            case (int) Performative.DESCRIPTOR_CLOSE:
                return Close.fromFields(fields);
            case (int) Performative.DESCRIPTOR_SASL_MECHANISMS:
                return SaslMechanisms.fromFields(fields);
            case (int) Performative.DESCRIPTOR_SASL_INIT:
                return SaslInit.fromFields(fields);
            case (int) Performative.DESCRIPTOR_SASL_CHALLENGE:
                return SaslChallenge.fromFields(fields);
            case (int) Performative.DESCRIPTOR_SASL_RESPONSE:
                return SaslResponse.fromFields(fields);
            case (int) Performative.DESCRIPTOR_SASL_OUTCOME:
                return SaslOutcome.fromFields(fields);
            default:
                throw new Amqp1ProtocolException("Unsupported performative descriptor 0x"
                        + Long.toHexString(descriptor));
        }
    }
}
