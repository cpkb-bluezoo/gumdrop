/*
 * Encoder.java
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

package org.bluezoo.gumdrop.http.qpack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.http.Header;

/**
 * Stateful QPACK field-section encoder (RFC 9204 section 4.5), backed
 * by a real {@link DynamicTable}.
 *
 * <p>Operates in strictly non-blocking mode: it never references an
 * entry the peer decoder hasn't yet acknowledged, so it always emits
 * {@code Base = Required Insert Count = Known Received Count} and never
 * uses post-Base indexing (section 4.5.3/4.5.5) -- a compliant peer
 * never blocks decoding our field sections, which is why {@link Decoder}
 * can safely advertise {@code SETTINGS_QPACK_BLOCKED_STREAMS = 0}.
 *
 * <p>Not thread-safe: one instance per connection direction.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Decoder
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 section 4.5</a>
 */
public final class Encoder extends QPACKConstants implements DecoderStreamHandler {

    private static final class OutstandingSection {
        final long requiredInsertCount;
        final List<Long> referencedIndices;

        OutstandingSection(long requiredInsertCount, List<Long> referencedIndices) {
            this.requiredInsertCount = requiredInsertCount;
            this.referencedIndices = referencedIndices;
        }
    }

    private final DynamicTable table;

    /**
     * Entries the peer decoder has acknowledged processing through
     * (RFC 9204 section 2.1.4) -- our non-blocking policy's Base/RIC value.
     */
    private long knownReceivedCount;

    /**
     * Per-outstanding (not yet acknowledged/cancelled) stream: the
     * Required Insert Count it was encoded with, and the absolute
     * indices it references, so {@link #sectionAcknowledgment}/
     * {@link #streamCancellation} can release the right table refs.
     * A stream is registered whenever {@code Base > 0} (the peer
     * decoder will Section-Acknowledge it, RFC 9204 section 4.5.1 /
     * {@link Decoder#decode}), not only when dynamic-table refs are
     * held -- otherwise a valid ack for a static/literal-only section
     * would be mistaken for {@code QPACK_DECODER_STREAM_ERROR}.
     */
    private final Map<Long, OutstandingSection> outstanding = new HashMap<Long, OutstandingSection>();

    /**
     * Owns the incremental parse state for this connection's one
     * decoder stream (RFC 9204 section 4.2); {@link #feedDecoderStream}
     * is the only entry point a caller outside this package needs, so
     * the parser itself stays package-private.
     */
    private final DecoderStreamParser decoderStreamParser = new DecoderStreamParser(this);

    /**
     * Most recent decoder-stream instruction validation failure
     * (RFC 9204 section 4.4.1 / 4.4.3). Cleared by
     * {@link #takeLastInstructionError}.
     */
    private String lastInstructionError;

    /**
     * Creates an encoder with the given dynamic table capacity.
     *
     * @param capacity the initial dynamic table capacity in octets
     */
    public Encoder(int capacity) {
        this.table = new DynamicTable(capacity);
    }

    /**
     * Feeds bytes received on the peer's QPACK decoder stream (RFC 9204
     * section 4.2), applying any complete Section Acknowledgment/Stream
     * Cancellation/Insert Count Increment instructions they contain. May
     * be called any number of times with arbitrary chunk boundaries.
     * Check {@link #takeLastInstructionError} afterwards.
     *
     * @param data newly received decoder-stream bytes
     */
    public void feedDecoderStream(ByteBuffer data) {
        decoderStreamParser.receive(data);
    }

    /**
     * Returns, and clears, the most recent error from validating a
     * decoder-stream instruction (RFC 9204 section 4.4.1 / 4.4.3). The
     * caller should close the connection with
     * {@code QPACK_DECODER_STREAM_ERROR} if this returns non-null after
     * feeding decoder-stream bytes.
     *
     * @return the error message, or null if there was none
     */
    public String takeLastInstructionError() {
        String error = lastInstructionError;
        lastInstructionError = null;
        return error;
    }

    /**
     * Changes the dynamic table's capacity, writing the corresponding
     * encoder-stream instruction (RFC 9204 section 4.3.1) to {@code out}.
     *
     * @param out the destination buffer for the encoder-stream instruction
     * @param capacity the new capacity in octets
     */
    public void setCapacity(ByteBuffer out, int capacity) {
        table.setCapacity(capacity);
        EncoderStreamWriter.writeSetDynamicTableCapacity(out, capacity);
    }

    /**
     * Encodes one field section for {@code streamId} (the request/
     * response/push stream it belongs to), writing the field-line bytes
     * to {@code fieldSection} and any resulting encoder-stream
     * instructions to {@code encoderInstructions} (often nothing
     * written at all). Preserve instruction order relative to other
     * calls to this method when sending them on the encoder stream.
     *
     * @param fieldSection the destination buffer for the encoded field section
     * @param encoderInstructions the destination buffer for any
     *                            resulting encoder-stream instructions
     * @param streamId the stream this field section belongs to
     * @param fields the headers to encode, in wire order
     */
    public void encode(ByteBuffer fieldSection, ByteBuffer encoderInstructions,
            long streamId, List<Header> fields) {
        long base = knownReceivedCount; // non-blocking policy: Base = RIC = KRC
        ByteBuffer fieldLines = ByteBuffer.allocate(estimateFieldLinesCapacity(fields));
        List<Long> referenced = new ArrayList<Long>();

        for (Header header : fields) {
            String name = header.getName();
            String value = header.getValue();

            int staticIndex = STATIC_TABLE_INDEX.indexOf(header);
            if (staticIndex != -1) {
                // RFC 9204 section 4.5.2: Indexed Field Line, T=1 (static)
                PrefixedInteger.encode(fieldLines, 0xc0, staticIndex, 6);
                continue;
            }

            DynamicTable.FindResult dynamicMatch = table.find(name, value, base);
            if (dynamicMatch != null && dynamicMatch.fullMatch) {
                table.addRef(dynamicMatch.absoluteIndex);
                referenced.add(dynamicMatch.absoluteIndex);
                // RFC 9204 section 4.5.2: Indexed Field Line, T=0 (dynamic),
                // relative index = base - 1 - absoluteIndex
                PrefixedInteger.encode(fieldLines, 0x80, base - 1 - dynamicMatch.absoluteIndex, 6);
                continue;
            }

            // Looked up once and reused below - same name, so the same
            // answer - rather than repeating the lookup for the insert
            // instruction and again for the field line itself.
            int staticNameIndex = STATIC_TABLE_INDEX.indexOfName(name);

            // Not referenceable yet under our non-blocking policy. Emit a
            // literal for this section; opportunistically insert for
            // future reuse, unless an equivalent entry is already
            // in-flight (skip to avoid duplicate insert instructions).
            DynamicTable.FindResult alreadyPresent = table.find(name, value, table.getInsertCount());
            boolean insertedNow = false;
            if (alreadyPresent == null || !alreadyPresent.fullMatch) {
                long insertedIndex = table.insert(name, value);
                insertedNow = insertedIndex != -1;
                if (insertedNow) {
                    if (staticNameIndex != -1) {
                        EncoderStreamWriter.writeInsertWithNameReference(
                                encoderInstructions, true, staticNameIndex, valueBytes(value));
                    } else {
                        EncoderStreamWriter.writeInsertWithLiteralName(
                                encoderInstructions, nameBytes(name), valueBytes(value));
                    }
                }
            }

            DynamicTable.FindResult dynamicNameMatch = table.find(name, value, base);
            if (staticNameIndex != -1) {
                // RFC 9204 section 4.5.4: Literal Field Line with Name
                // Reference, N=0, T=1 (static)
                PrefixedInteger.encode(fieldLines, 0x50, staticNameIndex, 4);
                QPACKStrings.write(fieldLines, valueBytes(value), 7, 0x00);
            } else if (dynamicNameMatch != null && !dynamicNameMatch.fullMatch) {
                // RFC 9204 section 4.5.4: Literal Field Line with Name
                // Reference, N=0, T=0 (dynamic)
                PrefixedInteger.encode(fieldLines, 0x40, base - 1 - dynamicNameMatch.absoluteIndex, 4);
                QPACKStrings.write(fieldLines, valueBytes(value), 7, 0x00);
            } else {
                // RFC 9204 section 4.5.6: Literal Field Line with Literal Name, N=0
                QPACKStrings.write(fieldLines, nameBytes(name), 3, 0x20);
                QPACKStrings.write(fieldLines, valueBytes(value), 7, 0x00);
            }
        }

        long encodedRic = RequiredInsertCount.encode(base, table.getCapacity());
        PrefixedInteger.encode(fieldSection, 0, encodedRic, 8);
        PrefixedInteger.encode(fieldSection, 0, 0, 7); // Base = RIC: sign 0, delta 0
        fieldLines.flip();
        fieldSection.put(fieldLines);

        // Decoder sends Section Acknowledgment iff RIC > 0. Under our
        // non-blocking policy RIC = base = knownReceivedCount, so every
        // section with base > 0 must be tracked -- including those that
        // hold no dynamic-table refs (static/literal-only).
        if (base > 0) {
            outstanding.put(streamId, new OutstandingSection(base, referenced));
        }
    }

    /**
     * A rough (over-)estimate of the encoded size of a header list,
     * for sizing the caller-invisible scratch buffer {@link #encode}
     * uses while building field lines: worst case, every header is a
     * literal with a literal name, roughly twice its raw UTF-8 length
     * plus a few bytes of framing overhead each.
     */
    private static int estimateFieldLinesCapacity(List<Header> fields) {
        int estimate = 16;
        for (Header header : fields) {
            estimate += 8 + 2 * (header.getName().length() + header.getValue().length());
        }
        return estimate;
    }

    private static byte[] nameBytes(String name) {
        return name.toLowerCase().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] valueBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    // ── DecoderStreamHandler ──

    /**
     * RFC 9204 section 4.4.1: the decoder has fully processed
     * {@code streamId}'s field section: release its table references
     * and advance Known Received Count if this section depended on more
     * insertions than we already knew were received.
     *
     * <p>An acknowledgment for a stream that was never encoded, or that
     * was already acknowledged, is a connection error of type
     * {@code QPACK_DECODER_STREAM_ERROR}.
     */
    @Override
    public void sectionAcknowledgment(long streamId) {
        OutstandingSection section = outstanding.remove(streamId);
        if (section == null) {
            lastInstructionError = "QPACK Section Acknowledgment for unknown or already-acknowledged stream: "
                    + streamId;
            return;
        }
        for (long absoluteIndex : section.referencedIndices) {
            table.releaseRef(absoluteIndex);
        }
        knownReceivedCount = Math.max(knownReceivedCount, section.requiredInsertCount);
    }

    /**
     * RFC 9204 section 4.4.2: {@code streamId} was reset/abandoned:
     * release its table references without advancing Known Received Count.
     */
    @Override
    public void streamCancellation(long streamId) {
        OutstandingSection section = outstanding.remove(streamId);
        if (section != null) {
            for (long absoluteIndex : section.referencedIndices) {
                table.releaseRef(absoluteIndex);
            }
        }
    }

    /**
     * RFC 9204 section 4.4.3: advance Known Received Count directly.
     *
     * <p>An Increment of zero, or one that pushes Known Received Count
     * past the number of insertions this encoder has made, is a
     * connection error of type {@code QPACK_DECODER_STREAM_ERROR}.
     */
    @Override
    public void insertCountIncrement(long increment) {
        if (increment <= 0) {
            lastInstructionError = "QPACK Insert Count Increment must be greater than zero: " + increment;
            return;
        }
        if (knownReceivedCount + increment > table.getInsertCount()) {
            lastInstructionError = "QPACK Insert Count Increment "
                    + increment + " would advance Known Received Count past Insert Count "
                    + table.getInsertCount();
            return;
        }
        knownReceivedCount += increment;
    }
}
