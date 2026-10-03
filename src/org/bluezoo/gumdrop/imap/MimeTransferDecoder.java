/*
 * MimeTransferDecoder.java
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

/**
 * Incremental decoder for the base64 and quoted-printable content transfer
 * encodings (RFC 2045), fed in arbitrary chunks. Identity encodings are
 * passed through.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MimeTransferDecoder {

    /** Receives decoded octets. */
    interface Output {
        void write(byte[] buf, int off, int len);
    }

    private enum Mode { IDENTITY, BASE64, QUOTED_PRINTABLE }

    private final Mode mode;
    private final Output output;
    private final byte[] out = new byte[4096];
    private int outLength;

    // base64
    private int bits;
    private int bitCount;
    private boolean padded;

    // quoted-printable: 0 normal, 1 after '=', 2 after '=' and one hex digit,
    // 3 after '=' CR
    private int qpState;
    private int qpHigh;

    private MimeTransferDecoder(Mode mode, Output output) {
        this.mode = mode;
        this.output = output;
    }

    /**
     * Creates a decoder for the encoding.
     *
     * @param encoding the lower-case Content-Transfer-Encoding value, or null
     * @param output where decoded octets go
     * @return the decoder, or null when the encoding is not one that can be
     *         decoded to a binary representation (an unknown extension)
     */
    static MimeTransferDecoder create(String encoding, Output output) {
        if (encoding == null || encoding.equals("7bit")
                || encoding.equals("8bit") || encoding.equals("binary")) {
            return new MimeTransferDecoder(Mode.IDENTITY, output);
        }
        if (encoding.equals("base64")) {
            return new MimeTransferDecoder(Mode.BASE64, output);
        }
        if (encoding.equals("quoted-printable")) {
            return new MimeTransferDecoder(Mode.QUOTED_PRINTABLE, output);
        }
        return null;
    }

    void write(byte[] buf, int off, int len) {
        if (mode == Mode.IDENTITY) {
            output.write(buf, off, len);
            return;
        }
        for (int i = off; i < off + len; i++) {
            int b = buf[i] & 0xff;
            if (mode == Mode.BASE64) {
                base64(b);
            } else {
                quotedPrintable(b);
            }
        }
    }

    void finish() {
        if (qpState == 1) {
            emit('=');
        } else if (qpState == 2) {
            emit('=');
            emit(hexChar(qpHigh));
        }
        qpState = 0;
        flush();
    }

    private void emit(int value) {
        if (outLength == out.length) {
            flush();
        }
        out[outLength++] = (byte) value;
    }

    private void flush() {
        if (outLength > 0) {
            output.write(out, 0, outLength);
            outLength = 0;
        }
    }

    private static int sextet(int b) {
        if (b >= 'A' && b <= 'Z') {
            return b - 'A';
        }
        if (b >= 'a' && b <= 'z') {
            return b - 'a' + 26;
        }
        if (b >= '0' && b <= '9') {
            return b - '0' + 52;
        }
        if (b == '+') {
            return 62;
        }
        if (b == '/') {
            return 63;
        }
        return -1;
    }

    private void base64(int b) {
        if (padded) {
            return;
        }
        if (b == '=') {
            padded = true;
            return;
        }
        int v = sextet(b);
        if (v < 0) {
            return;
        }
        bits = (bits << 6) | v;
        bitCount += 6;
        if (bitCount >= 8) {
            bitCount -= 8;
            emit((bits >> bitCount) & 0xff);
            bits &= (1 << bitCount) - 1;
        }
    }

    private static int hex(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        return -1;
    }

    private void quotedPrintable(int b) {
        switch (qpState) {
            case 0:
                if (b == '=') {
                    qpState = 1;
                } else {
                    emit(b);
                }
                break;
            case 1:
                if (b == '\r') {
                    qpState = 3;
                } else if (b == '\n') {
                    qpState = 0;
                } else if (hex(b) >= 0) {
                    qpHigh = hex(b);
                    qpState = 2;
                } else {
                    emit('=');
                    qpState = 0;
                    quotedPrintable(b);
                }
                break;
            case 2:
                if (hex(b) >= 0) {
                    emit((qpHigh << 4) | hex(b));
                    qpState = 0;
                } else {
                    emit('=');
                    emit(hexChar(qpHigh));
                    qpState = 0;
                    quotedPrintable(b);
                }
                break;
            default:
                qpState = 0;
                if (b != '\n') {
                    quotedPrintable(b);
                }
                break;
        }
    }

    private static int hexChar(int v) {
        return v < 10 ? '0' + v : 'A' + v - 10;
    }
}
