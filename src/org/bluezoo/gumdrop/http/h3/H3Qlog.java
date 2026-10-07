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

import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.quic.QlogJson;
import org.bluezoo.gumdrop.quic.QlogSink;
import org.bluezoo.gumdrop.telemetry.QlogAttributes;

/**
 * Builds and reports the HTTP/3 and HTTP capsule qlog events
 * (draft-ietf-quic-qlog-h3-events-13), with the event and field names the
 * draft gives them. Callers test {@link QlogSink#isQlogEnabled()} first,
 * so nothing here runs on a connection that does not log.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class H3Qlog {

    private H3Qlog() {
    }

    static final String OWNER_LOCAL = "local";
    static final String OWNER_REMOTE = "remote";

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
        QlogJson data = QlogJson.object().put("owner", owner);
        if (streamId >= 0) {
            data.put("stream_id", streamId);
        }
        data.put("new", type);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:stream_type_set", data.build());
    }

    static void parametersSet(QlogSink sink, String owner, long[] settings) {
        QlogJson data = QlogJson.object().put("owner", owner);
        QlogJson additional = null;
        int max = settings.length - 1;
        for (int i = 0; i < max; i += 2) {
            long id = settings[i];
            long value = settings[i + 1];
            if (id == H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE) {
                data.put("max_header_list_size", value);
            } else if (id == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
                data.put("max_table_capacity", value);
            } else if (id == H3FrameHandler.SETTINGS_QPACK_BLOCKED_STREAMS) {
                data.put("blocked_streams_count", value);
            } else if (id == H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL) {
                data.put("enable_connect_protocol", value == 1);
            } else if (id == H3FrameHandler.SETTINGS_H3_DATAGRAM) {
                data.put("h3_datagram", value == 1);
            } else {
                if (additional == null) {
                    additional = QlogJson.object();
                }
                additional.put("0x" + Long.toHexString(id), value);
            }
        }
        if (additional != null) {
            data.putRaw("additional_settings", additional.build());
        }
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:parameters_set", data.build());
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
            data.put("stream_id", streamId);
        }
        data.putRaw("frame", frame);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, event, data.build());
    }

    static void priorityUpdated(QlogSink sink, boolean push, long id, String priority) {
        QlogJson data = QlogJson.object().put("type", push ? "push" : "request");
        data.put("id", id);
        data.put("new", priority);
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP3, "http3:priority_updated", data.build());
    }

    static void datagramCreated(QlogSink sink, long streamId, int payloadLength) {
        datagram(sink, "http3:datagram_created", streamId, payloadLength);
    }

    static void datagramParsed(QlogSink sink, long streamId, int payloadLength) {
        datagram(sink, "http3:datagram_parsed", streamId, payloadLength);
    }

    private static void datagram(QlogSink sink, String event, long streamId, int payloadLength) {
        QlogJson data = QlogJson.object().put("quarter_stream_id", streamId / 4);
        data.put("payload_length", payloadLength);
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
            data.put("stream_id", streamId);
        }
        data.beginObject("capsule");
        if (type == 0) {
            data.put("capsule_type", "datagram");
            data.put("payload_length", length);
        } else {
            data.put("capsule_type", "unknown");
            data.put("raw_capsule_type", type);
            data.put("raw_capsule_length", length);
        }
        data.endObject();
        sink.emitQlog(QlogAttributes.SCHEMA_HTTP, event, data.build());
    }

    // ── frames, as the JSON the frame events carry ──

    static String dataFrame(int length) {
        return QlogJson.object().put("frame_type", "data").put("length", length).build();
    }

    static String headersFrame(List<Header> fields) {
        QlogJson frame = QlogJson.object().put("frame_type", "headers");
        frame.beginArray("headers");
        for (Header field : fields) {
            frame.itemRaw(QlogJson.object().put("name", field.getName()).put("value", field.getValue()).build());
        }
        frame.endArray();
        return frame.build();
    }

    static String settingsFrame(long[] settings) {
        QlogJson frame = QlogJson.object().put("frame_type", "settings");
        frame.beginArray("settings");
        int max = settings.length - 1;
        for (int i = 0; i < max; i += 2) {
            frame.itemRaw(QlogJson.object().put("name", settingName(settings[i])).put("value", settings[i + 1])
                    .build());
        }
        frame.endArray();
        return frame.build();
    }

    static String goawayFrame(long id) {
        return QlogJson.object().put("frame_type", "goaway").put("id", id).build();
    }

    static String maxPushIdFrame(long pushId) {
        return QlogJson.object().put("frame_type", "max_push_id").put("push_id", pushId).build();
    }

    static String cancelPushFrame(long pushId) {
        return QlogJson.object().put("frame_type", "cancel_push").put("push_id", pushId).build();
    }

    /** A frame the draft has no name for, such as PRIORITY_UPDATE. */
    static String unknownFrame(long frameType) {
        return QlogJson.object().put("frame_type", "unknown").put("raw_frame_type", frameType).build();
    }

    private static String settingName(long id) {
        if (id == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
            return "qpack_max_table_capacity";
        }
        if (id == H3FrameHandler.SETTINGS_MAX_FIELD_SECTION_SIZE) {
            return "max_field_section_size";
        }
        if (id == H3FrameHandler.SETTINGS_QPACK_BLOCKED_STREAMS) {
            return "qpack_blocked_streams";
        }
        if (id == H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL) {
            return "enable_connect_protocol";
        }
        if (id == H3FrameHandler.SETTINGS_H3_DATAGRAM) {
            return "h3_datagram";
        }
        return "0x" + Long.toHexString(id);
    }
}
