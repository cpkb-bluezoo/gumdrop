/*
 * GrpcMetadata.java
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

package org.bluezoo.gumdrop.grpc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * The metadata of a gRPC call: the custom headers and trailers that travel
 * with the request and the response, as an ordered multimap.
 *
 * <p>A key is lower case letters, digits, {@code '-'}, {@code '_'} and
 * {@code '.'}. A value is printable ASCII, except that a key ending in
 * {@code -bin} carries arbitrary bytes, which are base64 encoded on the wire;
 * use {@link #addBinary} and {@link #getBinary} for those. Keys that begin
 * {@code grpc-}, and {@code content-type} and {@code te}, are used by the
 * protocol itself and cannot be added.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class GrpcMetadata {

    private static final String BINARY_SUFFIX = "-bin";

    private final List<String> keys = new ArrayList<String>();
    /** The wire form of each value: base64 text for a binary key. */
    private final List<String> values = new ArrayList<String>();

    /**
     * Adds a text value.
     *
     * @param key the key; must not end in {@code -bin}
     * @param value printable ASCII
     * @throws IllegalArgumentException if the key or value is not allowed
     */
    public void add(String key, String value) {
        checkKey(key);
        if (key.endsWith(BINARY_SUFFIX)) {
            throw new IllegalArgumentException("Use addBinary for key " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException("Value of " + key
                        + " must be printable ASCII");
            }
        }
        keys.add(key);
        values.add(value);
    }

    /**
     * Adds a binary value.
     *
     * @param key the key; must end in {@code -bin}
     * @param value the bytes
     * @throws IllegalArgumentException if the key is not allowed
     */
    public void addBinary(String key, byte[] value) {
        checkKey(key);
        if (!key.endsWith(BINARY_SUFFIX)) {
            throw new IllegalArgumentException("Binary key must end in -bin: " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        keys.add(key);
        values.add(Base64.getEncoder().encodeToString(value));
    }

    /**
     * Returns the first text value for a key.
     *
     * @param key the key
     * @return the value, or {@code null} if there is none
     */
    public String get(String key) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key)) {
                return values.get(i);
            }
        }
        return null;
    }

    /**
     * Returns every text value for a key, in the order received or added.
     *
     * @param key the key
     * @return the values, empty if there are none
     */
    public List<String> getAll(String key) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key)) {
                out.add(values.get(i));
            }
        }
        return out;
    }

    /**
     * Returns the first binary value for a key.
     *
     * @param key the key, ending in {@code -bin}
     * @return the bytes, or {@code null} if there is none
     */
    public byte[] getBinary(String key) {
        String value = get(key);
        return value == null ? null : decode(value);
    }

    /**
     * Returns every binary value for a key.
     *
     * @param key the key, ending in {@code -bin}
     * @return the values, empty if there are none
     */
    public List<byte[]> getAllBinary(String key) {
        List<byte[]> out = new ArrayList<byte[]>();
        List<String> all = getAll(key);
        for (int i = 0; i < all.size(); i++) {
            out.add(decode(all.get(i)));
        }
        return out;
    }

    /**
     * Returns the distinct keys, in the order they first appear.
     *
     * @return the keys
     */
    public List<String> keys() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++) {
            if (!out.contains(keys.get(i))) {
                out.add(keys.get(i));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns whether there are no entries.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * Returns the number of entries; a key with two values counts twice.
     * With {@link #keyAt} and {@link #wireValueAt} this lets a transport
     * send the entries.
     *
     * @return the number of entries
     */
    public int size() {
        return keys.size();
    }

    /**
     * Returns the key of an entry.
     *
     * @param index from 0 to {@code size() - 1}
     * @return the key
     */
    public String keyAt(int index) {
        return keys.get(index);
    }

    /**
     * Returns the value of an entry as it is sent: base64 for a binary key.
     *
     * @param index from 0 to {@code size() - 1}
     * @return the wire value
     */
    public String wireValueAt(int index) {
        return values.get(index);
    }

    /**
     * Adds an entry received from the wire. A binary value is decoded, with
     * or without padding as gRPC allows, and an entry that is not valid
     * metadata is dropped rather than failing the call.
     *
     * @param key the key as received
     * @param wireValue the value as received
     * @return true if the entry was kept
     */
    public boolean addReceived(String key, String wireValue) {
        try {
            if (key.endsWith(BINARY_SUFFIX)) {
                addBinary(key, decode(wireValue));
            } else {
                add(key, wireValue);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns whether a key is one the protocol itself uses.
     *
     * @param key the key
     * @return true if it is reserved
     */
    public static boolean isReserved(String key) {
        return key.startsWith("grpc-") || key.equals("content-type") || key.equals("te");
    }

    private static byte[] decode(String wireValue) {
        // base64 values may be sent with or without the trailing padding
        return Base64.getDecoder().decode(wireValue.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("Invalid metadata key: " + key);
            }
        }
        if (isReserved(key)) {
            throw new IllegalArgumentException("Reserved metadata key: " + key);
        }
    }
}
