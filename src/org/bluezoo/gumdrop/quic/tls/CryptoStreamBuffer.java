/*
 * CryptoStreamBuffer.java
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.tls.HandshakeEngine;
import org.bluezoo.gumdrop.tls.TlsEventSink;

/**
 * Reassembles a QUIC CRYPTO stream for one {@link EncryptionLevel} into
 * complete TLS handshake messages (RFC 9000 section 19.6, RFC 9001
 * section 4.1).
 *
 * <p>CRYPTO frames identify their data by byte offset within a per-level
 * stream, the same reassembly problem as a QUIC STREAM frame -- delegated
 * to the shared {@link StreamReassembler}, which buffers out-of-order or
 * overlapping frames until the gap preceding them closes. CRYPTO frames
 * are not subject to QUIC flow control the way STREAM frames are (RFC
 * 9000 section 7.5), so this buffer is capped independently as a
 * denial-of-service mitigation: a peer that keeps sending far-future
 * CRYPTO data without ever closing the gap causes {@link
 * #receive} to throw rather than buffer unboundedly.
 *
 * <p>This class is pure reassembly: it hands back events so the concrete
 * {@code QuicTlsEngine} can dispatch them through
 * {@link QuicHandshakeAsyncOffload}, off the caller's thread. Ordinary
 * messages are returned whole; a {@code CompressedCertificate} (RFC 8879)
 * is instead delivered as begin, body chunks and end as its bytes arrive,
 * so a large post-quantum certificate chain is never buffered here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class CryptoStreamBuffer {

    /** RFC 8446 section 4: handshake message header is type(1) + length(3) octets. */
    private static final int MESSAGE_HEADER_LENGTH = 4;

    // Generous for any real TLS 1.3 handshake flight (ClientHello,
    // EncryptedExtensions+Certificate+CertificateVerify+Finished) while
    // still bounding how much reordered data a misbehaving or malicious
    // peer can make this endpoint buffer before the handshake completes.
    private static final long MAX_BUFFERED_BYTES = 65536;

    private static final int KIND_MESSAGE = 0;
    private static final int KIND_STREAM_BEGIN = 1;
    private static final int KIND_STREAM_DATA = 2;
    private static final int KIND_STREAM_END = 3;

    /**
     * One step of handshake input extracted from the stream: a complete
     * message, or one event of an incrementally delivered message.
     */
    public static final class Event {

        private final int kind;
        private final byte[] data;

        private Event(int kind, byte[] data) {
            this.kind = kind;
            this.data = data;
        }

        /**
         * Feeds this input to the handshake engine.
         *
         * @param engine the engine
         * @param sink where the engine pushes resulting events
         */
        public void dispatch(HandshakeEngine engine, TlsEventSink sink) {
            switch (kind) {
                case KIND_MESSAGE:
                    engine.processMessage(data, sink);
                    break;
                case KIND_STREAM_BEGIN:
                    engine.beginStreamedMessage(data, sink);
                    break;
                case KIND_STREAM_DATA:
                    engine.streamedMessageData(data, sink);
                    break;
                default:
                    engine.endStreamedMessage(sink);
                    break;
            }
        }

        /** The framed message bytes, or null if this is a streamed-message event. */
        byte[] message() {
            return kind == KIND_MESSAGE ? data : null;
        }
    }

    private final StreamReassembler reassembler = new StreamReassembler(MAX_BUFFERED_BYTES);
    private final byte[] header = new byte[MESSAGE_HEADER_LENGTH];
    private int headerLength;
    private byte[] message;
    private int messageFill;
    private int streamRemaining;
    private boolean streaming;

    /**
     * Accepts newly received CRYPTO frame data and returns every input now
     * available, in stream order. Nothing is ever returned twice.
     *
     * <p>Each ordinary message is a fresh, independent array. A
     * {@code CompressedCertificate} yields a begin event carrying its
     * 4-byte header, then body chunks no larger than the data received,
     * then an end event.
     *
     * @param offset the byte offset of {@code data} within this level's CRYPTO stream
     * @param data the received handshake data
     * @return the inputs now available, in stream order, or an empty list
     * @throws StreamReassembler.BufferLimitExceededException if reordered
     *         data exceeds this buffer's configured limit
     */
    public List<Event> receive(long offset, ByteBuffer data)
            throws StreamReassembler.BufferLimitExceededException {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        byte[] contiguous = reassembler.receive(offset, chunk);
        if (contiguous.length == 0) {
            return Collections.emptyList();
        }
        List<Event> events = new ArrayList<Event>();
        int pos = 0;
        while (pos < contiguous.length) {
            if (streaming) {
                int n = Math.min(streamRemaining, contiguous.length - pos);
                events.add(new Event(KIND_STREAM_DATA,
                        java.util.Arrays.copyOfRange(contiguous, pos, pos + n)));
                pos += n;
                streamRemaining -= n;
                if (streamRemaining == 0) {
                    streaming = false;
                    events.add(new Event(KIND_STREAM_END, null));
                }
            } else if (message != null) {
                int n = Math.min(message.length - messageFill, contiguous.length - pos);
                System.arraycopy(contiguous, pos, message, messageFill, n);
                messageFill += n;
                pos += n;
                if (messageFill == message.length) {
                    events.add(new Event(KIND_MESSAGE, message));
                    message = null;
                }
            } else {
                int n = Math.min(MESSAGE_HEADER_LENGTH - headerLength, contiguous.length - pos);
                System.arraycopy(contiguous, pos, header, headerLength, n);
                headerLength += n;
                pos += n;
                if (headerLength == MESSAGE_HEADER_LENGTH) {
                    headerLength = 0;
                    startMessage(events);
                }
            }
        }
        return events;
    }

    private void startMessage(List<Event> events) {
        int bodyLength = ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        if (HandshakeEngine.isStreamedMessageType(header[0] & 0xff)) {
            events.add(new Event(KIND_STREAM_BEGIN, header.clone()));
            if (bodyLength == 0) {
                events.add(new Event(KIND_STREAM_END, null));
            } else {
                streaming = true;
                streamRemaining = bodyLength;
            }
            return;
        }
        byte[] full = new byte[MESSAGE_HEADER_LENGTH + bodyLength];
        System.arraycopy(header, 0, full, 0, MESSAGE_HEADER_LENGTH);
        if (bodyLength == 0) {
            events.add(new Event(KIND_MESSAGE, full));
            return;
        }
        message = full;
        messageFill = MESSAGE_HEADER_LENGTH;
    }
}
