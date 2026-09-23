/*
 * CertificateMessageParser.java
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

package org.bluezoo.gumdrop.tls;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;

/**
 * Push parser for a framed TLS 1.3 {@code Certificate} message
 * (RFC 8446 section 4.4.2) whose bytes arrive in arbitrary chunks.
 * Each certificate is parsed as soon as its DER is complete and the DER
 * discarded, so working memory is a single certificate plus the parsed
 * chain, never the whole message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class CertificateMessageParser {

    private static final int HEADER = 0;
    private static final int CONTEXT_LENGTH = 1;
    private static final int CONTEXT = 2;
    private static final int LIST_LENGTH = 3;
    private static final int ENTRY_LENGTH = 4;
    private static final int ENTRY_DATA = 5;
    private static final int EXT_LENGTH = 6;
    private static final int EXT_DATA = 7;
    private static final int DONE = 8;

    private final int maxMessageSize;
    private final List<X509Certificate> chain = new ArrayList<X509Certificate>();
    private final byte[] small = new byte[4];
    private int smallLen;
    private int state = HEADER;
    private int messageRemaining;
    private int listRemaining;
    private int fieldRemaining;
    private byte[] field;
    private int fieldPos;
    private byte[] context = new byte[0];

    CertificateMessageParser(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * Consumes the next bytes of the message.
     *
     * @throws HandshakeFormatException if the framing or a certificate is
     *         malformed (see {@link HandshakeFormatException#isBadCertificate})
     */
    void write(byte[] data, int offset, int length) throws HandshakeFormatException {
        int end = offset + length;
        int pos = offset;
        while (pos < end) {
            if (state == DONE) {
                throw new HandshakeFormatException("trailing data after Certificate message");
            }
            switch (state) {
                case HEADER:
                    pos = fill(data, pos, end, 4);
                    if (smallLen == 4) {
                        if ((small[0] & 0xff) != HandshakeMessages.HANDSHAKE_TYPE_CERTIFICATE) {
                            throw new HandshakeFormatException("decompressed certificate message is invalid", true);
                        }
                        messageRemaining = ((small[1] & 0xff) << 16) | ((small[2] & 0xff) << 8)
                                | (small[3] & 0xff);
                        smallLen = 0;
                        if (messageRemaining > maxMessageSize - 4) {
                            throw new HandshakeFormatException("decompressed certificate exceeds limit");
                        }
                        state = CONTEXT_LENGTH;
                    }
                    break;
                case CONTEXT_LENGTH:
                    pos = consume(data, pos, end, 1);
                    if (smallLen == 1) {
                        fieldRemaining = small[0] & 0xff;
                        smallLen = 0;
                        context = new byte[fieldRemaining];
                        fieldPos = 0;
                        state = CONTEXT;
                        if (fieldRemaining == 0) {
                            state = LIST_LENGTH;
                        }
                    }
                    break;
                case CONTEXT: {
                    int n = Math.min(fieldRemaining, end - pos);
                    accountBody(n);
                    System.arraycopy(data, pos, context, fieldPos, n);
                    fieldPos += n;
                    fieldRemaining -= n;
                    pos += n;
                    if (fieldRemaining == 0) {
                        state = LIST_LENGTH;
                    }
                    break;
                }
                case LIST_LENGTH:
                    pos = consume(data, pos, end, 3);
                    if (smallLen == 3) {
                        listRemaining = u24();
                        smallLen = 0;
                        if (listRemaining != messageRemaining) {
                            throw new HandshakeFormatException("inconsistent certificate_list length");
                        }
                        state = listRemaining == 0 ? DONE : ENTRY_LENGTH;
                    }
                    break;
                case ENTRY_LENGTH:
                    pos = consume(data, pos, end, 3);
                    if (smallLen == 3) {
                        fieldRemaining = u24();
                        smallLen = 0;
                        checkList(fieldRemaining);
                        field = new byte[fieldRemaining];
                        fieldPos = 0;
                        state = ENTRY_DATA;
                        if (fieldRemaining == 0) {
                            throw new HandshakeFormatException("empty certificate entry");
                        }
                    }
                    break;
                case ENTRY_DATA: {
                    int n = Math.min(fieldRemaining, end - pos);
                    accountBody(n);
                    System.arraycopy(data, pos, field, fieldPos, n);
                    fieldPos += n;
                    fieldRemaining -= n;
                    pos += n;
                    if (fieldRemaining == 0) {
                        try {
                            chain.add(CertificateVerifier.parseChain(Collections.singletonList(field)).get(0));
                        } catch (CertificateException e) {
                            throw new HandshakeFormatException(e.getMessage(), true);
                        }
                        field = null;
                        state = EXT_LENGTH;
                    }
                    break;
                }
                case EXT_LENGTH:
                    pos = consume(data, pos, end, 2);
                    if (smallLen == 2) {
                        fieldRemaining = ((small[0] & 0xff) << 8) | (small[1] & 0xff);
                        smallLen = 0;
                        checkList(fieldRemaining);
                        state = fieldRemaining == 0 ? afterEntry() : EXT_DATA;
                    }
                    break;
                case EXT_DATA: {
                    int n = Math.min(fieldRemaining, end - pos);
                    accountBody(n);
                    fieldRemaining -= n;
                    pos += n;
                    if (fieldRemaining == 0) {
                        state = afterEntry();
                    }
                    break;
                }
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private int afterEntry() {
        return listRemaining == 0 ? DONE : ENTRY_LENGTH;
    }

    private void checkList(int fieldLength) throws HandshakeFormatException {
        if (fieldLength > listRemaining) {
            throw new HandshakeFormatException("certificate entry overruns certificate_list");
        }
    }

    private void accountBody(int n) {
        messageRemaining -= n;
        if (state == ENTRY_DATA || state == EXT_DATA) {
            listRemaining -= n;
        }
    }

    private int u24() {
        return ((small[0] & 0xff) << 16) | ((small[1] & 0xff) << 8) | (small[2] & 0xff);
    }

    private int fill(byte[] data, int pos, int end, int want) {
        int n = Math.min(want - smallLen, end - pos);
        System.arraycopy(data, pos, small, smallLen, n);
        smallLen += n;
        return pos + n;
    }

    private int consume(byte[] data, int pos, int end, int want) {
        int newPos = fill(data, pos, end, want);
        if (smallLen == want) {
            messageRemaining -= want;
            if (state == ENTRY_LENGTH || state == EXT_LENGTH) {
                listRemaining -= want;
            }
        }
        return newPos;
    }

    /**
     * Verifies the message was completely and consistently received.
     *
     * @throws HandshakeFormatException if it was truncated
     */
    void finish() throws HandshakeFormatException {
        if (state != DONE || messageRemaining != 0) {
            throw new HandshakeFormatException("truncated Certificate message");
        }
    }

    byte[] getContext() {
        return context;
    }

    List<X509Certificate> getChain() {
        return chain;
    }
}
