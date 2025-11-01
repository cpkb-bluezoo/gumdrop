/*
 * ByteArrays.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.util;

/**
 * Utility functions dealing with byte arrays.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ByteArrays {

    /**
     * Returns the given hexadecimal string decoded as a byte array.
     */
    public static byte[] toByteArray(String hexString) {
        if (hexString == null || hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must not be null and must have an even length");
        }
        byte[] byteArray = new byte[hexString.length() / 2];
        int i = 0, j = 0;
        while (i < hexString.length()) {
            int hi = Character.digit(hexString.charAt(i++), 0x10);
            int lo = Character.digit(hexString.charAt(i++), 0x10);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Not a hexadecimal string: "+hexString);
            }
            byteArray[j++] = (byte) ((hi << 4) | lo);
        }
        return byteArray;
    }

    /**
     * Returns the given byte array encoded as a hexadecimal string.
     */
    public static String toHexString(byte[] byteArray) {
        if (byteArray == null) {
            throw new IllegalArgumentException("Byte array must not be null");
        }
        char[] charArray = new char[byteArray.length * 2];
        int i = 0, j = 0;
        while (i < byteArray.length) {
            int c = byteArray[i++] & 0xff;
            charArray[j++] = Character.forDigit((c >> 4) & 0xf, 0x10);
            charArray[j++] = Character.forDigit(c & 0xf, 0x10);
        }
        return new String(charArray);
    }

    /**
     * Indicates whether the given byte arrays are equal.
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

}
