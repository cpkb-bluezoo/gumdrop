/*
 * H3Qlog.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.quic.QlogJson;
import org.bluezoo.gumdrop.quic.QlogSink;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;

/**
 * Builds and reports the HTTP/3 and HTTP capsule qlog events
 * (draft-ietf-quic-qlog-h3-events-14), with the event and field names the
 * draft gives them. Callers test {@link QlogSink#isQlogEnabled()} first,
 * so nothing here runs on a connection that does not log.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class H3Qlog {

    private H3Qlog() {
    }

    static final String INITIATOR_LOCAL = "local";
    static final String INITIATOR_REMOTE = "remote";

    // field names
    static final String F_INITIATOR = "initiator";
    static final String F_STREAM_ID = "stream_id";
    static final String F_PUSH_ID = "push_id";
    static final String F_STREAM_TYPE = "stream_type";
    static final String F_MAX_FIELD_SECTION_SIZE = "max_field_section_size";
    static final String F_MAX_TABLE_CAPACITY = "max_table_capacity";
    static final String F_BLOCKED_STREAMS_COUNT = "blocked_streams_count";
    static final String F_EXTENDED_CONNECT = "extended_connect";
    static final String F_H3_DATAGRAM = "h3_datagram";
    static final String F_UNKNOWN_SETTINGS = "unknown_settings";
    static final String F_NAME = "name";
    static final String F_NAME_BYTES = "name_bytes";
    static final String F_VALUE = "value";
    static final String F_FRAME = "frame";
    static final String F_FRAME_TYPE = "frame_type";
    static final String F_FRAME_TYPE_BYTES = "frame_type_bytes";
    static final String F_QUARTER_STREAM_ID = "quarter_stream_id";
    static final String F_NEW = "new";
    static final String F_RAW = "raw";
    static final String F_LENGTH = "length";
    static final String F_CAPSULE = "capsule";
    static final String F_CAPSULE_TYPE = "capsule_type";
    static final String F_CAPSULE_TYPE_VALUE = "capsule_type_value";

    static final String NAME_UNKNOWN = "unknown";

    static final String STREAM_TYPE_REQUEST = "request";
    static final String STREAM_TYPE_CONTROL = "control";
    static final String STREAM_TYPE_PUSH = "push";
    static final String STREAM_TYPE_QPACK_ENCODE = "qpack_encode";
    static final String STREAM_TYPE_QPACK_DECODE = "qpack_decode";
    static final String STREAM_TYPE_UNKNOWN = "unknown";

    /**
     * The qlog sink behind an endpoint, or null if it is not a QUIC stream.
     */
    static QlogSink sinkOf(Object endpoint) {
        return endpoint instanceof QlogSink ? (QlogSink) endpoint : null;
    }

    /** True if events are being logged to this sink. */
    static boolean on(QlogSink sink) {
        return sink != null && sink.isQlogEnabled();
    }

    // ── events ──

    static void streamTypeSet(QlogSink sink, String owner, long streamId, String type) {
        QlogJson data = QlogJson.object().put(F_INITIATOR, owner);
        if (streamId >= 0) {
            data.put(F_STREAM_ID, streamId);
        }
        data.put(F_STREAM_TYPE, type);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:stream_type_set", data.build());
    }

    static void parametersSet(QlogSink sink, String owner, long[] settings) {
        QlogJson data = QlogJson.object().put(F_INITIATOR, owner);
        List<String> unknown = null;
        int max = settings.length - 1;
        for (int i = 0; i < max; i += 2) {
            long id = settings[i];
            long value = settings[i + 1];
            if (id == H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE) {
                data.put(F_MAX_FIELD_SECTION_SIZE, value);
            } else if (id == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
                data.put(F_MAX_TABLE_CAPACITY, value);
            } else if (id == H3FrameHandler.SETTINGS_QPACK_BLOCKED_STREAMS) {
                data.put(F_BLOCKED_STREAMS_COUNT, value);
            } else if (id == H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL) {
                data.put(F_EXTENDED_CONNECT, value);
            } else if (id == H3FrameHandler.SETTINGS_H3_DATAGRAM) {
                data.put(F_H3_DATAGRAM, value);
            } else {
                if (unknown == null) {
                    unknown = new ArrayList<String>();
                }
                unknown.add(unknownSetting(id, value));
            }
        }
        if (unknown != null) {
            data.beginArray(F_UNKNOWN_SETTINGS);
            for (String setting : unknown) {
                data.itemRaw(setting);
            }
            data.endArray();
        }
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:parameters_set", data.build());
    }

    private static String unknownSetting(long id, long value) {
        return QlogJson.object().put(F_NAME, NAME_UNKNOWN).put(F_NAME_BYTES, id).put(F_VALUE, value).build();
    }

    static void frameCreated(QlogSink sink, long streamId, String frame) {
        frame(sink, "http3:frame_created", streamId, frame);
    }

    static void frameParsed(QlogSink sink, long streamId, String frame) {
        frame(sink, "http3:frame_parsed", streamId, frame);
    }

    private static void frame(QlogSink sink, String event, long streamId, String frame) {
        QlogJson data = QlogJson.object();
        if (streamId >= 0) {
            data.put(F_STREAM_ID, streamId);
        }
        data.putRaw(F_FRAME, frame);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, event, data.build());
    }

    static void priorityUpdated(QlogSink sink, boolean push, long id, String priority) {
        QlogJson data = QlogJson.object().put(push ? F_PUSH_ID : F_STREAM_ID, id);
        data.put(F_NEW, priority);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:priority_updated", data.build());
    }

    static void datagramCreated(QlogSink sink, long streamId, int payloadLength) {
        datagram(sink, "http3:datagram_created", streamId, payloadLength);
    }

    static void datagramParsed(QlogSink sink, long streamId, int payloadLength) {
        datagram(sink, "http3:datagram_parsed", streamId, payloadLength);
    }

    private static void datagram(QlogSink sink, String event, long streamId, int payloadLength) {
        QlogJson data = QlogJson.object().put(F_QUARTER_STREAM_ID, streamId / 4);
        data.beginObject(F_RAW).put(F_LENGTH, payloadLength).endObject();
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, event, data.build());
    }

    static void capsuleCreated(QlogSink sink, long streamId, long type, int length) {
        capsule(sink, "http:capsule_created", streamId, type, length);
    }

    static void capsuleParsed(QlogSink sink, long streamId, long type, int length) {
        capsule(sink, "http:capsule_parsed", streamId, type, length);
    }

    private static void capsule(QlogSink sink, String event, long streamId, long type, int length) {
        QlogJson data = QlogJson.object();
        if (streamId >= 0) {
            data.put(F_STREAM_ID, streamId);
        }
        data.beginObject(F_CAPSULE);
        if (type == 0) {
            data.put(F_CAPSULE_TYPE, "datagram");
        } else {
            data.put(F_CAPSULE_TYPE, NAME_UNKNOWN);
            data.put(F_CAPSULE_TYPE_VALUE, type);
        }
        data.beginObject(F_RAW).put(F_LENGTH, length).endObject();
        data.endObject();
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP, event, data.build());
    }

    // ── frames, as the JSON the frame events carry ──

    static String dataFrame(int length) {
        return QlogJson.object().put(F_FRAME_TYPE, "data")
                .beginObject(F_RAW).put(F_LENGTH, length).endObject().build();
    }

    static String headersFrame(List<Header> fields) {
        QlogJson frame = QlogJson.object().put(F_FRAME_TYPE, "headers");
        frame.beginArray("headers");
        for (Header field : fields) {
            frame.itemRaw(QlogJson.object().put("name", field.getName()).put("value", field.getValue()).build());
        }
        frame.endArray();
        return frame.build();
    }

    static String settingsFrame(long[] settings) {
        QlogJson frame = QlogJson.object().put(F_FRAME_TYPE, "settings");
        frame.beginArray("settings");
        int max = settings.length - 1;
        for (int i = 0; i < max; i += 2) {
            frame.itemRaw(settingEntry(settings[i], settings[i + 1]));
        }
        frame.endArray();
        return frame.build();
    }

    static String goawayFrame(long id) {
        return QlogJson.object().put(F_FRAME_TYPE, "goaway").put("id", id).build();
    }

    static String maxPushIdFrame(long pushId) {
        return QlogJson.object().put(F_FRAME_TYPE, "max_push_id").put("push_id", pushId).build();
    }

    static String cancelPushFrame(long pushId) {
        return QlogJson.object().put(F_FRAME_TYPE, "cancel_push").put("push_id", pushId).build();
    }

    /** A frame the draft has no name for, such as PRIORITY_UPDATE. */
    static String unknownFrame(long frameType) {
        return QlogJson.object().put(F_FRAME_TYPE, "unknown").put(F_FRAME_TYPE_BYTES, frameType).build();
    }

    private static String settingEntry(long id, long value) {
        String name = settingName(id);
        if (name == null) {
            return unknownSetting(id, value);
        }
        return QlogJson.object().put(F_NAME, name).put(F_VALUE, value).build();
    }

    // The draft's name for a setting, or null if it has none.
    private static String settingName(long id) {
        if (id == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
            return "settings_qpack_max_table_capacity";
        }
        if (id == H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE) {
            return "settings_max_field_section_size";
        }
        if (id == H3FrameHandler.SETTINGS_QPACK_BLOCKED_STREAMS) {
            return "settings_qpack_blocked_streams";
        }
        if (id == H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL) {
            return "settings_enable_connect_protocol";
        }
        if (id == H3FrameHandler.SETTINGS_H3_DATAGRAM) {
            return "settings_h3_datagram";
        }
        return null;
    }
}
