/*
 * SettingsFrame.java
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
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A HTTP/2 SETTINGS frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class SettingsFrame extends Frame {

    static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
    static final int SETTINGS_ENABLE_PUSH = 0x2;
    static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
    static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
    static final int SETTINGS_MAX_FRAME_SIZE = 0x5;
    static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;

    private static final int SETTING_ENTRY_SIZE = 6;

    boolean ack;

    Map<Integer,Integer> settings;

    /**
     * Constructor for a settings frame received from the client.
     * The payload ByteBuffer should be positioned at the start of payload data
     * with limit set to the end of payload data.
     */
    protected SettingsFrame(int flags, ByteBuffer payload) throws ProtocolException {
        ack = (flags & FLAG_ACK) != 0;
        settings = new LinkedHashMap<>();
        while (payload.hasRemaining()) {
            int identifier = (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
            int value = (payload.get() & 0xff) << 24
                | (payload.get() & 0xff) << 16
                | (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
            switch (identifier) {
                case SETTINGS_ENABLE_PUSH:
                    if (value > 1) { // values other than 0 or 1 must be treated as PROTOCOL_ERROR
                        throw new ProtocolException();
                    }
                    break;
                case SETTINGS_MAX_FRAME_SIZE:
                    if (value < 16384) {
                        throw new ProtocolException();
                    }
                    // fall through
                default:
                    if (value < 1) {
                        String message = HTTPConnection.L10N.getString("err.bad_settings_value");
                        message = MessageFormat.format(message, identifier, value);
                        throw new ProtocolException(message);
                    }
                    break;
            }
            settings.put(identifier, value);
        }
    }

    /**
     * Constructor for a settings frame to send to the client.
     */
    protected SettingsFrame(boolean ack) {
        this.ack = ack;
        settings = new LinkedHashMap<>();
    }

    void set(int identifier, int value) {
        settings.put(identifier, value);
    }

    public int getLength() {
        return settings.size() * SETTING_ENTRY_SIZE;
    }

    public int getType() {
        return TYPE_SETTINGS;
    }

    public int getFlags() {
        return ack ? FLAG_ACK : 0;
    }

    public int getStream() {
        return 0;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        // If ACK is set, payload of SETTINGS frame must be empty
        if (!ack) {
            for (int identifier : settings.keySet()) {
                int value = settings.get(identifier);
                buf.put((byte) ((identifier >> 8) & 0xff));
                buf.put((byte) (identifier & 0xff));
                buf.put((byte) ((value >> 24) & 0xff));
                buf.put((byte) ((value >> 16) & 0xff));
                buf.put((byte) ((value >> 8) & 0xff));
                buf.put((byte) (value & 0xff));
            }
        }
    }

    void apply(HTTPConnection c) {
        for (int identifier : settings.keySet()) {
            int value = settings.get(identifier);
            switch (identifier) {
                case SETTINGS_HEADER_TABLE_SIZE:
                    c.headerTableSize = value;
                    break;
                case SETTINGS_ENABLE_PUSH:
                    c.enablePush = (value == 1);
                    break;
                case SETTINGS_MAX_CONCURRENT_STREAMS:
                    c.maxConcurrentStreams = value;
                    break;
                case SETTINGS_INITIAL_WINDOW_SIZE:
                    c.initialWindowSize = value;
                    break;
                case SETTINGS_MAX_FRAME_SIZE:
                    c.maxFrameSize = value;
                    break;
                case SETTINGS_MAX_HEADER_LIST_SIZE:
                    c.maxHeaderListSize = value;
                    break;
                default:
                    break;
            }
        }
    }

    // -- debugging --

    protected void appendFields(StringBuilder buf) {
        super.appendFields(buf);
        buf.append(";ack=").append(ack);
        buf.append(";settings={");
        boolean first = true;
        for (Map.Entry<Integer,Integer> entry : settings.entrySet()) {
            int identifier = entry.getKey();
            int value = entry.getValue();
            if (!first) {
                buf.append(',');
            } else {
                first = false;
            }
            buf.append(toString(identifier)).append('=').append(value);
        }
        buf.append("}");
    }

    private String toString(int identifier) {
        switch (identifier) {
            case SETTINGS_HEADER_TABLE_SIZE:
                return "SETTINGS_HEADER_TABLE_SIZE";
            case SETTINGS_ENABLE_PUSH:
                return "SETTINGS_ENABLE_PUSH";
            case SETTINGS_MAX_CONCURRENT_STREAMS:
                return "SETTINGS_MAX_CONCURRENT_STREAMS";
            case SETTINGS_INITIAL_WINDOW_SIZE:
                return "SETTINGS_INITIAL_WINDOW_SIZE";
            case SETTINGS_MAX_FRAME_SIZE:
                return "SETTINGS_MAX_FRAME_SIZE";
            case SETTINGS_MAX_HEADER_LIST_SIZE:
                return "SETTINGS_MAX_HEADER_LIST_SIZE";
            default:
                return "!!ERROR!!";
        }
    }

}
