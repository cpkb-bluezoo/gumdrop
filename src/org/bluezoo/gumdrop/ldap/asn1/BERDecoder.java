/*
 * BERDecoder.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ldap.asn1;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming BER (Basic Encoding Rules) decoder for ASN.1 data.
 *
 * <p>This decoder is designed for use with non-blocking I/O. It accepts
 * data incrementally via {@link #receive(ByteBuffer)} and returns complete
 * elements via {@link #next()}.</p>
 *
 * <h4>Usage Example</h4>
 * <pre>{@code
 * BERDecoder decoder = new BERDecoder();
 *
 * // In receive callback
 * decoder.receive(buffer);
 *
 * ASN1Element element;
 * while ((element = decoder.next()) != null) {
 *     // Process complete element
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BERDecoder {

    // Decoder states
    private static final int STATE_TAG = 0;
    private static final int STATE_TAG_MULTI = 1;
    private static final int STATE_LENGTH = 2;
    private static final int STATE_LENGTH_MULTI = 3;
    private static final int STATE_VALUE = 4;

    // Internal buffer for accumulating data
    private ByteBuffer buffer;
    
    // Current decode state
    private int state;
    private int tag;
    private int length;
    private int lengthBytesRemaining;
    private byte[] valueBuffer;
    private int valueOffset;
    
    // Completed elements ready for retrieval
    private final List<ASN1Element> completed;

    /**
     * Creates a new BER decoder with default buffer size (8KB).
     */
    public BERDecoder() {
        this(8192);
    }

    /**
     * Creates a new BER decoder with the specified initial buffer size.
     *
     * @param bufferSize initial buffer capacity
     */
    public BERDecoder(int bufferSize) {
        buffer = ByteBuffer.allocate(bufferSize);
        buffer.flip(); // Start empty, ready for reading
        completed = new ArrayList<ASN1Element>();
        reset();
    }

    /**
     * Resets the decoder state, discarding any partial data.
     */
    public void reset() {
        state = STATE_TAG;
        tag = 0;
        length = 0;
        lengthBytesRemaining = 0;
        valueBuffer = null;
        valueOffset = 0;
        buffer.clear();
        buffer.flip();
        completed.clear();
    }

    /**
     * Receives data for decoding.
     *
     * @param data the data to decode
     * @throws ASN1Exception if the data is malformed
     */
    public void receive(ByteBuffer data) throws ASN1Exception {
        // Append new data to our buffer
        ensureCapacity(data.remaining());
        int pos = buffer.position();
        int lim = buffer.limit();
        buffer.position(lim);
        buffer.limit(buffer.capacity());
        buffer.put(data);
        buffer.limit(buffer.position());
        buffer.position(pos);

        // Process as much as we can
        decode();
    }

    /**
     * Returns the next complete element, or null if none available.
     *
     * @return the next element, or null
     */
    public ASN1Element next() {
        if (completed.isEmpty()) {
            return null;
        }
        return completed.remove(0);
    }

    /**
     * Returns whether there is data still being accumulated.
     *
     * @return true if partial data exists
     */
    public boolean hasPartialData() {
        return state != STATE_TAG || buffer.hasRemaining();
    }

    private void ensureCapacity(int additional) {
        int required = buffer.remaining() + additional;
        if (required > buffer.capacity()) {
            int newCapacity = Math.max(buffer.capacity() * 2, required);
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            newBuffer.put(buffer);
            newBuffer.flip();
            buffer = newBuffer;
        }
    }

    private void decode() throws ASN1Exception {
        while (buffer.hasRemaining()) {
            switch (state) {
                case STATE_TAG:
                    decodeTag();
                    break;
                case STATE_TAG_MULTI:
                    decodeTagMulti();
                    break;
                case STATE_LENGTH:
                    decodeLength();
                    break;
                case STATE_LENGTH_MULTI:
                    decodeLengthMulti();
                    break;
                case STATE_VALUE:
                    decodeValue();
                    break;
            }
            
            // If we're back to STATE_TAG and have no more data, break
            if (state == STATE_TAG && !buffer.hasRemaining()) {
                break;
            }
        }
        
        // Compact the buffer to free up space
        buffer.compact();
        buffer.flip();
    }

    private void decodeTag() throws ASN1Exception {
        int b = buffer.get() & 0xFF;
        if ((b & 0x1F) == 0x1F) {
            // Multi-byte tag
            tag = b;
            state = STATE_TAG_MULTI;
        } else {
            tag = b;
            state = STATE_LENGTH;
        }
    }

    private void decodeTagMulti() throws ASN1Exception {
        while (buffer.hasRemaining()) {
            int b = buffer.get() & 0xFF;
            tag = (tag << 8) | b;
            if ((b & 0x80) == 0) {
                // Last byte of multi-byte tag
                state = STATE_LENGTH;
                return;
            }
        }
    }

    private void decodeLength() throws ASN1Exception {
        int b = buffer.get() & 0xFF;
        if (b == 0x80) {
            // Indefinite length - not supported for simplicity
            throw new ASN1Exception("Indefinite length encoding not supported");
        } else if ((b & 0x80) == 0) {
            // Short form
            length = b;
            startValue();
        } else {
            // Long form
            lengthBytesRemaining = b & 0x7F;
            if (lengthBytesRemaining > 4) {
                throw new ASN1Exception("Length too large: " + lengthBytesRemaining + " bytes");
            }
            length = 0;
            state = STATE_LENGTH_MULTI;
        }
    }

    private void decodeLengthMulti() throws ASN1Exception {
        while (buffer.hasRemaining() && lengthBytesRemaining > 0) {
            int b = buffer.get() & 0xFF;
            length = (length << 8) | b;
            lengthBytesRemaining--;
        }
        if (lengthBytesRemaining == 0) {
            startValue();
        }
    }

    private void startValue() throws ASN1Exception {
        if (length == 0) {
            // Empty value
            completeElement(new byte[0]);
        } else if (length > 10 * 1024 * 1024) {
            // Sanity check - 10MB max
            throw new ASN1Exception("Value too large: " + length + " bytes");
        } else {
            valueBuffer = new byte[length];
            valueOffset = 0;
            state = STATE_VALUE;
        }
    }

    private void decodeValue() throws ASN1Exception {
        int available = buffer.remaining();
        int needed = length - valueOffset;
        int toCopy = Math.min(available, needed);
        
        buffer.get(valueBuffer, valueOffset, toCopy);
        valueOffset += toCopy;
        
        if (valueOffset == length) {
            completeElement(valueBuffer);
        }
    }

    private void completeElement(byte[] value) throws ASN1Exception {
        ASN1Element element;
        
        if (ASN1Type.isConstructed(tag)) {
            // Parse children
            List<ASN1Element> children = parseChildren(value);
            element = new ASN1Element(tag, children);
        } else {
            element = new ASN1Element(tag, value);
        }
        
        completed.add(element);
        
        // Reset for next element
        state = STATE_TAG;
        tag = 0;
        length = 0;
        valueBuffer = null;
        valueOffset = 0;
    }

    private List<ASN1Element> parseChildren(byte[] data) throws ASN1Exception {
        List<ASN1Element> children = new ArrayList<ASN1Element>();
        BERDecoder childDecoder = new BERDecoder(data.length);
        childDecoder.receive(ByteBuffer.wrap(data));
        
        ASN1Element child;
        while ((child = childDecoder.next()) != null) {
            children.add(child);
        }
        
        if (childDecoder.hasPartialData()) {
            throw new ASN1Exception("Incomplete child element in constructed type");
        }
        
        return children;
    }
}

