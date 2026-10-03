/*
 * H3ResponseWire.java
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

package org.bluezoo.gumdrop.http.h3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.HeaderFieldHandler;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;

/**
 * Reads back what an {@link H3Stream} wrote on its request stream: the
 * HTTP/3 frames (RFC 9114 section 7), with each HEADERS frame's QPACK field
 * section decoded, and whether the stream was closed (FIN).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class H3ResponseWire {

    static final long TYPE_DATA = 0x00;
    static final long TYPE_HEADERS = 0x01;

    /** One frame; fields is set for a HEADERS frame. */
    static final class Frame {
        long type;
        byte[] payload;
        final List<String[]> fields = new ArrayList<String[]>();

        String get(String name) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i)[0].equals(name)) {
                    return fields.get(i)[1];
                }
            }
            return null;
        }
    }

    final List<Frame> frames = new ArrayList<Frame>();
    final boolean fin;

    /** Reads the frames queued so far on the stream of a test connection. */
    H3ResponseWire(QuicConnection conn, long streamId) {
        byte[] wire = QuicConnectionTestFactory.queuedStreamBytes(conn, streamId);
        fin = QuicConnectionTestFactory.queuedStreamFin(conn, streamId);
        Decoder decoder = new Decoder(4096);
        int[] pos = new int[] {0};
        while (pos[0] < wire.length) {
            Frame f = new Frame();
            f.type = varint(wire, pos);
            int len = (int) varint(wire, pos);
            f.payload = new byte[len];
            System.arraycopy(wire, pos[0], f.payload, 0, len);
            pos[0] += len;
            if (f.type == TYPE_HEADERS) {
                final Frame target = f;
                try {
                    decoder.decode(streamId, ByteBuffer.wrap(f.payload), new HeaderFieldHandler() {
                        @Override
                        public void field(ByteBuffer name, ByteBuffer value) {
                            target.fields.add(new String[] {
                                    StandardCharsets.ISO_8859_1.decode(name.duplicate()).toString(),
                                    StandardCharsets.ISO_8859_1.decode(value.duplicate()).toString() });
                        }
                    });
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            frames.add(f);
        }
    }

    /** The HEADERS frames, in order. */
    List<Frame> headerFrames() {
        List<Frame> out = new ArrayList<Frame>();
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).type == TYPE_HEADERS) {
                out.add(frames.get(i));
            }
        }
        return out;
    }

    /** The total DATA payload length. */
    int dataBytes() {
        int n = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).type == TYPE_DATA) {
                n += frames.get(i).payload.length;
            }
        }
        return n;
    }

    /** Returns the index of the first frame of a type, or -1. */
    int indexOf(long type) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).type == type) {
                return i;
            }
        }
        return -1;
    }

    private static long varint(byte[] b, int[] pos) {
        int first = b[pos[0]] & 0xff;
        int length = 1 << (first >> 6);
        long v = first & 0x3f;
        for (int i = 1; i < length; i++) {
            v = (v << 8) | (b[pos[0] + i] & 0xff);
        }
        pos[0] += length;
        return v;
    }
}
