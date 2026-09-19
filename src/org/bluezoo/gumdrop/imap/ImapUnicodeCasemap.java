/*
 * ImapUnicodeCasemap.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@code i;unicode-casemap} string comparison (RFC 5051) for I18NLEVEL=1.
 *
 * <p>Code points are case-folded using Unicode simple case folding via
 * {@link Character#toLowerCase(int)}, then compared as UTF-8 octets.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ImapUnicodeCasemap {

    private ImapUnicodeCasemap() {
    }

    /**
     * Compares two strings for IMAP SORT/THREAD collation.
     *
     * @return negative, zero, or positive as in {@link String#compareTo}
     */
    public static int compare(String left, String right) {
        String a = left != null ? left : "";
        String b = right != null ? right : "";
        byte[] ab = foldToUtf8(a);
        byte[] bb = foldToUtf8(b);
        int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            int diff = (ab[i] & 0xff) - (bb[i] & 0xff);
            if (diff != 0) {
                return diff;
            }
        }
        return ab.length - bb.length;
    }

    private static byte[] foldToUtf8(String s) {
        StringBuilder folded = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            cp = foldCodePoint(cp);
            folded.appendCodePoint(cp);
        }
        return folded.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int foldCodePoint(int cp) {
        // RFC 5051 / Unicode simple case folding special cases (subset)
        if (cp == 0x0130) { // LATIN CAPITAL LETTER I WITH DOT ABOVE
            return 0x0069;
        }
        if (cp == 0x212a) { // KELVIN SIGN
            return 0x006b;
        }
        if (cp == 0x212b) { // ANGSTROM SIGN
            return 0x00e5;
        }
        return Character.toLowerCase(cp);
    }

    /**
     * Returns true if {@code text} is empty after null-safe normalization.
     */
    public static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }

    /**
     * Normalizes whitespace runs in header-derived text for sort extraction.
     */
    public static String normalizeSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean inSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t' || cp == '\r' || cp == '\n' || cp == ' ') {
                if (!inSpace && sb.length() > 0) {
                    sb.append(' ');
                    inSpace = true;
                }
            } else {
                sb.appendCodePoint(cp);
                inSpace = false;
            }
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }
}
