/*
 * DnsTcpFraming.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RFC 1035 section 4.2.2 length-prefixed DNS messages on a byte stream.
 */
public final class DnsTcpFraming {

    public static final int MAX_DNS_MESSAGE_SIZE = 65535;

    private DnsTcpFraming() {
    }

    public static void writeFramed(OutputStream out, byte[] message) throws IOException {
        if (message.length > MAX_DNS_MESSAGE_SIZE) {
            throw new IOException("DNS message too large");
        }
        out.write((message.length >> 8) & 0xFF);
        out.write(message.length & 0xFF);
        out.write(message);
        out.flush();
    }

    public static byte[] readFramed(InputStream in) throws IOException {
        int hi = in.read();
        if (hi < 0) {
            return null;
        }
        int lo = in.read();
        if (lo < 0) {
            throw new IOException("truncated DNS length prefix");
        }
        int length = ((hi & 0xFF) << 8) | (lo & 0xFF);
        if (length <= 0 || length > MAX_DNS_MESSAGE_SIZE) {
            throw new IOException("invalid DNS message length: " + length);
        }
        byte[] buf = new byte[length];
        readFully(in, buf, 0, length);
        return buf;
    }

    public static void readFully(InputStream in, byte[] buf, int off, int len)
            throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                throw new IOException("truncated DNS message");
            }
            total += n;
        }
    }

    /**
     * Parses consecutive length-prefixed DNS messages from a DoQ/TCP stream
     * buffer (RFC 1035 section 4.2.2). Used for multi-message AXFR/IXFR.
     *
     * @return one wire-format DNS message per frame (without the 2-octet prefix)
     */
    public static List<byte[]> readAllFramedMessages(byte[] stream)
            throws IOException {
        if (stream.length == 0) {
            return Collections.emptyList();
        }
        List<byte[]> messages = new ArrayList<byte[]>();
        int offset = 0;
        while (offset < stream.length) {
            if (offset + 2 > stream.length) {
                throw new IOException("truncated DNS length prefix");
            }
            int length = ((stream[offset] & 0xFF) << 8)
                    | (stream[offset + 1] & 0xFF);
            offset += 2;
            if (length <= 0 || length > MAX_DNS_MESSAGE_SIZE) {
                throw new IOException("invalid DNS message length: " + length);
            }
            if (offset + length > stream.length) {
                throw new IOException("truncated DNS message");
            }
            messages.add(Arrays.copyOfRange(stream, offset, offset + length));
            offset += length;
        }
        return messages;
    }
}
