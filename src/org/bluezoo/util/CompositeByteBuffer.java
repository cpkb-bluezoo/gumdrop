/*
 * CompositeByteBuffer.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.util;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.InvalidMarkException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

/**
 * A composite ByteBuffer that provides a unified view over two underlying
 * ByteBuffers: an underflow buffer (for leftover bytes from previous
 * processing) and a data buffer (for new incoming data). This avoids
 * expensive copying operations when joining buffers.
 * <p>
 * This class mimics the standard ByteBuffer API and is designed for use
 * in streaming data processing scenarios where partial reads are common.
 * <h3>Usage Pattern</h3>
 * The typical workflow in a {@code receive(ByteBuffer data)} method is:
 * <pre>
 * compositeBuffer.put(data);     // Add new data to composite view
 * compositeBuffer.flip();         // Prepare for reading
 * 
 * // Process data using get(), position(), etc.
 * while (compositeBuffer.hasRemaining()) {
 *     byte b = compositeBuffer.get();
 *     // ... process byte ...
 * }
 * 
 * compositeBuffer.compact();      // Save unread bytes for next cycle
 * return;                         // Wait for next receive()
 * </pre>
 * <h3>Internal State</h3>
 * <ul>
 * <li>The underflow buffer holds leftover bytes from previous cycles
 *     (always in read mode: position=0, limit=data length)</li>
 * <li>The data buffer is the external buffer passed to {@code put()}
 *     (its position/limit are captured but the buffer itself is not
 *     modified during reads)</li>
 * <li>The composite buffer maintains its own position/limit/mark
 *     independently, providing a unified view over both buffers</li>
 * </ul>
 * <h3>Key Behaviors</h3>
 * <ul>
 * <li>{@code put(data)} - Adds data to view, leaves buffer in write
 *     mode (position at capacity)</li>
 * <li>{@code flip()} - Switches to read mode (position=0, limit=old
 *     position)</li>
 * <li>{@code get()} - Reads bytes, automatically handling transitions
 *     between underflow and data buffers</li>
 * <li>{@code compact()} - Moves unread bytes to underflow buffer,
 *     prepares for next {@code put()}</li>
 * </ul>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class CompositeByteBuffer {

    /**
     * The underflow buffer.
     * May be null which is equivalent to zero bytes.
     * This data comes before the data in the data buffer in the unified view.
     * Bytes in the underflow buffer should always start from position zero.
     * This is an internal buffer managed by the composite buffer.
     */
    private ByteBuffer underflowBuffer;

    /**
     * The data buffer.
     * May be null which is equivalent to zero bytes.
     * This data comes after the data in the underflow buffer in the unified view.
     * Bytes in the data buffer start wherever the buffer's position() was
     * when it was assigned via put(), and end wherever its limit() was
     * when it was assigned. Data beyond these cannot be read.
     * This is an external buffer. Modifying its contents may have
     * unsafe and unforeseen side effects.
     */
    private ByteBuffer dataBuffer;
    
    // Composite view state
    private int position;
    private int limit;
    private int capacity;
    private int mark = -1;

    // Cache underflow limit
    private int underflowLimit;
    
    // Data buffer window
    private int dataPosition;
    private int dataLimit;

    // Minimum amount to grow underflow buffer by
    private int bufferMinGrowth;
    
    /**
     * Creates a new composite byte buffer with a default minimum growth
     * size of 256 bytes for the underflow buffer.
     */
    public CompositeByteBuffer() {
        this(256);
    }
    
    /**
     * Creates a new composite byte buffer with the specified minimum
     * growth size for the underflow buffer.
     * 
     * @param bufferMinGrowth the minimum size to allocate when growing
     *        the underflow buffer
     */
    public CompositeByteBuffer(int bufferMinGrowth) {
        this.bufferMinGrowth = bufferMinGrowth;
    }
    
    /**
     * Adds the data in the specified buffer to the view created by the
     * composite buffer. This will be called at the start of the receive
     * cycle. Further calls to this method without calling compact will
     * overwrite previous data.
     * <p>
     * After this call, the buffer is in write mode with position set to
     * capacity (as if all the data has just been "written").
     * 
     * @param buf the buffer to add
     * @return this buffer for chaining
     */
    public CompositeByteBuffer put(ByteBuffer buf) {
        dataBuffer = buf;
        dataPosition = buf.position();
        dataLimit = buf.limit();
        capacity = underflowLimit + (dataLimit - dataPosition);
        limit = capacity;
        position = capacity;
        return this;
    }
    
    /**
     * Compacts this buffer by moving unread bytes to the underflow
     * buffer. This prepares the buffer for the next receive() cycle.
     * <p>
     * The bytes between the buffer's current position and its limit, if
     * any, are copied to the underflow buffer. The buffer's position is
     * then set to the number of bytes copied (the underflow limit), the
     * limit is set to the capacity, and the mark is discarded. The data
     * buffer reference is cleared, ready for the next put() call.
     * 
     * @return this buffer for chaining
     */
    public CompositeByteBuffer compact() {
        int remainingBytes = remaining();
        
        if (remainingBytes == 0) {
            // Nothing to save, ensure underflow buffer exists but is empty
            if (underflowBuffer != null) {
                underflowBuffer.clear().limit(0);
            } else {
                underflowBuffer = ByteBuffer.allocate(0);
            }
        } else {
            ByteBuffer oldUnderflow = underflowBuffer;
            int oldUnderflowLimit = underflowLimit;
            int underflowCapacity = (underflowBuffer != null) ? underflowBuffer.capacity() : 0;
            
            if (underflowCapacity < remainingBytes) {
                // Grow underflow buffer - allocate new one
                int newCapacity = Math.max(remainingBytes, bufferMinGrowth);
                underflowBuffer = ByteBuffer.allocate(newCapacity);
                
                // Copy remaining bytes from old underflow (bulk transfer)
                if (position < oldUnderflowLimit && oldUnderflow != null) {
                    int oldPos = oldUnderflow.position();
                    int oldLim = oldUnderflow.limit();
                    oldUnderflow.position(position).limit(oldUnderflowLimit);
                    underflowBuffer.put(oldUnderflow);
                    oldUnderflow.position(oldPos).limit(oldLim);  // Restore state
                }
                
                // Copy remaining bytes from data buffer (bulk transfer)
                if (position < capacity && dataBuffer != null) {
                    int dataRemaining = capacity - Math.max(position, oldUnderflowLimit);
                    if (dataRemaining > 0) {
                        int dataPos = Math.max(0, position - oldUnderflowLimit) + dataPosition;
                        int oldPos = dataBuffer.position();
                        int oldLim = dataBuffer.limit();
                        dataBuffer.position(dataPos).limit(dataPos + dataRemaining);
                        underflowBuffer.put(dataBuffer);
                        dataBuffer.position(oldPos).limit(oldLim);  // Restore state
                    }
                }
                
                underflowBuffer.flip();
            } else {
                // Reuse existing underflow buffer
                // Note: underflowBuffer and oldUnderflow are the same object
                underflowBuffer.clear();
                
                if (position < oldUnderflowLimit) {
                    // Bytes remaining in underflow buffer - copy to start
                    int bytesToCopy = oldUnderflowLimit - position;
                    // Manual copy since we're reusing the same buffer
                    for (int i = 0; i < bytesToCopy; i++) {
                        underflowBuffer.put(oldUnderflow.get(position + i));
                    }
                }
                
                // Copy remaining bytes from data buffer if any (bulk transfer)
                if (position < capacity && dataBuffer != null) {
                    int dataRemaining = capacity - Math.max(position, oldUnderflowLimit);
                    if (dataRemaining > 0) {
                        int dataPos = Math.max(0, position - oldUnderflowLimit) + dataPosition;
                        int oldPos = dataBuffer.position();
                        int oldLim = dataBuffer.limit();
                        dataBuffer.position(dataPos).limit(dataPos + dataRemaining);
                        underflowBuffer.put(dataBuffer);
                        dataBuffer.position(oldPos).limit(oldLim);  // Restore state
                    }
                }
                
                underflowBuffer.flip();
            }
        }
        
        // Clear data buffer reference and update state
        dataBuffer = null;
        mark = -1;
        underflowLimit = (underflowBuffer != null) ? underflowBuffer.limit() : 0;
        capacity = underflowLimit;
        limit = capacity;
        position = limit;
        
        return this;
    }

    /**
     * Returns the current position in the composite buffer.
     * 
     * @return the position
     */
    public int position() {
        return position;
    }
    
    /**
     * Returns the limit of the composite buffer.
     * 
     * @return the limit
     */
    public int limit() {
        return limit;
    }
    
    /**
     * Sets the limit of the composite buffer.
     * 
     * @param newLimit the new limit position
     * @return this buffer for chaining
     * @throws IllegalArgumentException if the new limit is invalid
     */
    public CompositeByteBuffer limit(int newLimit) {
        if (newLimit < 0 || newLimit > capacity) {
            throw new IllegalArgumentException("Invalid limit: " + newLimit + " (capacity=" + capacity + ")");
        }
        if (position > newLimit) {
            position = newLimit;
        }
        this.limit = newLimit;
        return this;
    }
    
    /**
     * Returns the capacity of the composite buffer.
     * 
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }
    
    /**
     * Returns the number of remaining bytes in the composite buffer.
     * 
     * @return the number of bytes between the current position and the
     *         limit
     */
    public int remaining() {
        return limit - position;
    }
    
    /**
     * Tells whether there are any remaining bytes to read.
     * 
     * @return true if, and only if, there is at least one byte
     *         remaining in this buffer
     */
    public boolean hasRemaining() {
        return remaining() > 0;
    }
    
    /**
     * Relative get method. Reads the byte at this buffer's current
     * position, and then increments the position.
     * 
     * @return the byte at the current position
     * @throws BufferUnderflowException if the buffer's current position
     *         is not smaller than its limit
     */
    public byte get() {
        if (position >= limit) {
            throw new BufferUnderflowException();
        }
        
        byte result;
        if (position < underflowLimit) {
            // Read from underflow buffer
            result = underflowBuffer.get(position);
        } else {
            // Read from data buffer
            int p = position - underflowLimit + dataPosition;
            result = dataBuffer.get(p);
        }
        
        position++;
        return result;
    }
    
    /**
     * Absolute get method. Reads the byte at the given index without
     * modifying the current position.
     * 
     * @param index the index from which the byte will be read
     * @return the byte at the given index
     * @throws IndexOutOfBoundsException if index is negative or not
     *         smaller than the buffer's limit
     */
    public byte get(int index) {
        if (index < 0 || index >= limit) {
            throw new IndexOutOfBoundsException();
        }
        
        if (index < underflowLimit) {
            return underflowBuffer.get(index);
        } else {
            int p = index - underflowLimit + dataPosition;
            return dataBuffer.get(p);
        }
    }

    /**
     * Returns the virtual index of the first occurrence of the specified
     * byte in this composite buffer, searching from the current position
     * up to (but not including) the current limit.
     * <p>
     * This method respects the buffer's position and limit, similar to how
     * other ByteBuffer operations work. The returned index is an absolute
     * virtual index (not relative to position).
     * 
     * @param b the byte to search for
     * @return the virtual index of the first occurrence of the byte, or -1
     *         if not found within the position-to-limit range
     */
    public int indexOf(byte b) {
        // Search from current position to limit
        for (int i = position; i < limit; i++) {
            if (i < underflowLimit) {
                if (underflowBuffer.get(i) == b) {
                    return i;
                }
            } else {
                int p = i - underflowLimit + dataPosition;
                if (dataBuffer.get(p) == b) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    /**
     * Sets this buffer's position. If the mark is defined and larger
     * than the new position then it is discarded.
     * 
     * @param newPosition the new position value; must be non-negative
     *        and no larger than the current limit
     * @return this buffer for chaining
     * @throws IllegalArgumentException if newPosition is negative or
     *         larger than the current limit
     */
    public CompositeByteBuffer position(int newPosition) {
        if (newPosition < 0 || newPosition > limit) {
            throw new IllegalArgumentException("Invalid position: " + newPosition + " (limit=" + limit + ", capacity=" + capacity + ")");
        }
        if (mark > newPosition) {
            mark = -1;
        }
        this.position = newPosition;
        return this;
    }
    
    /**
     * Sets this buffer's mark at its current position.
     * 
     * @return this buffer for chaining
     */
    public CompositeByteBuffer mark() {
        mark = position;
        return this;
    }
    
    /**
     * Resets this buffer's position to the previously-marked position.
     * 
     * @return this buffer for chaining
     * @throws InvalidMarkException if the mark has not been set
     */
    public CompositeByteBuffer reset() {
        if (mark < 0) {
            throw new InvalidMarkException();
        }
        position = mark;
        return this;
    }
    
    /**
     * Clears this buffer. The position is set to zero, the limit is set
     * to the capacity, and the mark is discarded.
     * 
     * @return this buffer for chaining
     */
    public CompositeByteBuffer clear() {
        position = 0;
        limit = capacity;
        mark = -1;
        return this;
    }
    
    /**
     * Flips this buffer. The limit is set to the current position and
     * then the position is set to zero. If the mark is defined then it
     * is discarded.
     * 
     * @return this buffer for chaining
     */
    public CompositeByteBuffer flip() {
        limit = position;
        position = 0;
        mark = -1;
        return this;
    }
    
    /**
     * Rewinds this buffer. The position is set to zero and the mark is
     * discarded.
     * 
     * @return this buffer for chaining
     */
    public CompositeByteBuffer rewind() {
        position = 0;
        mark = -1;
        return this;
    }
        
    /**
     * Creates a new composite byte buffer that shares this buffer's
     * content. The two buffers' position, limit, and mark values will be
     * independent. The new buffer's capacity, limit, and position will
     * be identical to those of this buffer. The two buffers will share
     * the same underlying data buffers.
     * 
     * @return a new CompositeByteBuffer that shares content with this
     *         buffer
     */
    public CompositeByteBuffer slice() {
        CompositeByteBuffer sliced = new CompositeByteBuffer();
        sliced.underflowBuffer = this.underflowBuffer;
        sliced.dataBuffer = this.dataBuffer;
        sliced.underflowLimit = this.underflowLimit;
        sliced.capacity = this.capacity;
        sliced.limit = this.limit;
        sliced.position = this.position;
        sliced.mark = -1; // Mark is not copied in ByteBuffer.slice()
        return sliced;
    }
    
    /**
     * Creates a new composite byte buffer that shares this buffer's
     * content. Identical to slice() except that the mark is also copied.
     * The two buffers' position, limit, and mark values will be
     * independent, but they will share the same underlying data buffers.
     * 
     * @return a new CompositeByteBuffer that shares content with this
     *         buffer
     */
    public CompositeByteBuffer duplicate() {
        CompositeByteBuffer dup = new CompositeByteBuffer();
        dup.underflowBuffer = this.underflowBuffer;
        dup.dataBuffer = this.dataBuffer;
        dup.underflowLimit = this.underflowLimit;
        dup.capacity = this.capacity;
        dup.limit = this.limit;
        dup.position = this.position;
        dup.mark = this.mark; // Mark IS copied in ByteBuffer.duplicate()
        return dup;
    }
    
    /**
     * Tells whether or not this buffer is backed by an accessible byte
     * array. Always returns false for CompositeByteBuffer since it's
     * backed by multiple buffers.
     * 
     * @return false
     */
    public boolean hasArray() {
        return false;
    }
    
    /**
     * Returns the byte array that backs this buffer (optional
     * operation). This method is not supported for CompositeByteBuffer.
     * 
     * @return (never returns)
     * @throws UnsupportedOperationException always
     */
    public byte[] array() {
        throw new UnsupportedOperationException("CompositeByteBuffer does not support direct array access");
    }
    
    /**
     * Returns the offset within this buffer's backing array of the first
     * element of the buffer (optional operation). This method is not
     * supported for CompositeByteBuffer.
     * 
     * @return (never returns)
     * @throws UnsupportedOperationException always
     */
    public int arrayOffset() {
        throw new UnsupportedOperationException("CompositeByteBuffer does not support direct array access");
    }
    
    /**
     * Relative bulk get method. Copies bytes from this buffer into the
     * destination array. This method uses efficient bulk transfer
     * operations on the underlying buffers.
     * 
     * @param dst the destination byte array
     * @param offset the offset in the destination array to start writing
     * @param length the number of bytes to copy
     * @return this buffer for chaining
     * @throws BufferUnderflowException if there are fewer than length
     *         bytes remaining in this buffer
     * @throws IndexOutOfBoundsException if the preconditions on the
     *         offset and length parameters do not hold
     */
    public CompositeByteBuffer get(byte[] dst, int offset, int length) {
        if (length > remaining()) {
            throw new BufferUnderflowException();
        }
        
        if (offset < 0 || length < 0 || offset + length > dst.length) {
            throw new IndexOutOfBoundsException();
        }
        
        if (length == 0) {
            return this;
        }
        
        int bytesToCopy = length;
        int dstOffset = offset;
        
        // Copy from underflow buffer if we're in that region
        if (position < underflowLimit && underflowBuffer != null) {
            int bytesFromUnderflow = Math.min(bytesToCopy, underflowLimit - position);
            
            // Save and restore underflow buffer state
            int oldPos = underflowBuffer.position();
            int oldLim = underflowBuffer.limit();
            underflowBuffer.position(position).limit(position + bytesFromUnderflow);
            underflowBuffer.get(dst, dstOffset, bytesFromUnderflow);
            underflowBuffer.position(oldPos).limit(oldLim);
            
            position += bytesFromUnderflow;
            dstOffset += bytesFromUnderflow;
            bytesToCopy -= bytesFromUnderflow;
        }
        
        // Copy from data buffer if we have more to copy
        if (bytesToCopy > 0 && dataBuffer != null) {
            int dataPos = Math.max(0, position - underflowLimit) + dataPosition;
            
            // Save and restore data buffer state
            int oldPos = dataBuffer.position();
            int oldLim = dataBuffer.limit();
            dataBuffer.position(dataPos).limit(dataPos + bytesToCopy);
            dataBuffer.get(dst, dstOffset, bytesToCopy);
            dataBuffer.position(oldPos).limit(oldLim);
            
            position += bytesToCopy;
        }
        
        return this;
    }
    
    /**
     * Relative bulk get method. Copies bytes from this buffer into the
     * destination array. This method is equivalent to
     * {@code get(dst, 0, dst.length)}.
     * 
     * @param dst the destination byte array
     * @return this buffer for chaining
     * @throws BufferUnderflowException if there are fewer than
     *         dst.length bytes remaining in this buffer
     */
    public CompositeByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }
    
    /**
     * Decodes bytes from this composite buffer into the given character
     * buffer using the given charset decoder. This method handles the
     * complexity of decoding across the underflow/data buffer boundary.
     * <p>
     * If decoding from the underflow buffer results in {@code UNDERFLOW}
     * and there is still data in the data buffer, this method will
     * compact the buffers (merging remaining bytes) and retry the decode
     * operation. This handles the case where a multi-byte character
     * spans the boundary between the two buffers.
     * <p>
     * The composite buffer's position is updated to reflect the number
     * of bytes consumed during decoding.
     * 
     * @param decoder the charset decoder to use
     * @param out the character buffer to decode into
     * @param endOfInput true if this is the last chunk of input
     * @return a {@code CoderResult} describing the result of the decode
     *         operation
     */
    public CoderResult decode(CharsetDecoder decoder, CharBuffer out,
                              boolean endOfInput) {
        CoderResult result;
        
        // Determine which buffer(s) we're decoding from
        boolean hasUnderflow = (underflowBuffer != null && 
                                underflowLimit > 0);
        boolean hasData = (dataBuffer != null && 
                          dataLimit > dataPosition);
        
        if (!hasUnderflow && !hasData) {
            // No data to decode
            return CoderResult.UNDERFLOW;
        }
        
        if (hasUnderflow && hasData && position < underflowLimit) {
            // We have both buffers and position is in underflow region
            // Decode from underflow first
            int oldPos = underflowBuffer.position();
            int oldLim = underflowBuffer.limit();
            underflowBuffer.position(position).limit(underflowLimit);
            
            result = decoder.decode(underflowBuffer, out, false);
            
            // Update composite position based on bytes consumed
            int bytesConsumed = underflowBuffer.position() - position;
            position += bytesConsumed;
            
            underflowBuffer.position(oldPos).limit(oldLim); // Restore
            
            // Handle UNDERFLOW with data buffer remaining
            // Need to merge buffers if:
            // 1. We've consumed all of underflow (position >= underflowLimit), OR
            // 2. We consumed nothing (bytesConsumed == 0) - incomplete multi-byte sequence
            if (result.isUnderflow() && hasData && 
                (position >= underflowLimit || bytesConsumed == 0)) {
                // Multi-byte character might span boundary
                // Compact to merge buffers and try again
                compact();
                flip();
                
                // Retry decode from merged buffer
                if (underflowBuffer != null && underflowLimit > 0) {
                    oldPos = underflowBuffer.position();
                    oldLim = underflowBuffer.limit();
                    underflowBuffer.position(position).limit(underflowLimit);
                    
                    result = decoder.decode(underflowBuffer, out, 
                                           endOfInput);
                    
                    bytesConsumed = underflowBuffer.position() - position;
                    position += bytesConsumed;
                    
                    underflowBuffer.position(oldPos).limit(oldLim);
                }
                
                return result;
            }
            
            // If we finished underflow and have more to decode
            if (result.isUnderflow() && position >= underflowLimit && 
                hasData) {
                // Continue with data buffer
                int oldDataPos = dataBuffer.position();
                int oldDataLim = dataBuffer.limit();
                dataBuffer.position(dataPosition).limit(dataLimit);
                
                result = decoder.decode(dataBuffer, out, endOfInput);
                
                bytesConsumed = dataBuffer.position() - dataPosition;
                position += bytesConsumed;
                
                dataBuffer.position(oldDataPos).limit(oldDataLim);
            }
            
            return result;
            
        } else if (hasUnderflow && position < underflowLimit) {
            // Only underflow buffer (or data buffer exhausted)
            int oldPos = underflowBuffer.position();
            int oldLim = underflowBuffer.limit();
            underflowBuffer.position(position).limit(underflowLimit);
            
            result = decoder.decode(underflowBuffer, out, endOfInput);
            
            int bytesConsumed = underflowBuffer.position() - position;
            position += bytesConsumed;
            
            underflowBuffer.position(oldPos).limit(oldLim);
            return result;
            
        } else if (hasData) {
            // Only data buffer (or past underflow)
            int dataPos = Math.max(0, position - underflowLimit) + 
                         dataPosition;
            int oldPos = dataBuffer.position();
            int oldLim = dataBuffer.limit();
            dataBuffer.position(dataPos).limit(dataLimit);
            
            result = decoder.decode(dataBuffer, out, endOfInput);
            
            int bytesConsumed = dataBuffer.position() - dataPos;
            position += bytesConsumed;
            
            dataBuffer.position(oldPos).limit(oldLim);
            return result;
            
        } else {
            // Should not reach here
            return CoderResult.UNDERFLOW;
        }
    }
    
}
