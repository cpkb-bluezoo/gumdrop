/*
 * Decoder.java
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

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * Stateful QPACK field-section decoder (RFC 9204 section 4.5), backed
 * by a real {@link DynamicTable} mirrored from the peer encoder's
 * instruction stream.
 *
 * <p>Decodes arbitrary spec-compliant encodings -- including
 * dynamic-table and post-Base references -- since a real peer encoder
 * is not obligated to follow this package's own {@link Encoder}'s
 * non-blocking encoding policy. The one thing this decoder does not do
 * is <em>buffer and wait</em> for a blocked field section: since
 * gumdrop's own encoder never advertises
 * {@code SETTINGS_QPACK_BLOCKED_STREAMS} greater than 0, a compliant
 * peer never sends a Required Insert Count this decoder can't already
 * satisfy, so {@link #decode} rejecting a field section as "blocked"
 * should never happen against a compliant encoder.
 *
 * <p>Not thread-safe: one instance per connection direction.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Encoder
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 section 4.5</a>
 */
public final class Decoder extends QPACKConstants implements EncoderStreamHandler {

    private final DynamicTable table;

    /**
     * The capacity ceiling this decoder declared via
     * {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}, fixed at construction.
     * RFC 9204 section 3.2.3 requires a peer exceeding this to be
     * treated as a connection error -- never silently honoured.
     */
    private final int maxCapacity;

    private final ByteArrayOutputStream pendingInstructions = new ByteArrayOutputStream();

    private String lastInstructionError;

    /** Reusable scratch buffer for {@link #pendingBuffer}/{@link #flushPendingBuffer}. */
    private ByteBuffer pendingScratch;

    /**
     * Creates a decoder with the given dynamic table capacity, also
     * used as the {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} ceiling.
     *
     * @param capacity the dynamic table capacity in octets
     */
    public Decoder(int capacity) {
        this.table = new DynamicTable(capacity);
        this.maxCapacity = capacity;
    }

    /**
     * Returns, and clears, the most recent error from processing an
     * encoder-stream instruction (RFC 9204 section 3.2.3's capacity
     * check is the only one currently possible). The caller should
     * close the connection with {@code QPACK_ENCODER_STREAM_ERROR} if
     * this returns non-null after feeding encoder-stream bytes.
     *
     * @return the error message, or null if there was none
     */
    public String takeLastInstructionError() {
        String error = lastInstructionError;
        lastInstructionError = null;
        return error;
    }

    /**
     * Returns, and clears, any decoder-stream instruction bytes queued
     * by {@link #decode} (Section Acknowledgment) or by mirroring an
     * encoder-stream insertion (Insert Count Increment), for the caller
     * to send on the decoder stream.
     *
     * @return the queued bytes, possibly empty
     */
    public byte[] takePendingInstructions() {
        byte[] result = pendingInstructions.toByteArray();
        pendingInstructions.reset();
        return result;
    }

    /**
     * RFC 9204 section 4.4.2: notifies the peer encoder that
     * {@code streamId} was reset or otherwise abandoned before its
     * (possibly still in-flight) field section was processed, so the
     * encoder can release its table references for it. Call this from
     * the transport layer's stream-reset handling, not from
     * {@link #decode}, which the reset stream's field section will
     * never reach.
     *
     * @param streamId the stream that was cancelled
     */
    public void cancelStream(long streamId) {
        DecoderStreamWriter.writeStreamCancellation(pendingBuffer(), streamId);
        flushPendingBuffer();
    }

    /**
     * Decodes one field section received on {@code streamId}.
     *
     * @param streamId the stream this field section was received on
     * @param block the encoded field section
     * @return the decoded headers, in wire order
     * @throws ProtocolException if the field section is malformed, or
     *         has a Required Insert Count this decoder cannot already
     *         satisfy (see the class documentation)
     */
    public List<Header> decode(long streamId, ByteBuffer block) throws ProtocolException {
        if (!block.hasRemaining()) {
            throw new ProtocolException("QPACK field section underflow reading prefix");
        }
        int requiredInsertCountByte = block.get() & 0xff;
        long encodedRic = PrefixedInteger.decode(block, requiredInsertCountByte, 8);
        long ric = RequiredInsertCount.decode(encodedRic, table.getInsertCount(), table.getCapacity());
        if (ric == RequiredInsertCount.INVALID) {
            throw new ProtocolException("QPACK field section has an invalid Required Insert Count");
        }
        // The wraparound math above only reconstructs a plausible RIC; it
        // doesn't check we've actually received that many insertions yet.
        // A RIC beyond what we've processed would require blocking, which
        // this decoder never permits (see class documentation).
        if (ric > table.getInsertCount()) {
            throw new ProtocolException("QPACK field section is blocked on dynamic table entries not yet received");
        }

        if (!block.hasRemaining()) {
            throw new ProtocolException("QPACK field section underflow reading Base");
        }
        int deltaBaseByte = block.get() & 0xff;
        boolean sign = (deltaBaseByte & 0x80) != 0;
        long deltaBase = PrefixedInteger.decode(block, deltaBaseByte, 7);
        long base;
        if (sign) {
            if (ric < deltaBase + 1) {
                throw new ProtocolException("QPACK field section has an invalid Base (negative)");
            }
            base = ric - deltaBase - 1;
        } else {
            base = ric + deltaBase;
        }

        List<Header> fields = new ArrayList<Header>();
        while (block.hasRemaining()) {
            int first = block.get(block.position()) & 0xff;
            if ((first & 0x80) != 0) {
                fields.add(decodeIndexedFieldLine(block, base));
            } else if ((first & 0xc0) == 0x40) {
                fields.add(decodeLiteralFieldLineWithNameReference(block, base));
            } else if ((first & 0xe0) == 0x20) {
                fields.add(decodeLiteralFieldLineWithLiteralName(block));
            } else if ((first & 0xf0) == 0x10) {
                fields.add(decodePostBaseIndexedFieldLine(block, base));
            } else if ((first & 0xf0) == 0x00) {
                fields.add(decodeLiteralFieldLineWithPostBaseNameReference(block, base));
            } else {
                throw new ProtocolException("QPACK unsupported field line representation");
            }
        }

        if (ric > 0) {
            DecoderStreamWriter.writeSectionAcknowledgment(pendingBuffer(), streamId);
            flushPendingBuffer();
        }
        return fields;
    }

    // RFC 9204 section 4.5.2: '1|T|Index(6+)'
    private Header decodeIndexedFieldLine(ByteBuffer block, long base) throws ProtocolException {
        int firstByte = block.get() & 0xff;
        boolean isStatic = (firstByte & 0x40) != 0;
        long index = PrefixedInteger.decode(block, firstByte, 6);
        if (isStatic) {
            if (index < 0 || index >= STATIC_TABLE_SIZE) {
                throw new ProtocolException("QPACK static table index out of range: " + index);
            }
            return STATIC_TABLE.get((int) index);
        }
        long absoluteIndex = resolveDynamicIndex(base, index);
        Header header = table.get(absoluteIndex);
        if (header == null) {
            throw new ProtocolException("QPACK dynamic table index not live: " + absoluteIndex);
        }
        return header;
    }

    // RFC 9204 section 4.5.4: '01|N|T|NameIndex(4+)' + value string literal
    private Header decodeLiteralFieldLineWithNameReference(ByteBuffer block, long base) throws ProtocolException {
        int firstByte = block.get() & 0xff;
        boolean isStatic = (firstByte & 0x10) != 0;
        long nameIndex = PrefixedInteger.decode(block, firstByte, 4);
        byte[] valueBytes = QPACKStrings.read(block, 7);
        String name;
        if (isStatic) {
            if (nameIndex < 0 || nameIndex >= STATIC_TABLE_SIZE) {
                throw new ProtocolException("QPACK static table index out of range: " + nameIndex);
            }
            name = STATIC_TABLE.get((int) nameIndex).getName();
        } else {
            long absoluteIndex = resolveDynamicIndex(base, nameIndex);
            Header entry = table.get(absoluteIndex);
            if (entry == null) {
                throw new ProtocolException("QPACK dynamic table index not live: " + absoluteIndex);
            }
            name = entry.getName();
        }
        return new Header(name, decodeText(valueBytes));
    }

    // RFC 9204 section 4.5.6: '001|N|H|NameLen(3+)' + name bytes + value string literal
    private Header decodeLiteralFieldLineWithLiteralName(ByteBuffer block) throws ProtocolException {
        byte[] nameBytes = QPACKStrings.read(block, 3);
        byte[] valueBytes = QPACKStrings.read(block, 7);
        return new Header(decodeText(nameBytes), decodeText(valueBytes));
    }

    // RFC 9204 section 4.5.3: '0001|Index(4+)'
    private Header decodePostBaseIndexedFieldLine(ByteBuffer block, long base) throws ProtocolException {
        int firstByte = block.get() & 0xff;
        long index = PrefixedInteger.decode(block, firstByte, 4);
        long absoluteIndex = base + index;
        Header header = table.get(absoluteIndex);
        if (header == null) {
            throw new ProtocolException("QPACK dynamic table index not live: " + absoluteIndex);
        }
        return header;
    }

    // RFC 9204 section 4.5.5: '0000|N|NameIdx(3+)' + value string literal
    private Header decodeLiteralFieldLineWithPostBaseNameReference(ByteBuffer block, long base)
            throws ProtocolException {
        int firstByte = block.get() & 0xff;
        long nameIndex = PrefixedInteger.decode(block, firstByte, 3);
        byte[] valueBytes = QPACKStrings.read(block, 7);
        long absoluteIndex = base + nameIndex;
        Header entry = table.get(absoluteIndex);
        if (entry == null) {
            throw new ProtocolException("QPACK dynamic table index not live: " + absoluteIndex);
        }
        return new Header(entry.getName(), decodeText(valueBytes));
    }

    // RFC 9204 section 3.2.6: a name/index reference's relative index counts
    // back from (base - 1); a post-Base one counts forward from base --
    // handled by the two decode*PostBase* methods instead of this one.
    private long resolveDynamicIndex(long base, long relativeIndex) throws ProtocolException {
        long absoluteIndex = base - 1 - relativeIndex;
        if (absoluteIndex < 0) {
            throw new ProtocolException("QPACK relative index out of range: " + relativeIndex);
        }
        return absoluteIndex;
    }

    private static String decodeText(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private ByteBuffer pendingBuffer() {
        if (pendingScratch == null || pendingScratch.capacity() < 16) {
            pendingScratch = ByteBuffer.allocate(16);
        }
        pendingScratch.clear();
        return pendingScratch;
    }

    private void flushPendingBuffer() {
        pendingScratch.flip();
        byte[] bytes = new byte[pendingScratch.remaining()];
        pendingScratch.get(bytes);
        pendingInstructions.write(bytes, 0, bytes.length);
    }

    // ── EncoderStreamHandler ──

    @Override
    public void setDynamicTableCapacity(long capacity) {
        if (capacity > maxCapacity) {
            // RFC 9204 section 3.2.3: exceeding the declared
            // SETTINGS_QPACK_MAX_TABLE_CAPACITY is a hard connection
            // error, not something to silently honour -- and the
            // rejected instruction must not take effect.
            lastInstructionError = "QPACK Set Dynamic Table Capacity " + capacity
                    + " exceeds the declared maximum " + maxCapacity;
            return;
        }
        table.setCapacity((int) capacity);
    }

    @Override
    public void insertWithNameReference(boolean isStaticTable, long nameIndex, byte[] value) {
        String name;
        if (isStaticTable) {
            if (nameIndex < 0 || nameIndex >= STATIC_TABLE_SIZE) {
                lastInstructionError = "QPACK Insert With Name Reference: static table index out of range: " + nameIndex;
                return;
            }
            name = STATIC_TABLE.get((int) nameIndex).getName();
        } else {
            long absoluteIndex = table.getInsertCount() - 1 - nameIndex;
            Header entry = table.get(absoluteIndex);
            if (entry == null) {
                lastInstructionError = "QPACK Insert With Name Reference: dynamic table index not live: " + absoluteIndex;
                return;
            }
            name = entry.getName();
        }
        table.insertMirrored(name, decodeText(value));
        queueInsertCountIncrement();
    }

    @Override
    public void insertWithLiteralName(byte[] name, byte[] value) {
        table.insertMirrored(decodeText(name), decodeText(value));
        queueInsertCountIncrement();
    }

    @Override
    public void duplicate(long relativeIndex) {
        long absoluteIndex = table.getInsertCount() - 1 - relativeIndex;
        Header entry = table.get(absoluteIndex);
        if (entry == null) {
            lastInstructionError = "QPACK Duplicate: dynamic table index not live: " + absoluteIndex;
            return;
        }
        table.insertMirrored(entry.getName(), entry.getValue());
        queueInsertCountIncrement();
    }

    @Override
    public void instructionError(String message) {
        lastInstructionError = message;
    }

    private void queueInsertCountIncrement() {
        DecoderStreamWriter.writeInsertCountIncrement(pendingBuffer(), 1);
        flushPendingBuffer();
    }
}
