/*
 * MessageWriter.java
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
import java.util.Map;

/**
 * Encodes the sections of an AMQP 1.0 message (core specification 3.2).
 *
 * <p>The sections before the body are encoded with the {@code write}
 * methods into an {@link Amqp1Encoder}. A body is sent as one or more
 * {@code data} sections: a large payload can be split across several, so
 * each chunk is framed with {@link #dataSectionPrefix(int)} and its
 * octets forwarded without copying into a larger structure.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MessageParser
 */
public final class MessageWriter {

    private MessageWriter() {
    }

    public static void writeHeader(Amqp1Encoder out, MessageHeader header) {
        header.write(out);
    }

    public static void writeProperties(Amqp1Encoder out, MessageProperties properties) {
        properties.write(out);
    }

    public static void writeDeliveryAnnotations(Amqp1Encoder out, Map<?, ?> annotations) {
        writeMapSection(out, MessageParser.SECTION_DELIVERY_ANNOTATIONS, annotations);
    }

    public static void writeMessageAnnotations(Amqp1Encoder out, Map<?, ?> annotations) {
        writeMapSection(out, MessageParser.SECTION_MESSAGE_ANNOTATIONS, annotations);
    }

    public static void writeApplicationProperties(Amqp1Encoder out, Map<?, ?> properties) {
        writeMapSection(out, MessageParser.SECTION_APPLICATION_PROPERTIES, properties);
    }

    public static void writeFooter(Amqp1Encoder out, Map<?, ?> footer) {
        writeMapSection(out, MessageParser.SECTION_FOOTER, footer);
    }

    /** Writes an {@code amqp-value} body section. */
    public static void writeAmqpValue(Amqp1Encoder out, Object value) {
        out.writeDescriptor(MessageParser.SECTION_AMQP_VALUE);
        out.writeObject(value);
    }

    /** Writes an {@code amqp-sequence} body section: one row. */
    public static void writeAmqpSequence(Amqp1Encoder out, List<?> row) {
        out.writeDescriptor(MessageParser.SECTION_AMQP_SEQUENCE);
        out.writeList(row);
    }

    private static void writeMapSection(Amqp1Encoder out, long descriptor, Map<?, ?> map) {
        out.writeDescriptor(descriptor);
        out.writeMap(map);
    }

    /**
     * Returns the octets that introduce a {@code data} section of
     * {@code length} body octets: the section descriptor and the binary
     * constructor with its length. Follow it with exactly {@code length}
     * octets of body.
     *
     * @param length the number of body octets in this section
     */
    public static ByteBuffer dataSectionPrefix(int length) {
        Amqp1Encoder e = new Amqp1Encoder(16);
        e.writeDescriptor(MessageParser.SECTION_DATA);
        // The binary constructor and length, with the body left to the caller
        if (length <= 0xFF) {
            e.writeRaw(new byte[] {(byte) Amqp1Types.BINARY8, (byte) length}, 0, 2);
        } else {
            e.writeRaw(new byte[] {(byte) Amqp1Types.BINARY32, (byte) (length >>> 24),
                (byte) (length >>> 16), (byte) (length >>> 8), (byte) length}, 0, 5);
        }
        return e.toByteBuffer();
    }
}
