/*
 * Frame.java
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

package org.bluezoo.gumdrop.http;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * A HTTP/2 frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public abstract class Frame {

    /**
     * The DATA frame type.
     */
    public static final int TYPE_DATA = 0x0;

    /**
     * The HEADERS frame type.
     */
    public static final int TYPE_HEADERS = 0x1;

    /**
     * The PRIORITY frame type.
     */
    public static final int TYPE_PRIORITY = 0x2;

    /**
     * The RST_STREAM frame type.
     */
    public static final int TYPE_RST_STREAM = 0x3;

    /**
     * The SETTINGS frame type.
     */
    public static final int TYPE_SETTINGS = 0x4;

    /**
     * The PUSH_PROMISE frame type.
     */
    public static final int TYPE_PUSH_PROMISE = 0x5;

    /**
     * The PING frame type.
     */
    public static final int TYPE_PING = 0x6;

    /**
     * The GOAWAY frame type.
     */
    public static final int TYPE_GOAWAY = 0x7;

    /**
     * The WINDOW_UPDATE frame type.
     */
    public static final int TYPE_WINDOW_UPDATE = 0x8;

    /**
     * The CONTINUATION frame type.
     */
    public static final int TYPE_CONTINUATION = 0x9;

    /**
     * When set, bit 0 indicates that this frame acknowledges
     * receipt and application of the peer's SETTINGS frame.
     */
    public static final int FLAG_ACK = 0x1; // SETTINGS

    /**
     * When set, bit 0 indicates that this frame is the
     * last that the endpoint will send for the identified stream.
     */
    public static final int FLAG_END_STREAM = 0x1; // DATA|HEADERS flag

    /**
     * When set, bit 2 indicates that this frame
     * contains an entire header block (Section 4.3) and is not followed
     * by any CONTINUATION frames.
     */
    public static final int FLAG_END_HEADERS = 0x4; // HEADERS|PUSH_PROMISE|CONTINUATION flag

    /**
     * When set, bit 3 indicates that the Pad Length field
     * and any padding that it describes are present.
     */
    public static final int FLAG_PADDED = 0x8; // DATA|HEADERS|PUSH_PROMISE flag

    /**
     * When set, bit 5 indicates that the Exclusive Flag
     * (E), Stream Dependency, and Weight fields are present
     */
    public static final int FLAG_PRIORITY = 0x20; // HEADERS flag

    public static final int ERROR_NO_ERROR = 0x0;
    public static final int ERROR_PROTOCOL_ERROR = 0x1;
    public static final int ERROR_INTERNAL_ERROR = 0x2;
    public static final int ERROR_FLOW_CONTROL_ERROR = 0x3;
    public static final int ERROR_SETTINGS_TIMEOUT = 0x4;
    public static final int ERROR_STREAM_CLOSED = 0x5;
    public static final int ERROR_FRAME_SIZE_ERROR = 0x6;
    public static final int ERROR_REFUSED_STREAM = 0x7;
    public static final int ERROR_CANCEL = 0x8;
    public static final int ERROR_COMPRESSION_ERROR = 0x9;
    public static final int ERROR_CONNECT_ERROR = 0xa;
    public static final int ERROR_ENHANCE_YOUR_CALM = 0xb;
    public static final int ERROR_INADEQUATE_SECURITY = 0xc;
    public static final int ERROR_HTTP_1_1_REQUIRED = 0xd;

    /**
     * Returns the length of this frame's payload.
     * This will be encoded as a 24-bit unsigned integer.
     * Values larger than 16384 must not be sent unless the receiver has set
     * a larger value for SETTINGS_MAX_FRAME_SIZE.
     */
    public abstract int getLength();

    /**
     * Returns the type of this frame.
     * This will be encoded as an unsigned 8-bit integer.
     */
    public abstract int getType();

    /**
     * Returns the flags associated with this frame.
     * These are type-specific.
     * This will be encoded as an unsigned 8-bit integer.
     */
    public abstract int getFlags();

    /**
     * Returns the stream identifier of the stream associated with this
     * frame.
     * This will be encoded as an unsigned 31-bit integer.
     */
    public abstract int getStream();

    protected void write(ByteBuffer buf) {
        // NB this part only writes the frame header.
        // Subclasses must override it to write the payload for that type.
        int length = getLength();
        int type = getType();
        int flags = getFlags();
        int stream = getStream();
        // header
        buf.put((byte) ((length >> 16) & 0xff));
        buf.put((byte) ((length >> 8) & 0xff));
        buf.put((byte) (length & 0xff));
        buf.put((byte) (type & 0xff));
        buf.put((byte) (flags & 0xff));
        buf.put((byte) ((stream >> 24) & 0x7f)); // NB reserved bit
        buf.put((byte) ((stream >> 16) & 0xff));
        buf.put((byte) ((stream >> 8) & 0xff));
        buf.put((byte) (stream & 0xff));
    }

    public String toString() {
        StringBuilder buf = new StringBuilder(getClass().getName());
        buf.append('[');
        appendFields(buf);
        buf.append(']');
        return buf.toString();
    }

    protected void appendFields(StringBuilder buf) {
        int length = getLength();
        int type = getType();
        buf.append(String.format("length=%d;type=%s", length, typeToString(type)));
        int flags = getFlags();
        if (flags != 0) {
            buf.append(";flags=").append(flagsToString(type, flags));
        }
        int stream = getStream();
        if (stream != 0) {
            buf.append(String.format(";stream=%d", stream));
        }
    }

    static String typeToString(int type) {
        switch (type) {
            case TYPE_DATA:
                return "DATA";
            case TYPE_HEADERS:
                return "HEADERS";
            case TYPE_PRIORITY:
                return "PRIORITY";
            case TYPE_RST_STREAM:
                return "RST_STREAM";
            case TYPE_SETTINGS:
                return "SETTINGS";
            case TYPE_PUSH_PROMISE:
                return "PUSH_PROMISE";
            case TYPE_PING:
                return "PING";
            case TYPE_GOAWAY:
                return "GOAWAY";
            case TYPE_WINDOW_UPDATE:
                return "WINDOW_UPDATE";
            case TYPE_CONTINUATION:
                return "CONTINUATION";
            default:
                return "(unknown type)";
        }
    }

    static String flagsToString(int type, int flags) {
        StringBuilder buf = new StringBuilder();
        if (type == TYPE_SETTINGS || type == TYPE_PING) {
            if ((flags & FLAG_ACK) != 0) {
                buf.append("ACK");
            }
        } else {
            if ((flags & FLAG_END_STREAM) != 0) {
                buf.append("END_STREAM");
            }
            if ((flags & FLAG_END_HEADERS) != 0) {
                if (buf.length() > 0) {
                    buf.append('|');
                }
                buf.append("END_HEADERS");
            }
            if ((flags & FLAG_PADDED) != 0) {
                if (buf.length() > 0) {
                    buf.append('|');
                }
                buf.append("PADDED");
            }
            if ((flags & FLAG_PRIORITY) != 0) {
                if (buf.length() > 0) {
                    buf.append('|');
                }
                buf.append("PRIORITY");
            }
        }
        return buf.toString();
    }

}
