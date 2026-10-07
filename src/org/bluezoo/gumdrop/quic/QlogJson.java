/*
 * QlogJson.java
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

package org.bluezoo.gumdrop.quic;

/**
 * Builds the JSON data object of a qlog event, for the protocols that run on a QUIC connection as well as the transport. The core module has no JSON
 * library, and the objects are small and flat, so this writes the text
 * directly.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QlogJson {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StringBuilder sb = new StringBuilder(160);

    private QlogJson() {
        sb.append('{');
    }

    /** Starts an object. */
    public static QlogJson object() {
        return new QlogJson();
    }

    private void separate() {
        char last = sb.charAt(sb.length() - 1);
        if (last != '{' && last != '[' && last != ':') {
            sb.append(',');
        }
    }

    private void key(String key) {
        separate();
        string(key);
        sb.append(':');
    }

    /** Appends value as a JSON string, quoted and escaped. */
    public static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 15]);
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    private void string(String value) {
        appendString(sb, value);
    }

    public QlogJson put(String key, String value) {
        key(key);
        string(value);
        return this;
    }

    public QlogJson put(String key, long value) {
        key(key);
        sb.append(value);
        return this;
    }

    public QlogJson put(String key, boolean value) {
        key(key);
        sb.append(value);
        return this;
    }

    /** Writes a duration given in microseconds as milliseconds, which is what qlog counts in. */
    public QlogJson putMillis(String key, long micros) {
        key(key);
        long abs = Math.abs(micros);
        long fraction = abs % 1000L;
        if (micros < 0) {
            sb.append('-');
        }
        sb.append(abs / 1000L).append('.');
        if (fraction < 100) {
            sb.append('0');
        }
        if (fraction < 10) {
            sb.append('0');
        }
        sb.append(fraction);
        return this;
    }

    /** Writes bytes as the lowercase hexadecimal string qlog uses. */
    public QlogJson putHex(String key, byte[] value) {
        key(key);
        sb.append('"');
        for (int i = 0; i < value.length; i++) {
            sb.append(HEX[(value[i] >> 4) & 15]).append(HEX[value[i] & 15]);
        }
        sb.append('"');
        return this;
    }

    /** Writes a number as given: for a value that is not an integer, such as 1.125. */
    public QlogJson put(String key, double value) {
        key(key);
        sb.append(value);
        return this;
    }

    /** Embeds JSON text built elsewhere. */
    public QlogJson putRaw(String key, String json) {
        key(key);
        sb.append(json);
        return this;
    }

    public QlogJson beginObject(String key) {
        key(key);
        sb.append('{');
        return this;
    }

    public QlogJson endObject() {
        sb.append('}');
        return this;
    }

    public QlogJson beginArray(String key) {
        key(key);
        sb.append('[');
        return this;
    }

    public QlogJson endArray() {
        sb.append(']');
        return this;
    }

    /** Adds an element that is already JSON text to the open array. */
    public QlogJson itemRaw(String json) {
        separate();
        sb.append(json);
        return this;
    }

    /** Adds an element to the open array. */
    public QlogJson item(String value) {
        separate();
        string(value);
        return this;
    }

    /** Ends the object and returns its text. */
    public String build() {
        sb.append('}');
        return sb.toString();
    }

    /** Returns the lowercase hexadecimal form of bytes. */
    public static String hex(byte[] value) {
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            out[2 * i] = HEX[(value[i] >> 4) & 15];
            out[2 * i + 1] = HEX[value[i] & 15];
        }
        return new String(out);
    }
}
