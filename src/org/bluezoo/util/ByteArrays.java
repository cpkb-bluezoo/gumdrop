/*
 * ByteArrays.java
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

package org.bluezoo.util;

/**
 * Utility methods for byte array operations.
 * 
 * <p>This class provides efficient implementations for common byte array
 * operations including hexadecimal encoding/decoding and comparison.</p>
 * 
 * <h4>Hexadecimal Conversion</h4>
 * <p>The {@link #toHexString(byte[])} and {@link #toByteArray(String)} methods
 * provide efficient conversion between byte arrays and hexadecimal strings.
 * These are optimized for performance, avoiding the overhead of 
 * {@code String.format()} or {@code Integer.toHexString()}.</p>
 * 
 * <h4>Usage Examples</h4>
 * <pre>{@code
 * // Convert MD5 hash to hex string
 * MessageDigest md = MessageDigest.getInstance("MD5");
 * md.update(data);
 * String hashHex = ByteArrays.toHexString(md.digest());
 * 
 * // Parse hex string back to bytes
 * byte[] bytes = ByteArrays.toByteArray("48656c6c6f");
 * 
 * // Constant-time comparison for security-sensitive contexts
 * boolean match = ByteArrays.equals(computed, expected);
 * }</pre>
 * 
 * <h4>Thread Safety</h4>
 * <p>All methods in this class are stateless and thread-safe.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ByteArrays {

    // Prevent instantiation
    private ByteArrays() {
    }

    /**
     * Decodes a hexadecimal string into a byte array.
     * 
     * <p>Each pair of hexadecimal characters is converted to a single byte.
     * Both uppercase and lowercase hex digits are accepted.</p>
     * 
     * @param hexString the hexadecimal string to decode (must have even length)
     * @return the decoded byte array
     * @throws IllegalArgumentException if the string is null, has odd length,
     *         or contains non-hexadecimal characters
     */
    public static byte[] toByteArray(String hexString) {
        if (hexString == null || hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must not be null and must have an even length");
        }
        byte[] byteArray = new byte[hexString.length() / 2];
        int i = 0;
        int j = 0;
        while (i < hexString.length()) {
            int hi = Character.digit(hexString.charAt(i++), 16);
            int lo = Character.digit(hexString.charAt(i++), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Not a hexadecimal string: " + hexString);
            }
            byteArray[j++] = (byte) ((hi << 4) | lo);
        }
        return byteArray;
    }

    /**
     * Encodes a byte array as a lowercase hexadecimal string.
     * 
     * <p>This implementation is optimized for performance, using direct
     * character array manipulation rather than {@code String.format()}
     * or {@code StringBuilder.append()}.</p>
     * 
     * <p>The output uses lowercase hexadecimal digits (0-9, a-f).</p>
     * 
     * @param byteArray the byte array to encode
     * @return the hexadecimal string representation
     * @throws IllegalArgumentException if the byte array is null
     */
    public static String toHexString(byte[] byteArray) {
        if (byteArray == null) {
            throw new IllegalArgumentException("Byte array must not be null");
        }
        char[] charArray = new char[byteArray.length * 2];
        int i = 0;
        int j = 0;
        while (i < byteArray.length) {
            int c = byteArray[i++] & 0xff;
            charArray[j++] = Character.forDigit((c >> 4) & 0xf, 16);
            charArray[j++] = Character.forDigit(c & 0xf, 16);
        }
        return new String(charArray);
    }

    /**
     * Compares two byte arrays for equality.
     * 
     * <p>This method returns {@code false} as soon as a mismatch is found,
     * making it efficient for general-purpose comparison. For security-sensitive
     * comparisons where timing attacks are a concern (e.g., comparing MACs),
     * use {@link #equalsConstantTime(byte[], byte[])} instead.</p>
     * 
     * @param b1 the first byte array
     * @param b2 the second byte array
     * @return {@code true} if both arrays are non-null, have the same length,
     *         and contain identical bytes; {@code false} otherwise
     */
    public static boolean equals(byte[] b1, byte[] b2) {
        if (b1 == null || b2 == null || b1.length != b2.length) {
            return false;
        }
        for (int i = 0; i < b1.length; i++) {
            if (b1[i] != b2[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two byte arrays for equality in constant time.
     * 
     * <p>This method always iterates through all bytes of equal-length arrays,
     * preventing timing attacks that could reveal information about the
     * position of mismatched bytes. Use this for security-sensitive comparisons
     * such as MAC verification or password hash comparison.</p>
     * 
     * <p>Returns {@code false} immediately if either array is null or if
     * the arrays have different lengths (length comparison is not constant-time
     * as it is typically not security-sensitive).</p>
     * 
     * @param b1 the first byte array
     * @param b2 the second byte array
     * @return {@code true} if both arrays are non-null, have the same length,
     *         and contain identical bytes; {@code false} otherwise
     */
    public static boolean equalsConstantTime(byte[] b1, byte[] b2) {
        if (b1 == null || b2 == null || b1.length != b2.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < b1.length; i++) {
            result |= b1[i] ^ b2[i];
        }
        return result == 0;
    }

}
