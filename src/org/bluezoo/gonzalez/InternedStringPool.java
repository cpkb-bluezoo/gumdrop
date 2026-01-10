/*
 * InternedStringPool.java
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

package org.bluezoo.gonzalez;

import java.nio.CharBuffer;

/**
 * Zero-allocation string interning pool using a sparse array.
 * 
 * <p>This pool uses a sparse array indexed by hash value to cache String objects
 * and look them up from CharBuffer windows without creating temporary String objects.
 * Limited linear probing handles hash collisions.
 * 
 * <p>When string interning is enabled, all cached strings are stored in the JVM
 * string intern pool, ensuring that strings passed to SAX handlers are already interned.
 * 
 * <p>Usage:
 * <pre>
 * InternedStringPool pool = new InternedStringPool();
 * pool.setStringInterning(true);  // Enable JVM string interning
 * String interned = pool.intern(charBuffer);  // Zero allocation if already pooled
 * </pre>
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
class InternedStringPool {
    
    /**
     * Maximum number of linear probes before falling back to allocation.
     * Limited to prevent long searches that would negate the performance benefit.
     */
    private static final int MAX_PROBES = 4;
    
    /**
     * Sparse array of cached strings, indexed by hash value.
     */
    private final String[] sparseArray;
    
    /**
     * Capacity of sparse array (power of 2 for fast modulo).
     */
    private final int capacity;
    
    /**
     * Mask for fast modulo: hash & mask == hash % capacity (when capacity is power of 2).
     */
    private final int mask;
    
    /**
     * Whether to use JVM string interning for cached strings.
     */
    private boolean stringInterning;
        
        /**
     * Creates a new intern pool with default capacity (512 slots).
     * Suitable for typical XML documents with ~100 unique element/attribute names.
         */
    InternedStringPool() {
        this(512);
    }
    
    /**
     * Creates a new intern pool with specified capacity.
     * Capacity will be rounded up to the next power of 2.
     * 
     * @param initialCapacity the initial capacity (will be rounded to power of 2)
     */
    InternedStringPool(int initialCapacity) {
        // Round up to next power of 2 for fast modulo
        this.capacity = nextPowerOfTwo(Math.max(256, initialCapacity));
        this.mask = capacity - 1;
        this.sparseArray = new String[capacity];
        this.stringInterning = false;
        }
        
    /**
     * Sets whether to use JVM string interning.
     * When enabled, all strings stored in the pool are interned,
     * ensuring strings passed to SAX handlers are canonical.
     * 
     * @param enabled true to enable string interning
     */
    void setStringInterning(boolean enabled) {
        this.stringInterning = enabled;
    }
    
    /**
     * Interns a string from a CharBuffer window, returning a canonical String instance.
     * 
     * <p>If the string is already in the pool, returns the existing String without allocation.
     * Otherwise, creates a new String from the CharBuffer, optionally interns it via the JVM,
     * stores it in the pool (if space available within probe limit), and returns it.
     * 
     * <p>Uses limited linear probing to handle hash collisions. After MAX_PROBES attempts,
     * falls back to creating a new String without caching to avoid long searches.
     * 
     * @param buffer the CharBuffer containing the string (uses position/limit as window)
     * @return the interned String
     */
    String intern(CharBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        
        int hash = computeHash(buffer);
        int index = hash & mask;  // Fast modulo for power-of-2
        
        // Limited linear probing
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int slot = (index + probe) & mask;
            String candidate = sparseArray[slot];
            
            if (candidate == null) {
                // Empty slot - create, store, and return
                String newString = buffer.toString();
                if (stringInterning) {
                    newString = newString.intern();
                }
                sparseArray[slot] = newString;
                return newString;
            }
            
            // Non-empty slot - check if it matches
            if (matches(candidate, buffer)) {
                return candidate;  // Cache hit!
            }
            
            // Hash collision - try next slot
        }
        
        // Exceeded probe limit - create without caching to avoid polluting cache
        String newString = buffer.toString();
        if (stringInterning) {
            newString = newString.intern();
        }
        return newString;
    }
    
    /**
     * Interns a String, returning a canonical String instance.
     * 
     * <p>If string interning is enabled, returns the JVM-interned version.
     * Otherwise, checks the pool and stores/returns as usual.
     * 
     * <p>This overload is specifically for namespace URIs, which are initially
     * attribute values but have very limited variety (5-10 per document) and are
     * reused constantly during namespace processing. They should be interned for efficiency.
     * 
     * <p><b>Note:</b> Regular attribute values should NOT be interned - they use
     * pooled StringBuilder with lazy toString(). Only namespace URIs (from xmlns attributes)
     * and pre-bound namespace constants should use this method.
     * 
     * @param str the String to intern (typically a namespace URI)
     * @return the interned String
     */
    String intern(String str) {
        if (str == null) {
            return null;
        }
        
        // If string interning enabled, use JVM intern pool directly
        if (stringInterning) {
            return str.intern();
        }
        
        // Otherwise, check our sparse array
        int hash = str.hashCode();
        int index = hash & mask;
        
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int slot = (index + probe) & mask;
            String candidate = sparseArray[slot];
            
            if (candidate == null) {
                // Empty slot - store and return
                sparseArray[slot] = str;
                return str;
            }
            
            if (candidate.equals(str)) {
                return candidate;  // Cache hit!
            }
        }
        
        // Exceeded probe limit - just return the string
        return str;
    }
    
    /**
     * Checks if a cached String matches the content of a CharBuffer window.
     * 
     * @param str the cached string
     * @param buffer the CharBuffer window (position to limit)
     * @return true if the string matches the buffer content
     */
    private boolean matches(String str, CharBuffer buffer) {
        int len = buffer.remaining();
        if (str.length() != len) {
            return false;
        }
        
        int pos = buffer.position();
        for (int i = 0; i < len; i++) {
            if (str.charAt(i) != buffer.get(pos + i)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Computes hash value for a CharBuffer window.
     * Uses the same algorithm as String.hashCode() for consistency.
     * 
     * @param buffer the CharBuffer window (position to limit)
     * @return the hash value
     */
    private int computeHash(CharBuffer buffer) {
        int h = 0;
        int pos = buffer.position();
        int len = buffer.remaining();
        
        for (int i = 0; i < len; i++) {
            h = 31 * h + buffer.get(pos + i);
        }
        
        return h;
    }
    
    /**
     * Returns the next power of 2 greater than or equal to n.
     * 
     * @param n the input value
     * @return the next power of 2
     */
    private int nextPowerOfTwo(int n) {
        // Handle edge cases
        if (n <= 0) return 1;
        if ((n & (n - 1)) == 0) return n;  // Already power of 2
        
        // Round up to next power of 2
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        n++;
        
        return n;
    }
    
    /**
     * Clears all interned strings from the pool.
     */
    void clear() {
        for (int i = 0; i < capacity; i++) {
            sparseArray[i] = null;
        }
    }
    
    /**
     * Returns the capacity of the sparse array.
     * 
     * @return the capacity
     */
    int capacity() {
        return capacity;
    }
}
