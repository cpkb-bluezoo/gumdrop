/*
 * MailboxNameCodec.java
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

package org.bluezoo.gumdrop.mailbox;

import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes mailbox names for safe filesystem storage.
 * 
 * <p>This codec uses a modified Quoted-Printable encoding based on RFC 2047,
 * but adapted for filesystem safety. The encoding uses {@code =XX} hex format
 * (where XX are uppercase hex digits) for:
 * <ul>
 *   <li>All non-ASCII characters (UTF-8 bytes with value &gt; 127)</li>
 *   <li>Path separator characters ({@code /}, {@code \})</li>
 *   <li>Windows-forbidden characters ({@code : * ? " &lt; &gt; |})</li>
 *   <li>The escape character itself ({@code =})</li>
 *   <li>Control characters (0x00-0x1F)</li>
 *   <li>Null byte (0x00)</li>
 * </ul>
 * 
 * <p>Unlike full RFC 2047, this codec does not use the {@code =?charset?Q?...?=}
 * wrapper format, as the {@code ?} character is forbidden in Windows filenames.
 * The encoding is always UTF-8.
 * 
 * <p><b>Filesystem Compatibility:</b>
 * <ul>
 *   <li><b>Unix/Linux:</b> All characters except {@code /} and null are valid.
 *       This encoding handles both.</li>
 *   <li><b>Windows:</b> Forbidden characters {@code \ / : * ? " &lt; &gt; |}
 *       are all encoded. Reserved names (CON, PRN, etc.) are not specifically
 *       handled as they are rare in mailbox names.</li>
 *   <li><b>macOS:</b> Similar to Unix; {@code :} is also encoded for HFS+
 *       compatibility.</li>
 * </ul>
 * 
 * <p>The encoded output contains only: {@code A-Z a-z 0-9 . _ - =}
 * 
 * <p><b>Example:</b>
 * <pre>
 * "Données/été" → "Donn=C3=A9es=2F=C3=A9t=C3=A9"
 * "Reports:2025" → "Reports=3A2025"
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxNameCodec {

    /** Characters that are always safe and never encoded */
    private static final String SAFE_CHARS = 
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-";

    /** Hex digits for encoding */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private MailboxNameCodec() {
        // Utility class
    }

    /**
     * Encodes a mailbox name for safe filesystem storage.
     * 
     * @param mailboxName the mailbox name (may contain Unicode characters)
     * @return the encoded filename-safe string
     */
    public static String encode(String mailboxName) {
        if (mailboxName == null || mailboxName.isEmpty()) {
            return mailboxName;
        }

        // Fast path: check if encoding is needed
        boolean needsEncoding = false;
        for (int i = 0; i < mailboxName.length(); i++) {
            if (needsEncode(mailboxName.charAt(i))) {
                needsEncoding = true;
                break;
            }
        }

        if (!needsEncoding) {
            return mailboxName;
        }

        // Convert to UTF-8 and encode
        byte[] utf8Bytes = mailboxName.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(utf8Bytes.length * 3);

        for (byte b : utf8Bytes) {
            int value = b & 0xFF;
            
            if (value <= 127 && !needsEncode((char) value)) {
                // Safe ASCII character
                result.append((char) value);
            } else {
                // Encode as =XX
                result.append('=');
                result.append(HEX_DIGITS[(value >> 4) & 0x0F]);
                result.append(HEX_DIGITS[value & 0x0F]);
            }
        }

        return result.toString();
    }

    /**
     * Decodes a filesystem-encoded mailbox name back to its original form.
     * 
     * @param encoded the encoded filename
     * @return the decoded mailbox name
     */
    public static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }

        // Fast path: check if decoding is needed
        if (encoded.indexOf('=') < 0) {
            return encoded;
        }

        // Decode =XX sequences
        byte[] result = new byte[encoded.length()];
        int writePos = 0;

        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);

            if (c == '=' && i + 2 < encoded.length()) {
                // Try to decode hex sequence
                int high = hexDigitValue(encoded.charAt(i + 1));
                int low = hexDigitValue(encoded.charAt(i + 2));

                if (high >= 0 && low >= 0) {
                    result[writePos++] = (byte) ((high << 4) | low);
                    i += 2;
                } else {
                    // Invalid hex sequence, keep as-is
                    result[writePos++] = (byte) c;
                }
            } else {
                result[writePos++] = (byte) c;
            }
        }

        return new String(result, 0, writePos, StandardCharsets.UTF_8);
    }

    /**
     * Checks if a character needs to be encoded.
     * 
     * @param c the character to check
     * @return true if the character must be encoded
     */
    private static boolean needsEncode(char c) {
        // Non-ASCII always needs encoding
        if (c > 127) {
            return true;
        }

        // Control characters (including null)
        if (c < 32) {
            return true;
        }

        // The escape character itself
        if (c == '=') {
            return true;
        }

        // Path separators
        if (c == '/' || c == '\\') {
            return true;
        }

        // Windows forbidden characters
        if (c == ':' || c == '*' || c == '?' || c == '"' || 
            c == '<' || c == '>' || c == '|') {
            return true;
        }

        // Safe character
        return false;
    }

    /**
     * Returns the numeric value of a hex digit, or -1 if invalid.
     */
    private static int hexDigitValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    /**
     * Checks if a mailbox name contains characters that would require encoding.
     * 
     * @param mailboxName the mailbox name to check
     * @return true if encoding is required
     */
    public static boolean requiresEncoding(String mailboxName) {
        if (mailboxName == null || mailboxName.isEmpty()) {
            return false;
        }

        for (int i = 0; i < mailboxName.length(); i++) {
            if (needsEncode(mailboxName.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that an encoded mailbox name is safe for all filesystems.
     * 
     * @param encoded the encoded name to validate
     * @return true if the name is safe
     */
    public static boolean isValidEncodedName(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }

        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);

            if (c == '=') {
                // Must be followed by exactly two hex digits
                if (i + 2 >= encoded.length()) {
                    return false;
                }
                if (hexDigitValue(encoded.charAt(i + 1)) < 0 ||
                    hexDigitValue(encoded.charAt(i + 2)) < 0) {
                    return false;
                }
                i += 2;
            } else if (SAFE_CHARS.indexOf(c) < 0) {
                return false;
            }
        }

        return true;
    }

}

